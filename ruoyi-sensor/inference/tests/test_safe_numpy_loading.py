import sys
from pathlib import Path

import numpy as np
import pytest

INFERENCE_DIR = Path(__file__).resolve().parents[1]
if str(INFERENCE_DIR) not in sys.path:
    sys.path.insert(0, str(INFERENCE_DIR))

from utils_signal import load_npy_signal


def test_numeric_npy_is_accepted(tmp_path):
    source = tmp_path / "signal.npy"
    np.save(source, np.arange(32, dtype=np.float32))
    result = load_npy_signal(source)
    assert result.dtype == np.float32
    assert result.shape == (32,)


def test_object_npy_is_rejected_without_unpickling(tmp_path):
    source = tmp_path / "object.npy"
    np.save(source, {"sig_acc_5120": np.arange(8)}, allow_pickle=True)
    with pytest.raises(ValueError, match="allow_pickle=False|Object"):
        load_npy_signal(source)


def test_non_finite_and_high_dimensional_arrays_are_rejected(tmp_path):
    non_finite = tmp_path / "nan.npy"
    high_dimensional = tmp_path / "cube.npy"
    np.save(non_finite, np.array([1.0, np.nan], dtype=np.float32))
    np.save(high_dimensional, np.zeros((2, 2, 2), dtype=np.float32))
    with pytest.raises(ValueError, match="NaN|infinite"):
        load_npy_signal(non_finite)
    with pytest.raises(ValueError, match="one or two dimensions"):
        load_npy_signal(high_dimensional)
