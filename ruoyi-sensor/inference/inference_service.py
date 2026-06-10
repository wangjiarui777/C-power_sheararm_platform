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
import threading
from contextlib import asynccontextmanager
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# =============================================================================
# 第三方库
# =============================================================================
import numpy as np
import scipy.io
import torch
import torch.nn as nn
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from starlette.responses import JSONResponse

# =============================================================================
# 项目内部模块
# =============================================================================
from models.wdcnn_mech_dg2 import WDCNNMechDG
from models.resnet18_1d import ResNet1D18
from utils_signal import _extract_signal_from_dict
from utils.dataset import FileInferenceDataset
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
GEAR_MODEL_PATH = BASE_DIR / "get" / "best_model_classwise_maha.pth"
BEARING_MODEL_PATH = BASE_DIR / "get" / "best_model.pth"
DATA_DIR = BASE_DIR / "get" / "got"

DEFAULT_PORT = int(os.environ.get("PORT", 5000))
DISPLAY_POINTS = 2048
DISPLAY_SPECTRUM_POINTS = 512
CONFIDENCE_MIN = 1.0
CONFIDENCE_MAX = 99.0
CACHE_MAX_SIZE = 32
VALID_MODEL_TYPES = {"gear", "bearing"}
MAX_UPLOAD_BYTES = int(os.environ.get("MAX_UPLOAD_BYTES", str(128 * 1024 * 1024)))
UPLOAD_CHUNK_BYTES = 1024 * 1024
INFERENCE_WORKERS = max(1, int(os.environ.get("INFERENCE_WORKERS", "1")))
BEARING_CLASS_CN_MAP = {
    "N": "正常",
    "normal": "正常",
    "healthy": "正常",
    "OR": "轴承外圈故障",
    "outer_ring": "轴承外圈故障",
    "outer": "轴承外圈故障",
    "B": "滚动体故障",
    "ball": "滚动体故障",
    "rolling_element": "滚动体故障",
    "IR": "轴承内圈故障",
    "inner_ring": "轴承内圈故障",
    "inner": "轴承内圈故障",
}

# =============================================================================
# 响应缓存
# =============================================================================
_response_cache: Dict[str, Tuple[float, Dict[str, Any]]] = {}
_inference_executor = ThreadPoolExecutor(max_workers=INFERENCE_WORKERS, thread_name_prefix="inference-worker")
_model_inference_lock = threading.Lock()

# =============================================================================
# 主事件循环引用（用于从同步端点安全广播 WebSocket 消息）
# =============================================================================
_main_loop: Optional[asyncio.AbstractEventLoop] = None


def _ensure_json_serializable(obj: Any) -> Any:
    """递归地将 numpy 数组/标量转换为 Python 原生类型，确保 JSON 可序列化。

    HTTP 的 JSONResponse 会自动处理 numpy，但 WebSocket 的 send_json 不会。
    """
    if isinstance(obj, np.ndarray):
        return obj.tolist()
    if isinstance(obj, (np.integer,)):
        return int(obj)
    if isinstance(obj, (np.floating,)):
        return float(obj)
    if isinstance(obj, np.bool_):
        return bool(obj)
    if isinstance(obj, dict):
        return {k: _ensure_json_serializable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_ensure_json_serializable(v) for v in obj]
    return obj


def _broadcast_analysis_sync(result: Dict[str, Any]) -> None:
    """从同步上下文中广播分析结果到所有 WebSocket 客户端（线程安全）。"""
    if _main_loop is None or _main_loop.is_closed():
        return
    try:
        payload = _ensure_json_serializable({
            "type": "auto_analysis",
            "success": True,
            "data": result,
        })
        asyncio.run_coroutine_threadsafe(
            ws_manager.broadcast(payload),
            _main_loop,
        )
    except Exception:
        logger.exception("WebSocket auto_analysis broadcast failed")


async def _broadcast_analysis_async(result: Dict[str, Any]) -> None:
    """从异步上下文中广播分析结果到所有 WebSocket 客户端。"""
    try:
        payload = _ensure_json_serializable({
            "type": "auto_analysis",
            "success": True,
            "data": result,
        })
        await ws_manager.broadcast(payload)
    except Exception:
        logger.exception("WebSocket auto_analysis broadcast failed")


