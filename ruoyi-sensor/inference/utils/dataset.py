"""
FileInferenceDataset — sliding-window dataset for .mat / .npy vibration files.

Matches the dataset used by 04_diagnose_unlabeled_target_closed_v8-test.py.
Each item is a single tensor of shape (1, window_size) with optional z-score
normalisation applied per-window.
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

import numpy as np
import scipy.io
import torch
from torch.utils.data import Dataset


def _extract_signal_from_mat(file_path: Path, signal_key: str) -> np.ndarray:
    """Load a 1-D float64 signal from a .mat file, preferring `signal_key`."""
    data = scipy.io.loadmat(str(file_path), squeeze_me=True, struct_as_record=False)

    # try the requested key first
    if signal_key and signal_key in data:
        arr = np.asarray(data[signal_key]).squeeze()
        if arr.ndim == 1 and arr.size > 0:
            return arr.astype(np.float64)

    # common fallback keys
    for key in ("DE_time", "FE_time", "BA_time", "sig_acc_51200",
                "signal", "sig", "data", "x", "X", "acc", "vibration"):
        if key in data:
            arr = np.asarray(data[key]).squeeze()
            if arr.ndim == 1 and arr.size > 0 and np.issubdtype(arr.dtype, np.number):
                return arr.astype(np.float64)

    # longest numeric 1-D array
    best = None
    best_len = 0
    for k, v in data.items():
        if str(k).startswith("__"):
            continue
        arr = np.asarray(v).squeeze()
        if arr.ndim == 1 and arr.size > best_len and np.issubdtype(arr.dtype, np.number):
            best = arr
            best_len = arr.size

    if best is not None:
        return best.astype(np.float64)

    raise RuntimeError(f"No usable 1-D numeric signal found in {file_path}")


def _load_npy_signal(file_path: Path, signal_key: str) -> np.ndarray:
    """Load a bounded numeric array without enabling Pickle deserialization."""
    from utils_signal import _validate_numpy_container, validate_numeric_array

    _validate_numpy_container(file_path)
    payload = np.load(str(file_path), allow_pickle=False)

    if isinstance(payload, np.lib.npyio.NpzFile):
        try:
            if signal_key in payload:
                return validate_numeric_array(payload[signal_key], f"{file_path.name}:{signal_key}").astype(np.float64)
            keys = [k for k in payload.files if not str(k).startswith("__")]
            if keys:
                return validate_numeric_array(payload[keys[0]], f"{file_path.name}:{keys[0]}").astype(np.float64)
        finally:
            payload.close()
        raise RuntimeError(f"No usable arrays in {file_path}")

    if isinstance(payload, np.ndarray):
        return validate_numeric_array(payload, file_path.name).astype(np.float64)

    raise TypeError(f"Unsupported npy format: {type(payload)}")


def load_signal_for_dataset(file_path: Path, signal_key: str = "DE_time") -> np.ndarray:
    """Load signal from .mat or .npy, returning float64 1-D array."""
    suffix = file_path.suffix.lower()
    if suffix == ".mat":
        sig = _extract_signal_from_mat(file_path, signal_key)
    elif suffix == ".npy":
        sig = _load_npy_signal(file_path, signal_key)
    else:
        raise ValueError(f"Unsupported file type: {suffix}")
    return np.nan_to_num(sig, nan=0.0, posinf=0.0, neginf=0.0)


class FileInferenceDataset(Dataset):
    """
    Sliding-window dataset for a single vibration file.

    Each __getitem__ returns a torch.Tensor of shape (1, window_size).

    Parameters
    ----------
    path : Path
        Path to .mat or .npy file.
    window_size : int
        Window length in samples.
    stride : int
        Stride between consecutive windows in samples.
    max_windows : int or None
        If set, only use the first `max_windows` windows.
    signal_key : str
        Key / variable name inside the file.
    normalize : str
        "zscore" → per-window z-score; "none" → raw.
    cache_signals : bool
        If True, keep the raw signal in memory (saves re-reads but uses RAM).
    """

    def __init__(
        self,
        path: Path,
        window_size: int,
        stride: int,
        max_windows: Optional[int] = None,
        signal_key: str = "DE_time",
        normalize: str = "zscore",
        cache_signals: bool = False,
    ) -> None:
        super().__init__()
        self.path = Path(path)
        self.window_size = int(window_size)
        self.stride = int(stride)
        self.max_windows = max_windows
        self.normalize = str(normalize).lower()
        self.cache_signals = bool(cache_signals)

        # load raw signal
        raw = load_signal_for_dataset(self.path, signal_key=signal_key)
        if self.cache_signals:
            self._cached_signal = raw
        else:
            self._cached_signal = None
            self._path_for_reload = self.path
            self._signal_key_for_reload = signal_key

        self._signal = raw
        self._n = int(raw.size)

        # number of windows
        if self._n < self.window_size:
            self._num_windows = 0
        else:
            self._num_windows = (self._n - self.window_size) // self.stride + 1

        if max_windows is not None and max_windows > 0:
            self._num_windows = min(self._num_windows, int(max_windows))

    def _get_signal(self) -> np.ndarray:
        if self._cached_signal is not None:
            return self._cached_signal
        return load_signal_for_dataset(self._path_for_reload, signal_key=self._signal_key_for_reload)

    def __len__(self) -> int:
        return self._num_windows

    def __getitem__(self, index: int) -> torch.Tensor:
        start = index * self.stride
        chunk = self._signal[start:start + self.window_size].copy()

        # z-score per window (matches training preprocessing)
        if self.normalize == "zscore":
            mu = chunk.mean()
            sigma = chunk.std()
            if sigma > 1e-8:
                chunk = (chunk - mu) / sigma
            else:
                chunk = chunk - mu

        return torch.from_numpy(chunk).float().unsqueeze(0)
