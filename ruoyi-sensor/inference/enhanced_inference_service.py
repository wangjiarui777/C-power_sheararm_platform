"""
[DEPRECATED] 此文件已被 inference_service.py 取代。

inference_service.py 直接调用 04.4 原生函数进行 FFT/降采样/工业指标计算，
消除了重复的包装代码。新部署请使用 inference_service.py。

本文件保留仅作参考。
"""

from __future__ import annotations

# =============================================================================
# 标准库 - 异步、动态导入、日志、文件操作等
# =============================================================================
import asyncio
import importlib.util
import logging
import os
from contextlib import asynccontextmanager
from io import BytesIO
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# =============================================================================
# 第三方库
# =============================================================================
import numpy as np                          # 数值计算
import scipy.io                             # 读取 .mat 文件
import torch                                # PyTorch 深度学习框架
import uvicorn                              # ASGI 服务器
from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from starlette.responses import JSONResponse
from pydantic import BaseModel

# =============================================================================
# 项目内部模块
# =============================================================================
from models.wdcnn_mech_dg2 import WDCNNMechDG   # 改进的WDCNN模型（含域泛化）
from utils_signal import load_npy_signal, top_fft_peaks  # 信号加载与FFT峰值提取工具
from db_writer import save_inference_result, query_history  # 诊断结果写入MySQL及历史查询

# ---------------------------------------------------------------------------
# 动态导入 v6 诊断模块（文件名以数字开头，无法直接 import，所以用 spec_from_file_location）
# ---------------------------------------------------------------------------
BASE_DIR = Path(__file__).resolve().parent  # 当前文件所在目录的绝对路径
_v6_spec = importlib.util.spec_from_file_location(
    "diagnose_v6",
    str(BASE_DIR / "04.4_diagnose_unlabeled_target.py"),  # v6 诊断脚本路径（04.4 包含平台展示字段）
)
v6 = importlib.util.module_from_spec(_v6_spec)  # 根据 spec 创建模块对象
_v6_spec.loader.exec_module(v6)                 # 执行模块代码，完成加载

# =============================================================================
# 日志配置
# =============================================================================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
logger = logging.getLogger("enhanced_inference")

# =============================================================================
# 路径与常量配置
# =============================================================================
MODEL_PATH = BASE_DIR / "get" / "best_model_classwise_maha.pth"  # 训练好的模型权重文件
DATA_DIR = BASE_DIR / "get" / "got"                               # 待分析数据文件目录

DEFAULT_PORT = int(os.environ.get("PORT", 5001))  # 服务端口，默认 5001
DISPLAY_POINTS = 2048                              # 前端显示波形的采样点数（降采样后）
DISPLAY_SPECTRUM_POINTS = 512                      # 前端显示频谱的采样点数（降采样后）
CONFIDENCE_MIN = 1.0                               # 置信度百分比下限
CONFIDENCE_MAX = 99.0                              # 置信度百分比上限

# =============================================================================
# 响应缓存 - 基于文件 mtime 判断是否需要重新计算
# =============================================================================
_response_cache: Dict[str, Tuple[float, Dict[str, Any]]] = {}
CACHE_MAX_SIZE = 32  # 最多缓存 32 条结果，超出后淘汰最旧的

# =============================================================================
# FastAPI 应用初始化
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


app = FastAPI(title="Enhanced Vibration Diagnosis Service v6", version="2.0.0", lifespan=lifespan)

# 添加 CORS 中间件 - 允许前端跨域访问
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],        # 允许所有来源（生产环境应限制为具体域名）
    allow_credentials=True,     # 允许携带凭证
    allow_methods=["*"],        # 允许所有 HTTP 方法
    allow_headers=["*"],        # 允许所有请求头
)


class AnalyzeResponse(BaseModel):
    """分析接口的响应模型"""
    success: bool               # 请求是否成功
    data: Dict[str, Any]        # 返回的诊断数据


