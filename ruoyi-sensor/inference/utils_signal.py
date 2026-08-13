from pathlib import Path

import numpy as np
import os
import zipfile
import scipy.io


def _is_numeric_array(value):
    try:
        arr = np.asarray(value)
    except Exception:
        return False
    return np.issubdtype(arr.dtype, np.number) and arr.size > 0


def _find_signal_key(payload, keys, preferred):
    """Pick the most likely signal array key from a MAT/NPZ dictionary."""
    key_map = {str(k).lower(): k for k in keys}

    for name in preferred:
        lowered = str(name).lower()
        if lowered in key_map and _is_numeric_array(payload[key_map[lowered]]):
            return key_map[lowered]

    for name in preferred:
        lowered = str(name).lower()
        for key in keys:
            key_lower = str(key).lower()
            if not (key_lower.endswith(lowered) or lowered in key_lower):
                continue
            if _is_numeric_array(payload[key]) and np.asarray(payload[key]).size >= 16:
                return key

    numeric_keys = [key for key in keys if _is_numeric_array(payload[key])]
    if numeric_keys:
        return max(numeric_keys, key=lambda key: np.asarray(payload[key]).size)

    return keys[0]


def _select_signal_key(payload, source_name="<memory>", preferred_signal_key=None):
    """Return the selected signal key without loading/converting the signal."""
    keys = [k for k in payload if not str(k).startswith("__")]
    if not keys:
        raise KeyError(f"No valid data variables found in {source_name}")

    preferred = [
        "sig_acc_5120", "raw", "data", "signal", "vibration",
        "x", "X", "voltage", "DE_time", "wave", "values",
    ]
    if preferred_signal_key:
        preferred = [str(preferred_signal_key)] + [
            name for name in preferred if name != str(preferred_signal_key)
        ]

    return _find_signal_key(payload, keys, preferred)


def _extract_signal_from_dict(payload, source_name="<memory>", preferred_signal_key=None):
    """Extract 1-D float32 signal from a dict of arrays.

    Parameters
    ----------
    payload : dict
        Dictionary loaded from .mat or .npz file.
    source_name : str
        Identifier used in error messages.
    preferred_signal_key : str or None
        If set, this key is tried **first** before the built-in preferred list.
    """
    selected = _select_signal_key(payload, source_name, preferred_signal_key)
    sig = np.asarray(payload[selected], dtype=np.float32).squeeze()
    if sig.size == 0:
        raise ValueError(f"Variable '{selected}' contains no samples.")
    if sig.ndim != 1:
        sig = sig.reshape(-1)
    return np.nan_to_num(sig, nan=0.0, posinf=0.0, neginf=0.0)


def _extract_mat_metadata(payload):
    """
    Extract sample rate and RPM metadata from a .mat file dictionary.

    Returns
    -------
    dict with keys: sample_rate (float or None), rpm (float or None)
    """
    meta = {"sample_rate": None, "rpm": None}

    for key in ("sample_rate", "sr", "fs", "Fs", "sampling_rate"):
        if key in payload:
            try:
                val = float(np.asarray(payload[key]).reshape(-1)[0])
                if val > 0:
                    meta["sample_rate"] = val
                    break
            except Exception:
                continue

    for key in ("rpm", "speed", "rotating_speed"):
        if key in payload:
            try:
                val = float(np.asarray(payload[key]).reshape(-1)[0])
                if val > 0:
                    meta["rpm"] = val
                    break
            except Exception:
                continue

    return meta


def load_signal(path, signal_key="sig_acc_5120"):
    """Load a vibration signal from .npy, .npz, or .mat file."""
    path = Path(path)
    suffix = path.suffix.lower()

    if suffix == ".mat":
        mat = scipy.io.loadmat(str(path))
        return _extract_signal_from_dict(mat, source_name=path.name, preferred_signal_key=signal_key)

    return load_npy_signal(path, signal_key=signal_key)


MAX_SIGNAL_FILE_BYTES = int(os.environ.get("INFERENCE_MAX_SIGNAL_FILE_BYTES", 64 * 1024 * 1024))
MAX_SIGNAL_ELEMENTS = int(os.environ.get("INFERENCE_MAX_SIGNAL_ELEMENTS", 10_000_000))
MAX_NPZ_EXPANDED_BYTES = int(os.environ.get("INFERENCE_MAX_NPZ_EXPANDED_BYTES", 128 * 1024 * 1024))
MAX_NPZ_COMPRESSION_RATIO = float(os.environ.get("INFERENCE_MAX_NPZ_COMPRESSION_RATIO", 100.0))


