#!/usr/bin/env python3
"""One-time legacy NPY/NPZ converter for an isolated, network-disabled host.

This tool intentionally enables Pickle and therefore MUST NOT be copied into an
online inference image. It requires an explicit acknowledgement flag and writes
only finite, bounded, plain float32 arrays plus a SHA-256 manifest.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

import numpy as np


MAX_INPUT_BYTES = 256 * 1024 * 1024
MAX_ELEMENTS = 10_000_000
ACKNOWLEDGEMENT = "I_UNDERSTAND_PICKLE_IS_UNSAFE"


def _safe_local_path(value: str, must_exist: bool) -> Path:
    if value.startswith(("\\\\", "//")):
        raise ValueError("network paths are forbidden")
    path = Path(value).expanduser().resolve()
    if must_exist and not path.is_file():
        raise ValueError("input must be a regular local file")
    return path


def _plain_numeric(value, label: str) -> np.ndarray:
    if isinstance(value, dict):
        candidates = [(str(key), item) for key, item in value.items()]
        numeric = []
        for key, item in candidates:
            try:
                array = np.asarray(item)
                if np.issubdtype(array.dtype, np.number) and not array.dtype.hasobject:
                    numeric.append((key, array))
            except Exception:
                continue
        if not numeric:
            raise ValueError(f"{label}: object payload contains no numeric array")
        _, value = max(numeric, key=lambda item: item[1].size)
    array = np.asarray(value)
    if array.dtype.hasobject or array.dtype.fields is not None:
        if array.size != 1:
            raise ValueError(f"{label}: nested object arrays are not supported")
        return _plain_numeric(array.reshape(-1)[0], label)
    if not np.issubdtype(array.dtype, np.number) or np.issubdtype(array.dtype, np.complexfloating):
        raise ValueError(f"{label}: only real numeric arrays may be exported")
    if array.ndim == 0 or array.ndim > 2 or array.size == 0 or array.size > MAX_ELEMENTS:
        raise ValueError(f"{label}: unsafe shape or element count")
    result = np.asarray(array, dtype=np.float32)
    if not np.isfinite(result).all():
        raise ValueError(f"{label}: NaN or infinite values are forbidden")
    return result


def convert(source: Path, destination: Path) -> dict:
    size = source.stat().st_size
    if size <= 0 or size > MAX_INPUT_BYTES:
        raise ValueError("input file size is outside the offline conversion limit")
    payload = np.load(source, allow_pickle=True)
    arrays = {}
    try:
        if isinstance(payload, np.lib.npyio.NpzFile):
            for key in payload.files:
                arrays[key] = _plain_numeric(payload[key], key)
        else:
            arrays["signal"] = _plain_numeric(payload, source.name)
    finally:
        if isinstance(payload, np.lib.npyio.NpzFile):
            payload.close()

    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.suffix.lower() == ".npy":
        if len(arrays) != 1:
            raise ValueError("NPY output requires exactly one extracted numeric array")
        np.save(destination, next(iter(arrays.values())), allow_pickle=False)
    elif destination.suffix.lower() == ".npz":
        np.savez_compressed(destination, **arrays)
    else:
        raise ValueError("destination extension must be .npy or .npz")

    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    return {
        "source_name": source.name,
        "output_name": destination.name,
        "sha256": digest,
        "size": destination.stat().st_size,
        "arrays": {key: {"shape": list(value.shape), "dtype": str(value.dtype)} for key, value in arrays.items()},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("destination")
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--acknowledge", required=True)
    args = parser.parse_args()
    if args.acknowledge != ACKNOWLEDGEMENT:
        parser.error(f"--acknowledge must equal {ACKNOWLEDGEMENT}")
    if os.environ.get("LEGACY_NPY_OFFLINE_MODE") != "1":
        parser.error("LEGACY_NPY_OFFLINE_MODE=1 is required")

    source = _safe_local_path(args.source, True)
    destination = _safe_local_path(args.destination, False)
    manifest = _safe_local_path(args.manifest, False)
    if source == destination or destination == manifest:
        parser.error("source, destination and manifest must be different files")
    record = convert(source, destination)
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(record, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
