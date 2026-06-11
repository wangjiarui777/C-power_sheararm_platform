"""
推理服务共享工具库

纯工具函数和基础设施类，不持有任何模型状态（gear_model/bearing_model）。
被 gear_service.py 和 bearing_service.py 独立导入。
"""
from __future__ import annotations

# =============================================================================
# 标准库
# =============================================================================
import asyncio
import functools
import json
import logging
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
from typing import Any, Awaitable, Callable, Dict, List, Optional, Tuple

# =============================================================================
# 第三方库
# =============================================================================
import numpy as np
import scipy.io
import torch
import uvicorn
from contextlib import asynccontextmanager
from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from starlette.responses import JSONResponse

from utils_signal import _extract_signal_from_dict
from db_writer import query_history, save_inference_result, _close_pool

# =============================================================================
# 日志
# =============================================================================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
logger = logging.getLogger("inference_common")

# =============================================================================
# 路径与常量
# =============================================================================
BASE_DIR = Path(__file__).resolve().parent
# 默认数据目录（各服务可用环境变量 GEAR_DATA_DIR / BEARING_DATA_DIR 覆盖）
DEFAULT_DATA_DIR = BASE_DIR / "get" / "got"

DISPLAY_POINTS = 2048
DISPLAY_SPECTRUM_POINTS = 512
CONFIDENCE_MIN = 1.0
CONFIDENCE_MAX = 99.0
CACHE_MAX_SIZE = 32
VALID_MODEL_TYPES = {"gear", "bearing"}
MAX_UPLOAD_BYTES = int(os.environ.get("MAX_UPLOAD_BYTES", str(128 * 1024 * 1024)))
UPLOAD_CHUNK_BYTES = 1024 * 1024
INFERENCE_WORKERS = max(4, int(os.environ.get("INFERENCE_WORKERS", str(min(8, (os.cpu_count() or 4) * 2)))))
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")


# =============================================================================
# 通用工具函数
# =============================================================================

def safe_torch_load(path, map_location):
    """兼容不同 PyTorch 版本的 torch.load。"""
    try:
        return torch.load(path, map_location=map_location, weights_only=False)
    except TypeError:
        return torch.load(path, map_location=map_location)


def _ensure_json_serializable(obj: Any) -> Any:
    """递归地将 numpy 数组/标量转换为 Python 原生类型，确保 JSON 可序列化。"""
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


def _clean_state_dict(state_dict: Dict[str, torch.Tensor]) -> Dict[str, torch.Tensor]:
    """Remove 'module.' prefix from DataParallel-wrapped state dict keys."""
    cleaned: Dict[str, torch.Tensor] = {}
    for k, v in state_dict.items():
        if k.startswith("module."):
            cleaned[k[len("module."):]] = v
        else:
            cleaned[k] = v
    return cleaned


def _first_text(*values: Any) -> Optional[str]:
    for value in values:
        if value is not None and str(value).strip():
            return str(value).strip()
    return None


def _first_int(*values: Any) -> Optional[int]:
    for value in values:
        if value is not None:
            try:
                return int(value)
            except (ValueError, TypeError):
                pass
    return None


def _normalize_model_type(model_type: Optional[str]) -> str:
    value = str(model_type or "gear").strip().lower()
    if value not in VALID_MODEL_TYPES:
        raise HTTPException(status_code=400, detail="model_type must be 'gear' or 'bearing'.")
    return value


def _build_phm_context(
    payload: Optional[Dict[str, Any]] = None,
    device_code: Optional[str] = None,
    channel_id: Optional[Any] = None,
    point_id: Optional[Any] = None,
) -> Dict[str, Any]:
    payload = payload or {}
    resolved_device = _first_text(
        device_code,
        payload.get("deviceCode"),
        payload.get("device_code"),
    )
    resolved_channel = _first_int(
        channel_id,
        payload.get("channelId"),
        payload.get("channel_id"),
    )
    resolved_point = _first_int(
        point_id,
        payload.get("pointId"),
        payload.get("point_id"),
    )
    result: Dict[str, Any] = {}
    if resolved_device:
        result["deviceCode"] = resolved_device
        result["device_code"] = resolved_device
    if resolved_channel is not None:
        result["channelId"] = resolved_channel
        result["channel_id"] = resolved_channel
    if resolved_point is not None:
        result["pointId"] = resolved_point
        result["point_id"] = resolved_point
    return result


def _with_phm_context(result: Dict[str, Any], context: Dict[str, Any]) -> Dict[str, Any]:
    for key, value in context.items():
        if value is not None and key not in result:
            result[key] = value
    return result