# =============================================================================
# WebSocket 连接管理器
# 负责维护所有活跃的 WebSocket 连接，支持广播消息给所有客户端
# =============================================================================
class ConnectionManager:
    def __init__(self) -> None:
        self.active_connections: List[WebSocket] = []  # 当前活跃的 WebSocket 连接列表

    async def connect(self, websocket: WebSocket) -> None:
        """接受新的 WebSocket 连接并加入活跃列表"""
        await websocket.accept()
        self.active_connections.append(websocket)
        logger.info("WebSocket client connected (total: %d)", len(self.active_connections))

    def disconnect(self, websocket: WebSocket) -> None:
        """从活跃列表中移除断开的 WebSocket 连接"""
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)
            logger.info("WebSocket client disconnected (total: %d)", len(self.active_connections))

    async def broadcast(self, message: Dict[str, Any]) -> None:
        """
        向所有活跃连接广播 JSON 消息
        自动清理已失效的连接（发送失败即为失效）
        """
        dead: List[WebSocket] = []  # 收集发送失败的连接
        for conn in self.active_connections:
            try:
                await conn.send_json(message)
            except Exception:
                dead.append(conn)
        # 批量移除失效连接
        for conn in dead:
            if conn in self.active_connections:
                self.active_connections.remove(conn)

    async def broadcast_health(self) -> None:
        """向所有活跃连接广播当前模型健康状态"""
        await self.broadcast({
            "type": "health_status",
            "status": "ok",
            "device": str(DEVICE),
            "model_loaded": MODEL is not None,
            "model_path": str(MODEL_PATH) if MODEL_PATH else "",
            "version": "v6_mahalanobis",
            "classes": CLASS_NAMES,
            "win_len": MODEL_PARAMS.get("win_len") if MODEL_PARAMS else None,
            "stride": MODEL_PARAMS.get("stride") if MODEL_PARAMS else None,
            "fs": MODEL_PARAMS.get("fs") if MODEL_PARAMS else None,
        })

    async def broadcast_file_list(self) -> None:
        """向所有活跃连接广播当前 .mat/.npy 文件列表"""
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


ws_manager = ConnectionManager()  # 全局 WebSocket 管理器单例


# =============================================================================
# 后台健康状态广播
# =============================================================================
_health_broadcaster_task: Optional[asyncio.Task] = None


async def _health_broadcaster(interval: float = 30.0) -> None:
    """每隔 interval 秒向所有 WebSocket 客户端推送一次健康状态"""
    await asyncio.sleep(5)  # 初始延迟，等待模型加载完成
    while True:
        await asyncio.sleep(interval)
        try:
            await ws_manager.broadcast_health()
        except Exception:
            logger.exception("Health broadcast failed")


# =============================================================================
# 文件监控器 - 自动分析新增/修改的数据文件，并通过 WebSocket 推送结果
# =============================================================================
_known_files: Dict[str, float] = {}       # 已跟踪文件字典：路径 -> 修改时间
_watcher_task: Optional[asyncio.Task] = None  # 监控任务引用


async def _auto_analyze_and_broadcast(file_path: Path) -> None:
    """
    自动分析一个文件并将诊断结果推送给所有 WebSocket 客户端
    在线程池中执行耗时的信号加载和诊断计算，避免阻塞事件循环
    """
    try:
        loop = asyncio.get_running_loop()
        # 在线程池中加载信号（I/O + 数据解析较重）
        raw_signal, _signal_field = await loop.run_in_executor(
            None, load_signal_from_file, file_path,
        )
        fs = float(MODEL_PARAMS.get("fs", 5120.0))  # 采样率
        # 在线程池中执行 v6 诊断（计算密集型）
        v6_result = await loop.run_in_executor(
            None, diagnose_signal_v6, raw_signal, file_path.name,
        )
        # 将诊断结果映射为前端期望的格式
        result = await loop.run_in_executor(
            None, map_result_to_frontend, v6_result, raw_signal, file_path.name, fs,
            {"analysis_mode": "v6_auto", "filename": file_path.name},
        )
        # 写入数据库（fail-safe）
        await _save_to_db(result)
        # 通过 WebSocket 广播诊断结果
        await ws_manager.broadcast({"type": "auto_analysis", "success": True, "data": result})
        logger.info("Auto-analyzed & pushed: %s -> %s", file_path.name, result.get("diagnosisResult"))
    except Exception as exc:
        logger.exception("Auto-analysis failed for %s", file_path.name)
        await ws_manager.broadcast({
            "type": "auto_analysis", "success": False,
            "filename": file_path.name, "error": str(exc),
        })


async def _file_watcher_loop(interval: float = 3.0) -> None:
    """
    文件监控主循环
    每隔 interval 秒扫描 DATA_DIR，自动分析新增或修改的 .mat / .npy 文件
    同时清理已删除文件的跟踪记录
    """
    global _known_files

    # 启动时先记录已有文件状态，但不触发分析（避免重启后重复分析历史数据）
    if DATA_DIR.exists():
        for p in DATA_DIR.glob("*"):
            if p.suffix.lower() in {".mat", ".npy"}:
                try:
                    _known_files[str(p)] = p.stat().st_mtime
                except OSError:
                    pass
    logger.info("File watcher seeded with %d known files", len(_known_files))

    while True:
        await asyncio.sleep(interval)  # 定时扫描间隔
        if not DATA_DIR.exists():
            continue

        # 扫描当前目录中的所有数据文件及其修改时间
        current: Dict[str, float] = {}
        for p in DATA_DIR.glob("*"):
            if p.suffix.lower() not in {".mat", ".npy"}:
                continue
            try:
                current[str(p)] = p.stat().st_mtime
            except OSError:
                continue

        # 检测新增或修改的文件，触发自动分析
        for path_str, mtime in current.items():
            prev = _known_files.get(path_str)
            if prev is None or prev != mtime:
                _known_files[path_str] = mtime
                await _auto_analyze_and_broadcast(Path(path_str))
                await ws_manager.broadcast_file_list()

        # 清理已被删除文件的跟踪记录
        for path_str in list(_known_files.keys()):
            if path_str not in current:
                del _known_files[path_str]