def _validate_numpy_container(path):
    """Reject oversized files and suspicious NPZ archives before NumPy parses them."""
    path = Path(path)
    size = path.stat().st_size
    if size <= 0 or size > MAX_SIGNAL_FILE_BYTES:
        raise ValueError(f"Signal file size is outside the allowed range: {size} bytes")
    if path.suffix.lower() == ".npz":
        if not zipfile.is_zipfile(path):
            raise ValueError("Invalid NPZ container")
        with zipfile.ZipFile(path) as archive:
            expanded = 0
            for entry in archive.infolist():
                if entry.is_dir() or not entry.filename.endswith(".npy"):
                    raise ValueError("NPZ may contain only NPY array entries")
                expanded += entry.file_size
                compressed = max(entry.compress_size, 1)
                if entry.file_size / compressed > MAX_NPZ_COMPRESSION_RATIO:
                    raise ValueError("NPZ compression ratio exceeds the safety limit")
            if expanded > MAX_NPZ_EXPANDED_BYTES:
                raise ValueError("NPZ expanded size exceeds the safety limit")


def validate_numeric_array(value, source_name="<array>"):
    """Return a finite, bounded 1-D float32 array without deserializing objects."""
    array = np.asarray(value)
    if array.dtype.hasobject or array.dtype.fields is not None:
        raise ValueError(f"Object and structured arrays are forbidden: {source_name}")
    if not np.issubdtype(array.dtype, np.number) or np.issubdtype(array.dtype, np.complexfloating):
        raise ValueError(f"Only real numeric arrays are supported: {source_name}")
    if array.ndim == 0 or array.ndim > 2:
        raise ValueError(f"Signal arrays must have one or two dimensions: {source_name}")
    if array.size == 0 or array.size > MAX_SIGNAL_ELEMENTS:
        raise ValueError(f"Signal element count is outside the allowed range: {array.size}")
    result = np.asarray(array, dtype=np.float32).reshape(-1)
    if not np.isfinite(result).all():
        raise ValueError(f"Signal contains NaN or infinite values: {source_name}")
    return result


def load_npy_signal(path, signal_key="sig_acc_5120"):
    """Load a numeric vibration signal without enabling Pickle deserialization."""
    path = Path(path)
    _validate_numpy_container(path)
    payload = np.load(str(path), allow_pickle=False)

    if isinstance(payload, np.lib.npyio.NpzFile):
        try:
            keys = [k for k in payload.files if not str(k).startswith("__")]
            if not keys:
                raise KeyError(f"No valid arrays found in: {path}")
            arrays = {key: validate_numeric_array(payload[key], f"{path.name}:{key}") for key in keys}
            return _extract_signal_from_dict(arrays, source_name=path.name, preferred_signal_key=signal_key)
        finally:
            payload.close()

    if isinstance(payload, np.ndarray):
        return validate_numeric_array(payload, path.name)

    raise TypeError(f"Unsupported npy file format: {type(payload)}")


def zscore_1d(x):
    """Z-score normalize a 1-D array to zero mean and unit variance."""
    x = np.asarray(x, dtype=np.float64)
    mu = x.mean()
    sigma = x.std()
    if sigma < 1e-8:
        return np.asarray(x - mu, dtype=np.float32)
    return np.asarray((x - mu) / sigma, dtype=np.float32)


def count_windows(total_len, win_len, stride):
    """Number of sliding windows for given signal length."""
    if total_len < win_len:
        return 0
    return (total_len - win_len) // stride + 1


def get_window(sig, start, win_len):
    """Extract a window of length win_len from signal starting at start."""
    return np.asarray(sig[start:start + win_len], dtype=np.float32)


def top_fft_peaks(sig, fs=5120.0, n_peaks=6):
    """Return top N FFT peak frequencies and amplitudes."""
    sig = np.asarray(sig, dtype=np.float32).reshape(-1)
    n = sig.size
    if n == 0:
        return []
    sig_centered = sig - sig.mean()
    amp = np.abs(np.fft.rfft(sig_centered)) / n
    if amp.size > 2:
        amp[1:-1] *= 2.0
    freqs = np.fft.rfftfreq(n, d=1.0 / fs)
    order = np.argsort(amp)[::-1]
    peaks = []
    for idx in order[:n_peaks]:
        peaks.append((float(freqs[idx]), float(amp[idx])))
    return peaks