# =============================================================================
# 信号加载
# =============================================================================

def _resolve_analysis_file(file_name: Optional[str], data_dir: Path) -> Path:
    """在 data_dir 中查找要分析的 .mat/.npy 文件。"""
    if file_name:
        safe_name = Path(file_name).name
        suffix = Path(safe_name).suffix.lower()
        candidates = [data_dir / safe_name] if suffix in {".mat", ".npy"} else [
            data_dir / f"{Path(safe_name).stem}.npy",
            data_dir / f"{Path(safe_name).stem}.mat",
        ]
        file_path = next((p for p in candidates if p.exists()), candidates[0])
        if not file_path.exists():
            raise FileNotFoundError(f"File not found: {file_path}")
    else:
        all_files = sorted(
            list(data_dir.glob("*.mat")) + list(data_dir.glob("*.npy")),
            key=lambda p: (p.stat().st_mtime, p.name),
            reverse=True,
        )
        if not all_files:
            raise FileNotFoundError(f"No .mat or .npy files found in {data_dir}")
        file_path = all_files[0]

    suffix = file_path.suffix.lower()
    if suffix not in {".mat", ".npy"}:
        raise ValueError(f"Unsupported file type: {suffix}. Only .mat and .npy are supported.")
    return file_path


def _load_signal_from_path(
    file_path: Path,
    preferred_signal_key: Optional[str] = None,
) -> Tuple[np.ndarray, str, Dict[str, Any]]:
    """从文件路径加载振动信号（.mat / .npy）。"""
    from utils_signal import load_npy_signal, _extract_mat_metadata

    suffix = file_path.suffix.lower()
    meta: Dict[str, Any] = {"sample_rate": None, "rpm": None}

    if suffix == ".mat":
        if not file_path.exists():
            raise FileNotFoundError(f".mat file not found: {file_path}")
        _META_KEYS = ['sample_rate', 'sr', 'fs', 'Fs', 'sampling_rate', 'rpm', 'speed', 'rotating_speed']
        if preferred_signal_key:
            try:
                mat = scipy.io.loadmat(
                    str(file_path),
                    variable_names=[preferred_signal_key] + _META_KEYS,
                    appendmat=False,
                )
                if preferred_signal_key not in mat:
                    raise KeyError(f"'{preferred_signal_key}' not found in {file_path.name}")
            except Exception:
                mat = scipy.io.loadmat(str(file_path), appendmat=False)
        else:
            mat = scipy.io.loadmat(str(file_path), appendmat=False)
        sig = _extract_signal_from_dict(
            mat, source_name=file_path.name, preferred_signal_key=preferred_signal_key,
        )
        meta = _extract_mat_metadata(mat)
    elif suffix == ".npy":
        sig = load_npy_signal(file_path, signal_key=preferred_signal_key or "sig_acc_5120")
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
    """从上传字节流加载振动信号（.mat / .npy）。"""
    from utils_signal import _extract_mat_metadata

    suffix = Path(filename).suffix.lower()
    meta: Dict[str, Any] = {"sample_rate": None, "rpm": None}
    t0 = time.perf_counter()

    if suffix == ".mat":
        _META_KEYS = ['sample_rate', 'sr', 'fs', 'Fs', 'sampling_rate', 'rpm', 'speed', 'rotating_speed']
        if preferred_signal_key:
            var_names = [preferred_signal_key] + _META_KEYS
            try:
                payload = scipy.io.loadmat(BytesIO(content), variable_names=var_names, appendmat=False)
                if preferred_signal_key not in payload:
                    raise KeyError(f"'{preferred_signal_key}' not found")
                sig = _extract_signal_from_dict(
                    payload, source_name=filename, preferred_signal_key=preferred_signal_key,
                )
                meta = _extract_mat_metadata(payload)
            except Exception:
                payload = scipy.io.loadmat(BytesIO(content), appendmat=False)
                sig = _extract_signal_from_dict(
                    payload, source_name=filename, preferred_signal_key=preferred_signal_key,
                )
                meta = _extract_mat_metadata(payload)
        else:
            payload = scipy.io.loadmat(BytesIO(content), appendmat=False)
            sig = _extract_signal_from_dict(
                payload, source_name=filename, preferred_signal_key=preferred_signal_key,
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
                    arrays, source_name=filename, preferred_signal_key=preferred_signal_key,
                )
                meta = _extract_mat_metadata(arrays)
            finally:
                payload.close()
        elif isinstance(payload, np.ndarray) and payload.dtype == object:
            if payload.size == 1 and isinstance(payload.reshape(-1)[0], dict):
                item_dict = payload.reshape(-1)[0]
                sig = _extract_signal_from_dict(
                    item_dict, source_name=filename, preferred_signal_key=preferred_signal_key,
                )
                meta = _extract_mat_metadata(item_dict)
            else:
                sig = np.concatenate([np.asarray(item).reshape(-1) for item in payload.flat])
        else:
            sig = np.asarray(payload, dtype=np.float32).reshape(-1)
    else:
        raise ValueError(f"Unsupported file type: {suffix}. Only .mat and .npy are supported.")

    sig = np.nan_to_num(np.asarray(sig, dtype=np.float32).reshape(-1),
                        nan=0.0, posinf=0.0, neginf=0.0)
    elapsed = (time.perf_counter() - t0) * 1000
    logger.info("Signal loaded from %s: %d samples, %.1f ms", filename, sig.size, elapsed)
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


