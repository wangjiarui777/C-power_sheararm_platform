from pathlib import Path

import numpy as np
import scipy.io


def _extract_signal_from_dict(payload, source_name="<memory>"):
    """Extract 1-D float32 signal from a dict of arrays."""
    keys = [k for k in payload if not str(k).startswith("__")]
    if not keys:
        raise KeyError(f"No valid data variables found in {source_name}")
    preferred = [
        "sig_acc_5120", "raw", "data", "signal", "vibration",
        "x", "X", "voltage", "DE_time", "wave", "values",
    ]
    selected = next((k for k in preferred if k in payload), keys[0])
    sig = np.asarray(payload[selected], dtype=np.float32).squeeze()
    if sig.size == 0:
        raise ValueError(f"Variable '{selected}' contains no samples.")
    if sig.ndim != 1:
        sig = sig.reshape(-1)
    return np.nan_to_num(sig, nan=0.0, posinf=0.0, neginf=0.0)


def load_signal(path, signal_key="sig_acc_5120"):
    """Load a vibration signal from .npy, .npz, or .mat file."""
    path = Path(path)
    suffix = path.suffix.lower()

    if suffix == ".mat":
        mat = scipy.io.loadmat(str(path))
        return _extract_signal_from_dict(mat, source_name=path.name)

    return load_npy_signal(path, signal_key=signal_key)


def load_npy_signal(path, signal_key="sig_acc_5120"):
    """Load a vibration signal from a .npy or .npz file."""
    payload = np.load(str(path), allow_pickle=True)

    if isinstance(payload, np.lib.npyio.NpzFile):
        try:
            if signal_key in payload:
                sig = np.asarray(payload[signal_key], dtype=np.float32).reshape(-1)
                return sig
            keys = [k for k in payload.files if not str(k).startswith("__")]
            if not keys:
                raise KeyError(f"No valid arrays found in: {path}")
            sig = np.asarray(payload[keys[0]], dtype=np.float32).reshape(-1)
            return sig
        finally:
            payload.close()

    if isinstance(payload, np.ndarray):
        if payload.dtype == object:
            if payload.size == 1:
                item = payload.reshape(-1)[0]
                if isinstance(item, dict):
                    if signal_key in item:
                        return np.asarray(item[signal_key], dtype=np.float32).reshape(-1)
                    keys = [k for k in item if not str(k).startswith("__")]
                    if keys:
                        return np.asarray(item[keys[0]], dtype=np.float32).reshape(-1)
            pieces = [np.asarray(item, dtype=np.float32).reshape(-1) for item in payload.flat]
            if pieces:
                return np.concatenate(pieces)
        return np.asarray(payload, dtype=np.float32).reshape(-1)

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
