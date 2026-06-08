from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

import numpy as np
from scipy.io import loadmat


def describe_value(value: Any) -> str:
    if isinstance(value, np.ndarray):
        return f"ndarray shape={value.shape} dtype={value.dtype}"
    if isinstance(value, np.generic):
        return f"numpy scalar type={type(value).__name__} value={value.item()}"
    if isinstance(value, (str, bytes)):
        return f"{type(value).__name__} len={len(value)}"
    return f"{type(value).__name__} value={value}"


def inspect_mat_file(mat_path: Path) -> None:
    if not mat_path.exists():
        raise FileNotFoundError(f"File not found: {mat_path}")

    data = loadmat(mat_path, squeeze_me=False, struct_as_record=False)
    keys = [k for k in data.keys() if not k.startswith("__")]

    print(f"File: {mat_path}")
    print(f"Fields found: {len(keys)}")

    if not keys:
        print("No payload fields found.")
        return

    for key in keys:
        value = data[key]
        print(f"- {key}: {describe_value(value)}")

        if isinstance(value, np.ndarray):
            try:
                arr = np.asarray(value)
                if arr.dtype == object:
                    print(f"  object array elements: {arr.size}")
                else:
                    flat = arr.reshape(-1)
                    preview = flat[:5].tolist()
                    print(f"  min={np.min(arr) if arr.size else 'n/a'} max={np.max(arr) if arr.size else 'n/a'} preview={preview}")
            except Exception as exc:
                print(f"  preview unavailable: {exc}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Inspect MATLAB .mat file fields and dimensions")
    parser.add_argument("mat_file", type=str, help="Path to .mat file")
    args = parser.parse_args()
    inspect_mat_file(Path(args.mat_file).expanduser().resolve())


if __name__ == "__main__":
    main()
