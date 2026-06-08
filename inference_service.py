"""
振动诊断推理服务 v2

直接调用 04.4_diagnose_unlabeled_target.py 的诊断引擎，
使用 04.4 原生的 compute_fft_full_curve / downsample_curve / compute_industrial_metrics
等函数，仅保留一层薄映射将结果适配为前端期望的字段名。

与 enhanced_inference_service.py 的区别：
- 不再重复实现 FFT/降采样/工业指标（改用 04.4 原生函数）
- map_result_to_frontend 替换为 _build_frontend_payload (~90行)
- _extract_signal_from_dict / load_signal 改用 utils_signal 中的版本
"""

from __future__ import annotations

# =============================================================================
# 标准库
# =============================================================================
import asyncio
import importlib.util
import json
import logging
import os
from contextlib import asynccontextmanager
from io import BytesIO
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# =============================================================================
# 第三方库
# =============================================================================
import numpy as np
import scipy.io
import torch
import uvicorn
from fastapi import FastAPI, File, HTTPException, Query, UploadFile, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from starlette.responses import JSONResponse

# =============================================================================
# 项目内部模块
# =============================================================================
from models.wdcnn_mech_dg2 import WDCNNMechDG
from utils_signal import _extract_signal_from_dict
from db_writer import save_inference_result, query_history

# ---------------------------------------------------------------------------
# 动态导入 04.4 诊断模块（文件名以数字开头，无法直接 import）
# ---------------------------------------------------------------------------
BASE_DIR = Path(__file__).resolve().parent
_v6_spec = importlib.util.spec_from_file_location(
    "diagnose_v6",
    str(BASE_DIR / "04.4_diagnose_unlabeled_target.py"),
)
v6 = importlib.util.module_from_spec(_v6_spec)
_v6_spec.loader.exec_module(v6)

# =============================================================================
# 日志配置
# =============================================================================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
logger = logging.getLogger("inference_service")

# =============================================================================
# 路径与常量
# =============================================================================
MODEL_PATH = BASE_DIR / "get" / "best_model_classwise_maha.pth"
DATA_DIR = BASE_DIR / "get" / "got"

DEFAULT_PORT = int(os.environ.get("PORT", 5001))
DISPLAY_POINTS = 2048
DISPLAY_SPECTRUM_POINTS = 512
CONFIDENCE_MIN = 1.0
CONFIDENCE_MAX = 99.0
CACHE_MAX_SIZE = 32

# =============================================================================
# 响应缓存
# =============================================================================
_response_cache: Dict[str, Tuple[float, Dict[str, Any]]] = {}

