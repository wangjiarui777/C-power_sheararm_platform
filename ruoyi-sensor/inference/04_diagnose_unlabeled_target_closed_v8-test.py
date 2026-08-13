from __future__ import annotations

import argparse
import csv
import json
import math
import re
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import torch
from torch.utils.data import DataLoader


# ======================================================================================
# Project path
# ======================================================================================

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

try:
    from models import ResNet1D18
except Exception:
    from models.resnet18_1d import ResNet1D18

from utils.dataset import FileInferenceDataset


# ======================================================================================
# Basic utils
# ======================================================================================

def ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def normalize_path(path: Path) -> str:
    return str(path.resolve()).replace("\\", "/")


def normalize_path_text(path_text: str) -> str:
    return str(Path(path_text).resolve()).replace("\\", "/")


def natural_key(s: str):
    return [
        int(t) if t.isdigit() else t.lower()
        for t in re.split(r"(\d+)", str(s))
    ]


def now_str() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def safe_float(x: Any, default: float = 0.0) -> float:
    try:
        v = float(x)
        if math.isnan(v) or math.isinf(v):
            return default
        return v
    except Exception:
        return default


def clamp(x: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, float(x)))


def to_jsonable(obj: Any) -> Any:
    if isinstance(obj, Path):
        return str(obj)
    if isinstance(obj, np.ndarray):
        return obj.tolist()
    if isinstance(obj, (np.integer,)):
        return int(obj)
    if isinstance(obj, (np.floating,)):
        return float(obj)
    if isinstance(obj, torch.Tensor):
        return obj.detach().cpu().numpy().tolist()
    if isinstance(obj, dict):
        return {str(k): to_jsonable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [to_jsonable(v) for v in obj]
    return obj


def make_device(device_arg: str) -> torch.device:
    if device_arg == "cuda" and not torch.cuda.is_available():
        print("[WARN] CUDA is not available. Use CPU instead.")
        return torch.device("cpu")
    return torch.device(device_arg)


def safe_torch_load(path: Path, map_location: torch.device):
    reconstruct = getattr(getattr(np, "_core", np.core).multiarray, "_reconstruct")
    torch.serialization.add_safe_globals([
        reconstruct,
        np.ndarray,
        np.dtype,
        type(np.dtype(np.float32)),
        type(np.dtype(np.float64)),
        type(np.dtype(np.int32)),
        type(np.dtype(np.int64)),
    ])
    try:
        checkpoint = torch.load(path, map_location=map_location, weights_only=True)
    except TypeError as exc:
        raise RuntimeError("PyTorch with weights_only=True support is required") from exc
    if not isinstance(checkpoint, dict):
        raise ValueError("model artifact must be a tensor state dictionary checkpoint")
    return checkpoint


def clean_state_dict(state_dict: Dict[str, torch.Tensor]) -> Dict[str, torch.Tensor]:
    cleaned = {}
    for k, v in state_dict.items():
        if k.startswith("module."):
            cleaned[k[len("module."):]] = v
        else:
            cleaned[k] = v
    return cleaned


def normalize_max_windows(v: int) -> Optional[int]:
    if v is None:
        return None
    v = int(v)
    if v <= 0:
        return None
    return v


def save_csv(path: Path, rows: List[Dict[str, Any]]) -> None:
    ensure_dir(path.parent)

    if not rows:
        with open(path, "w", newline="", encoding="utf-8-sig") as f:
            f.write("")
        return

    fieldnames: List[str] = []
    for row in rows:
        for k in row.keys():
            if k not in fieldnames:
                fieldnames.append(k)

    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


# ======================================================================================
# Metrics
# ======================================================================================

def accuracy_score_np(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    y_true = np.asarray(y_true).reshape(-1)
    y_pred = np.asarray(y_pred).reshape(-1)
    if y_true.size == 0:
        return 0.0
    return float(np.mean(y_true == y_pred))


def macro_f1_score_np(y_true: np.ndarray, y_pred: np.ndarray, num_classes: int) -> float:
    y_true = np.asarray(y_true).reshape(-1)
    y_pred = np.asarray(y_pred).reshape(-1)

    if y_true.size == 0:
        return 0.0

    f1s: List[float] = []

    for c in range(num_classes):
        tp = np.sum((y_true == c) & (y_pred == c))
        fp = np.sum((y_true != c) & (y_pred == c))
        fn = np.sum((y_true == c) & (y_pred != c))

        precision = tp / (tp + fp + 1e-12)
        recall = tp / (tp + fn + 1e-12)
        f1 = 2.0 * precision * recall / (precision + recall + 1e-12)

        f1s.append(float(f1))

    return float(np.mean(f1s))


def confusion_matrix_np(y_true: np.ndarray, y_pred: np.ndarray, num_classes: int) -> np.ndarray:
    cm = np.zeros((num_classes, num_classes), dtype=np.int64)

    for t, p in zip(y_true.reshape(-1), y_pred.reshape(-1)):
        t = int(t)
        p = int(p)
        if 0 <= t < num_classes and 0 <= p < num_classes:
            cm[t, p] += 1

    return cm


def per_class_report(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    class_names: List[str],
) -> Dict[str, Dict[str, Any]]:
    report: Dict[str, Dict[str, Any]] = {}

    for c, name in enumerate(class_names):
        tp = np.sum((y_true == c) & (y_pred == c))
        fp = np.sum((y_true != c) & (y_pred == c))
        fn = np.sum((y_true == c) & (y_pred != c))
        support = np.sum(y_true == c)

        precision = tp / (tp + fp + 1e-12)
        recall = tp / (tp + fn + 1e-12)
        f1 = 2.0 * precision * recall / (precision + recall + 1e-12)

        report[name] = {
            "precision": float(precision),
            "recall": float(recall),
            "f1": float(f1),
            "support": int(support),
        }

    return report


def save_confusion_matrix_csv(
    path: Path,
    cm: np.ndarray,
    class_names: List[str],
) -> None:
    ensure_dir(path.parent)

    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        writer.writerow(["true\\pred"] + class_names)

        for i, name in enumerate(class_names):
            writer.writerow([name] + [int(v) for v in cm[i].tolist()])


# ======================================================================================
# Ground-truth CSV for offline evaluation
# ======================================================================================

def parse_label_value(label_value: Any, class_names: List[str]) -> int:
    class_to_idx = {c: i for i, c in enumerate(class_names)}
    lower_to_idx = {c.lower(): i for i, c in enumerate(class_names)}

    s = str(label_value).strip()

    if s == "":
        raise ValueError("ground_truth_csv 中存在空标签。")

    if s in class_to_idx:
        return int(class_to_idx[s])

    if s.lower() in lower_to_idx:
        return int(lower_to_idx[s.lower()])

    try:
        y = int(float(s))
        if 0 <= y < len(class_names):
            return int(y)
    except Exception:
        pass

    raise ValueError(
        f"无法解析标签值：{label_value}。"
        f"允许类别为：{class_names}，或数字 0~{len(class_names)-1}。"
    )


def load_ground_truth_csv(
    csv_path: Path,
    class_names: List[str],
) -> Dict[str, Dict[str, int]]:
    if not csv_path.exists():
        raise FileNotFoundError(f"ground_truth_csv 不存在：{csv_path}")

    by_path: Dict[str, int] = {}
    by_filename: Dict[str, int] = {}

    with open(csv_path, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)

        if reader.fieldnames is None:
            raise RuntimeError(f"ground_truth_csv 无表头：{csv_path}")

        for row_idx, row in enumerate(reader, start=2):
            path_value = (
                row.get("path")
                or row.get("file_path")
                or row.get("rel_path")
                or ""
            )
            filename_value = row.get("filename") or ""

            label_value = (
                row.get("label")
                or row.get("class_name")
                or row.get("true_label")
                or row.get("y")
                or row.get("label_id")
                or ""
            )

            y = parse_label_value(label_value, class_names)

            if path_value:
                norm_p = normalize_path_text(path_value)
                by_path[norm_p] = y

                if not filename_value:
                    filename_value = Path(path_value).name

            if filename_value:
                by_filename[str(filename_value)] = y

            if not path_value and not filename_value:
                raise RuntimeError(
                    f"ground_truth_csv 第 {row_idx} 行缺少 path/file_path/rel_path/filename 字段。"
                )

    return {
        "by_path": by_path,
        "by_filename": by_filename,
    }


def lookup_ground_truth(
    path: Path,
    gt_maps: Dict[str, Dict[str, int]],
) -> Optional[int]:
    norm_p = normalize_path(path)
    filename = path.name

    if norm_p in gt_maps["by_path"]:
        return int(gt_maps["by_path"][norm_p])

    if filename in gt_maps["by_filename"]:
        return int(gt_maps["by_filename"][filename])

    return None


# ======================================================================================
# Raw signal loading for platform modules
# ======================================================================================

def _try_scalar(x: Any) -> Optional[float]:
    try:
        arr = np.asarray(x).squeeze()
        if arr.size == 1:
            return float(arr.reshape(-1)[0])
    except Exception:
        return None
    return None


def load_mat_signal_for_platform(
    path: Path,
    signal_key: str,
    default_fs: float,
) -> Tuple[np.ndarray, Dict[str, Any]]:
    """
    用于平台时域/频域特征展示。
    优先读取 signal_key；否则读取最长的一维数值数组。
    """
    meta: Dict[str, Any] = {
        "signal_key_used": "",
        "sample_rate": float(default_fs),
        "rpm_from_mat": None,
    }

    try:
        import scipy.io as sio

        data = sio.loadmat(str(path), squeeze_me=True, struct_as_record=False)

        # sample rate
        for k in ["sr", "sample_rate", "fs", "Fs", "sampling_rate"]:
            if k in data:
                v = _try_scalar(data[k])
                if v is not None and v > 0:
                    meta["sample_rate"] = float(v)
                    break

        # rpm
        for k in ["rpm", "speed", "rotating_speed"]:
            if k in data:
                v = _try_scalar(data[k])
                if v is not None and v > 0:
                    meta["rpm_from_mat"] = float(v)
                    break

        # requested signal key
        if signal_key and signal_key in data:
            sig = np.asarray(data[signal_key]).squeeze()
            if sig.ndim == 1 and sig.size > 0:
                meta["signal_key_used"] = signal_key
                return sig.astype(np.float64), meta

        # common fallback keys
        fallback_keys = [
            "DE_time", "FE_time", "BA_time",
            "sig_acc_51200", "signal", "sig", "data",
            "x", "X", "acc", "vibration",
        ]
        for k in fallback_keys:
            if k in data:
                sig = np.asarray(data[k]).squeeze()
                if sig.ndim == 1 and sig.size > 0 and np.issubdtype(sig.dtype, np.number):
                    meta["signal_key_used"] = k
                    return sig.astype(np.float64), meta

        # longest numeric vector
        best_key = None
        best_arr = None
        best_len = 0
        for k, v in data.items():
            if k.startswith("__"):
                continue
            arr = np.asarray(v).squeeze()
            if arr.ndim == 1 and arr.size > best_len and np.issubdtype(arr.dtype, np.number):
                best_key = k
                best_arr = arr
                best_len = arr.size

        if best_arr is not None:
            meta["signal_key_used"] = str(best_key)
            return np.asarray(best_arr, dtype=np.float64), meta

    except NotImplementedError:
        # MATLAB v7.3 HDF5 fallback
        try:
            import h5py

            with h5py.File(str(path), "r") as f:
                if signal_key and signal_key in f:
                    arr = np.asarray(f[signal_key]).squeeze()
                    meta["signal_key_used"] = signal_key
                    return arr.astype(np.float64), meta

                best_key = None
                best_arr = None
                best_len = 0

                def visit(name, obj):
                    nonlocal best_key, best_arr, best_len
                    try:
                        if hasattr(obj, "shape"):
                            arr = np.asarray(obj).squeeze()
                            if arr.ndim == 1 and arr.size > best_len and np.issubdtype(arr.dtype, np.number):
                                best_key = name
                                best_arr = arr
                                best_len = arr.size
                    except Exception:
                        pass

                f.visititems(visit)

                if best_arr is not None:
                    meta["signal_key_used"] = str(best_key)
                    return np.asarray(best_arr, dtype=np.float64), meta

        except Exception as e:
            raise RuntimeError(f"无法读取 MATLAB v7.3 文件：{path}\n{e}")

    except Exception as e:
        raise RuntimeError(f"读取 mat 信号失败：{path}\n{e}")

    raise RuntimeError(
        f"未在 mat 文件中找到可用一维数值信号：{path}\n"
        f"请确认 signal_key={signal_key} 是否正确。"
    )


def downsample_curve(x: np.ndarray, y: np.ndarray, max_points: int) -> Dict[str, List[float]]:
    x = np.asarray(x).reshape(-1)
    y = np.asarray(y).reshape(-1)

    n = min(x.size, y.size)
    if n <= 0:
        return {"x": [], "y": []}

    x = x[:n]
    y = y[:n]

    if max_points > 0 and n > max_points:
        idx = np.linspace(0, n - 1, max_points).astype(np.int64)
        x = x[idx]
        y = y[idx]

    return {
        "x": [float(v) for v in x],
        "y": [float(v) for v in y],
    }


def compute_time_features(sig: np.ndarray) -> Dict[str, float]:
    sig = np.asarray(sig, dtype=np.float64).reshape(-1)

    if sig.size == 0:
        return {
            "mean": 0.0,
            "std": 0.0,
            "rms": 0.0,
            "peak": 0.0,
            "peak_to_peak": 0.0,
            "abs_mean": 0.0,
            "crest_factor": 0.0,
            "kurtosis": 0.0,
            "skewness": 0.0,
        }

    mean = float(np.mean(sig))
    std = float(np.std(sig))
    rms = float(np.sqrt(np.mean(sig ** 2)))
    peak = float(np.max(np.abs(sig)))
    peak_to_peak = float(np.max(sig) - np.min(sig))
    abs_mean = float(np.mean(np.abs(sig)))

    crest_factor = peak / (rms + 1e-12)

    centered = sig - mean
    m2 = float(np.mean(centered ** 2)) + 1e-12
    m3 = float(np.mean(centered ** 3))
    m4 = float(np.mean(centered ** 4))

    skewness = m3 / (m2 ** 1.5 + 1e-12)
    kurtosis = m4 / (m2 ** 2 + 1e-12)

    return {
        "mean": mean,
        "std": std,
        "rms": rms,
        "peak": peak,
        "peak_to_peak": peak_to_peak,
        "abs_mean": abs_mean,
        "crest_factor": float(crest_factor),
        "kurtosis": float(kurtosis),
        "skewness": float(skewness),
    }


def compute_spectrum_module(
    sig: np.ndarray,
    fs: float,
    rpm: float,
    max_freq: float,
    max_points: int,
    top_k: int,
) -> Dict[str, Any]:
    sig = np.asarray(sig, dtype=np.float64).reshape(-1)

    if sig.size < 8:
        return {
            "sample_rate": fs,
            "frequency_resolution": 0.0,
            "dominant_peaks": [],
            "spectrum_curve": {"x": [], "y": []},
            "rotating_frequency_hz": rpm / 60.0 if rpm > 0 else None,
        }

    x = sig - np.mean(sig)
    n = int(x.size)

    win = np.hanning(n)
    xw = x * win

    freq = np.fft.rfftfreq(n, d=1.0 / fs)
    amp = np.abs(np.fft.rfft(xw)) * 2.0 / (np.sum(win) + 1e-12)

    if max_freq > 0:
        mask = freq <= max_freq
        freq = freq[mask]
        amp = amp[mask]

    # 忽略直流和极低频，寻找主峰
    valid = freq > 1.0
    freq_valid = freq[valid]
    amp_valid = amp[valid]

    dominant_peaks: List[Dict[str, Any]] = []
    if freq_valid.size > 0:
        order = np.argsort(amp_valid)[::-1]
        used = []
        for idx in order:
            f = float(freq_valid[idx])
            a = float(amp_valid[idx])

            # 避免非常接近的频率重复进入 top peaks
            too_close = any(abs(f - uf) < 2.0 for uf in used)
            if too_close:
                continue

            used.append(f)
            rotating_order = None
            if rpm > 0:
                fr = rpm / 60.0
                rotating_order = f / (fr + 1e-12)

            dominant_peaks.append({
                "frequency_hz": f,
                "amplitude": a,
                "order": None if rotating_order is None else float(rotating_order),
            })

            if len(dominant_peaks) >= top_k:
                break

    fr = rpm / 60.0 if rpm > 0 else None

    harmonic_markers = []
    if fr is not None and fr > 0:
        for k in range(1, 7):
            harmonic_markers.append({
                "name": f"{k}X",
                "frequency_hz": float(k * fr),
            })

    return {
        "sample_rate": float(fs),
        "frequency_resolution": float(fs / n),
        "max_frequency_hz": float(freq[-1]) if freq.size else 0.0,
        "rotating_frequency_hz": None if fr is None else float(fr),
        "dominant_peaks": dominant_peaks,
        "harmonic_markers": harmonic_markers,
        "spectrum_curve": downsample_curve(freq, amp, max_points),
    }


def compute_envelope_module(
    sig: np.ndarray,
    fs: float,
    rpm: float,
    max_freq: float,
    max_points: int,
    top_k: int,
) -> Dict[str, Any]:
    try:
        from scipy.signal import hilbert
    except Exception:
        return {
            "available": False,
            "reason": "scipy.signal.hilbert is unavailable.",
            "envelope_spectrum_curve": {"x": [], "y": []},
            "dominant_peaks": [],
        }

    sig = np.asarray(sig, dtype=np.float64).reshape(-1)

    if sig.size < 8:
        return {
            "available": False,
            "reason": "signal is too short.",
            "envelope_spectrum_curve": {"x": [], "y": []},
            "dominant_peaks": [],
        }

    x = sig - np.mean(sig)
    analytic = hilbert(x)
    env = np.abs(analytic)
    env = env - np.mean(env)

    n = int(env.size)
    win = np.hanning(n)
    freq = np.fft.rfftfreq(n, d=1.0 / fs)
    amp = np.abs(np.fft.rfft(env * win)) * 2.0 / (np.sum(win) + 1e-12)

    if max_freq > 0:
        mask = freq <= max_freq
        freq = freq[mask]
        amp = amp[mask]

    valid = freq > 1.0
    freq_valid = freq[valid]
    amp_valid = amp[valid]

    dominant_peaks: List[Dict[str, Any]] = []
    if freq_valid.size > 0:
        order = np.argsort(amp_valid)[::-1]
        used = []
        for idx in order:
            f = float(freq_valid[idx])
            a = float(amp_valid[idx])

            too_close = any(abs(f - uf) < 2.0 for uf in used)
            if too_close:
                continue

            used.append(f)
            rotating_order = None
            if rpm > 0:
                fr = rpm / 60.0
                rotating_order = f / (fr + 1e-12)

            dominant_peaks.append({
                "frequency_hz": f,
                "amplitude": a,
                "order": None if rotating_order is None else float(rotating_order),
            })

            if len(dominant_peaks) >= top_k:
                break

    return {
        "available": True,
        "max_frequency_hz": float(freq[-1]) if freq.size else 0.0,
        "dominant_peaks": dominant_peaks,
        "envelope_spectrum_curve": downsample_curve(freq, amp, max_points),
    }


# ======================================================================================
# Target records
# ======================================================================================

def list_mat_files(root: Path, recursive: bool) -> List[Path]:
    if recursive:
        files = list(root.rglob("*.mat"))
    else:
        files = list(root.glob("*.mat"))

    return sorted(files, key=lambda p: natural_key(str(p)))


def collect_target_records(
    target_root: Path,
    class_names: List[str],
    target_has_labels: bool,
    recursive: bool,
    ground_truth_csv: str = "",
) -> Tuple[List[Dict[str, Any]], bool, str]:
    """
    不从文件名解析标签。

    返回：
        records
        labels_available
        label_source
    """
    records: List[Dict[str, Any]] = []

    if target_has_labels:
        class_dirs_exist = [
            (target_root / c).exists() and (target_root / c).is_dir()
            for c in class_names
        ]

        if not any(class_dirs_exist):
            raise FileNotFoundError(
                "target_has_labels=True，但 target_root 下没有找到类别子目录。\n"
                f"target_root: {target_root}\n"
                f"需要的类别目录：{class_names}\n"
                "如果是现场无标签数据，请不要添加 --target_has_labels。"
            )

        for y, cls in enumerate(class_names):
            class_dir = target_root / cls
            if not class_dir.exists() or not class_dir.is_dir():
                raise FileNotFoundError(
                    f"target_has_labels=True，但缺少类别目录：{class_dir}"
                )

            files = list_mat_files(class_dir, recursive=recursive)
            if len(files) == 0:
                raise FileNotFoundError(f"类别目录中没有 .mat 文件：{class_dir}")

            for p in files:
                records.append({
                    "path": normalize_path(p),
                    "filename": p.name,
                    "y": int(y),
                    "true_label": cls,
                    "label_source": "folder",
                })

        records = sorted(records, key=lambda r: natural_key(r["path"]))
        return records, True, "folder"

    files = list_mat_files(target_root, recursive=recursive)

    if len(files) == 0 and any(p.is_dir() for p in target_root.iterdir()):
        files = list_mat_files(target_root, recursive=True)

    if len(files) == 0:
        raise FileNotFoundError(f"目标目录中没有 .mat 文件：{target_root}")

    gt_maps: Optional[Dict[str, Dict[str, int]]] = None
    labels_available = False
    label_source = "none"

    if ground_truth_csv:
        gt_maps = load_ground_truth_csv(Path(ground_truth_csv).resolve(), class_names)
        labels_available = True
        label_source = "ground_truth_csv"

    for p in files:
        y: Optional[int] = None
        true_label = ""
        this_label_source = "none"

        if gt_maps is not None:
            y = lookup_ground_truth(p, gt_maps)
            if y is None:
                raise RuntimeError(
                    "提供了 ground_truth_csv，但其中没有找到该文件真实标签：\n"
                    f"  {p}"
                )
            true_label = class_names[int(y)]
            this_label_source = "ground_truth_csv"

        records.append({
            "path": normalize_path(p),
            "filename": p.name,
            "y": None if y is None else int(y),
            "true_label": true_label,
            "label_source": this_label_source,
        })

    records = sorted(records, key=lambda r: natural_key(r["path"]))
    return records, labels_available, label_source


# ======================================================================================
# Model loading
# ======================================================================================

def load_checkpoint_and_model(
    checkpoint_path: Path,
    device: torch.device,
    args_classes: Optional[List[str]],
) -> Tuple[torch.nn.Module, Dict[str, Any], List[str]]:
    ckpt = safe_torch_load(checkpoint_path, map_location=device)

    if isinstance(ckpt, dict) and "state_dict" in ckpt:
        state_dict = ckpt["state_dict"]
        meta = dict(ckpt)
    elif isinstance(ckpt, dict):
        state_dict = ckpt
        meta = {}
    else:
        raise RuntimeError(f"Unsupported checkpoint type: {type(ckpt)}")

    if "state_dict" in meta:
        meta.pop("state_dict", None)

    state_dict = clean_state_dict(state_dict)

    if args_classes:
        class_names = [str(c) for c in args_classes]

    elif "classes" in meta and meta["classes"]:
        class_names = [str(c) for c in meta["classes"]]

    elif "class_names" in meta and meta["class_names"]:
        class_names = [str(c) for c in meta["class_names"]]

    elif "idx_to_class" in meta and isinstance(meta["idx_to_class"], dict):
        idx_to_class = meta["idx_to_class"]
        class_names = [idx_to_class[str(i)] for i in range(len(idx_to_class))]

    else:
        num_classes_from_fc = None
        for k, v in state_dict.items():
            if k.endswith("fc.weight"):
                num_classes_from_fc = int(v.shape[0])
                break

        if num_classes_from_fc is None:
            num_classes_from_fc = int(meta.get("num_classes", 3))

        class_names = [f"class_{i}" for i in range(num_classes_from_fc)]

    num_classes = len(class_names)

    if "num_classes" in meta:
        ckpt_num_classes = int(meta["num_classes"])
        if ckpt_num_classes != num_classes:
            raise RuntimeError(
                f"类别数不一致：checkpoint num_classes={ckpt_num_classes}, "
                f"实际 class_names={class_names}"
            )

    model = ResNet1D18(num_classes=num_classes).to(device)
    model.load_state_dict(state_dict, strict=True)
    model.eval()

    return model, meta, class_names


# ======================================================================================
# Prediction
# ======================================================================================

@torch.no_grad()
def predict_one_file(
    model: torch.nn.Module,
    path: Path,
    device: torch.device,
    window_size: int,
    stride: int,
    max_windows: Optional[int],
    signal_key: str,
    normalize: str,
    batch_size: int,
    num_workers: int,
    cache_signals: bool,
) -> Dict[str, Any]:
    ds = FileInferenceDataset(
        path=path,
        window_size=window_size,
        stride=stride,
        max_windows=max_windows,
        signal_key=signal_key,
        normalize=normalize,
        cache_signals=cache_signals,
    )

    loader = DataLoader(
        ds,
        batch_size=batch_size,
        shuffle=False,
        drop_last=False,
        num_workers=num_workers,
        pin_memory=(device.type == "cuda"),
    )

    logits_all: List[torch.Tensor] = []
    window_probs: List[torch.Tensor] = []
    window_preds: List[torch.Tensor] = []

    model.eval()

    for x in loader:
        x = x.to(device, non_blocking=True)

        logits, _feat = model(x)

        prob = torch.softmax(logits, dim=1)
        pred = prob.argmax(dim=1)

        logits_all.append(logits.detach().cpu())
        window_probs.append(prob.detach().cpu())
        window_preds.append(pred.detach().cpu())

    all_logits = torch.cat(logits_all, dim=0)
    all_probs = torch.cat(window_probs, dim=0)
    all_window_preds = torch.cat(window_preds, dim=0).numpy()

    mean_logits = all_logits.mean(dim=0)
    file_prob_from_logits = torch.softmax(mean_logits, dim=0).numpy()

    mean_prob = all_probs.mean(dim=0).numpy()

    pred = int(np.argmax(file_prob_from_logits))
    confidence = float(file_prob_from_logits[pred])
    vote_ratio = float(np.mean(all_window_preds == pred))

    num_classes = int(file_prob_from_logits.shape[0])
    vote_dist = []
    for c in range(num_classes):
        vote_dist.append(float(np.mean(all_window_preds == c)))

    return {
        "pred": pred,
        "confidence": confidence,
        "vote_ratio": vote_ratio,
        "prob": file_prob_from_logits,
        "mean_prob": mean_prob,
        "vote_dist": vote_dist,
        "window_preds": all_window_preds,
        "n_windows": int(len(ds)),
    }


# ======================================================================================
# Platform mapping
# ======================================================================================

def class_display_name(label: str) -> str:
    mapping = {
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
    }
    return mapping.get(str(label), str(label))


def is_normal_class(label: str, normal_class: str) -> bool:
    return str(label).lower() == str(normal_class).lower()


def reliability_level(
    confidence: float,
    vote_ratio: float,
    high_conf: float,
    high_vote: float,
    low_conf: float,
    low_vote: float,
) -> str:
    if confidence >= high_conf and vote_ratio >= high_vote:
        return "high"
    if confidence < low_conf or vote_ratio < low_vote:
        return "low"
    return "middle"


def compute_health_and_risk(
    pred_label: str,
    prob: np.ndarray,
    class_names: List[str],
    confidence: float,
    vote_ratio: float,
    normal_class: str,
) -> Dict[str, Any]:
    prob = np.asarray(prob, dtype=np.float64).reshape(-1)

    normal_idx = None
    for i, c in enumerate(class_names):
        if is_normal_class(c, normal_class):
            normal_idx = i
            break

    if normal_idx is not None:
        p_normal = float(prob[normal_idx])
        p_abnormal = float(1.0 - p_normal)
    else:
        p_normal = 0.0
        p_abnormal = float(1.0 - np.max(prob))

    reliability_score = 0.5 * float(confidence) + 0.5 * float(vote_ratio)

    # 风险分数：异常概率为主，低置信和低投票作为补偿项
    risk_score = 100.0 * (
        0.65 * p_abnormal
        + 0.20 * (1.0 - confidence)
        + 0.15 * (1.0 - vote_ratio)
    )

    if not is_normal_class(pred_label, normal_class):
        risk_score = max(risk_score, 55.0 + 35.0 * confidence)

    risk_score = clamp(risk_score, 0.0, 100.0)
    health_index = clamp(100.0 - risk_score, 0.0, 100.0)

    if health_index >= 80:
        risk_level = "低"
        health_state = "健康"
    elif health_index >= 60:
        risk_level = "关注"
        health_state = "关注"
    elif health_index >= 40:
        risk_level = "预警"
        health_state = "预警"
    else:
        risk_level = "高"
        health_state = "报警"

    return {
        "health_index": int(round(health_index)),
        "risk_score": float(risk_score),
        "risk_level": risk_level,
        "health_state": health_state,
        "p_normal": float(p_normal),
        "p_abnormal": float(p_abnormal),
        "reliability_score": float(reliability_score),
    }


def decision_reason(
    pred_label: str,
    confidence: float,
    vote_ratio: float,
    reliability: str,
    health_index: int,
    risk_level: str,
) -> str:
    display = class_display_name(pred_label)

    if reliability == "high":
        return (
            f"模型多数窗口一致判定为{display}，"
            f"文件级置信度为{confidence:.4f}，窗口投票比例为{vote_ratio:.4f}，"
            f"健康指数为{health_index}，综合风险等级为{risk_level}。"
        )

    if reliability == "middle":
        return (
            f"模型判定为{display}，但置信度或窗口投票比例处于中等水平；"
            f"confidence={confidence:.4f}，vote_ratio={vote_ratio:.4f}。"
            f"建议结合时域波形、频谱和包络谱进行复核。"
        )

    return (
        f"模型判定为{display}，但该文件靠近决策边界；"
        f"confidence={confidence:.4f}，vote_ratio={vote_ratio:.4f}。"
        f"建议人工复核或进行二次采样。"
    )


def build_diagnostic_evidence(
    pred_label: str,
    confidence: float,
    vote_ratio: float,
    reliability: str,
    time_features: Dict[str, float],
    spectrum_module: Dict[str, Any],
    envelope_module: Dict[str, Any],
    health_info: Dict[str, Any],
) -> List[Dict[str, Any]]:
    evidence: List[Dict[str, Any]] = []

    evidence.append({
        "rank": 1,
        "title": "模型判别结果",
        "level": "高" if confidence >= 0.80 else "中" if confidence >= 0.65 else "低",
        "description": (
            f"最终判别为 {class_display_name(pred_label)}，"
            f"置信度 {confidence:.4f}，窗口投票比例 {vote_ratio:.4f}。"
        ),
        "value": {
            "pred_label": pred_label,
            "confidence": confidence,
            "vote_ratio": vote_ratio,
            "reliability": reliability,
        },
    })

    rms = safe_float(time_features.get("rms", 0.0))
    peak = safe_float(time_features.get("peak", 0.0))
    kurt = safe_float(time_features.get("kurtosis", 0.0))
    crest = safe_float(time_features.get("crest_factor", 0.0))

    level = "高" if kurt >= 5.0 or crest >= 6.0 else "中" if kurt >= 3.5 or crest >= 4.5 else "低"
    evidence.append({
        "rank": 2,
        "title": "时域冲击特征",
        "level": level,
        "description": (
            f"RMS={rms:.4f}，峰值={peak:.4f}，峭度={kurt:.4f}，峰值因子={crest:.4f}。"
        ),
        "value": {
            "rms": rms,
            "peak": peak,
            "kurtosis": kurt,
            "crest_factor": crest,
        },
    })

    dom_peaks = spectrum_module.get("dominant_peaks", [])
    if dom_peaks:
        top = dom_peaks[0]
        desc = (
            f"频谱主峰位于 {top['frequency_hz']:.2f} Hz，"
            f"幅值 {top['amplitude']:.6f}。"
        )
    else:
        desc = "未检测到显著频谱主峰。"

    evidence.append({
        "rank": 3,
        "title": "频域主峰特征",
        "level": "中",
        "description": desc,
        "value": {
            "dominant_peaks": dom_peaks[:5],
        },
    })

    env_peaks = envelope_module.get("dominant_peaks", [])
    if envelope_module.get("available", False) and env_peaks:
        top_env = env_peaks[0]
        env_desc = (
            f"包络谱主峰位于 {top_env['frequency_hz']:.2f} Hz，"
            f"幅值 {top_env['amplitude']:.6f}。"
        )
    else:
        env_desc = "包络谱未启用或未检测到显著主峰。"

    evidence.append({
        "rank": 4,
        "title": "包络谱辅助证据",
        "level": "中" if envelope_module.get("available", False) else "低",
        "description": env_desc,
        "value": {
            "dominant_peaks": env_peaks[:5],
        },
    })

    evidence.append({
        "rank": 5,
        "title": "健康指数与风险等级",
        "level": str(health_info["risk_level"]),
        "description": (
            f"健康指数为 {health_info['health_index']}，"
            f"综合风险等级为 {health_info['risk_level']}。"
        ),
        "value": health_info,
    })

    return evidence


def build_maintenance_advice(
    pred_label: str,
    reliability: str,
    health_info: Dict[str, Any],
) -> Dict[str, Any]:
    display = class_display_name(pred_label)
    risk_level = str(health_info["risk_level"])
    health_index = int(health_info["health_index"])

    if risk_level == "高":
        priority = "高优先级"
        deadline = "3天内"
    elif risk_level == "预警":
        priority = "中高优先级"
        deadline = "7天内"
    elif risk_level == "关注":
        priority = "中优先级"
        deadline = "14天内"
    else:
        priority = "低优先级"
        deadline = "按计划巡检"

    if display == "正常":
        actions = [
            "保持当前监测频率",
            "继续跟踪 RMS、峭度、峰值因子等趋势指标",
            "若连续出现低置信度结果，建议复测并检查传感器安装状态",
        ]
        headline = "设备状态正常，建议持续监测"
    elif "外圈" in display:
        actions = [
            "计划停机检查轴承外圈及滚道表面",
            "复核传感器安装状态与测点方向",
            "对比频谱和包络谱中外圈故障相关特征",
            "检查润滑状态和轴承座紧固情况",
        ]
        headline = "计划停机检查轴承外圈并复测振动"
    elif "滚动体" in display:
        actions = [
            "检查滚动体表面损伤和保持架状态",
            "复核润滑状态，排查污染颗粒或润滑不足",
            "结合包络谱复核滚动体故障调制特征",
            "必要时进行拆检或更换轴承",
        ]
        headline = "检查滚动体及保持架状态"
    elif "内圈" in display:
        actions = [
            "检查轴承内圈及轴配合状态",
            "排查轴向窜动、装配偏心和过盈配合异常",
            "结合转频边带和包络谱复核内圈故障特征",
        ]
        headline = "检查轴承内圈及轴配合状态"
    else:
        actions = [
            "结合时域波形、频谱和包络谱进行复核",
            "检查传感器安装状态、采集通道和工况记录",
            "必要时进行二次采样或人工复核",
        ]
        headline = f"复核 {display} 诊断结果"

    if reliability == "low":
        actions.insert(0, "当前诊断可靠性偏低，建议优先进行二次采样复核")

    return {
        "priority": priority,
        "headline": headline,
        "recommended_deadline": deadline,
        "health_index": health_index,
        "risk_level": risk_level,
        "actions": actions,
    }


def build_platform_report(
    row: Dict[str, Any],
    raw_signal: np.ndarray,
    raw_meta: Dict[str, Any],
    args: argparse.Namespace,
    class_names: List[str],
    spectrum_module: Dict[str, Any],
    envelope_module: Dict[str, Any],
    time_features: Dict[str, float],
    health_info: Dict[str, Any],
    evidence: List[Dict[str, Any]],
    maintenance: Dict[str, Any],
) -> Dict[str, Any]:
    fs = float(raw_meta.get("sample_rate", args.sample_rate))
    duration = float(len(raw_signal) / fs) if fs > 0 else 0.0

    t = np.arange(len(raw_signal), dtype=np.float64) / fs if fs > 0 else np.arange(len(raw_signal))
    waveform_curve = downsample_curve(t, raw_signal, args.max_platform_points)

    pred_label = row["pred_label"]
    pred_display = class_display_name(pred_label)

    report = {
        "platform_version": "bearing_ccdg_platform_output_v1",
        "generated_at": now_str(),

        "device_info_module": {
            "device_id": args.device_id,
            "device_name": args.device_name,
            "device_type": args.device_type,
            "installation_position": args.installation_position,
            "channel": args.channel,
            "target_speed_rpm": int(args.target_speed),
            "sample_rate_hz": fs,
            "data_duration_s": duration,
            "model_version": args.model_version,
            "operator": args.operator,
            "analysis_time": now_str(),
            "source_file": row["filename"],
            "source_path": row["path"],
        },

        "top_status_cards_module": {
            "health_index": {
                "value": int(health_info["health_index"]),
                "unit": "/100",
                "state": health_info["health_state"],
            },
            "comprehensive_risk": {
                "value": health_info["risk_level"],
                "risk_score": health_info["risk_score"],
            },
            "fault_type": {
                "label": pred_label,
                "display_name": pred_display,
            },
            "confidence": {
                "value": round(float(row["confidence"]) * 100.0, 2),
                "unit": "%",
            },
            "reliability": {
                "level": row["reliability"],
                "vote_ratio": round(float(row["vote_ratio"]) * 100.0, 2),
                "unit": "%",
            },
        },

        "time_domain_feature_module": {
            "title": "时域特征分析",
            "unit": args.signal_unit,
            "channel": args.channel,
            "features": {
                "rms": time_features["rms"],
                "peak": time_features["peak"],
                "peak_to_peak": time_features["peak_to_peak"],
                "crest_factor": time_features["crest_factor"],
                "kurtosis": time_features["kurtosis"],
                "skewness": time_features["skewness"],
                "mean": time_features["mean"],
                "std": time_features["std"],
            },
            "waveform_curve": waveform_curve,
        },

        "frequency_domain_diagnosis_module": {
            "title": "频域诊断分析",
            "unit": args.signal_unit,
            "frequency_resolution_hz": spectrum_module.get("frequency_resolution", 0.0),
            "rotating_frequency_hz": spectrum_module.get("rotating_frequency_hz", None),
            "harmonic_markers": spectrum_module.get("harmonic_markers", []),
            "dominant_peaks": spectrum_module.get("dominant_peaks", []),
            "spectrum_curve": spectrum_module.get("spectrum_curve", {"x": [], "y": []}),
            "envelope_spectrum": envelope_module,
        },

        "model_discrimination_module": {
            "title": "模型判别结果",
            "pred_label": pred_label,
            "pred_display_name": pred_display,
            "confidence": row["confidence"],
            "vote_ratio": row["vote_ratio"],
            "reliability": row["reliability"],
            "decision_reason": row["decision_reason"],
            "class_probabilities": {
                cname: row[f"prob_{cname}"] for cname in class_names
            },
            "window_vote_distribution": {
                cname: row[f"vote_{cname}"] for cname in class_names
            },
        },

        "diagnostic_evidence_module": {
            "title": "诊断证据",
            "items": evidence,
        },

        "health_trend_module": {
            "title": "健康指数趋势",
            "current_health_index": int(health_info["health_index"]),
            "current_risk_level": health_info["risk_level"],
            "note": "单次脚本运行仅输出当前健康指数；若平台持续接入，可按 analysis_records.csv 形成趋势曲线。",
        },

        "maintenance_advice_module": maintenance,

        "analysis_record_module": {
            "analysis_time": now_str(),
            "model_version": args.model_version,
            "data_duration_s": duration,
            "diagnosis_result": pred_display,
            "confidence_percent": round(float(row["confidence"]) * 100.0, 2),
            "health_index": int(health_info["health_index"]),
            "risk_level": health_info["risk_level"],
            "operator": args.operator,
            "status": "完成",
        },
    }

    return report


# ======================================================================================
# Summary
# ======================================================================================

def summarize_predictions(
    rows: List[Dict[str, Any]],
    class_names: List[str],
    labels_available: bool,
    low_conf: float,
    low_vote: float,
) -> Dict[str, Any]:
    pred_ids = np.asarray([int(r["pred_id"]) for r in rows], dtype=np.int64)

    pred_distribution: Dict[str, int] = {}
    for i, c in enumerate(class_names):
        pred_distribution[c] = int(np.sum(pred_ids == i))

    summary: Dict[str, Any] = {
        "num_files": int(len(rows)),
        "classes": class_names,
        "labels_available": bool(labels_available),
        "pred_distribution": pred_distribution,
        "mean_confidence": float(np.mean([float(r["confidence"]) for r in rows])) if rows else 0.0,
        "mean_vote_ratio": float(np.mean([float(r["vote_ratio"]) for r in rows])) if rows else 0.0,
        "mean_health_index": float(np.mean([float(r["health_index"]) for r in rows])) if rows else 0.0,
        "low_conf_file_ratio": float(np.mean([float(r["confidence"]) < low_conf for r in rows])) if rows else 0.0,
        "low_vote_file_ratio": float(np.mean([float(r["vote_ratio"]) < low_vote for r in rows])) if rows else 0.0,
        "reliability_distribution": {
            "high": int(sum(1 for r in rows if r["reliability"] == "high")),
            "middle": int(sum(1 for r in rows if r["reliability"] == "middle")),
            "low": int(sum(1 for r in rows if r["reliability"] == "low")),
        },
        "risk_distribution": {
            "低": int(sum(1 for r in rows if r["risk_level"] == "低")),
            "关注": int(sum(1 for r in rows if r["risk_level"] == "关注")),
            "预警": int(sum(1 for r in rows if r["risk_level"] == "预警")),
            "高": int(sum(1 for r in rows if r["risk_level"] == "高")),
        },
    }

    pred_class_stats: Dict[str, Dict[str, Any]] = {}
    for i, c in enumerate(class_names):
        sub = [r for r in rows if int(r["pred_id"]) == i]

        if not sub:
            pred_class_stats[c] = {
                "num_files": 0,
                "mean_confidence": 0.0,
                "mean_vote_ratio": 0.0,
                "mean_health_index": 0.0,
                "low_conf_ratio": 0.0,
                "low_vote_ratio": 0.0,
            }
        else:
            pred_class_stats[c] = {
                "num_files": int(len(sub)),
                "mean_confidence": float(np.mean([float(r["confidence"]) for r in sub])),
                "mean_vote_ratio": float(np.mean([float(r["vote_ratio"]) for r in sub])),
                "mean_health_index": float(np.mean([float(r["health_index"]) for r in sub])),
                "low_conf_ratio": float(np.mean([float(r["confidence"]) < low_conf for r in sub])),
                "low_vote_ratio": float(np.mean([float(r["vote_ratio"]) < low_vote for r in sub])),
            }

    summary["pred_class_stats"] = pred_class_stats

    if labels_available:
        y_true = np.asarray([int(r["true_id"]) for r in rows], dtype=np.int64)
        y_pred = np.asarray([int(r["pred_id"]) for r in rows], dtype=np.int64)

        file_acc = accuracy_score_np(y_true, y_pred)
        file_mf1 = macro_f1_score_np(y_true, y_pred, len(class_names))
        cm = confusion_matrix_np(y_true, y_pred, len(class_names))
        cls_report = per_class_report(y_true, y_pred, class_names)

        summary["file_acc"] = file_acc
        summary["file_macro_f1"] = file_mf1
        summary["confusion_matrix"] = cm.tolist()
        summary["classification_report"] = cls_report

        true_class_stats: Dict[str, Dict[str, Any]] = {}
        for i, c in enumerate(class_names):
            sub = [r for r in rows if int(r["true_id"]) == i]

            if not sub:
                true_class_stats[c] = {
                    "num_files": 0,
                    "acc": 0.0,
                    "mean_confidence": 0.0,
                    "mean_vote_ratio": 0.0,
                    "mean_health_index": 0.0,
                }
            else:
                true_class_stats[c] = {
                    "num_files": int(len(sub)),
                    "acc": float(np.mean([int(r["correct"]) for r in sub])),
                    "mean_confidence": float(np.mean([float(r["confidence"]) for r in sub])),
                    "mean_vote_ratio": float(np.mean([float(r["vote_ratio"]) for r in sub])),
                    "mean_health_index": float(np.mean([float(r["health_index"]) for r in sub])),
                }

        summary["true_class_stats"] = true_class_stats

    return summary


def build_dashboard_summary(
    rows: List[Dict[str, Any]],
    platform_reports: List[Dict[str, Any]],
    args: argparse.Namespace,
    class_names: List[str],
    metrics: Dict[str, Any],
) -> Dict[str, Any]:
    if not rows:
        return {}

    # 选取最需要展示的文件：优先风险最高，其次健康指数最低
    sorted_idx = sorted(
        range(len(rows)),
        key=lambda i: (
            -float(rows[i]["risk_score"]),
            float(rows[i]["health_index"]),
        ),
    )
    idx = sorted_idx[0]
    selected = platform_reports[idx]

    health_trend_points = []
    for i, r in enumerate(rows, start=1):
        health_trend_points.append({
            "index": i,
            "filename": r["filename"],
            "health_index": int(r["health_index"]),
            "risk_level": r["risk_level"],
            "pred_label": r["pred_label"],
            "confidence": float(r["confidence"]),
            "vote_ratio": float(r["vote_ratio"]),
        })

    dashboard = {
        "dashboard_version": "bearing_ccdg_dashboard_v1",
        "generated_at": now_str(),
        "target_speed": int(args.target_speed),
        "device_id": args.device_id,
        "device_name": args.device_name,
        "model_version": args.model_version,
        "selected_file_policy": "highest_risk_file",
        "selected_file": rows[idx]["filename"],
        "selected_report": selected,
        "batch_summary": metrics,
        "health_trend_module": {
            "title": "健康指数趋势",
            "points": health_trend_points,
        },
    }

    return dashboard


# ======================================================================================
# Main
# ======================================================================================

def test(args: argparse.Namespace) -> None:
    start_time = time.time()

    checkpoint_path = Path(args.checkpoint).resolve()
    target_root = Path(args.target_root).resolve()

    if not checkpoint_path.exists():
        raise FileNotFoundError(f"checkpoint 不存在：{checkpoint_path}")

    if not target_root.exists():
        raise FileNotFoundError(f"target_root 不存在：{target_root}")

    device = make_device(args.device)

    model, ckpt_meta, class_names = load_checkpoint_and_model(
        checkpoint_path=checkpoint_path,
        device=device,
        args_classes=args.classes,
    )

    source_speeds = ckpt_meta.get("source_speeds", [])
    source_speeds = [int(s) for s in source_speeds] if source_speeds else []

    train_rpms = ckpt_meta.get("train_rpms", [])
    val_rpms = ckpt_meta.get("val_rpms", [])

    if source_speeds and int(args.target_speed) in set(source_speeds) and not args.allow_source_speed_test:
        raise RuntimeError(
            f"target_speed={args.target_speed} 属于 checkpoint 记录的 source_speeds={source_speeds}。\n"
            "按照零样本域泛化协议，目标转速应为源域之外的未知转速。\n"
            "如果只是调试，请显式添加 --allow_source_speed_test。"
        )

    if args.window_size > 0:
        window_size = int(args.window_size)
    else:
        window_size = int(ckpt_meta.get("window_size", 4096))

    if args.stride > 0:
        stride = int(args.stride)
    else:
        stride = int(ckpt_meta.get("stride", window_size))

    if args.signal_key:
        signal_key = str(args.signal_key)
    else:
        signal_key = str(ckpt_meta.get("signal_key", "DE_time"))

    if args.normalize != "auto":
        normalize = str(args.normalize)
    else:
        normalize = str(ckpt_meta.get("normalize", "zscore"))

    max_windows = normalize_max_windows(args.eval_windows_per_file)

    save_dir = Path(args.save_dir)
    if not save_dir.is_absolute():
        save_dir = ROOT / save_dir

    ensure_dir(save_dir)
    platform_report_dir = save_dir / "platform_reports"
    ensure_dir(platform_report_dir)

    records, labels_available, label_source = collect_target_records(
        target_root=target_root,
        class_names=class_names,
        target_has_labels=args.target_has_labels,
        recursive=args.recursive,
        ground_truth_csv=args.ground_truth_csv,
    )

    run_config = {
        "checkpoint": normalize_path(checkpoint_path),
        "target_root": normalize_path(target_root),
        "target_speed": int(args.target_speed),

        "target_has_labels": bool(args.target_has_labels),
        "ground_truth_csv": "" if not args.ground_truth_csv else normalize_path(Path(args.ground_truth_csv)),
        "labels_available": bool(labels_available),
        "label_source": label_source,
        "filename_label_parsing_used": False,

        "classes": class_names,
        "source_speeds": source_speeds,
        "train_rpms": train_rpms,
        "val_rpms": val_rpms,

        "window_size": window_size,
        "stride": stride,
        "eval_windows_per_file": "ALL" if max_windows is None else int(max_windows),

        "signal_key": signal_key,
        "normalize": normalize,
        "device": str(device),

        "device_id": args.device_id,
        "device_name": args.device_name,
        "device_type": args.device_type,
        "installation_position": args.installation_position,
        "channel": args.channel,
        "model_version": args.model_version,
        "operator": args.operator,

        "platform_output": True,
        "target_data_used_for_training_or_selection": False,
    }

    with open(save_dir / "test_config.json", "w", encoding="utf-8") as f:
        json.dump(to_jsonable(run_config), f, ensure_ascii=False, indent=2)

    print("=" * 110)
    print("CCDG TARGET-SPEED TESTING + PLATFORM MODULE OUTPUT")
    print(f"Checkpoint       : {checkpoint_path}")
    print(f"Source speeds    : {source_speeds}")
    print(f"Train rpms       : {train_rpms}")
    print(f"Val rpms         : {val_rpms}")
    print(f"Target root      : {target_root}")
    print(f"Target speed     : {args.target_speed}")
    print(f"Target labels    : {labels_available}")
    print(f"Label source     : {label_source}")
    print(f"Parse filename   : False")
    print(f"Files            : {len(records)}")
    print(f"Classes          : {class_names}")
    print(f"Window/stride    : {window_size}/{stride}")
    print(f"Eval windows     : {'ALL' if max_windows is None else max_windows}")
    print(f"Signal key       : {signal_key}")
    print(f"Normalize        : {normalize}")
    print(f"Device           : {device}")
    print(f"Platform reports : {platform_report_dir}")
    print("Training/selection target data usage: NONE. This script is final evaluation only.")
    print("=" * 110)

    rows: List[Dict[str, Any]] = []
    platform_reports: List[Dict[str, Any]] = []
    analysis_records: List[Dict[str, Any]] = []

    for idx, rec in enumerate(records, start=1):
        path = Path(rec["path"])

        pred_result = predict_one_file(
            model=model,
            path=path,
            device=device,
            window_size=window_size,
            stride=stride,
            max_windows=max_windows,
            signal_key=signal_key,
            normalize=normalize,
            batch_size=args.batch_size,
            num_workers=args.num_workers,
            cache_signals=args.cache_signals,
        )

        pred_id = int(pred_result["pred"])
        pred_label = class_names[pred_id]
        pred_display = class_display_name(pred_label)

        conf = float(pred_result["confidence"])
        vote = float(pred_result["vote_ratio"])

        prob = np.asarray(pred_result["prob"], dtype=np.float64)
        mean_prob = np.asarray(pred_result["mean_prob"], dtype=np.float64)
        vote_dist = list(pred_result["vote_dist"])

        n_windows = int(pred_result["n_windows"])
        window_pred_ids = [int(x) for x in pred_result["window_preds"].tolist()]

        reliability = reliability_level(
            confidence=conf,
            vote_ratio=vote,
            high_conf=args.high_conf,
            high_vote=args.high_vote,
            low_conf=args.low_conf,
            low_vote=args.low_vote,
        )

        health_info = compute_health_and_risk(
            pred_label=pred_label,
            prob=prob,
            class_names=class_names,
            confidence=conf,
            vote_ratio=vote,
            normal_class=args.normal_class,
        )

        reason = decision_reason(
            pred_label=pred_label,
            confidence=conf,
            vote_ratio=vote,
            reliability=reliability,
            health_index=int(health_info["health_index"]),
            risk_level=str(health_info["risk_level"]),
        )

        true_id = rec.get("y", None)
        true_label = rec.get("true_label", "")

        correct: Any = ""
        if true_id is not None:
            true_id = int(true_id)
            true_label = class_names[true_id]
            correct = int(true_id == pred_id)

        # 平台特征模块
        raw_signal, raw_meta = load_mat_signal_for_platform(
            path=path,
            signal_key=signal_key,
            default_fs=args.sample_rate,
        )

        fs = float(raw_meta.get("sample_rate", args.sample_rate))
        rpm_for_feature = float(args.target_speed)
        if raw_meta.get("rpm_from_mat", None) is not None:
            rpm_for_feature = float(raw_meta["rpm_from_mat"])

        time_features = compute_time_features(raw_signal)

        max_freq = args.spectrum_max_freq
        if max_freq <= 0:
            max_freq = fs / 2.0

        spectrum_module = compute_spectrum_module(
            sig=raw_signal,
            fs=fs,
            rpm=rpm_for_feature,
            max_freq=max_freq,
            max_points=args.max_platform_points,
            top_k=args.top_k_peaks,
        )

        envelope_module = compute_envelope_module(
            sig=raw_signal,
            fs=fs,
            rpm=rpm_for_feature,
            max_freq=args.envelope_max_freq,
            max_points=args.max_platform_points,
            top_k=args.top_k_peaks,
        )

        evidence = build_diagnostic_evidence(
            pred_label=pred_label,
            confidence=conf,
            vote_ratio=vote,
            reliability=reliability,
            time_features=time_features,
            spectrum_module=spectrum_module,
            envelope_module=envelope_module,
            health_info=health_info,
        )

        maintenance = build_maintenance_advice(
            pred_label=pred_label,
            reliability=reliability,
            health_info=health_info,
        )

        row: Dict[str, Any] = {
            "index": idx,
            "path": normalize_path(path),
            "filename": path.name,
            "target_speed": int(args.target_speed),

            "true_id": "" if true_id is None else int(true_id),
            "true_label": true_label,
            "label_source": rec.get("label_source", "none"),

            "pred_id": pred_id,
            "pred_label": pred_label,
            "pred_display_name": pred_display,
            "correct": correct,

            "confidence": conf,
            "vote_ratio": vote,
            "reliability": reliability,
            "decision_reason": reason,

            "health_index": int(health_info["health_index"]),
            "risk_score": float(health_info["risk_score"]),
            "risk_level": str(health_info["risk_level"]),
            "health_state": str(health_info["health_state"]),

            "rms": float(time_features["rms"]),
            "peak": float(time_features["peak"]),
            "kurtosis": float(time_features["kurtosis"]),
            "crest_factor": float(time_features["crest_factor"]),

            "n_windows": n_windows,
        }

        for c, cname in enumerate(class_names):
            row[f"prob_{cname}"] = float(prob[c])
            row[f"mean_prob_{cname}"] = float(mean_prob[c])
            row[f"vote_{cname}"] = float(vote_dist[c])

        row["window_pred_ids"] = window_pred_ids
        rows.append(row)

        platform_report = build_platform_report(
            row=row,
            raw_signal=raw_signal,
            raw_meta=raw_meta,
            args=args,
            class_names=class_names,
            spectrum_module=spectrum_module,
            envelope_module=envelope_module,
            time_features=time_features,
            health_info=health_info,
            evidence=evidence,
            maintenance=maintenance,
        )

        platform_reports.append(platform_report)

        report_path = platform_report_dir / f"{path.stem}_platform_report.json"
        with open(report_path, "w", encoding="utf-8") as f:
            json.dump(to_jsonable(platform_report), f, ensure_ascii=False, indent=2)

        analysis_record = {
            "analysis_time": now_str(),
            "model_version": args.model_version,
            "data_duration": platform_report["device_info_module"]["data_duration_s"],
            "diagnosis_result": pred_display,
            "confidence_percent": round(conf * 100.0, 2),
            "health_index": int(health_info["health_index"]),
            "risk_level": str(health_info["risk_level"]),
            "operator": args.operator,
            "status": "完成",
            "report_file": normalize_path(report_path),
            "source_file": path.name,
        }
        analysis_records.append(analysis_record)

        if args.print_each_file:
            if true_id is None:
                print(
                    f"[{idx:04d}/{len(records)}] {path.name} -> "
                    f"{pred_display} conf={conf:.4f} vote={vote:.4f} "
                    f"health={health_info['health_index']} risk={health_info['risk_level']} "
                    f"reliability={reliability}"
                )
            else:
                flag = "OK" if correct == 1 else "ERR"
                print(
                    f"[{idx:04d}/{len(records)}] {path.name} true={true_label} -> "
                    f"{pred_display} conf={conf:.4f} vote={vote:.4f} "
                    f"health={health_info['health_index']} risk={health_info['risk_level']} "
                    f"reliability={reliability} {flag}"
                )

    rows_for_csv: List[Dict[str, Any]] = []
    for r in rows:
        rr = dict(r)
        rr.pop("window_pred_ids", None)
        rows_for_csv.append(rr)

    save_csv(save_dir / "target_predictions.csv", rows_for_csv)
    save_csv(save_dir / "analysis_records.csv", analysis_records)

    metrics = summarize_predictions(
        rows=rows,
        class_names=class_names,
        labels_available=labels_available,
        low_conf=args.low_conf,
        low_vote=args.low_vote,
    )

    metrics["run_config"] = run_config
    metrics["elapsed_sec"] = float(time.time() - start_time)

    with open(save_dir / "target_metrics.json", "w", encoding="utf-8") as f:
        json.dump(to_jsonable(metrics), f, ensure_ascii=False, indent=2)

    if labels_available and "confusion_matrix" in metrics:
        cm = np.asarray(metrics["confusion_matrix"], dtype=np.int64)
        save_confusion_matrix_csv(save_dir / "confusion_matrix.csv", cm, class_names)

    low_rows = [
        r for r in rows_for_csv
        if float(r["confidence"]) < args.low_conf or float(r["vote_ratio"]) < args.low_vote
    ]
    save_csv(save_dir / "low_reliability_files.csv", low_rows)

    dashboard_summary = build_dashboard_summary(
        rows=rows_for_csv,
        platform_reports=platform_reports,
        args=args,
        class_names=class_names,
        metrics=metrics,
    )

    with open(save_dir / "platform_dashboard.json", "w", encoding="utf-8") as f:
        json.dump(to_jsonable(dashboard_summary), f, ensure_ascii=False, indent=2)

    print("=" * 110)
    print("TARGET TEST FINISHED")
    print(f"Save dir              : {save_dir}")
    print(f"Prediction CSV        : {save_dir / 'target_predictions.csv'}")
    print(f"Metrics JSON          : {save_dir / 'target_metrics.json'}")
    print(f"Platform dashboard    : {save_dir / 'platform_dashboard.json'}")
    print(f"Platform reports dir  : {platform_report_dir}")
    print(f"Analysis records CSV  : {save_dir / 'analysis_records.csv'}")
    print(f"Low reliability CSV   : {save_dir / 'low_reliability_files.csv'}")

    if labels_available:
        print("-" * 110)
        print(f"File ACC              : {metrics['file_acc']:.4f}")
        print(f"File Macro-F1         : {metrics['file_macro_f1']:.4f}")
        print(f"Confusion matrix CSV  : {save_dir / 'confusion_matrix.csv'}")
    else:
        print("-" * 110)
        print("No ground-truth labels are used. This is industrial unlabeled inference.")
        print("ACC / Macro-F1 / confusion matrix are not computed.")

    print("-" * 110)
    print(f"Mean confidence       : {metrics['mean_confidence']:.4f}")
    print(f"Mean vote ratio       : {metrics['mean_vote_ratio']:.4f}")
    print(f"Mean health index     : {metrics['mean_health_index']:.2f}")
    print(f"Low conf ratio        : {metrics['low_conf_file_ratio']:.4f}  (conf < {args.low_conf})")
    print(f"Low vote ratio        : {metrics['low_vote_file_ratio']:.4f}  (vote < {args.low_vote})")
    print(f"Reliability dist      : {metrics['reliability_distribution']}")
    print(f"Risk dist             : {metrics['risk_distribution']}")
    print(f"Pred distribution     : {metrics['pred_distribution']}")
    print("=" * 110)


# ======================================================================================
# CLI
# ======================================================================================

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Final target-speed testing with platform module output."
    )

    parser.add_argument("--checkpoint", type=str, default=r"D:\Dr_treasurechest\05_code\02-cpower_code\self_bearing_ccdg_improve\ccdg-v2\runs\train_ccdg_v2\best_model.pt")
    parser.add_argument("--target_root", type=str, default=r"D:\Dr_treasurechest\05_code\01-data\self_bearing\unlabel\1800rpm")
    parser.add_argument("--target_speed", type=int, default=1800)
    parser.add_argument("--save_dir", type=str, default=r"D:\Dr_treasurechest\05_code\02-cpower_code\self_bearing_ccdg_improve\ccdg-v2\runs\target_test\target_platform_test\1800")

    parser.add_argument(
        "--target_has_labels",
        action="store_true",
        help="目标目录是否按 N/OR/B 子文件夹组织。工业现场不要打开。",
    )

    parser.add_argument(
        "--ground_truth_csv",
        type=str,
        default="",
        help="离线评价用真实标签 CSV。工业现场不使用。",
    )

    parser.add_argument("--allow_source_speed_test", action="store_true")
    parser.add_argument("--classes", type=str, nargs="*", default=None)

    parser.add_argument("--signal_key", type=str, default="")
    parser.add_argument("--window_size", type=int, default=0)
    parser.add_argument("--stride", type=int, default=0)
    parser.add_argument("--eval_windows_per_file", type=int, default=-1)
    parser.add_argument("--normalize", type=str, default="auto", choices=["auto", "zscore", "none"])

    parser.add_argument("--recursive", action="store_true")
    parser.add_argument("--batch_size", type=int, default=256)
    parser.add_argument("--device", type=str, default="cuda")
    parser.add_argument("--num_workers", type=int, default=0)
    parser.add_argument("--cache_signals", action="store_true")

    # 平台参数
    parser.add_argument("--device_id", type=str, default="Bearing-TestRig-A01")
    parser.add_argument("--device_name", type=str, default="自建轴承试验台")
    parser.add_argument("--device_type", type=str, default="轴承试验台")
    parser.add_argument("--installation_position", type=str, default="TOP")
    parser.add_argument("--channel", type=str, default="CH1")
    parser.add_argument("--operator", type=str, default="远程工程师")
    parser.add_argument("--model_version", type=str, default="CCDG-v2")
    parser.add_argument("--signal_unit", type=str, default="g")
    parser.add_argument("--sample_rate", type=float, default=16000.0)
    parser.add_argument("--normal_class", type=str, default="N")

    # 平台曲线输出
    parser.add_argument("--max_platform_points", type=int, default=1200)
    parser.add_argument("--spectrum_max_freq", type=float, default=0.0, help="<=0 表示 Nyquist")
    parser.add_argument("--envelope_max_freq", type=float, default=1000.0)
    parser.add_argument("--top_k_peaks", type=int, default=8)

    # 可靠性阈值
    parser.add_argument("--high_conf", type=float, default=0.80)
    parser.add_argument("--high_vote", type=float, default=0.90)
    parser.add_argument("--low_conf", type=float, default=0.65)
    parser.add_argument("--low_vote", type=float, default=0.70)

    parser.add_argument("--print_each_file", action="store_true")

    return parser.parse_args()


if __name__ == "__main__":
    test(parse_args())