# =============================================================================
# 全局模型状态
# =============================================================================
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")  # 自动选择 GPU/CPU
MODEL: Optional[WDCNNMechDG] = None               # 加载的模型实例
MODEL_PARAMS: Dict[str, Any] = {}                 # 模型参数（fs, win_len, stride 等）
CLASS_NAMES: List[str] = []                       # 故障类别名称列表
CLASSWISE_CFG: Dict[str, Any] = {}                # 各类别的开集识别配置
CLASS_FILE_UNK_OVERRIDES: Dict[str, float] = {}   # 各类别的未知样本比率阈值覆盖
MEAN_MAHA_ACCEPT_OVERRIDES: Dict[str, float] = {} # 各类别的马氏距离接受阈值覆盖
KNOWN_FAULT_PROTECT_CLASSES: List[str] = []       # 已知故障保护类别（不会被误判为未知）


# =============================================================================
# 信号加载辅助函数 - 支持 .mat 和 .npy 两种格式
# =============================================================================
def _extract_signal_from_dict(
    payload: Dict[str, Any], source_name: str = "<memory>"
) -> Tuple[np.ndarray, str]:
    """
    从字典数据结构中自动提取振动信号数组
    优先匹配常见信号变量名（如 sig_acc_5120, raw, data, vibration 等）

    参数:
        payload: 从 .mat 或 .npz 文件加载的字典数据
        source_name: 数据来源标识（用于日志和错误提示）

    返回:
        (信号数组, 匹配到的变量名)
    """
    # 过滤掉以 __ 开头的内部/元数据键
    keys = [k for k in payload if not str(k).startswith("__")]
    if not keys:
        raise KeyError(f"No valid data variables found in {source_name}")

    # 按优先级尝试匹配常见的振动信号变量名
    preferred = [
        "sig_acc_5120", "raw", "data", "signal", "vibration",
        "x", "X", "voltage", "DE_time", "wave", "values",
    ]
    selected = next((k for k in preferred if k in payload), keys[0])

    # 转为 float32 一维数组
    signal = np.asarray(payload[selected], dtype=np.float32).squeeze()
    if signal.size == 0:
        raise ValueError(f"Variable '{selected}' contains no samples.")
    if signal.ndim != 1:
        signal = signal.reshape(-1)  # 多维数组展平为一维

    # 将 NaN 和无穷值替换为 0，避免后续计算异常
    signal = np.nan_to_num(signal, nan=0.0, posinf=0.0, neginf=0.0)
    logger.info("Loaded signal variable '%s' from %s", selected, source_name)
    return signal, selected


def load_signal_from_file(file_path: Path) -> Tuple[np.ndarray, str]:
    """
    从文件中加载振动信号
    支持 .mat（MATLAB 格式）和 .npy（NumPy 格式）

    返回:
        (信号数组, 文件名)
    """
    suffix = file_path.suffix.lower()
    if suffix == ".mat":
        if not file_path.exists():
            raise FileNotFoundError(f".mat file not found: {file_path}")
        mat = scipy.io.loadmat(str(file_path))  # 用 scipy 读取 .mat 文件
        return _extract_signal_from_dict(mat, source_name=file_path.name)
    if suffix == ".npy":
        sig = load_npy_signal(file_path, signal_key="sig_acc_5120")
        return sig, file_path.name
    raise ValueError(f"Unsupported file type: {suffix}. Only .mat and .npy are supported.")


# =============================================================================
# 显示数据降采样 - 减少前端绘图数据量，降低渲染压力
# =============================================================================
def downsample_for_display(signal: np.ndarray, target_points: int) -> np.ndarray:
    """
    将原始信号降采样到指定点数，用于前端波形显示
    使用线性插值法，保留信号的整体形状特征
    """
    signal = np.asarray(signal, dtype=np.float32).reshape(-1)
    if signal.size <= target_points:
        return signal.copy()  # 数据量不大时不降采样

    # 线性插值降采样
    x_old = np.linspace(0.0, 1.0, signal.size, endpoint=True)
    x_new = np.linspace(0.0, 1.0, target_points, endpoint=True)
    return np.interp(x_new, x_old, signal).astype(np.float32)


def _downsample_spectrum(
    freq_axis: np.ndarray, freq_amp: np.ndarray, target_points: int = 512,
) -> Tuple[List[float], List[float]]:
    """
    将频谱数据降采样到指定点数，减轻前端渲染负担
    使用等间隔采样，保持频谱的整体包络形状
    """
    n = freq_amp.size
    if n <= target_points:
        return freq_axis.astype(float).tolist(), freq_amp.astype(float).tolist()
    indices = np.linspace(0, n - 1, target_points, dtype=int)
    return freq_axis[indices].astype(float).tolist(), freq_amp[indices].astype(float).tolist()