# =============================================================================
# FastAPI 应用
# =============================================================================
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Lifespan context manager: startup & shutdown logic (FastAPI modern pattern)."""
    global MODEL, MODEL_PARAMS, CLASS_NAMES, CLASSWISE_CFG
    global CLASS_FILE_UNK_OVERRIDES, MEAN_MAHA_ACCEPT_OVERRIDES, KNOWN_FAULT_PROTECT_CLASSES
    global _watcher_task, _health_broadcaster_task

    # ---- startup ----
    logger.info("Loading model from %s", MODEL_PATH)
    MODEL, MODEL_PARAMS, CLASS_NAMES, CLASSWISE_CFG = load_model()
    logger.info("Model loaded on %s", DEVICE)

    CLASS_FILE_UNK_OVERRIDES = v6.parse_class_threshold_overrides(
        "healthy:0.70,single_pitting:0.85,multi_pitting:0.85,single_spalling:0.85"
    )
    MEAN_MAHA_ACCEPT_OVERRIDES = v6.parse_maha_accept_overrides(
        "single_pitting:30,multi_pitting:25,single_spalling:20"
    )
    KNOWN_FAULT_PROTECT_CLASSES = v6.parse_protect_classes(
        "single_pitting,multi_pitting,single_spalling"
    )
    logger.info("v6 thresholds configured")

    _watcher_task = asyncio.create_task(_file_watcher_loop())
    logger.info("File watcher started")
    _health_broadcaster_task = asyncio.create_task(_health_broadcaster())
    logger.info("Health broadcaster started")

    yield  # app runs here

    # ---- shutdown ----
    if _watcher_task:
        _watcher_task.cancel()
        logger.info("File watcher stopped")
    if _health_broadcaster_task:
        _health_broadcaster_task.cancel()
        logger.info("Health broadcaster stopped")


app = FastAPI(title="Vibration Diagnosis Service v2", version="2.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# =============================================================================
# WebSocket 连接管理
# =============================================================================
class ConnectionManager:
    def __init__(self) -> None:
        self.active_connections: List[WebSocket] = []

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        self.active_connections.append(websocket)
        logger.info("WebSocket client connected (total: %d)", len(self.active_connections))

    def disconnect(self, websocket: WebSocket) -> None:
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)
            logger.info("WebSocket client disconnected (total: %d)", len(self.active_connections))

    async def broadcast(self, message: Dict[str, Any]) -> None:
        dead: List[WebSocket] = []
        for conn in self.active_connections:
            try:
                await conn.send_json(message)
            except Exception:
                dead.append(conn)
        for conn in dead:
            if conn in self.active_connections:
                self.active_connections.remove(conn)

    async def broadcast_health(self) -> None:
        """Broadcast current model health status to all connected clients."""
        await self.broadcast({
            "type": "health_status",
            "status": "ok",
            "device": str(DEVICE),
            "model_loaded": MODEL is not None,
            "model_path": str(MODEL_PATH) if MODEL_PATH else "",
            "version": "v2_direct_04.4",
            "classes": CLASS_NAMES,
            "win_len": MODEL_PARAMS.get("win_len") if MODEL_PARAMS else None,
            "stride": MODEL_PARAMS.get("stride") if MODEL_PARAMS else None,
            "fs": MODEL_PARAMS.get("fs") if MODEL_PARAMS else None,
        })

    async def broadcast_file_list(self) -> None:
        """Broadcast current .mat/.npy file list to all connected clients."""
        items: List[Dict[str, str]] = []
        if DATA_DIR.exists():
            for p in sorted(DATA_DIR.glob("*"), key=lambda p: p.stat().st_mtime, reverse=True):
                if p.suffix.lower() in {".mat", ".npy"}:
                    items.append({
                        "name": p.stem,
                        "source_name": p.name,
                        "label": p.stem,
                    })
        await self.broadcast({
            "type": "file_list",
            "data": items,
        })


ws_manager = ConnectionManager()

# =============================================================================
# 后台健康状态广播
# =============================================================================
_health_broadcaster_task: Optional[asyncio.Task] = None


async def _health_broadcaster(interval: float = 30.0) -> None:
    """Periodically broadcast health status to WebSocket clients."""
    await asyncio.sleep(5)  # initial delay for model to load
    while True:
        await asyncio.sleep(interval)
        try:
            await ws_manager.broadcast_health()
        except Exception:
            logger.exception("Health broadcast failed")

# =============================================================================
# 文件监控
# =============================================================================
_known_files: Dict[str, float] = {}
_watcher_task: Optional[asyncio.Task] = None


async def _auto_analyze_and_broadcast(file_path: Path) -> None:
    try:
        loop = asyncio.get_running_loop()
        raw_signal, _signal_field = await loop.run_in_executor(
            None, _load_signal_from_path, file_path,
        )
        fs = float(MODEL_PARAMS.get("fs", 5120.0))
        v6_result = await loop.run_in_executor(
            None, _diagnose, raw_signal, file_path.name,
        )
        result = await loop.run_in_executor(
            None, _build_frontend_payload, v6_result, raw_signal,
            file_path.name, fs, {"analysis_mode": "v6_auto", "filename": file_path.name},
        )
        await _save_to_db(result)
        await ws_manager.broadcast({"type": "auto_analysis", "success": True, "data": result})
        logger.info("Auto-analyzed & pushed: %s -> %s", file_path.name, result.get("diagnosisResult"))
    except Exception as exc:
        logger.exception("Auto-analysis failed for %s", file_path.name)
        await ws_manager.broadcast({
            "type": "auto_analysis", "success": False,
            "filename": file_path.name, "error": str(exc),
        })


async def _file_watcher_loop(interval: float = 3.0) -> None:
    global _known_files

    if DATA_DIR.exists():
        for p in DATA_DIR.glob("*"):
            if p.suffix.lower() in {".mat", ".npy"}:
                try:
                    _known_files[str(p)] = p.stat().st_mtime
                except OSError:
                    pass
    logger.info("File watcher seeded with %d known files", len(_known_files))

    while True:
        await asyncio.sleep(interval)
        if not DATA_DIR.exists():
            continue

        current: Dict[str, float] = {}
        for p in DATA_DIR.glob("*"):
            if p.suffix.lower() not in {".mat", ".npy"}:
                continue
            try:
                current[str(p)] = p.stat().st_mtime
            except OSError:
                continue

        for path_str, mtime in current.items():
            prev = _known_files.get(path_str)
            if prev is None or prev != mtime:
                _known_files[path_str] = mtime
                await _auto_analyze_and_broadcast(Path(path_str))
                await ws_manager.broadcast_file_list()

        for path_str in list(_known_files.keys()):
            if path_str not in current:
                del _known_files[path_str]


# =============================================================================
# 全局模型状态
# =============================================================================
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
MODEL: Optional[WDCNNMechDG] = None
MODEL_PARAMS: Dict[str, Any] = {}
CLASS_NAMES: List[str] = []
CLASSWISE_CFG: Dict[str, Any] = {}
CLASS_FILE_UNK_OVERRIDES: Dict[str, float] = {}
MEAN_MAHA_ACCEPT_OVERRIDES: Dict[str, float] = {}
KNOWN_FAULT_PROTECT_CLASSES: List[str] = []


# =============================================================================
# 信号加载 — 委托给 utils_signal
# =============================================================================
def _load_signal_from_path(file_path: Path) -> Tuple[np.ndarray, str]:
    """
    从文件路径加载振动信号（.mat / .npy）。

    .mat 使用 scipy.io.loadmat + _extract_signal_from_dict（自动匹配变量名），
    .npy 使用 load_npy_signal（固定 key）。
    """
    suffix = file_path.suffix.lower()
    if suffix == ".mat":
        if not file_path.exists():
            raise FileNotFoundError(f".mat file not found: {file_path}")
        mat = scipy.io.loadmat(str(file_path))
        sig = _extract_signal_from_dict(mat, source_name=file_path.name)
    elif suffix == ".npy":
        from utils_signal import load_npy_signal
        sig = load_npy_signal(file_path, signal_key="sig_acc_5120")
    else:
        raise ValueError(f"Unsupported file type: {suffix}. Only .mat and .npy are supported.")

    sig = np.nan_to_num(np.asarray(sig, dtype=np.float32).reshape(-1),
                        nan=0.0, posinf=0.0, neginf=0.0)
    return sig, file_path.name


def _load_signal_from_bytes(filename: str, content: bytes) -> Tuple[np.ndarray, str]:
    """从上传字节流加载振动信号（.mat / .npy）。"""
    suffix = Path(filename).suffix.lower()
    if suffix == ".mat":
        payload = scipy.io.loadmat(BytesIO(content))
        sig = _extract_signal_from_dict(payload, source_name=filename)
    elif suffix == ".npy":
        payload = np.load(BytesIO(content), allow_pickle=True)
        if isinstance(payload, np.lib.npyio.NpzFile):
            try:
                if not payload.files:
                    raise ValueError("No arrays found in uploaded npz file.")
                arrays = {name: payload[name] for name in payload.files}
                sig = _extract_signal_from_dict(arrays, source_name=filename)
            finally:
                payload.close()
        elif isinstance(payload, np.ndarray) and payload.dtype == object:
            if payload.size == 1 and isinstance(payload.reshape(-1)[0], dict):
                sig = _extract_signal_from_dict(payload.reshape(-1)[0], source_name=filename)
            else:
                sig = np.concatenate([np.asarray(item).reshape(-1) for item in payload.flat])
        else:
            sig = np.asarray(payload, dtype=np.float32).reshape(-1)
    else:
        raise ValueError(f"Unsupported file type: {suffix}. Only .mat and .npy are supported.")

    sig = np.nan_to_num(np.asarray(sig, dtype=np.float32).reshape(-1),
                        nan=0.0, posinf=0.0, neginf=0.0)
    return sig, filename


# =============================================================================
# 缓存辅助
# =============================================================================
def _get_cached_or_compute(file_path: Path, compute_fn, *args: Any, **kwargs: Any) -> Dict[str, Any]:
    cache_key = str(file_path.resolve())
    try:
        mtime = file_path.stat().st_mtime
    except OSError:
        mtime = 0.0

    if cache_key in _response_cache:
        cached_mtime, cached_result = _response_cache[cache_key]
        if cached_mtime == mtime:
            logger.info("Cache hit for %s", file_path.name)
            return cached_result

    result = compute_fn(*args, **kwargs)
    _response_cache[cache_key] = (mtime, result)

    if len(_response_cache) > CACHE_MAX_SIZE:
        oldest = next(iter(_response_cache))
        del _response_cache[oldest]
        logger.debug("Cache evicted oldest entry")
    return result


# =============================================================================
# 数据库写入
# =============================================================================
async def _save_to_db(result: Dict[str, Any]) -> None:
    try:
        loop = asyncio.get_running_loop()
        await loop.run_in_executor(None, save_inference_result, result)
    except Exception as exc:
        logger.warning("DB write failed (non-fatal): %s", exc)


def _save_to_db_sync(result: Dict[str, Any]) -> None:
    try:
        save_inference_result(result)
    except Exception as exc:
        logger.warning("DB write failed (non-fatal): %s", exc)


# =============================================================================
# 模型加载
# =============================================================================
def load_model() -> Tuple[WDCNNMechDG, Dict[str, Any], List[str], Dict[str, Any]]:
    if not MODEL_PATH.exists():
        raise FileNotFoundError(f"Model checkpoint not found: {MODEL_PATH}")

    ckpt = v6.safe_torch_load(MODEL_PATH, map_location=DEVICE)
    params = ckpt["params"]
    class_names = [str(x) for x in ckpt["classes"]]
    num_classes = len(class_names)
    classwise_cfg = ckpt["classwise_open_set"]

    model = WDCNNMechDG(
        num_classes=num_classes,
        num_domains=2,
        fs=float(params["fs"]),
        win_len=int(params["win_len"]),
        feat_dim=int(params["feat_dim"]),
    ).to(DEVICE)
    model.load_state_dict(ckpt["model_state"])
    model.eval()

    logger.info("Model loaded: %d classes %s on %s", num_classes, class_names, DEVICE)
    return model, params, class_names, classwise_cfg


# =============================================================================
# 诊断执行 — 直接调用 04.4 的 diagnose_signal_array
# =============================================================================
def _diagnose(raw_signal: np.ndarray, source_name: str = "") -> Dict[str, Any]:
    """
    直接调用 04.4 的 diagnose_signal_array，无中间包装。
    """
    sig = np.asarray(raw_signal, dtype=np.float32).reshape(-1)

    return v6.diagnose_signal_array(
        model=MODEL,
        sig=sig,
        device=DEVICE,
        win_len=int(MODEL_PARAMS["win_len"]),
        stride=int(MODEL_PARAMS["stride"]),
        batch_size=int(MODEL_PARAMS.get("batch_size", 128)),
        class_names=CLASS_NAMES,
        classwise_cfg=CLASSWISE_CFG,
        class_file_unk_overrides=CLASS_FILE_UNK_OVERRIDES,
        mean_maha_accept_overrides=MEAN_MAHA_ACCEPT_OVERRIDES,
        fs=float(MODEL_PARAMS.get("fs", 5120.0)),
        unknown_vote_required=3,
        healthy_conf_protect=0.80,
        healthy_unknown_ratio_allow=1.01,
        known_fault_conf_protect=0.99,
        known_fault_unknown_ratio_allow=None,
        known_fault_mean_votes_allow=2.0,
        known_fault_protect_classes=KNOWN_FAULT_PROTECT_CLASSES,
        maha_accept_min_conf=0.85,
        maha_accept_mean_votes_allow=3.10,
        file_label=source_name,
    )


# =============================================================================
# 前端数据映射 — 使用 04.4 原生函数，仅做字段名适配
# =============================================================================
def _build_evidence_list(v6_result: Dict[str, Any]) -> List[Dict[str, Any]]:
    """构建前端证据列表，使用 04.4 的 translate_decision_reason 生成中文描述。"""
    closed_pred = str(v6_result["closed_prediction"])
    confidence = float(v6_result["closed_confidence"])
    unknown_ratio = float(v6_result["unknown_ratio"])
    seg_consistency = float(v6_result["segment_consistency"])
    mean_maha = float(v6_result["mean_mahalanobis"])
    mean_entropy = float(v6_result["mean_entropy"])
    class_file_unk_thr = v6_result.get("class_file_unknown_ratio_threshold", "N/A")
    decision_reason = str(v6_result["decision_reason"])

    evidence: List[Dict[str, Any]] = [
        {
            "title": "决策原因",
            "desc": decision_reason,
            "type": "info",
            "level": "信息",
        },
        {
            "title": "闭集预测",
            "desc": f"{closed_pred} (置信度 {confidence:.4f})",
            "type": "info",
            "level": "信息",
        },
        {
            "title": "Unknown比例",
            "desc": f"{unknown_ratio:.4f} / 阈值 {class_file_unk_thr}",
            "type": "warning" if unknown_ratio > 0.3 else "info",
            "level": "高" if unknown_ratio > 0.5 else "中" if unknown_ratio > 0.3 else "低",
        },
        {
            "title": "片段一致性",
            "desc": f"{seg_consistency:.4f}",
            "type": "success" if seg_consistency > 0.8 else "warning",
            "level": "高" if seg_consistency > 0.8 else "中",
        },
        {
            "title": "Mean Mahalanobis",
            "desc": f"{mean_maha:.4f}",
            "type": "info",
            "level": "信息",
        },
        {
            "title": "Average entropy",
            "desc": f"{mean_entropy:.4f}",
            "type": "info",
            "level": "信息",
        },
    ]

    ef = v6_result.get("evidence_frequencies", [])
    if ef:
        freq_strs = [f"{f:.1f} Hz" for f, _ in ef[:3]]
        evidence.append({
            "title": "特征频率",
            "desc": ", ".join(freq_strs) if freq_strs else "无",
            "type": "info",
            "level": "信息",
        })

    return evidence


def _build_top_probabilities(v6_result: Dict[str, Any]) -> List[Dict[str, Any]]:
    """从 v6_result 的 prob_{class} 字段构建前端概率列表。"""
    top_probs: List[Dict[str, Any]] = []
    for cname in CLASS_NAMES:
        key = f"prob_{cname}"
        if key in v6_result:
            top_probs.append({
                "class": cname,
                "probability": round(float(v6_result[key]) * 100.0, 2),
            })
    top_probs.sort(key=lambda x: x["probability"], reverse=True)
    return top_probs


def _build_frontend_payload(
    v6_result: Dict[str, Any],
    raw_signal: np.ndarray,
    source_name: str,
    sample_rate: float = 5120.0,
    extra: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """
    将 04.4 诊断结果映射为前端兼容格式。

    时域/频谱/工业指标直接使用 04.4 原生函数计算，
    不再重复实现 FFT / 降采样 / 统计量。
    """
    # ---- 核心诊断字段 ----
    final_pred = str(v6_result["final_prediction"])
    closed_pred = str(v6_result["closed_prediction"])
    confidence = float(v6_result["closed_confidence"])
    health_score = float(v6_result["health_score"])
    alarm_level = str(v6_result["alarm_level"])
    decision_reason = str(v6_result["decision_reason"])

    # 中文决策原因（来自 04.4）
    decision_reason_cn = v6.translate_decision_reason(decision_reason)

    # ---- 时域波形（使用 04.4 的 downsample_curve） ----
    sig = np.asarray(raw_signal, dtype=np.float64).reshape(-1)
    n = sig.size
    time_axis_full = np.arange(n, dtype=np.float64) / sample_rate if sample_rate > 0 else np.arange(n, dtype=np.float64)
    time_axis, time_data = v6.downsample_curve(time_axis_full, sig, max_points=DISPLAY_POINTS)

    # ---- 频谱（使用 04.4 的 compute_fft_full_curve） ----
    spectrum = v6.compute_fft_full_curve(sig, fs=sample_rate, max_points=DISPLAY_SPECTRUM_POINTS, f_min=0.0)
    freq_axis = spectrum["freq_hz"]
    freq_data = spectrum["amplitude"]

    # ---- 工业指标（使用 04.4 的 compute_industrial_metrics） ----
    metrics = v6.compute_industrial_metrics(sig, fs=sample_rate)
    rms = v6.safe_float(metrics["rms"])
    peak = v6.safe_float(metrics["peak"])

    # ---- 证据列表 ----
    evidence = _build_evidence_list(v6_result)

    # ---- 类别概率 ----
    top_probs = _build_top_probabilities(v6_result)

    # ---- 置信度百分比 ----
    confidence_pct = round(float(np.clip(confidence * 100.0, CONFIDENCE_MIN, CONFIDENCE_MAX)), 2)

    # ---- 风险等级映射 ----
    alarm_to_risk = {"normal": "低", "attention": "中", "warning": "中", "alarm": "高"}

    # ---- 组装返回 ----
    data: Dict[str, Any] = {
        # 核心
        "label": final_pred,
        "diagnosisResult": final_pred,
        "diagnosisName": final_pred,
        "confidence": confidence_pct,
        "healthIndex": int(round(health_score)),
        "riskLevel": alarm_to_risk.get(alarm_level, "中"),
        "alarmLevel": alarm_level,
        "diagnosisDetail": (
            f"v6决策: {decision_reason} | "
            f"闭集:{closed_pred} conf={confidence:.4f} | "
            f"unk_ratio={v6_result['unknown_ratio']:.4f}"
        ),
        "diagnosis_detail": f"v6决策: {decision_reason_cn}",
        "decision_reason": decision_reason,

        # 中间指标
        "closedPrediction": closed_pred,
        "unknownRatio": round(float(v6_result["unknown_ratio"]), 6),
        "segmentConsistency": round(float(v6_result["segment_consistency"]), 6),
        "meanMahalanobis": round(float(v6_result["mean_mahalanobis"]), 6),
        "meanEntropy": round(float(v6_result["mean_entropy"]), 6),

        # 来源
        "source_name": source_name,
        "sourceName": source_name,

        # 可视化
        "topProbabilities": top_probs[:3],
        "evidence": evidence,
        "time_axis": time_axis,
        "time_data": time_data,
        "waveform": time_data,
        "freq_axis": freq_axis,
        "frequencyAxis": freq_axis,
        "freq_data": freq_data,
        "spectrum": freq_data,

        # 信号统计
        "rms": round(rms, 6),
        "latestRms": round(rms, 6),
        "peak": round(peak, 6),
        "latestPeak": round(peak, 6),

        # 元数据
        "sample_rate": sample_rate,
        "sampleRate": sample_rate,
        "count": len(time_data),
        "analysis_mode": "v6_mahalanobis",
    }

    if extra:
        data.update(extra)

    return data




# =============================================================================
# API 端点
# =============================================================================

@app.get("/health")
def health() -> Dict[str, Any]:
    return {
        "status": "ok",
        "device": str(DEVICE),
        "model_loaded": MODEL is not None,
        "model_path": str(MODEL_PATH),
        "version": "v2_direct_04.4",
        "classes": CLASS_NAMES,
        "win_len": MODEL_PARAMS.get("win_len"),
        "stride": MODEL_PARAMS.get("stride"),
        "fs": MODEL_PARAMS.get("fs"),
    }


@app.get("/mat-files")
def mat_files() -> JSONResponse:
    items: List[Dict[str, Any]] = []
    if DATA_DIR.exists():
        all_files = list(DATA_DIR.glob("*.mat")) + list(DATA_DIR.glob("*.npy"))
        all_files.sort(key=lambda p: p.stat().st_mtime, reverse=True)
        for p in all_files:
            items.append({
                "name": p.stem,
                "label": p.stem,
                "source_name": p.name,
            })
    return JSONResponse(
        content={"success": True, "data": items},
        headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"},
    )


@app.get("/analyze")
def analyze(file_name: Optional[str] = Query(default=None, min_length=1)) -> Dict[str, Any]:
    if MODEL is None:
        raise HTTPException(status_code=500, detail="Model is not loaded.")

    try:
        if file_name:
            safe_name = Path(file_name).name
            suffix = Path(safe_name).suffix.lower()
            if suffix not in {".mat", ".npy"}:
                safe_name = f"{Path(file_name).stem}.mat"
            file_path = DATA_DIR / safe_name
        else:
            mat_files_list = sorted(DATA_DIR.glob("*.mat"))
            npy_files_list = sorted(DATA_DIR.glob("*.npy"))
            all_files = mat_files_list + npy_files_list
            if not all_files:
                raise FileNotFoundError(f"No .mat or .npy files found in {DATA_DIR}")
            file_path = max(all_files, key=lambda p: (p.stat().st_mtime, p.name))

        def _compute() -> Dict[str, Any]:
            raw_signal, _ = _load_signal_from_path(file_path)
            fs = float(MODEL_PARAMS.get("fs", 5120.0))
            v6_result = _diagnose(raw_signal, source_name=file_path.name)
            return _build_frontend_payload(
                v6_result, raw_signal, file_path.name, sample_rate=fs,
                extra={"analysis_mode": "v6_latest" if file_name is None else "v6_specified"},
            )

        result = _get_cached_or_compute(file_path, _compute)
        logger.info("analyze: file=%s diagnosis=%s", file_path.name, result.get("diagnosisResult"))
        _save_to_db_sync(result)
        return JSONResponse(
            content={"success": True, "data": result},
            headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"},
        )

    except Exception as exc:
        logger.exception("analyze failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/analyze/upload")
async def analyze_upload(file: UploadFile = File(...)) -> Dict[str, Any]:
    if MODEL is None:
        raise HTTPException(status_code=500, detail="Model is not loaded.")

    suffix = Path(file.filename).suffix.lower()
    if suffix not in {".mat", ".npy"}:
        raise HTTPException(status_code=400, detail="Only .mat and .npy files are supported.")

    content = await file.read()
    try:
        raw_signal, _ = _load_signal_from_bytes(file.filename, content)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Failed to parse file: {exc}") from exc

    fs = float(MODEL_PARAMS.get("fs", 5120.0))
    v6_result = _diagnose(raw_signal, source_name=file.filename)
    result = _build_frontend_payload(
        v6_result, raw_signal, file.filename, sample_rate=fs,
        extra={"analysis_mode": "v6_upload"},
    )
    await _save_to_db(result)
    return {"success": True, "data": result}


@app.post("/infer")
def infer(payload: Dict[str, Any]) -> Dict[str, Any]:
    if MODEL is None:
        raise HTTPException(status_code=500, detail="Model is not loaded.")

    file_path = str(payload.get("filePath") or "")
    if not file_path:
        raise HTTPException(status_code=400, detail="filePath is required.")

    source_path = Path(file_path)
    if not source_path.is_absolute():
        source_path = DATA_DIR / source_path

    if not source_path.exists():
        raise HTTPException(status_code=404, detail=f"File not found: {source_path}")

    def _compute() -> Dict[str, Any]:
        raw_signal, _ = _load_signal_from_path(source_path)
        fs = float(MODEL_PARAMS.get("fs", 5120.0))
        v6_result = _diagnose(raw_signal, source_name=source_path.name)
        return _build_frontend_payload(
            v6_result, raw_signal, source_path.name, sample_rate=fs,
            extra={
                "analysis_mode": payload.get("analysisMode", "v6_infer"),
                "deviceCode": payload.get("deviceCode"),
                "filename": payload.get("filename") or source_path.name,
                "batchId": payload.get("batchId"),
                "sampleTime": payload.get("sampleTime"),
                "modelVersion": "best_model_classwise_maha.pth (v6)",
            },
        )

    result = _get_cached_or_compute(source_path, _compute)
    _save_to_db_sync(result)
    return {"success": True, "data": result}


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket) -> None:
    await ws_manager.connect(websocket)
    try:
        while True:
            raw = await websocket.receive_text()
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                continue
            msg_type = data.get("type", "")
            if msg_type == "ping":
                await websocket.send_json({"type": "pong"})
            elif msg_type == "subscribe":
                channel = data.get("channel", "")
                if channel == "health":
                    await websocket.send_json({
                        "type": "health_status",
                        "status": "ok",
                        "device": str(DEVICE),
                        "model_loaded": MODEL is not None,
                        "model_path": str(MODEL_PATH) if MODEL_PATH else "",
                        "version": "v2_direct_04.4",
                        "classes": CLASS_NAMES,
                        "win_len": MODEL_PARAMS.get("win_len") if MODEL_PARAMS else None,
                        "stride": MODEL_PARAMS.get("stride") if MODEL_PARAMS else None,
                        "fs": MODEL_PARAMS.get("fs") if MODEL_PARAMS else None,
                    })
                elif channel == "mat_files":
                    items: List[Dict[str, str]] = []
                    if DATA_DIR.exists():
                        for p in sorted(DATA_DIR.glob("*"), key=lambda p: p.stat().st_mtime, reverse=True):
                            if p.suffix.lower() in {".mat", ".npy"}:
                                items.append({
                                    "name": p.stem,
                                    "source_name": p.name,
                                    "label": p.stem,
                                })
                    await websocket.send_json({
                        "type": "file_list",
                        "data": items,
                    })
    except Exception:
        pass
    finally:
        ws_manager.disconnect(websocket)


@app.get("/history")
def history(
    start_time: str = Query(..., min_length=1),
    end_time: str = Query(..., min_length=1),
    device_code: Optional[str] = Query(default=None),
) -> Dict[str, Any]:
    try:
        records = query_history(start_time, end_time, device_code)
        serialized: List[Dict[str, Any]] = []
        for row in records:
            item = dict(row)
            for key in ("sample_time", "create_time", "update_time"):
                if item.get(key):
                    item[key] = str(item[key])
            for key in ("confidence", "unknown_ratio", "segment_consistency",
                        "mean_mahalanobis", "mean_entropy"):
                if item.get(key) is not None:
                    item[key] = float(item[key])
            serialized.append(item)
        logger.info("history: %d records for [%s ~ %s]", len(serialized), start_time, end_time)
        return {"success": True, "data": serialized, "total": len(serialized)}
    except Exception as exc:
        logger.exception("history query failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc


# =============================================================================
# 入口
# =============================================================================
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=DEFAULT_PORT, reload=False)