# =============================================================================
# 频谱 + 工业指标（单次 FFT 同时产出）
# =============================================================================

def _get_v6():
    """惰性导入 v6 模块，避免循环依赖。"""
    import importlib.util
    _v6_spec = importlib.util.spec_from_file_location(
        "diagnose_v6",
        str(BASE_DIR / "04.4_diagnose_unlabeled_target.py"),
    )
    v6 = importlib.util.module_from_spec(_v6_spec)
    _v6_spec.loader.exec_module(v6)
    return v6


def _compute_spectrum_and_metrics(
    sig: np.ndarray,
    fs: float,
    spectrum_max_points: int = DISPLAY_SPECTRUM_POINTS,
    f_min: float = 0.0,
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """对原始信号做一次 FFT，同时返回频谱数据和工业指标。"""
    v6 = _get_v6()

    x = np.asarray(sig, dtype=np.float64).reshape(-1)
    x = np.nan_to_num(x, nan=0.0, posinf=0.0, neginf=0.0)
    n = len(x)

    if n < 2:
        empty_spectrum = {"freq_hz": [], "amplitude": [], "frequency_resolution_hz": None}
        empty_metrics = {
            "mean": 0.0, "std": 0.0, "rms": 0.0, "peak": 0.0,
            "peak_to_peak": 0.0, "skewness": 0.0, "kurtosis": 0.0,
            "crest_factor": 0.0, "shape_factor": 0.0, "impulse_factor": 0.0,
            "clearance_factor": 0.0, "frequency_center": 0.0,
            "frequency_rms": 0.0, "frequency_std": 0.0,
        }
        return empty_spectrum, empty_metrics

    eps = 1e-12
    xc = x - np.mean(x)
    win = np.hanning(n)
    xw = xc * win

    # 单次 FFT
    spec = np.abs(np.fft.rfft(xw))
    freqs = np.fft.rfftfreq(n, d=1.0 / float(fs))

    # 频谱（用于前端频域图）
    coherent_gain = np.mean(win) + eps
    amp = spec / (n * coherent_gain)
    if len(amp) > 2:
        amp[1:-1] *= 2.0

    if f_min is not None:
        mask = freqs >= float(f_min)
        freqs_filtered = freqs[mask]
        amp_filtered = amp[mask]
    else:
        freqs_filtered = freqs
        amp_filtered = amp

    freq_ds, amp_ds = v6.downsample_curve(freqs_filtered, amp_filtered, max_points=spectrum_max_points)
    spectrum = {
        "freq_hz": freq_ds,
        "amplitude": amp_ds,
        "frequency_resolution_hz": float(fs) / float(n),
    }

    # 工业指标（复用同一 FFT）
    mean_v = float(np.mean(x))
    std_v = float(np.std(x) + eps)
    abs_x = np.abs(x)
    mean_abs = float(np.mean(abs_x) + eps)
    rms_v = float(np.sqrt(np.mean(x ** 2)) + eps)
    peak_v = float(np.max(abs_x))
    peak_to_peak = float(np.max(x) - np.min(x))

    centered = x - mean_v
    skewness = float(np.mean(centered ** 3) / (std_v ** 3 + eps))
    kurtosis = float(np.mean(centered ** 4) / (std_v ** 4 + eps))
    crest_factor = float(peak_v / (rms_v + eps))
    shape_factor = float(rms_v / (mean_abs + eps))
    impulse_factor = float(peak_v / (mean_abs + eps))
    clearance_factor = float(peak_v / ((np.mean(np.sqrt(abs_x + eps)) ** 2) + eps))

    power = spec ** 2
    power_sum = float(np.sum(power) + eps)
    frequency_center = float(np.sum(freqs * power) / power_sum)
    mean_square_frequency = float(np.sum((freqs ** 2) * power) / power_sum)
    frequency_rms = float(np.sqrt(mean_square_frequency))
    frequency_std = float(np.sqrt(np.sum(((freqs - frequency_center) ** 2) * power) / power_sum))

    metrics = {
        "mean": v6.safe_float(mean_v),
        "std": v6.safe_float(std_v),
        "rms": v6.safe_float(rms_v),
        "peak": v6.safe_float(peak_v),
        "peak_to_peak": v6.safe_float(peak_to_peak),
        "skewness": v6.safe_float(skewness),
        "kurtosis": v6.safe_float(kurtosis),
        "crest_factor": v6.safe_float(crest_factor),
        "shape_factor": v6.safe_float(shape_factor),
        "impulse_factor": v6.safe_float(impulse_factor),
        "clearance_factor": v6.safe_float(clearance_factor),
        "frequency_center": v6.safe_float(frequency_center),
        "frequency_rms": v6.safe_float(frequency_rms),
        "frequency_std": v6.safe_float(frequency_std),
    }

    return spectrum, metrics


# ---- v6 工具函数薄封装（供轴承服务等不便直接导入 04.4 的模块使用） ----

def downsample_curve(x, y, max_points=4000):
    """等间隔降采样曲线（内联实现，不依赖 v6 模块）。"""
    x = np.asarray(x, dtype=np.float64).reshape(-1)
    y = np.asarray(y, dtype=np.float64).reshape(-1)
    n = min(len(x), len(y))
    x, y = x[:n], y[:n]
    if n == 0:
        return [], []
    max_points = int(max_points)
    if max_points <= 0 or n <= max_points:
        return x.tolist(), y.tolist()
    idx = np.linspace(0, n - 1, max_points).astype(np.int64)
    return x[idx].tolist(), y[idx].tolist()


def safe_float(x, default=0.0):
    """安全浮点数转换（内联实现，不依赖 v6 模块）。"""
    if x is None:
        return float(default)
    try:
        v = float(np.asarray(x).reshape(-1)[0])
        return v if np.isfinite(v) else float(default)
    except (ValueError, TypeError, IndexError):
        return float(default)


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
# 响应缓存（线程安全，参数化）
# =============================================================================

async def _get_cached_or_compute(
    file_path: Path,
    cache: Dict[str, Tuple[float, Dict[str, Any]]],
    cache_lock: threading.Lock,
    executor: ThreadPoolExecutor,
    compute_fn,
) -> Dict[str, Any]:
    """线程安全的响应缓存, 缓存命中直接返回, 未命中则在推理线程池执行."""
    cache_key = str(file_path.resolve())
    try:
        mtime = file_path.stat().st_mtime
    except OSError:
        mtime = 0.0

    with cache_lock:
        if cache_key in cache:
            cached_mtime, cached_result = cache[cache_key]
            if cached_mtime == mtime:
                logger.info("Cache hit for %s", file_path.name)
                return cached_result

    loop = asyncio.get_running_loop()
    result = await loop.run_in_executor(executor, compute_fn)

    with cache_lock:
        cache[cache_key] = (mtime, result)
        if len(cache) > CACHE_MAX_SIZE:
            oldest = next(iter(cache))
            del cache[oldest]
            logger.debug("Cache evicted oldest entry")
    return result


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
        safe_message = _ensure_json_serializable(message)
        dead: List[WebSocket] = []
        # 迭代前拷贝列表，避免 concurrent disconnect() 修改 active_connections 导致竞态
        for conn in list(self.active_connections):
            try:
                await conn.send_json(safe_message)
            except Exception:
                dead.append(conn)
        for conn in dead:
            if conn in self.active_connections:
                self.active_connections.remove(conn)

    async def broadcast_health(self, health_fn) -> None:
        """Broadcast current model health status to all connected clients."""
        payload = health_fn()
        payload["type"] = "health_status"
        await self.broadcast(payload)

    async def broadcast_file_list(self, data_dir: Path) -> None:
        """Broadcast current .mat/.npy file list to all connected clients."""
        items: List[Dict[str, str]] = []
        if data_dir.exists():
            for p in sorted(data_dir.glob("*"), key=lambda p: p.stat().st_mtime, reverse=True):
                if p.suffix.lower() in {".mat", ".npy"}:
                    items.append({
                        "name": p.stem,
                        "source_name": p.name,
                        "label": p.stem,
                    })
        await self.broadcast({"type": "file_list", "data": items})


# =============================================================================
# 文件监控（参数化）
# =============================================================================

async def _notify_file_available(ws_manager: ConnectionManager, file_path: Path) -> None:
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


async def _file_watcher_loop(
    ws_manager: ConnectionManager,
    data_dir: Path,
    interval: float = 3.0,
    on_new_file: Optional[Callable[[Path], Awaitable[None]]] = None,
) -> None:
    """Periodically scan data_dir for new/changed .mat/.npy files and broadcast changes.

    If *on_new_file* is provided, new/changed files trigger automatic inference
    instead of just a file_available notification.  A 1-second stabilisation delay
    prevents analysing a file that is still being written.
    """
    known_files: Dict[str, float] = {}

    if data_dir.exists():
        for p in data_dir.glob("*"):
            if p.suffix.lower() in {".mat", ".npy"}:
                try:
                    known_files[str(p)] = p.stat().st_mtime
                except OSError:
                    pass
    logger.info("File watcher seeded with %d known files", len(known_files))

    while True:
        await asyncio.sleep(interval)
        if not data_dir.exists():
            continue

        current: Dict[str, float] = {}
        for p in data_dir.glob("*"):
            if p.suffix.lower() not in {".mat", ".npy"}:
                continue
            try:
                current[str(p)] = p.stat().st_mtime
            except OSError:
                continue

        file_list_changed = False
        for path_str, mtime in current.items():
            prev = known_files.get(path_str)
            if prev is None or prev != mtime:
                # 等待 1s 让文件写入完成，再确认 mtime 稳定
                await asyncio.sleep(1.0)
                try:
                    new_mtime = Path(path_str).stat().st_mtime
                except OSError:
                    continue
                if new_mtime != mtime:
                    continue  # 文件仍在变化，下个周期再处理

                known_files[path_str] = mtime
                if on_new_file:
                    await on_new_file(Path(path_str))
                else:
                    await _notify_file_available(ws_manager, Path(path_str))
                file_list_changed = True

        for path_str in list(known_files.keys()):
            if path_str not in current:
                del known_files[path_str]
                file_list_changed = True

        if file_list_changed:
            await ws_manager.broadcast_file_list(data_dir)


async def _health_broadcaster(
    ws_manager: ConnectionManager,
    health_fn,
    interval: float = 30.0,
) -> None:
    """Periodically broadcast health status to WebSocket clients."""
    await asyncio.sleep(5)
    while True:
        await asyncio.sleep(interval)
        try:
            await ws_manager.broadcast_health(health_fn)
        except Exception:
            logger.exception("Health broadcast failed")


# =============================================================================
# 诊断服务基类
# =============================================================================

class DiagnosisServiceBase:
    """诊断服务基类 — 封装 FastAPI 应用、API 端点、WebSocket、文件监控、缓存、DB。

    子类只需覆写:
      - service_name / default_port / default_data_dir / model_checkpoint_path
      - load_model()
      - diagnose(raw_signal, source_name) -> dict
      - build_frontend_payload(diag_result, raw_signal, source_name, sample_rate, extra) -> dict
      - build_health_payload() -> dict
    """

    # =====================================================================
    # 子类必须覆写的属性与方法
    # =====================================================================

    service_name: str = ""
    default_port: int = 5000
    default_data_dir: Path = DEFAULT_DATA_DIR
    model_checkpoint_path: Path = DEFAULT_DATA_DIR

    def load_model(self) -> None:
        """加载模型到 self.model, self.model_params, self.class_names。"""
        raise NotImplementedError

    def diagnose(self, raw_signal: np.ndarray, source_name: str) -> Dict[str, Any]:
        """执行推理，返回诊断结果字典。"""
        raise NotImplementedError

    def build_frontend_payload(
        self, diag_result: Dict[str, Any], raw_signal: np.ndarray,
        source_name: str, sample_rate: float,
        extra: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """将诊断结果映射为前端数据格式。"""
        raise NotImplementedError

    def build_health_payload(self) -> Dict[str, Any]:
        """返回健康状态字典。"""
        raise NotImplementedError

    def _validate_model_type(self, model_type: Optional[str]) -> None:
        """验证 model_type 参数。基类默认接受任何值（含 None）。

        齿轮服务可覆写此方法以拒绝 model_type='bearing'。
        """
        if model_type is not None and str(model_type).strip().lower() not in VALID_MODEL_TYPES:
            raise HTTPException(status_code=400, detail="model_type must be 'gear' or 'bearing'.")

    # =====================================================================
    # 生命周期
    # =====================================================================

    def __init__(self) -> None:
        self.port = int(os.environ.get(f"{self.service_name.upper()}_PORT",
                                       os.environ.get("PORT", str(self.default_port))))
        self.data_dir = Path(os.environ.get(f"{self.service_name.upper()}_DATA_DIR",
                                            str(self.default_data_dir)))

        self.model: Any = None
        self.model_params: Dict[str, Any] = {}
        self.class_names: List[str] = []

        self._inference_lock = threading.Lock()
        self._response_cache: Dict[str, Tuple[float, Dict[str, Any]]] = {}
        self._cache_lock = threading.Lock()
        self._inference_executor = ThreadPoolExecutor(
            max_workers=INFERENCE_WORKERS, thread_name_prefix=f"{self.service_name}-worker")

        self.ws_manager = ConnectionManager()
        self._main_loop: Optional[asyncio.AbstractEventLoop] = None
        self._watcher_task: Optional[asyncio.Task] = None
        self._health_task: Optional[asyncio.Task] = None

        self._logger = logging.getLogger(f"{self.service_name}_service")

    # =====================================================================
    # 内部辅助
    # =====================================================================

    def _get_signal_key(self) -> str:
        return str(self.model_params.get("signal_key", "DE_time"))

    def _get_sample_rate(self, extra: Optional[Dict[str, Any]] = None) -> float:
        if extra:
            sr = extra.get("sample_rate") or extra.get("sampleRate")
            if sr is not None:
                return float(sr)
        return float(self.model_params.get("fs", 5120.0))

    def _prefix_analysis_mode(self, mode: str) -> str:
        prefix = f"{self.service_name}_"
        return mode if mode.startswith(prefix) else f"{prefix}{mode}"

    # =====================================================================
    # 广播包装器
    # =====================================================================

    async def _broadcast_analysis_async(self, result: Dict[str, Any]) -> None:
        try:
            payload = _ensure_json_serializable({"type": "auto_analysis", "success": True, "data": result})
            await self.ws_manager.broadcast(payload)
        except Exception:
            self._logger.exception("WebSocket auto_analysis broadcast failed")

    # =====================================================================
    # 推理调度
    # =====================================================================

    def run_analysis(
        self, raw_signal: np.ndarray, source_name: str,
        analysis_mode: str, extra: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """统一的推理→构建前端payload流程。"""
        extra_payload = dict(extra or {})
        extra_payload["modelType"] = self.service_name
        t0 = time.perf_counter()

        with self._inference_lock:
            t_infer = time.perf_counter()
            fs = self._get_sample_rate(extra_payload)
            diag_result = self.diagnose(raw_signal, source_name=source_name)
            self._logger.info("%s inference: %.1f ms", self.service_name,
                              (time.perf_counter() - t_infer) * 1000)

        extra_payload["analysis_mode"] = self._prefix_analysis_mode(analysis_mode)

        t_post = time.perf_counter()
        result = self.build_frontend_payload(diag_result, raw_signal, source_name, fs, extra=extra_payload)
        self._logger.info("%s post-process: %.1f ms, total: %.1f ms",
                          self.service_name,
                          (time.perf_counter() - t_post) * 1000,
                          (time.perf_counter() - t0) * 1000)
        return result

    def analyze_uploaded_content(
        self, filename: str, content: bytes,
        extra: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """从上传字节流加载信号 + 调度推理。"""
        raw_signal, _, file_meta = _load_signal_from_bytes(filename, content, self._get_signal_key())
        extra_payload: Dict[str, Any] = {"filename": filename}
        if extra:
            extra_payload.update(extra)
        if file_meta.get("sample_rate"):
            extra_payload["sampleRate"] = float(file_meta["sample_rate"])
            extra_payload["sample_rate"] = float(file_meta["sample_rate"])
        return self.run_analysis(raw_signal, filename, "upload", extra=extra_payload)

    # =====================================================================
    # 文件列表工具
    # =====================================================================

    def _list_data_files(self) -> List[Dict[str, Any]]:
        items: List[Dict[str, Any]] = []
        if self.data_dir.exists():
            all_files = sorted(
                list(self.data_dir.glob("*.mat")) + list(self.data_dir.glob("*.npy")),
                key=lambda p: p.stat().st_mtime, reverse=True,
            )
            for p in all_files:
                items.append({"name": p.stem, "label": p.stem, "source_name": p.name})
        return items

    # =====================================================================
    # FastAPI 端点方法
    # =====================================================================

    async def _endpoint_health(self) -> Dict[str, Any]:
        return self.build_health_payload()

    async def _endpoint_mat_files(self) -> JSONResponse:
        return JSONResponse(
            content={"success": True, "data": self._list_data_files()},
            headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"},
        )

    async def _endpoint_analyze(
        self,
        file_name: Optional[str] = Query(default=None, min_length=1),
        model_type: Optional[str] = Query(default=None),
        device_code: Optional[str] = Query(default=None),
        channel_id: Optional[int] = Query(default=None),
        point_id: Optional[int] = Query(default=None),
    ) -> Dict[str, Any]:
        self._validate_model_type(model_type)
        if self.model is None:
            raise HTTPException(status_code=500, detail=f"{self.service_name} model not loaded.")
        phm_context = _build_phm_context(device_code=device_code, channel_id=channel_id, point_id=point_id)

        try:
            file_path = _resolve_analysis_file(file_name, self.data_dir)

            def _compute() -> Dict[str, Any]:
                raw_signal, _, file_meta = _load_signal_from_path(file_path, self._get_signal_key())
                extra: Dict[str, Any] = {}
                if file_meta.get("sample_rate"):
                    extra["sample_rate"] = float(file_meta["sample_rate"])
                    extra["sampleRate"] = float(file_meta["sample_rate"])
                _with_phm_context(extra, phm_context)
                mode = f"{self.service_name}_latest" if file_name is None else f"{self.service_name}_specified"
                return self.run_analysis(raw_signal, file_path.name, mode, extra=extra)

            result = await _get_cached_or_compute(
                file_path, self._response_cache, self._cache_lock, self._inference_executor, _compute)
            self._logger.info("analyze: file=%s diagnosis=%s", file_path.name, result.get("diagnosisResult"))
            await _save_to_db(result)
            await self._broadcast_analysis_async(result)
            return JSONResponse(
                content={"success": True, "data": result},
                headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"},
            )
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except Exception as exc:
            self._logger.exception("analyze failed")
            raise HTTPException(status_code=500, detail=str(exc)) from exc

    async def _endpoint_analyze_upload(
        self,
        file: UploadFile = File(...),
        model_type: Optional[str] = Form(default=None),
        device_code: Optional[str] = Form(default=None),
        channel_id: Optional[int] = Form(default=None),
        point_id: Optional[int] = Form(default=None),
    ) -> Dict[str, Any]:
        self._validate_model_type(model_type)
        if self.model is None:
            raise HTTPException(status_code=500, detail=f"{self.service_name} model not loaded.")
        phm_context = _build_phm_context(device_code=device_code, channel_id=channel_id, point_id=point_id)

        suffix = Path(file.filename).suffix.lower()
        if suffix not in {".mat", ".npy"}:
            raise HTTPException(status_code=400, detail="Only .mat and .npy files are supported.")

        t0 = time.perf_counter()
        content = await _read_upload_limited(file)
        self._logger.info("Upload read: %d bytes, %.1f ms", len(content), (time.perf_counter() - t0) * 1000)

        try:
            loop = asyncio.get_running_loop()
            t_analysis = time.perf_counter()
            result = await loop.run_in_executor(
                self._inference_executor,
                self.analyze_uploaded_content, file.filename, content, phm_context,
            )
            self._logger.info("Upload analysis: %.1f ms", (time.perf_counter() - t_analysis) * 1000)
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(status_code=400, detail=f"Failed to analyze file: {exc}") from exc

        t_db = time.perf_counter()
        await _save_to_db(result)
        await self._broadcast_analysis_async(result)
        self._logger.info("DB+broadcast: %.1f ms, upload total: %.1f ms",
                          (time.perf_counter() - t_db) * 1000, (time.perf_counter() - t0) * 1000)
        return JSONResponse(
            content={"success": True, "data": result},
            headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"},
        )

    async def _endpoint_infer(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        if self.model is None:
            raise HTTPException(status_code=500, detail=f"{self.service_name} model not loaded.")
        phm_context = _build_phm_context(payload)

        file_path = str(payload.get("filePath") or "")
        if not file_path:
            raise HTTPException(status_code=400, detail="filePath is required.")

        source_path = Path(file_path)
        if not source_path.is_absolute():
            source_path = self.data_dir / source_path
        if not source_path.exists():
            raise HTTPException(status_code=404, detail=f"File not found: {source_path}")

        suffix = source_path.suffix.lower()
        if suffix not in {".mat", ".npy"}:
            raise HTTPException(status_code=400, detail=f"Unsupported file type: {suffix}")

        def _compute() -> Dict[str, Any]:
            raw_signal, _, file_meta = _load_signal_from_path(source_path, self._get_signal_key())
            extra = {
                "deviceCode": payload.get("deviceCode"),
                "filename": payload.get("filename") or source_path.name,
                "batchId": payload.get("batchId"),
                "sampleTime": payload.get("sampleTime"),
            }
            _with_phm_context(extra, phm_context)
            if file_meta.get("sample_rate"):
                extra["sample_rate"] = float(file_meta["sample_rate"])
                extra["sampleRate"] = float(file_meta["sample_rate"])
            return self.run_analysis(raw_signal, source_path.name, payload.get("analysisMode", "infer"), extra=extra)

        result = await _get_cached_or_compute(
            source_path, self._response_cache, self._cache_lock, self._inference_executor, _compute)
        await _save_to_db(result)
        await self._broadcast_analysis_async(result)
        return JSONResponse(
            content={"success": True, "data": result},
            headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"},
        )

    async def _endpoint_websocket(self, websocket: WebSocket) -> None:
        await self.ws_manager.connect(websocket)
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
                        payload = self.build_health_payload()
                        payload["type"] = "health_status"
                        await websocket.send_json(payload)
                    elif channel == "mat_files":
                        await websocket.send_json({"type": "file_list", "data": self._list_data_files()})
        except Exception:
            pass
        finally:
            self.ws_manager.disconnect(websocket)

    async def _endpoint_history(
        self,
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
                for key in ("confidence", "unknown_ratio", "segment_consistency", "mean_mahalanobis", "mean_entropy"):
                    if item.get(key) is not None:
                        item[key] = float(item[key])
                serialized.append(item)
            return {"success": True, "data": serialized, "total": len(serialized)}
        except Exception as exc:
            self._logger.exception("history query failed")
            raise HTTPException(status_code=500, detail=str(exc)) from exc

    # =====================================================================
    # 应用构建
    # =====================================================================

    @asynccontextmanager
    async def _lifespan(self, app: FastAPI):
        # ---- startup ----
        self._main_loop = asyncio.get_running_loop()
        self._logger.info("Starting %s service on port %d", self.service_name, self.port)

        self._logger.info("Loading %s model from %s", self.service_name, self.model_checkpoint_path)
        self.load_model()
        self._logger.info("%s model loaded on %s", self.service_name, DEVICE)

        async def _on_new_file(file_path: Path) -> None:
            try:
                def _compute():
                    raw_signal, _, file_meta = _load_signal_from_path(file_path, self._get_signal_key())
                    extra: Dict[str, Any] = {}
                    if file_meta.get("sample_rate"):
                        extra["sample_rate"] = float(file_meta["sample_rate"])
                        extra["sampleRate"] = float(file_meta["sample_rate"])
                    return self.run_analysis(raw_signal, file_path.name, "auto", extra=extra)

                loop = asyncio.get_running_loop()
                result = await loop.run_in_executor(self._inference_executor, _compute)
                await _save_to_db(result)
                await self._broadcast_analysis_async(result)
                self._logger.info("Auto-analysis complete: %s → %s",
                                  file_path.name, result.get("diagnosisResult"))
            except Exception:
                self._logger.exception("Auto-analysis failed for %s", file_path.name)

        self._watcher_task = asyncio.create_task(
            _file_watcher_loop(self.ws_manager, self.data_dir, on_new_file=_on_new_file))
        self._health_task = asyncio.create_task(
            _health_broadcaster(self.ws_manager, self.build_health_payload))
        self._logger.info("File watcher and health broadcaster started")

        yield
        # ---- shutdown ----
        self._main_loop = None
        if self._watcher_task:
            self._watcher_task.cancel()
        if self._health_task:
            self._health_task.cancel()
        self._inference_executor.shutdown(wait=False, cancel_futures=True)
        _close_pool()
        self._logger.info("%s service shutdown complete", self.service_name)

    def create_app(self) -> FastAPI:
        """创建 FastAPI 应用，注册所有端点。"""
        title = f"{self.service_name.capitalize()} Diagnosis Service"
        app = FastAPI(title=title, version="3.0.0", lifespan=self._lifespan)
        app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True,
                           allow_methods=["*"], allow_headers=["*"])

        # 注册端点（使用 bound method，自动绑定 self）
        app.get("/health")(self._endpoint_health)
        app.get("/mat-files")(self._endpoint_mat_files)
        app.get("/analyze")(self._endpoint_analyze)
        app.post("/analyze/upload")(self._endpoint_analyze_upload)
        app.post("/infer")(self._endpoint_infer)
        app.websocket("/ws")(self._endpoint_websocket)
        app.get("/history")(self._endpoint_history)

        return app

    def run(self) -> None:
        """启动 uvicorn 服务。"""
        uvicorn.run(self.create_app(), host="0.0.0.0", port=self.port, reload=False)