# =============================================================================
# 响应缓存 - 使用 LRU 策略，基于文件修改时间判断是否需要重新计算
# =============================================================================
def _get_cached_or_compute(file_path: Path, compute_fn, *args: Any, **kwargs: Any) -> Dict[str, Any]:
    """
    如果文件的 mtime 未发生变化，直接返回缓存结果
    否则调用 compute_fn 重新计算并更新缓存

    缓存策略: 当缓存条目超过 CACHE_MAX_SIZE 时，淘汰最早加入的那条
    """
    cache_key = str(file_path.resolve())  # 用文件绝对路径作为缓存键
    try:
        mtime = file_path.stat().st_mtime  # 获取文件最后修改时间
    except OSError:
        mtime = 0.0

    if cache_key in _response_cache:
        cached_mtime, cached_result = _response_cache[cache_key]
        if cached_mtime == mtime:  # 文件未修改，命中缓存
            logger.info("Cache hit for %s", file_path.name)
            return cached_result

    # 缓存未命中或文件已更新，重新计算
    result = compute_fn(*args, **kwargs)
    _response_cache[cache_key] = (mtime, result)

    # 缓存数量超过上限，移除最旧的条目（简单 LRU 淘汰）
    if len(_response_cache) > CACHE_MAX_SIZE:
        oldest = next(iter(_response_cache))
        del _response_cache[oldest]
        logger.debug("Cache evicted oldest entry")
    return result


async def _save_to_db(result: Dict[str, Any]) -> None:
    """异步写入MySQL，失败不影响主流程"""
    try:
        loop = asyncio.get_running_loop()
        await loop.run_in_executor(None, save_inference_result, result)
    except Exception as exc:
        logger.warning("DB write failed (non-fatal): %s", exc)


def _save_to_db_sync(result: Dict[str, Any]) -> None:
    """同步写入MySQL（用于同步端点），失败不影响主流程"""
    try:
        save_inference_result(result)
    except Exception as exc:
        logger.warning("DB write failed (non-fatal): %s", exc)


# =============================================================================
# 模型加载
# =============================================================================
def load_model() -> Tuple[WDCNNMechDG, Dict[str, Any], List[str], Dict[str, Any]]:
    """
    加载 PyTorch 模型权重和配置
    从检查点文件中恢复模型结构、参数、类别名称和开集识别配置

    返回:
        (模型实例, 参数字典, 类别名称列表, 各类别开集配置)
    """
    if not MODEL_PATH.exists():
        raise FileNotFoundError(f"Model checkpoint not found: {MODEL_PATH}")

    # 安全加载模型检查点（使用 v6 模块提供的安全加载函数）
    ckpt = v6.safe_torch_load(MODEL_PATH, map_location=DEVICE)
    params = ckpt["params"]                          # 模型超参数（fs, win_len 等）
    class_names = [str(x) for x in ckpt["classes"]]  # 故障类别名称
    num_classes = len(class_names)
    classwise_cfg = ckpt["classwise_open_set"]       # 各类别的开集识别阈值配置

    # 构建 WDCNN 模型结构
    model = WDCNNMechDG(
        num_classes=num_classes,
        num_domains=2,                               # 域数量（源域+目标域）
        fs=float(params["fs"]),                      # 采样频率
        win_len=int(params["win_len"]),              # 窗口长度（采样点数）
        feat_dim=int(params["feat_dim"]),            # 特征维度
    ).to(DEVICE)
    model.load_state_dict(ckpt["model_state"])       # 加载权重
    model.eval()                                     # 设为评估模式（禁用 dropout 等）

    logger.info(
        "Model loaded: %d classes %s on %s",
        num_classes, class_names, DEVICE,
    )
    return model, params, class_names, classwise_cfg