# =============================================================================
# FastAPI 应用
# =============================================================================
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Lifespan context manager: startup & shutdown logic (FastAPI modern pattern)."""
    global _main_loop
    global gear_model, gear_model_params, gear_class_names, gear_classwise_cfg
    global bearing_model, bearing_model_params, bearing_class_names
    global CLASS_FILE_UNK_OVERRIDES, MEAN_MAHA_ACCEPT_OVERRIDES, KNOWN_FAULT_PROTECT_CLASSES
    global _watcher_task, _health_broadcaster_task

    # ---- startup ----
    _main_loop = asyncio.get_running_loop()
    logger.info("Main event loop stored for WebSocket broadcast")

    logger.info("Loading gear model from %s", GEAR_MODEL_PATH)
    gear_model, gear_model_params, gear_class_names, gear_classwise_cfg = load_gear_model()
    logger.info("Gear model loaded on %s", DEVICE)

    logger.info("Loading bearing model from %s", BEARING_MODEL_PATH)
    bearing_model, bearing_model_params, bearing_class_names = load_bearing_model()
    logger.info("Bearing model loaded on %s", DEVICE)

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
    _main_loop = None
    if _watcher_task:
        _watcher_task.cancel()
        logger.info("File watcher stopped")
    if _health_broadcaster_task:
        _health_broadcaster_task.cancel()
        logger.info("Health broadcaster stopped")
    _inference_executor.shutdown(wait=False, cancel_futures=True)
    logger.info("Inference executor stopped")


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
        payload = _build_health_payload()
        payload["type"] = "health_status"
        await self.broadcast(payload)

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


async def _notify_file_available(file_path: Path) -> None:
    """Notify clients that a new analysis file is available."""
    try:
        await ws_manager.broadcast({
            "type": "file_available",
            "success": True,
            "filename": file_path.name,
            "source_name": file_path.name,
        })
        logger.info("New analysis file available: %s", file_path.name)
    except Exception as exc:
        logger.exception("File availability broadcast failed for %s", file_path.name)
        await ws_manager.broadcast({
            "type": "file_available", "success": False,
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
                await _notify_file_available(Path(path_str))
                await ws_manager.broadcast_file_list()

        for path_str in list(_known_files.keys()):
            if path_str not in current:
                del _known_files[path_str]


# =============================================================================
# 全局模型状态
# =============================================================================
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
gear_model: Optional[WDCNNMechDG] = None
gear_model_params: Dict[str, Any] = {}
gear_class_names: List[str] = []
gear_classwise_cfg: Dict[str, Any] = {}
bearing_model: Optional[nn.Module] = None
bearing_model_params: Dict[str, Any] = {}
bearing_class_names: List[str] = []
CLASS_FILE_UNK_OVERRIDES: Dict[str, float] = {}
MEAN_MAHA_ACCEPT_OVERRIDES: Dict[str, float] = {}
KNOWN_FAULT_PROTECT_CLASSES: List[str] = []


# =============================================================================
# 信号加载 — 委托给 utils_signal
# =============================================================================
def _resolve_analysis_file(file_name: Optional[str]) -> Path:

    if file_name:
        safe_name = Path(file_name).name
        suffix = Path(safe_name).suffix.lower()
        candidates = [DATA_DIR / safe_name] if suffix in {".mat", ".npy"} else [
            DATA_DIR / f"{Path(safe_name).stem}.npy",
            DATA_DIR / f"{Path(safe_name).stem}.mat",
        ]
        file_path = next((p for p in candidates if p.exists()), candidates[0])
        if not file_path.exists():
            raise FileNotFoundError(f"File not found: {file_path}")
    else:
        all_files = sorted(
            list(DATA_DIR.glob("*.mat")) + list(DATA_DIR.glob("*.npy")),
            key=lambda p: (p.stat().st_mtime, p.name),
            reverse=True,
        )
        if not all_files:
            raise FileNotFoundError(f"No .mat or .npy files found in {DATA_DIR}")
        file_path = all_files[0]

    suffix = file_path.suffix.lower()
    if suffix not in {".mat", ".npy"}:
        raise ValueError(f"Unsupported file type: {suffix}. Only .mat and .npy are supported.")
    return file_path


def _load_signal_from_path(
    file_path: Path,
    preferred_signal_key: Optional[str] = None,
) -> Tuple[np.ndarray, str, Dict[str, Any]]:
    """
    从文件路径加载振动信号（.mat / .npy）。

    Parameters
    ----------
    file_path : Path
    preferred_signal_key : str or None
        优先使用的信号变量名。对 .mat 文件会优先匹配该 key；
        对 .npy 文件会作为 load_npy_signal 的 signal_key。

    Returns
    -------
    (signal, filename, metadata)
        metadata 包含从文件中提取的 sample_rate / rpm（可能为 None）。
    """
    from utils_signal import load_npy_signal, _extract_mat_metadata

    suffix = file_path.suffix.lower()
    meta: Dict[str, Any] = {"sample_rate": None, "rpm": None}

    if suffix == ".mat":
        if not file_path.exists():
            raise FileNotFoundError(f".mat file not found: {file_path}")
        mat = scipy.io.loadmat(str(file_path))
        sig = _extract_signal_from_dict(
            mat,
            source_name=file_path.name,
            preferred_signal_key=preferred_signal_key,
        )
        # 从 .mat 元数据字段提取 sample_rate / rpm
        meta = _extract_mat_metadata(mat)
    elif suffix == ".npy":
        sig = load_npy_signal(
            file_path,
            signal_key=preferred_signal_key or "sig_acc_5120",
        )
    else:
        raise ValueError(f"Unsupported file type: {suffix}. Only .mat and .npy are supported.")

    sig = np.nan_to_num(np.asarray(sig, dtype=np.float32).reshape(-1),
                        nan=0.0, posinf=0.0, neginf=0.0)
    return sig, file_path.name, meta


def _load_signal_from_bytes(
    filename: str,
    content: bytes,
    preferred_signal_key: Optional[str] = None,
) -> Tuple[np.ndarray, str, Dict[str, Any]]:
    """从上传字节流加载振动信号（.mat / .npy）。

    Returns
    -------
    (signal, filename, metadata)
    """
    from utils_signal import _extract_mat_metadata

    suffix = Path(filename).suffix.lower()
    meta: Dict[str, Any] = {"sample_rate": None, "rpm": None}

    if suffix == ".mat":
        payload = scipy.io.loadmat(BytesIO(content))
        sig = _extract_signal_from_dict(
            payload,
            source_name=filename,
            preferred_signal_key=preferred_signal_key,
        )
        meta = _extract_mat_metadata(payload)
    elif suffix == ".npy":
        payload = np.load(BytesIO(content), allow_pickle=True)
        if isinstance(payload, np.lib.npyio.NpzFile):
            try:
                if not payload.files:
                    raise ValueError("No arrays found in uploaded npz file.")
                arrays = {name: payload[name] for name in payload.files}
                sig = _extract_signal_from_dict(
                    arrays,
                    source_name=filename,
                    preferred_signal_key=preferred_signal_key,
                )
            finally:
                payload.close()
        elif isinstance(payload, np.ndarray) and payload.dtype == object:
            if payload.size == 1 and isinstance(payload.reshape(-1)[0], dict):
                sig = _extract_signal_from_dict(
                    payload.reshape(-1)[0],
                    source_name=filename,
                    preferred_signal_key=preferred_signal_key,
                )
            else:
                sig = np.concatenate([np.asarray(item).reshape(-1) for item in payload.flat])
        else:
            sig = np.asarray(payload, dtype=np.float32).reshape(-1)
    else:
        raise ValueError(f"Unsupported file type: {suffix}. Only .mat and .npy are supported.")

    sig = np.nan_to_num(np.asarray(sig, dtype=np.float32).reshape(-1),
                        nan=0.0, posinf=0.0, neginf=0.0)
    return sig, filename, meta


async def _read_upload_limited(file: UploadFile) -> bytes:
    """Read an upload in chunks, rejecting oversized files before memory spikes."""
    chunks: List[bytes] = []
    total = 0
    while True:
        chunk = await file.read(UPLOAD_CHUNK_BYTES)
        if not chunk:
            break
        total += len(chunk)
        if total > MAX_UPLOAD_BYTES:
            raise HTTPException(
                status_code=413,
                detail=f"Uploaded file is too large. Limit is {MAX_UPLOAD_BYTES // (1024 * 1024)} MB.",
            )
        chunks.append(chunk)
    return b"".join(chunks)


def _analyze_uploaded_content(model_type: str, filename: str, content: bytes) -> Dict[str, Any]:
    preferred_key = (
        bearing_model_params.get("signal_key", "DE_time") if model_type == "bearing"
        else gear_model_params.get("signal_key", "sig_acc_5120")
    )
    raw_signal, _, file_meta = _load_signal_from_bytes(filename, content, preferred_key)
    extra: Dict[str, Any] = {"filename": filename}
    if model_type == "bearing" and file_meta.get("sample_rate"):
        extra["sampleRate"] = float(file_meta["sample_rate"])
        extra["sample_rate"] = float(file_meta["sample_rate"])
    return _run_analysis(model_type, raw_signal, filename, f"{model_type}_upload", extra=extra)


# =============================================================================
# 缓存辅助
# =============================================================================
def _get_cached_or_compute(
    file_path: Path,
    model_type: str,
    compute_fn,
    *args: Any,
    **kwargs: Any,
) -> Dict[str, Any]:
    cache_key = f"{model_type}:{file_path.resolve()}"
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
def _clean_state_dict(state_dict: Dict[str, torch.Tensor]) -> Dict[str, torch.Tensor]:
    """Remove 'module.' prefix from DataParallel-wrapped state dict keys."""
    cleaned: Dict[str, torch.Tensor] = {}
    for k, v in state_dict.items():
        if k.startswith("module."):
            cleaned[k[len("module."):]] = v
        else:
            cleaned[k] = v
    return cleaned


def load_gear_model() -> Tuple[WDCNNMechDG, Dict[str, Any], List[str], Dict[str, Any]]:
    if not GEAR_MODEL_PATH.exists():
        raise FileNotFoundError(f"Gear model checkpoint not found: {GEAR_MODEL_PATH}")

    ckpt = v6.safe_torch_load(GEAR_MODEL_PATH, map_location=DEVICE)
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

    logger.info("Gear model loaded: %d classes %s on %s", num_classes, class_names, DEVICE)
    return model, params, class_names, classwise_cfg


def load_bearing_model() -> Tuple[nn.Module, Dict[str, Any], List[str]]:
    """
    Load bearing diagnosis model (ResNet1D18) from best_model.pth.

    The checkpoint follows the CCDG training pipeline format:
        state_dict  — model weights (possibly wrapped with 'module.' prefix)
        classes     — list of class label strings, e.g. ['N', 'OR', 'B']
        window_size / stride / signal_key / normalize — preprocessing config
    """
    if not BEARING_MODEL_PATH.exists():
        raise FileNotFoundError(f"Bearing model checkpoint not found: {BEARING_MODEL_PATH}")

    ckpt = v6.safe_torch_load(BEARING_MODEL_PATH, map_location=DEVICE)

    # ---- extract state dict ----
    if "state_dict" in ckpt:
        state_dict = _clean_state_dict(ckpt["state_dict"])
    else:
        state_dict = _clean_state_dict(ckpt)

    # ---- class names ----
    if "classes" in ckpt and ckpt["classes"]:
        class_names = [str(c) for c in ckpt["classes"]]
    else:
        # fallback: infer from fc.weight shape
        for k, v in state_dict.items():
            if k.endswith("fc.weight"):
                num_classes = int(v.shape[0])
                class_names = [f"class_{i}" for i in range(num_classes)]
                break
        else:
            class_names = ["N", "OR", "B"]

    num_classes = len(class_names)

    # ---- params ----
    params = {
        "fs": float(ckpt.get("sample_rate", ckpt.get("fs", 16000.0))),
        "win_len": int(ckpt.get("window_size", ckpt.get("win_len", 4096))),
        "stride": int(ckpt.get("stride", 4096)),
        "batch_size": int(ckpt.get("batch_size", 64)),
        "signal_key": str(ckpt.get("signal_key", "DE_time")),
        "normalize": str(ckpt.get("normalize", "zscore")),
        "num_classes": num_classes,
    }

    # ---- build model ----
    model = ResNet1D18(num_classes=num_classes).to(DEVICE)
    model.load_state_dict(state_dict, strict=True)
    model.eval()

    logger.info(
        "Bearing model loaded: %d classes %s on %s (window=%d, stride=%d, fs=%.0f)",
        num_classes, class_names, DEVICE,
        params["win_len"], params["stride"], params["fs"],
    )
    return model, params, class_names


# =============================================================================
# 诊断执行 — 直接调用 04.4 的 diagnose_signal_array
# =============================================================================
def _diagnose_gear(raw_signal: np.ndarray, source_name: str = "") -> Dict[str, Any]:
    """
    直接调用 04.4 的 diagnose_signal_array，无中间包装。
    """
    if gear_model is None:
        raise RuntimeError("Gear model is not loaded.")

    sig = np.asarray(raw_signal, dtype=np.float32).reshape(-1)
    win_len = int(gear_model_params["win_len"])
    stride = int(gear_model_params["stride"])
    if v6.count_windows(len(sig), win_len, stride) <= 0:
        raise ValueError(
            f"Signal too short ({sig.size} samples) for gear window size {win_len}"
        )

    return v6.diagnose_signal_array(
        model=gear_model,
        sig=sig,
        device=DEVICE,
        win_len=win_len,
        stride=stride,
        batch_size=int(gear_model_params.get("batch_size", 128)),
        class_names=gear_class_names,
        classwise_cfg=gear_classwise_cfg,
        class_file_unk_overrides=CLASS_FILE_UNK_OVERRIDES,
        mean_maha_accept_overrides=MEAN_MAHA_ACCEPT_OVERRIDES,
        fs=float(gear_model_params.get("fs", 5120.0)),
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


def _normalize_model_type(model_type: Optional[str]) -> str:
    value = str(model_type or "gear").strip().lower()
    if value not in VALID_MODEL_TYPES:
        raise HTTPException(status_code=400, detail="model_type must be 'gear' or 'bearing'.")
    return value


@torch.no_grad()
def _diagnose_bearing(raw_signal: np.ndarray, source_name: str = "") -> Dict[str, Any]:
    """
    Bearing diagnosis using ResNet1D18 with sliding-window inference.

    Preprocessing matches the CCDG training pipeline:
      - Per-window z-score normalization
      - Window size / stride from checkpoint params
      - File-level prediction = argmax of mean softmax probabilities
    """
    if bearing_model is None:
        raise RuntimeError("Bearing model is not loaded.")

    sig = np.asarray(raw_signal, dtype=np.float64).reshape(-1)
    win_len = int(bearing_model_params["win_len"])
    stride = int(bearing_model_params["stride"])
    batch_size = int(bearing_model_params.get("batch_size", 64))

    # window count
    if sig.size < win_len:
        n_win = 0
    else:
        n_win = (sig.size - win_len) // stride + 1

    if n_win == 0:
        raise ValueError(
            f"Signal too short ({sig.size} samples) for window size {win_len}"
        )

    probs_all: List[torch.Tensor] = []
    seg_preds: List[int] = []
    bearing_model.eval()

    for i in range(0, n_win, batch_size):
        windows = []
        for j in range(i, min(i + batch_size, n_win)):
            start = j * stride
            chunk = sig[start:start + win_len].copy()
            # per-window z-score (matches CCDG training pipeline)
            mu = chunk.mean()
            sigma = chunk.std()
            if sigma > 1e-8:
                chunk = (chunk - mu) / sigma
            else:
                chunk = chunk - mu
            windows.append(chunk)

        xb = torch.from_numpy(np.stack(windows)).float().unsqueeze(1).to(DEVICE)
        logits, _feat = bearing_model(xb)
        probs = torch.softmax(logits, dim=1).cpu()
        probs_all.append(probs)
        seg_preds.extend(torch.argmax(probs, dim=1).tolist())

    probs_tensor = torch.cat(probs_all, dim=0)          # [n_win, num_classes]
    mean_probs = probs_tensor.mean(dim=0).numpy()       # [num_classes]
    final_idx = int(np.argmax(mean_probs))

    seg_preds_np = np.asarray(seg_preds, dtype=np.int64)
    segment_consistency = (
        float((seg_preds_np == final_idx).mean()) if seg_preds_np.size else 0.0
    )

    entropy_per_segment = -torch.sum(
        probs_tensor * torch.log(torch.clamp(probs_tensor, min=1e-12)),
        dim=1,
    )
    mean_entropy = float(entropy_per_segment.mean().item())

    # determine decision reason based on confidence and consistency
    confidence = float(mean_probs[final_idx])
    if confidence >= 0.80 and segment_consistency >= 0.80:
        decision_reason = "bearing_high_confidence"
    elif confidence >= 0.65 and segment_consistency >= 0.60:
        decision_reason = "bearing_moderate_confidence"
    else:
        decision_reason = "bearing_low_confidence"

    return {
        "source_name": source_name,
        "prediction_index": final_idx,
        "prediction": bearing_class_names[final_idx] if final_idx < len(bearing_class_names) else "unknown",
        "confidence": confidence,
        "mean_probs": mean_probs,
        "segment_consistency": segment_consistency,
        "num_segments": int(n_win),
        "mean_entropy": mean_entropy,
        "decision_reason": decision_reason,
        "class_names": bearing_class_names,
    }


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
    for cname in gear_class_names:
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
    final_pred_cn = str(v6_result.get("final_result_cn", final_pred))
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
        # 核心 — 优先使用中文名称
        "label": final_pred_cn,
        "diagnosisResult": final_pred_cn,
        "diagnosisName": final_pred_cn,
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


def _build_bearing_frontend_payload(
    bearing_result: Dict[str, Any],
    raw_signal: np.ndarray,
    source_name: str,
    sample_rate: float = 16000.0,
    extra: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    sig = np.asarray(raw_signal, dtype=np.float64).reshape(-1)
    n = sig.size
    time_axis_full = np.arange(n, dtype=np.float64) / sample_rate if sample_rate > 0 else np.arange(n, dtype=np.float64)
    time_axis, time_data = v6.downsample_curve(time_axis_full, sig, max_points=DISPLAY_POINTS)
    spectrum = v6.compute_fft_full_curve(sig, fs=sample_rate, max_points=DISPLAY_SPECTRUM_POINTS, f_min=0.0)
    metrics = v6.compute_industrial_metrics(sig, fs=sample_rate)

    # use checkpoint class names dynamically
    class_names = bearing_result.get("class_names", bearing_class_names)
    prediction = str(bearing_result["prediction"])
    prediction_cn = BEARING_CLASS_CN_MAP.get(prediction, prediction)
    confidence = float(bearing_result["confidence"])
    confidence_pct = round(float(np.clip(confidence * 100.0, CONFIDENCE_MIN, CONFIDENCE_MAX)), 2)
    mean_entropy = float(bearing_result["mean_entropy"])
    segment_consistency = float(bearing_result["segment_consistency"])
    mean_probs = np.asarray(bearing_result["mean_probs"], dtype=np.float64)

    top_probs = [
        {"class": cname, "probability": round(float(prob) * 100.0, 2)}
        for cname, prob in zip(class_names, mean_probs)
    ]
    top_probs.sort(key=lambda x: x["probability"], reverse=True)

    # risk / health assessment
    is_normal = prediction.lower() in ("n", "normal", "healthy")
    risk_level = "低" if is_normal else "高"
    alarm_level = "normal" if is_normal else "alarm"
    health_score = confidence * 100.0 if is_normal else max(0.0, 100.0 - confidence * 100.0)

    evidence = [
        {
            "title": "模型类型",
            "desc": f"轴承诊断模型 (ResNet1D18, {len(class_names)}类)",
            "type": "info",
            "level": "信息",
        },
        {
            "title": "决策原因",
            "desc": str(bearing_result["decision_reason"]),
            "type": "info",
            "level": "信息",
        },
        {
            "title": "片段一致性",
            "desc": f"{segment_consistency:.4f}",
            "type": "success" if segment_consistency > 0.8 else "warning",
            "level": "高" if segment_consistency > 0.8 else "中",
        },
        {
            "title": "Average entropy",
            "desc": f"{mean_entropy:.4f}",
            "type": "info",
            "level": "信息",
        },
    ]

    data: Dict[str, Any] = {
        "label": prediction_cn,
        "diagnosisResult": prediction_cn,
        "diagnosisName": prediction_cn,
        "confidence": confidence_pct,
        "healthIndex": int(round(health_score)),
        "riskLevel": risk_level,
        "alarmLevel": alarm_level,
        "diagnosisDetail": (
            f"轴承诊断: {bearing_result['decision_reason']} | "
            f"预测:{prediction}({prediction_cn}) conf={confidence:.4f}"
        ),
        "diagnosis_detail": f"轴承诊断: {bearing_result['decision_reason']}",
        "decision_reason": str(bearing_result["decision_reason"]),
        "closedPrediction": prediction,
        "unknownRatio": 0.0,
        "segmentConsistency": round(segment_consistency, 6),
        "meanMahalanobis": 0.0,
        "meanEntropy": round(mean_entropy, 6),
        "source_name": source_name,
        "sourceName": source_name,
        "topProbabilities": top_probs[:len(class_names)],
        "evidence": evidence,
        "time_axis": time_axis,
        "time_data": time_data,
        "waveform": time_data,
        "freq_axis": spectrum["freq_hz"],
        "frequencyAxis": spectrum["freq_hz"],
        "freq_data": spectrum["amplitude"],
        "spectrum": spectrum["amplitude"],
        "rms": round(v6.safe_float(metrics["rms"]), 6),
        "latestRms": round(v6.safe_float(metrics["rms"]), 6),
        "peak": round(v6.safe_float(metrics["peak"]), 6),
        "latestPeak": round(v6.safe_float(metrics["peak"]), 6),
        "sample_rate": sample_rate,
        "sampleRate": sample_rate,
        "count": len(time_data),
        "analysis_mode": "bearing_resnet18",
        "modelType": "bearing",
        "modelVersion": "best_model.pth (ResNet1D18)",
        "numSegments": int(bearing_result["num_segments"]),
    }

    if extra:
        data.update(extra)
    return data


def _run_analysis(
    model_type: str,
    raw_signal: np.ndarray,
    source_name: str,
    analysis_mode: str,
    extra: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    model_type = _normalize_model_type(model_type)
    mode = str(analysis_mode or "infer")
    if not mode.startswith(f"{model_type}_"):
        mode = f"{model_type}_{mode}"
    extra_payload = dict(extra or {})
    extra_payload["modelType"] = model_type

    with _model_inference_lock:
        if model_type == "gear":
            fs = float(extra_payload.get("sample_rate") or extra_payload.get("sampleRate")
                       or gear_model_params.get("fs", 5120.0))
            v6_result = _diagnose_gear(raw_signal, source_name=source_name)
            extra_payload.setdefault("modelVersion", "best_model_classwise_maha.pth (v6)")
            extra_payload["analysis_mode"] = mode
            return _build_frontend_payload(v6_result, raw_signal, source_name, sample_rate=fs, extra=extra_payload)

        fs = float(extra_payload.get("sample_rate") or extra_payload.get("sampleRate")
                   or bearing_model_params.get("fs", 16000.0))
        bearing_result = _diagnose_bearing(raw_signal, source_name=source_name)
        extra_payload.setdefault("modelVersion", "best_model.pth (ResNet1D18)")
        extra_payload["analysis_mode"] = mode
        return _build_bearing_frontend_payload(
            bearing_result,
            raw_signal,
            source_name,
            sample_rate=fs,
            extra=extra_payload,
        )


def _build_health_payload() -> Dict[str, Any]:
    return {
        "status": "ok",
        "device": str(DEVICE),
        "model_loaded": gear_model is not None and bearing_model is not None,
        "gear_model_loaded": gear_model is not None,
        "bearing_model_loaded": bearing_model is not None,
        "model_path": str(GEAR_MODEL_PATH),
        "gear_model_path": str(GEAR_MODEL_PATH),
        "bearing_model_path": str(BEARING_MODEL_PATH),
        "version": "v2_dual_model",
        "classes": gear_class_names,
        "gear_classes": gear_class_names,
        "bearing_classes": bearing_class_names,
        "bearing_classes_cn": [BEARING_CLASS_CN_MAP.get(c, c) for c in bearing_class_names],
        "win_len": gear_model_params.get("win_len"),
        "stride": gear_model_params.get("stride"),
        "fs": gear_model_params.get("fs"),
        "gear_params": gear_model_params,
        "bearing_params": bearing_model_params,
    }




# =============================================================================
# API 端点
# =============================================================================

@app.get("/health")
def health() -> Dict[str, Any]:
    return _build_health_payload()


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
def analyze(
    file_name: Optional[str] = Query(default=None, min_length=1),
    model_type: str = Query(default="gear"),
) -> Dict[str, Any]:
    model_type = _normalize_model_type(model_type)

    try:
        file_path = _resolve_analysis_file(file_name)
        if model_type == "gear" and gear_model is None:
            raise HTTPException(status_code=500, detail="Gear model is not loaded.")
        if model_type == "bearing" and bearing_model is None:
            raise HTTPException(status_code=500, detail="Bearing model is not loaded.")

        def _compute() -> Dict[str, Any]:
            preferred_key = (
                bearing_model_params.get("signal_key", "DE_time") if model_type == "bearing"
                else gear_model_params.get("signal_key", "sig_acc_5120")
            )
            raw_signal, _, file_meta = _load_signal_from_path(file_path, preferred_key)
            extra: Dict[str, Any] = {}
            if file_meta.get("sample_rate"):
                extra["sample_rate"] = float(file_meta["sample_rate"])
                extra["sampleRate"] = float(file_meta["sample_rate"])
            return _run_analysis(
                model_type,
                raw_signal,
                file_path.name,
                f"{model_type}_{'latest' if file_name is None else 'specified'}",
                extra=extra,
            )

        result = _get_cached_or_compute(file_path, model_type, _compute)
        logger.info(
            "analyze: file=%s model=%s diagnosis=%s",
            file_path.name,
            model_type,
            result.get("diagnosisResult"),
        )
        _save_to_db_sync(result)
        _broadcast_analysis_sync(result)
        return JSONResponse(
            content={"success": True, "data": result},
            headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"},
        )

    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("analyze failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/analyze/upload")
async def analyze_upload(
    file: UploadFile = File(...),
    model_type: str = Form(default="gear"),
) -> Dict[str, Any]:
    model_type = _normalize_model_type(model_type)

    suffix = Path(file.filename).suffix.lower()
    if suffix not in {".mat", ".npy"}:
        raise HTTPException(status_code=400, detail="Only .mat and .npy files are supported.")

    content = await _read_upload_limited(file)
    try:
        if model_type == "gear" and gear_model is None:
            raise HTTPException(status_code=500, detail="Gear model is not loaded.")
        if model_type == "bearing" and bearing_model is None:
            raise HTTPException(status_code=500, detail="Bearing model is not loaded.")
        loop = asyncio.get_running_loop()
        result = await loop.run_in_executor(
            _inference_executor,
            _analyze_uploaded_content,
            model_type,
            file.filename,
            content,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Failed to analyze file: {exc}") from exc

    await _save_to_db(result)
    await _broadcast_analysis_async(result)
    return {"success": True, "data": result}


@app.post("/infer")
def infer(payload: Dict[str, Any]) -> Dict[str, Any]:
    model_type = _normalize_model_type(payload.get("modelType") or payload.get("model_type") or "gear")

    file_path = str(payload.get("filePath") or "")
    if not file_path:
        raise HTTPException(status_code=400, detail="filePath is required.")

    source_path = Path(file_path)
    if not source_path.is_absolute():
        source_path = DATA_DIR / source_path

    if not source_path.exists():
        raise HTTPException(status_code=404, detail=f"File not found: {source_path}")

    suffix = source_path.suffix.lower()
    if suffix not in {".mat", ".npy"}:
        raise HTTPException(status_code=400, detail=f"Unsupported file type: {suffix}. Only .mat and .npy are supported.")

    if model_type == "gear" and gear_model is None:
        raise HTTPException(status_code=500, detail="Gear model is not loaded.")
    if model_type == "bearing" and bearing_model is None:
        raise HTTPException(status_code=500, detail="Bearing model is not loaded.")

    def _compute() -> Dict[str, Any]:
        preferred_key = (
            bearing_model_params.get("signal_key", "DE_time") if model_type == "bearing"
            else gear_model_params.get("signal_key", "sig_acc_5120")
        )
        raw_signal, _, file_meta = _load_signal_from_path(source_path, preferred_key)
        extra = {
            "deviceCode": payload.get("deviceCode"),
            "filename": payload.get("filename") or source_path.name,
            "batchId": payload.get("batchId"),
            "sampleTime": payload.get("sampleTime"),
        }
        if file_meta.get("sample_rate"):
            extra["sample_rate"] = float(file_meta["sample_rate"])
            extra["sampleRate"] = float(file_meta["sample_rate"])
        return _run_analysis(
            model_type,
            raw_signal,
            source_path.name,
            payload.get("analysisMode", f"{model_type}_infer"),
            extra=extra,
        )

    result = _get_cached_or_compute(source_path, model_type, _compute)
    _save_to_db_sync(result)
    _broadcast_analysis_sync(result)
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
                    payload = _build_health_payload()
                    payload["type"] = "health_status"
                    await websocket.send_json(payload)
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
