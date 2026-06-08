from __future__ import annotations

import argparse
import hashlib
import json
import socket
import struct
import sys
import time
from pathlib import Path
from typing import Any, Dict, Tuple

import numpy as np
import requests
import scipy.io


DEFAULT_RECEIVER_HOST = "127.0.0.1"
DEFAULT_RECEIVER_PORT = 8888
DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
DEFAULT_PYTHON_URL = "http://127.0.0.1:5001"
DEFAULT_DEVICE_CODE = "BEARING-001"
DEFAULT_SAMPLE_NAME = "pipeline_test.mat"


def log(message: str) -> None:
    print(f"[TEST] {message}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_sample_mat(output_path: Path, length: int = 4096, fs: float = 10240.0) -> None:
    t = np.arange(length, dtype=np.float32) / fs
    signal = (
        0.8 * np.sin(2 * np.pi * 50 * t)
        + 0.3 * np.sin(2 * np.pi * 120 * t)
        + 0.05 * np.random.default_rng(42).normal(size=length).astype(np.float32)
    ).astype(np.float32)
    scipy.io.savemat(output_path.as_posix(), {
        "signal": signal,
        "data": signal,
        "vibration": signal,
    })


def encode_header(filename: str, filesize: int, sha256: str) -> bytes:
    payload = {
        "filename": filename,
        "filesize": filesize,
        "sha256": sha256,
    }
    return json.dumps(payload, ensure_ascii=False).encode("utf-8")


def send_to_receiver(file_path: Path, host: str, port: int) -> str:
    file_bytes = file_path.read_bytes()
    header = encode_header(file_path.name, len(file_bytes), sha256_file(file_path))

    with socket.create_connection((host, port), timeout=10) as sock:
        stream = sock.makefile("rwb")
        stream.write(b"CWRU_MAT_V1\n")
        stream.write(struct.pack(">I", len(header)))
        stream.write(header)
        stream.write(file_bytes)
        stream.flush()

        response = stream.readline().decode("utf-8", errors="replace").strip()
        return response


def post_json(url: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    resp = requests.post(url, json=payload, timeout=60)
    resp.raise_for_status()
    return resp.json()


def poll_latest_result(backend_url: str, device_code: str, timeout_seconds: int = 60) -> Dict[str, Any]:
    deadline = time.time() + timeout_seconds
    last_data: Dict[str, Any] = {}
    while time.time() < deadline:
        resp = requests.get(f"{backend_url}/sensor/vibration/diagnosis/latest", params={"deviceCode": device_code}, timeout=15)
        resp.raise_for_status()
        payload = resp.json()
        data = payload.get("data", payload)
        if isinstance(data, dict):
            last_data = data
            if data.get("status") in {"���", "�쳣"} or data.get("diagnosisResult") not in {None, "������������ѽ���", ""}:
                return data
        time.sleep(2)
    return last_data


def main() -> int:
    parser = argparse.ArgumentParser(description="Test the MAT -> receiver -> backend -> python inference pipeline.")
    parser.add_argument("--receiver-host", default=DEFAULT_RECEIVER_HOST)
    parser.add_argument("--receiver-port", type=int, default=DEFAULT_RECEIVER_PORT)
    parser.add_argument("--backend-url", default=DEFAULT_BACKEND_URL)
    parser.add_argument("--python-url", default=DEFAULT_PYTHON_URL)
    parser.add_argument("--device-code", default=DEFAULT_DEVICE_CODE)
    parser.add_argument("--output", default=DEFAULT_SAMPLE_NAME)
    parser.add_argument("--keep-file", action="store_true")
    args = parser.parse_args()

    output_path = Path(args.output).resolve()
    log(f"Creating sample MAT at {output_path}")
    build_sample_mat(output_path)

    try:
        log(f"Checking Python inference service: {args.python_url}/health")
        health_resp = requests.get(f"{args.python_url}/health", timeout=15)
        health_resp.raise_for_status()
        log(f"Python service ready: {health_resp.json()}")

        log(f"Sending MAT to receiver {args.receiver_host}:{args.receiver_port}")
        receiver_resp = send_to_receiver(output_path, args.receiver_host, args.receiver_port)
        log(f"Receiver response: {receiver_resp}")
        if not receiver_resp.startswith("OK"):
            raise RuntimeError(f"Receiver did not accept the file: {receiver_resp}")

        log("Polling backend latest diagnosis")
        latest = poll_latest_result(args.backend_url, args.device_code, timeout_seconds=90)
        log(f"Latest diagnosis: {json.dumps(latest, ensure_ascii=False, indent=2)}")

        result = str(latest.get("diagnosisResult") or latest.get("diagnosisName") or "")
        confidence = latest.get("confidence")
        status = latest.get("status")
        log(f"Pipeline OK -> status={status}, result={result}, confidence={confidence}")
        return 0
    except Exception as exc:
        log(f"Pipeline failed: {exc}")
        return 1
    finally:
        if not args.keep_file and output_path.exists():
            try:
                output_path.unlink()
            except Exception:
                pass


if __name__ == "__main__":
    sys.exit(main())