# =============================================================================
# v6 马氏距离诊断包装器 — 直接使用 diagnose_signal_array 避免文件往返
# =============================================================================
def diagnose_signal_v6(raw_signal: np.ndarray, source_name: str = "") -> Dict[str, Any]:
    """
    使用 v6 马氏距离诊断流程对内存中的信号数组进行故障诊断。

    直接调用 v6.diagnose_signal_array（与 diagnose_one_file 逻辑完全一致），
    消除不必要的 np.save → np.load 文件往返，避免：
    - 文件 I/O 开销
    - 并发请求时的临时文件竞态条件
    - Windows 文件锁定问题

    参数:
        raw_signal: 原始振动信号一维数组
        source_name: 信号来源名称（用于结果标识）

    返回:
        诊断结果字典，包含预测类别、置信度、各片段分析等
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
        # unknown_vote_required=3 而非校准的2：
        # inv_cov 矩阵极值达 10000，导致 ratio_far_maha ≡ 1.0（所有 segment 的
        # Mahalanobis 距离都超阈值），cond_far_maha 完全失效。
        # 若 vote_required=2，则 unknown ≈ low_conf OR high_entropy（过于敏感）。
        # 提升至 3 要求 low_conf AND high_entropy 同时成立，补偿 Mahalanobis 失效。
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
# 诊断结果映射 - 将 v6 的详细诊断输出转换为前端可消费的 JSON 格式
# =============================================================================
def map_result_to_frontend(
    v6_result: Dict[str, Any],
    raw_signal: np.ndarray,
    source_name: str,
    sample_rate: float = 5120.0,
    extra: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """
    将 v6 诊断结果映射为前端兼容的数据结构

    包含:
    - 最终预测标签和置信度
    - 健康指数和告警等级
    - 决策证据（特征频率、片段一致性、马氏距离等）
    - 降采样后的时域波形和频域频谱
    - 各故障类别的概率分布
    """
    # 提取核心诊断结果
    final_pred = v6_result["final_prediction"]        # 最终预测类别（可能为 "未知"）
    closed_pred = v6_result["closed_prediction"]      # 闭集预测结果
    confidence = float(v6_result["closed_confidence"]) # 闭集置信度
    health_score = float(v6_result["health_score"])    # 健康指数（0-100）
    alarm_level = v6_result["alarm_level"]             # 告警等级

    # ---- 时域波形处理 ----
    display_sig = downsample_for_display(raw_signal, DISPLAY_POINTS)
    time_axis = (np.arange(display_sig.size, dtype=np.float32) / sample_rate).astype(float).tolist()

    # ---- 频域频谱处理 ----
    sig_centered = raw_signal - raw_signal.mean()  # 去除直流分量
    n = raw_signal.size
    fft_amp = np.abs(np.fft.rfft(sig_centered)) / n  # 单边 FFT 幅值计算
    if fft_amp.size > 2:
        fft_amp[1:-1] *= 2.0  # 非直流/奈奎斯特分量幅度修正（补偿能量）
    max_amp = float(np.max(fft_amp)) if fft_amp.size else 1.0
    if max_amp > 0:
        fft_amp = fft_amp / max_amp  # 归一化到 [0, 1]
    freq_axis_full = np.fft.rfftfreq(n, d=1.0 / sample_rate)  # 频率轴
    freq_axis, freq_data = _downsample_spectrum(
        freq_axis_full, fft_amp, DISPLAY_SPECTRUM_POINTS,
    )

    # ---- 决策证据列表 - 在前端展示为什么做出这个诊断 ----
    evidence: List[Dict[str, Any]] = []

    evidence.append({
        "title": "决策原因",
        "desc": v6_result["decision_reason"],
        "type": "info",
        "level": "信息",
    })
    evidence.append({
        "title": "闭集预测",
        "desc": f"{closed_pred} (置信度 {confidence:.4f})",
        "type": "info",
        "level": "信息",
    })
    evidence.append({
        "title": "Unknown比例",
        "desc": f"{v6_result['unknown_ratio']:.4f} / 阈值 {v6_result.get('class_file_unknown_ratio_threshold', 'N/A')}",
        "type": "warning" if v6_result["unknown_ratio"] > 0.3 else "info",
        "level": "高" if v6_result["unknown_ratio"] > 0.5 else "中" if v6_result["unknown_ratio"] > 0.3 else "低",
    })
    evidence.append({
        "title": "片段一致性",
        "desc": f"{v6_result['segment_consistency']:.4f}",
        "type": "success" if v6_result["segment_consistency"] > 0.8 else "warning",
        "level": "高" if v6_result["segment_consistency"] > 0.8 else "中",
    })
    evidence.append({
        "title": "Mean Mahalanobis",
        "desc": f"{v6_result['mean_mahalanobis']:.4f}",
        "type": "info",
        "level": "信息",
    })
    evidence.append({
        "title": "Average entropy",
        "desc": f"{v6_result['mean_entropy']:.4f}",
        "type": "info",
        "level": "信息",
    })

    # ---- 证据频率（Top-N FFT 峰值） ----
    ef = v6_result.get("evidence_frequencies", [])
    if ef:
        freq_strs = [f"{f:.1f} Hz" for f, _ in ef[:3]]
        evidence.append({
            "title": "特征频率",
            "desc": ", ".join(freq_strs) if freq_strs else "无",
            "type": "info",
            "level": "信息",
        })

    # ---- 各类别概率分布（用于前端饼图/柱状图展示） ----
    top_probs: List[Dict[str, Any]] = []
    for i, cname in enumerate(CLASS_NAMES):
        key = f"prob_{cname}"
        if key in v6_result:
            top_probs.append({
                "class": cname,
                "probability": round(float(v6_result[key]) * 100.0, 2),
            })
    top_probs.sort(key=lambda x: x["probability"], reverse=True)  # 按概率降序排列

    # ---- 告警等级到风险等级的映射 ----
    alarm_to_risk = {
        "normal": "低",
        "attention": "中",
        "warning": "中",
        "alarm": "高",
    }

    # ---- 计算振动信号的基础统计量 ----
    rms = float(np.sqrt(np.mean(np.square(raw_signal)))) if raw_signal.size else 0.0  # 均方根值
    peak = float(np.max(np.abs(raw_signal))) if raw_signal.size else 0.0               # 峰值

    # 置信度转为百分比，并限制在 [1%, 99%] 范围内
    confidence_pct = round(float(np.clip(confidence * 100.0, CONFIDENCE_MIN, CONFIDENCE_MAX)), 2)

    # ---- 组装返回给前端的完整数据 ----
    data: Dict[str, Any] = {
        # 诊断核心结果
        "label": final_pred,
        "diagnosisResult": final_pred,
        "diagnosisName": final_pred,
        "confidence": confidence_pct,
        "healthIndex": int(round(health_score)),
        "riskLevel": alarm_to_risk.get(alarm_level, "中"),
        "alarmLevel": alarm_level,
        "diagnosisDetail": f"v6决策: {v6_result['decision_reason']} | 闭集:{closed_pred} conf={confidence:.4f} | unk_ratio={v6_result['unknown_ratio']:.4f}",
        "diagnosis_detail": f"v6决策: {v6_result['decision_reason']}",
        "decision_reason": v6_result["decision_reason"],

        # 诊断过程中间结果
        "closedPrediction": closed_pred,
        "unknownRatio": round(float(v6_result["unknown_ratio"]), 6),
        "segmentConsistency": round(float(v6_result["segment_consistency"]), 6),
        "meanMahalanobis": round(float(v6_result["mean_mahalanobis"]), 6),
        "meanEntropy": round(float(v6_result["mean_entropy"]), 6),

        # 数据来源信息
        "source_name": source_name,
        "sourceName": source_name,

        # 前端可视化数据
        "topProbabilities": top_probs[:3],                              # Top-3 类别概率
        "evidence": evidence,                                           # 决策证据
        "time_axis": time_axis,                                         # 时域横轴（时间/秒）
        "time_data": display_sig.astype(float).tolist(),                # 时域纵轴（幅值）
        "waveform": display_sig.astype(float).tolist(),                 # 波形数据（别名）
        "freq_axis": freq_axis,                                         # 频域横轴（频率/Hz）
        "frequencyAxis": freq_axis,                                     # 频率轴（别名，兼容不同前端字段名）
        "freq_data": freq_data,                                         # 频域纵轴（归一化幅值）
        "spectrum": freq_data,                                          # 频谱数据（别名）
        "rms": round(rms, 6),                                           # 均方根值
        "latestRms": round(rms, 6),                                     # 均方根值（别名，兼容前端）
        "peak": round(peak, 6),                                         # 峰值
        "latestPeak": round(peak, 6),                                   # 峰值（别名，兼容前端）

        # 元数据
        "sample_rate": sample_rate,
        "sampleRate": sample_rate,
        "count": int(display_sig.size),
        "analysis_mode": "v6_mahalanobis",
    }

    # 合并额外的元数据（如分析模式、设备编码等）
    if extra:
        data.update(extra)

    return data


# =============================================================================
# API 端点
# =============================================================================

# ---- 健康检查接口 ----
@app.get("/health")
def health() -> Dict[str, Any]:
    """
    GET /health
    返回服务健康状态，包括设备信息、模型加载状态、模型参数等
    """
    return {
        "status": "ok",
        "device": str(DEVICE),
        "model_loaded": MODEL is not None,
        "model_path": str(MODEL_PATH),
        "version": "v6_mahalanobis",
        "classes": CLASS_NAMES,
        "win_len": MODEL_PARAMS.get("win_len"),
        "stride": MODEL_PARAMS.get("stride"),
        "fs": MODEL_PARAMS.get("fs"),
    }


# ---- 数据文件列表接口 ----
@app.get("/mat-files")
def mat_files() -> JSONResponse:
    """
    GET /mat-files
    返回 DATA_DIR 中所有 .mat 和 .npy 文件列表
    按修改时间倒序排列，最新的文件在前
    """
    items: List[Dict[str, Any]] = []
    if DATA_DIR.exists():
        all_files = list(DATA_DIR.glob("*.mat")) + list(DATA_DIR.glob("*.npy"))
        all_files.sort(key=lambda p: p.stat().st_mtime, reverse=True)
        for p in all_files:
            items.append({
                "name": p.stem,         # 文件名（不含扩展名）
                "label": p.stem,        # 显示标签
                "source_name": p.name,  # 完整文件名
            })
    return JSONResponse(
        content={"success": True, "data": items},
        headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"},  # 禁止缓存
    )


# ---- 分析接口（GET） ----
@app.get("/analyze", response_model=AnalyzeResponse)
def analyze(
    file_name: Optional[str] = Query(default=None, min_length=1),
    model_type: str = Query(default="gear"),
) -> Dict[str, Any]:
    """
    GET /analyze?file_name=xxx.mat[&model_type=gear]

    Analyze a specific data file or the latest file.
    Supports response caching based on file mtime.

    Parameters
    ----------
    file_name : str, optional
        File name to analyze (.mat or .npy). If not specified,
        the latest file in DATA_DIR is used.
    model_type : str, default 'gear'
        Diagnosis model type. This service only supports 'gear'.
        For bearing diagnosis, use inference_service.py instead.
    """
    model_type = str(model_type or "gear").strip().lower()
    if model_type not in ("gear",):
        raise HTTPException(
            status_code=400,
            detail=f"This service only supports model_type='gear', got '{model_type}'."
                   f" For bearing diagnosis, use inference_service.py.",
        )

    if MODEL is None:
        raise HTTPException(status_code=500, detail="Model is not loaded.")

    try:
        # 确定要分析的文件路径
        if file_name:
            safe_name = Path(file_name).name
            suffix = Path(safe_name).suffix.lower()
            # 如果未指定扩展名，默认补充 .mat
            if suffix not in {".mat", ".npy"}:
                safe_name = f"{Path(file_name).stem}.mat"
            file_path = DATA_DIR / safe_name
        else:
            # 未指定文件名时，取最新修改的数据文件
            mat_files = sorted(DATA_DIR.glob("*.mat"))
            npy_files = sorted(DATA_DIR.glob("*.npy"))
            all_files = mat_files + npy_files
            if not all_files:
                raise FileNotFoundError(f"No .mat or .npy files found in {DATA_DIR}")
            file_path = max(all_files, key=lambda p: (p.stat().st_mtime, p.name))

        def _compute() -> Dict[str, Any]:
            """执行完整的诊断计算流程"""
            raw_signal, _signal_field = load_signal_from_file(file_path)
            fs = float(MODEL_PARAMS.get("fs", 5120.0))
            v6_result = diagnose_signal_v6(raw_signal, source_name=file_path.name)
            return map_result_to_frontend(
                v6_result, raw_signal, file_path.name, sample_rate=fs,
                extra={"analysis_mode": "v6_latest" if file_name is None else "v6_specified"},
            )

        # 尝试从缓存获取，缓存未命中则重新计算
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


# ---- 文件上传分析接口（POST） ----
@app.post("/analyze/upload", response_model=AnalyzeResponse)
async def analyze_upload(
    file: UploadFile = File(...),
    model_type: str = Form(default="gear"),
) -> Dict[str, Any]:
    """
    POST /analyze/upload
    上传 .mat 或 .npy 文件进行在线诊断分析

    支持的上传格式:
    - .mat: 通过 scipy.io.loadmat 解析
    - .npy: 通过 numpy.load 解析（也支持 .npz 压缩格式）

    数据会自动识别信号变量名并提取一维振动信号

    Note: This service only supports gear model. For bearing diagnosis,
    use inference_service.py instead.
    """
    model_type = str(model_type or "gear").strip().lower()
    if model_type not in ("gear",):
        raise HTTPException(
            status_code=400,
            detail=f"This service only supports model_type='gear', got '{model_type}'."
                   f" For bearing diagnosis, use inference_service.py.",
        )

    if MODEL is None:
        raise HTTPException(status_code=500, detail="Model is not loaded.")

    # 校验文件扩展名
    suffix = Path(file.filename).suffix.lower()
    if suffix not in {".mat", ".npy"}:
        raise HTTPException(status_code=400, detail="Only .mat and .npy files are supported.")

    content = await file.read()
    try:
        if suffix == ".mat":
            # .mat 文件：用 scipy 读取，自动提取振动信号
            payload = scipy.io.loadmat(BytesIO(content))
            raw_signal, _ = _extract_signal_from_dict(payload, source_name=file.filename)
        else:
            # .npy / .npz 文件：用 numpy 读取
            payload = np.load(BytesIO(content), allow_pickle=True)
            if isinstance(payload, np.lib.npyio.NpzFile):
                # .npz 压缩格式：遍历数组，自动提取信号
                try:
                    if not payload.files:
                        raise ValueError("No arrays found in uploaded npz file.")
                    arrays = {name: payload[name] for name in payload.files}
                    raw_signal, _ = _extract_signal_from_dict(arrays, source_name=file.filename)
                finally:
                    payload.close()
            elif isinstance(payload, np.ndarray) and payload.dtype == object:
                # object 类型数组：尝试提取字典或拼接多个子数组
                if payload.size == 1 and isinstance(payload.reshape(-1)[0], dict):
                    raw_signal, _ = _extract_signal_from_dict(
                        payload.reshape(-1)[0], source_name=file.filename,
                    )
                else:
                    raw_signal = np.concatenate(
                        [np.asarray(item).reshape(-1) for item in payload.flat]
                    )
            else:
                # 普通数组：直接展平为一维
                raw_signal = np.asarray(payload, dtype=np.float32).reshape(-1)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Failed to parse file: {exc}") from exc

    # 信号清洗：处理 NaN 和无穷值
    raw_signal = np.nan_to_num(
        np.asarray(raw_signal, dtype=np.float32).reshape(-1),
        nan=0.0, posinf=0.0, neginf=0.0,
    )
    fs = float(MODEL_PARAMS.get("fs", 5120.0))

    # 执行诊断
    v6_result = diagnose_signal_v6(raw_signal, source_name=file.filename)
    result = map_result_to_frontend(
        v6_result, raw_signal, file.filename, sample_rate=fs,
        extra={"analysis_mode": "v6_upload"},
    )
    await _save_to_db(result)
    return {"success": True, "data": result}


# ---- 通用推理接口（POST JSON） ----
@app.post("/infer")
def infer(payload: Dict[str, Any]) -> Dict[str, Any]:
    """
    POST /infer
    通过 JSON 请求体指定文件路径进行诊断分析
    适用于外部系统（如 Java 后端）通过 HTTP 调用

    请求体示例:
    {
        "filePath": "xxx.mat",
        "deviceCode": "设备A",
        "batchId": "批次001",
        "sampleTime": "2024-01-01 12:00:00",
        "analysisMode": "v6_infer",
        "modelType": "gear"
    }

    Note: This service only supports gear model. For bearing diagnosis,
    use inference_service.py instead.
    """
    # Validate model type -- this service only supports gear
    model_type = str(payload.get("modelType") or payload.get("model_type") or "gear").strip().lower()
    if model_type not in ("gear",):
        raise HTTPException(
            status_code=400,
            detail=f"This service only supports model_type='gear', got '{model_type}'."
                   f" For bearing diagnosis, use inference_service.py.",
        )

    if MODEL is None:
        raise HTTPException(status_code=500, detail="Model is not loaded.")

    file_path = str(payload.get("filePath") or "")
    if not file_path:
        raise HTTPException(status_code=400, detail="filePath is required.")

    source_path = Path(file_path)
    # 相对路径自动拼接到 DATA_DIR 下
    if not source_path.is_absolute():
        source_path = DATA_DIR / source_path

    if not source_path.exists():
        raise HTTPException(status_code=404, detail=f"File not found: {source_path}")

    def _compute() -> Dict[str, Any]:
        """执行完整的诊断计算流程"""
        raw_signal, _signal_field = load_signal_from_file(source_path)
        fs = float(MODEL_PARAMS.get("fs", 5120.0))
        v6_result = diagnose_signal_v6(raw_signal, source_name=source_path.name)
        return map_result_to_frontend(
            v6_result, raw_signal, source_path.name, sample_rate=fs,
            extra={
                "analysis_mode": payload.get("analysisMode", "v6_infer"),
                "deviceCode": payload.get("deviceCode"),         # 设备编码
                "filename": payload.get("filename") or source_path.name,
                "batchId": payload.get("batchId"),               # 批次ID
                "sampleTime": payload.get("sampleTime"),         # 采样时间
                "modelVersion": "best_model_classwise_maha.pth (v6)",  # 模型版本
            },
        )

    result = _get_cached_or_compute(source_path, _compute)
    _save_to_db_sync(result)
    return {"success": True, "data": result}


# ---- WebSocket 端点 ----
@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket) -> None:
    """
    WebSocket /ws
    建立 WebSocket 长连接，接收服务端推送的实时诊断结果、健康状态、文件列表
    客户端可通过订阅消息主动请求特定类型的数据
    """
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
                        "version": "v6_mahalanobis",
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
        pass  # 客户端断开连接
    finally:
        ws_manager.disconnect(websocket)


# ---- 历史数据查询接口 ----
@app.get("/history")
def history(
    start_time: str = Query(..., min_length=1, description="开始时间 (YYYY-MM-DD HH:MM:SS)"),
    end_time: str = Query(..., min_length=1, description="结束时间 (YYYY-MM-DD HH:MM:SS)"),
    device_code: Optional[str] = Query(default=None, description="设备编码（可选）"),
) -> Dict[str, Any]:
    """
    GET /history?start_time=...&end_time=...&device_code=...
    查询指定时间范围内的历史诊断记录

    返回 enhanced_inference_record 表中的记录列表（不含 wave_json 和 spectrum_json 以减少数据量）
    """
    try:
        records = query_history(start_time, end_time, device_code)
        # 将 datetime 对象转为字符串，确保 JSON 可序列化
        serialized: List[Dict[str, Any]] = []
        for row in records:
            item = dict(row)
            for key in ("sample_time", "create_time", "update_time"):
                if item.get(key):
                    item[key] = str(item[key])
            # 转换 Decimal 为 float（pymysql 返回的 DECIMAL 列是 Decimal 类型）
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
# 程序入口
# =============================================================================
if __name__ == "__main__":
    # 使用 uvicorn 启动 ASGI 服务，监听所有网卡
    uvicorn.run(app, host="0.0.0.0", port=DEFAULT_PORT, reload=False)
