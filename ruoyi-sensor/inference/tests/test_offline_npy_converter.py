import importlib.util
from pathlib import Path

import numpy as np
import pytest


TOOL = Path(__file__).parents[2] / "offline-tools" / "convert_legacy_object_npy.py"
SPEC = importlib.util.spec_from_file_location("legacy_converter", TOOL)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_converter_emits_plain_numeric_array_and_manifest_data(tmp_path):
    source = tmp_path / "legacy.npy"
    output = tmp_path / "clean.npy"
    np.save(source, {"sig_acc_5120": np.arange(32, dtype=np.float64)}, allow_pickle=True)
    record = MODULE.convert(source, output)
    result = np.load(output, allow_pickle=False)
    assert result.dtype == np.float32
    assert result.shape == (32,)
    assert len(record["sha256"]) == 64


def test_converter_rejects_nested_object_array(tmp_path):
    source = tmp_path / "legacy.npy"
    output = tmp_path / "clean.npy"
    np.save(source, np.array([{"a": [1]}, {"b": [2]}], dtype=object), allow_pickle=True)
    with pytest.raises(ValueError, match="nested object arrays"):
        MODULE.convert(source, output)
