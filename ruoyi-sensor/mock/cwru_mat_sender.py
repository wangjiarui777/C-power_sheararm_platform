#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate random CWRU-style .mat files and send them to the receiver.

Protocol expected by the Spring MAT receiver:
1. Send the ASCII protocol line `CWRU_MAT_V2\n`
2. Send a 4-byte big-endian integer header length
3. Send UTF-8 JSON header bytes
4. Wait for `READY\n`
5. Send raw file bytes

Default behavior:
- generate a fresh random `.mat` file every 10 seconds
- randomize the output filename
- use a reference `.mat` file to copy the field structure

Examples:
  python ruoyi-sensor/mock/cwru_mat_sender.py --file ruoyi-sensor/inference/get/got/CH1_20260515_085738_sr7497_rpm3000_UN_7500.mat
  python ruoyi-sensor/mock/cwru_mat_sender.py --file ruoyi-sensor/inference/get/got --once
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import socket
import struct
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from scipy.io import loadmat, savemat

MAGIC = b"CWRU_MAT_V2\n"
DEFAULT_PORT = 8888
DEFAULT_INTERVAL = 10
CHUNK_SIZE = 64 * 1024


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_header(path: Path, args: argparse.Namespace) -> dict:
    stat = path.stat()
    return {
        "filename": path.name,
        "filesize": stat.st_size,
        "sha256": sha256_file(path),
        "deviceCode": args.device_code,
        "pointCode": args.point_code,
        "channelId": args.channel_id,
        "acquisitionTime": datetime.now(timezone.utc).isoformat(),
    }


def load_reference(path: Path) -> dict:
    try:
        return loadmat(path.as_posix())
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError(f"Failed to parse reference MAT file: {exc}") from exc


def describe_mat(path: Path) -> None:
    data = load_reference(path)
    print(f"[mat] {path.name} field summary:")
    for key, value in data.items():
        if key.startswith("__"):
            continue
        shape = getattr(value, "shape", None)
        dtype = getattr(value, "dtype", None)
        preview = ""
        try:
            flat = np.asarray(value).ravel()
            if flat.size:
                items = ", ".join(str(x) for x in flat[:5])
                preview = f" preview=[{items}]"
        except Exception:  # noqa: BLE001
            pass
        print(f"  - {key}: shape={shape}, dtype={dtype}{preview}")


def _scalar(value, default):
    if value is None:
        return default
    arr = np.asarray(value).reshape(-1)
    if arr.size == 0:
        return default
    item = arr[0]
    try:
        return item.item()
    except Exception:
        return item


def _infer_shape(ref: dict) -> int:
    if "raw" in ref:
        raw = np.asarray(ref["raw"])
        return int(raw.shape[0])
    if "DE_time" in ref:
        de = np.asarray(ref["DE_time"])
        return int(de.shape[0])
    raise RuntimeError("Reference MAT file does not contain `raw` or `DE_time`.")


def generate_random_mat(reference_path: Path, output_dir: Path, sample_index: int = 0) -> Path:
    ref = load_reference(reference_path)
    n = _infer_shape(ref)

    sr = int(_scalar(ref.get("sr_actual", ref.get("sr")), 7500))
    rpm = int(_scalar(ref.get("rpm"), 3000))
    fault_type = str(_scalar(ref.get("fault_type"), "UN"))
    channel_name = str(_scalar(ref.get("channel_name"), "CH1"))
    load = int(_scalar(ref.get("load"), 0))
    fault_size = float(_scalar(ref.get("fault_size"), 0.0))
    zero_noise_rms_g = float(_scalar(ref.get("zero_noise_rms_g"), 0.0055))
    cal_slope = float(_scalar(ref.get("cal_slope"), -6.37388e-07))
    cal_intercept = float(_scalar(ref.get("cal_intercept"), -0.078909))
    voltage_sensitivity = float(_scalar(ref.get("voltage_sensitivity"), 0.2))
    voltage_zero = float(_scalar(ref.get("voltage_zero"), 3.0))
    raw_to_sensor_count_scale = float(_scalar(ref.get("raw_to_sensor_count_scale"), 0.00367083))
    zero_raw_median = float(_scalar(ref.get("zero_raw_median"), 4284727.0))

    sample_rate = float(sr)
    t = np.arange(n, dtype=np.float64) / sample_rate

    # Every third file gets a high-disturbance waveform to simulate sudden faults.
    burst_mode = (sample_index + 1) % 3 == 0

    base = -14.4175
    if burst_mode:
        amp1 = random.uniform(1.0, 2.2)
        amp2 = random.uniform(0.25, 0.8)
        amp3 = random.uniform(0.08, 0.25)
        f1 = random.uniform(18.0, 36.0)
        f2 = random.uniform(2.5, 9.0)
        f3 = random.uniform(140.0, 320.0)
        burst_center = random.uniform(0.35, 0.75)
        burst_width = random.uniform(0.04, 0.12)
        burst_amp = random.uniform(2.0, 5.5)
    else:
        amp1 = random.uniform(0.18, 0.35)
        amp2 = random.uniform(0.02, 0.08)
        amp3 = random.uniform(0.01, 0.04)
        f1 = random.uniform(28.0, 42.0)
        f2 = random.uniform(2.0, 8.0)
        f3 = random.uniform(120.0, 260.0)
        burst_center = 0.5
        burst_width = 0.18
        burst_amp = 0.0

    phase1 = random.uniform(0.0, 2.0 * math.pi)
    phase2 = random.uniform(0.0, 2.0 * math.pi)
    phase3 = random.uniform(0.0, 2.0 * math.pi)

    envelope = 1.0 + 0.08 * np.sin(2.0 * math.pi * f2 * t + phase2)
    carrier = (
        amp1 * np.sin(2.0 * math.pi * f1 * t + phase1)
        + amp2 * np.sin(2.0 * math.pi * f3 * t + phase3)
        + amp3 * np.sin(2.0 * math.pi * (f1 * 2.0) * t + phase1 / 3.0)
    )
    if burst_mode:
        burst_window = np.exp(-0.5 * ((t / t[-1] - burst_center) / burst_width) ** 2)
        burst_wave = burst_amp * burst_window * (
            np.sin(2.0 * math.pi * random.uniform(55.0, 120.0) * t + phase1)
            + 0.6 * np.sin(2.0 * math.pi * random.uniform(180.0, 420.0) * t + phase2)
        )
        impulse_count = random.randint(4, 10)
        impulse_positions = random.sample(range(max(50, n // 8), min(n - 50, n - n // 8)), impulse_count)
        impulses = np.zeros(n, dtype=np.float64)
        for pos in impulse_positions:
            width = random.randint(6, 28)
            height = random.uniform(0.8, 2.8)
            start = max(0, pos - width)
            end = min(n, pos + width)
            window = np.hanning(end - start) if end - start > 1 else np.ones(1, dtype=np.float64)
            impulses[start:end] += height * window
        noise = np.random.normal(0.0, 0.06, size=n)
        de_time = base + envelope * carrier + burst_wave + impulses + noise
    else:
        noise = np.random.normal(0.0, 0.015, size=n)
        de_time = base + envelope * carrier + noise

    raw = np.round(zero_raw_median + (de_time - base) / max(abs(cal_slope), 1e-12)).astype(np.int32)
    voltage = de_time * (voltage_sensitivity / 10.0)
    sample_time = t.reshape(-1, 1)

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    suffix = random.randint(1000, 9999)
    stem = f"{channel_name}_{stamp}_sr{sr}_rpm{rpm}_{fault_type}_{suffix}"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"{stem}.mat"

    mat_payload = {
        "DE_time": de_time.reshape(-1, 1),
        "raw": raw.reshape(-1, 1),
        "voltage": voltage.reshape(-1, 1),
        "sample_time": sample_time,
        "sr": np.array([[sr]], dtype=np.uint16),
        "sr_actual": np.array([[float(sr)]], dtype=np.float64),
        "drate_name": np.array([f"DRATE_{sr}"], dtype=object),
        "drate_code": np.array([[208]], dtype=np.uint8),
        "rpm": np.array([[rpm]], dtype=np.uint16),
        "load": np.array([[load]], dtype=np.uint8),
        "fault_type": np.array([fault_type], dtype=object),
        "fault_size": np.array([[fault_size]], dtype=np.float64),
        "channel_name": np.array([channel_name], dtype=object),
        "adc_channel": np.array([[1]], dtype=np.uint8),
        "segment_seconds": np.array([[float(n) / sample_rate]], dtype=np.float64),
        "cal_slope": np.array([[cal_slope]], dtype=np.float64),
        "cal_intercept": np.array([[cal_intercept]], dtype=np.float64),
        "voltage_zero": np.array([[voltage_zero]], dtype=np.float64),
        "voltage_sensitivity": np.array([[voltage_sensitivity]], dtype=np.float64),
        "zero_calibration_used": np.array([[1]], dtype=np.uint8),
        "created_time": np.array([datetime.now().strftime("%Y-%m-%d %H:%M:%S")], dtype=object),
        "zero_raw_median": np.array([[zero_raw_median]], dtype=np.float64),
        "raw_to_sensor_count_scale": np.array([[raw_to_sensor_count_scale]], dtype=np.float64),
        "g_per_raw_near_zero": np.array([[abs(cal_slope)]], dtype=np.float64),
        "zero_noise_rms_g": np.array([[zero_noise_rms_g]], dtype=np.float64),
    }
    savemat(output_path, mat_payload, do_compression=True)
    return output_path


def send_one(host: str, port: int, path: Path, args: argparse.Namespace) -> str:
    header = build_header(path, args)
    header_bytes = json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    with socket.create_connection((host, port), timeout=10) as sock:
        sock.settimeout(30)
        sock.sendall(MAGIC)
        sock.sendall(struct.pack(">I", len(header_bytes)))
        sock.sendall(header_bytes)

        ready = b""
        while not ready.endswith(b"\n"):
            part = sock.recv(4096)
            if not part:
                raise RuntimeError("receiver closed before READY")
            ready += part
        if ready.decode("utf-8", errors="replace").strip() != "READY":
            raise RuntimeError(f"receiver rejected header: {ready!r}")

        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(CHUNK_SIZE), b""):
                sock.sendall(chunk)

        response = b""
        while not response.endswith(b"\n"):
            part = sock.recv(4096)
            if not part:
                break
            response += part

    return response.decode("utf-8", errors="replace").strip()


def iter_files(target: Path):
    if target.is_file():
        yield target
        return
    if target.is_dir():
        for item in sorted(target.glob("*.mat")):
            if item.is_file():
                yield item
        return
    raise FileNotFoundError(f"Path not found: {target}")


def parse_args() -> argparse.Namespace:
    default_output_dir = Path(__file__).resolve().parents[1] / "inference" / "get" / "got"
    parser = argparse.ArgumentParser(description="Generate random CWRU-style .mat files and send them.")
    parser.add_argument("--host", default="127.0.0.1", help="Receiver host, default: 127.0.0.1")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"Receiver port, default: {DEFAULT_PORT}")
    parser.add_argument("--file", required=True, help="Reference .mat file or a directory containing .mat files")
    parser.add_argument("--interval", type=int, default=DEFAULT_INTERVAL, help=f"Seconds between sends, default: {DEFAULT_INTERVAL}")
    parser.add_argument("--once", action="store_true", help="Generate and send one file only")
    parser.add_argument("--output-dir", default=str(default_output_dir), help="Directory for generated .mat files")
    parser.add_argument("--keep-generated", action="store_true", help="Keep generated files instead of deleting them after send")
    parser.add_argument("--device-code", default="DEV-001", help="MAT deviceCode")
    parser.add_argument("--point-code", default="CH1", help="MAT pointCode")
    parser.add_argument("--channel-id", type=int, default=1, help="Physical MAT channel number")
    return parser.parse_args()


def resolve_reference_files(target: Path) -> list[Path]:
    candidates = [target]
    if not target.exists():
        sensor_root = Path(__file__).resolve().parents[1]
        candidates.extend([
            Path("got").resolve(),
            Path("get") / "got",
            sensor_root / "inference" / "get" / "got",
        ])
    for candidate in candidates:
        try:
            files = list(iter_files(candidate.resolve()))
            if files:
                return files
        except FileNotFoundError:
            continue
    return []


def main() -> int:
    args = parse_args()
    target = Path(args.file).expanduser().resolve()

    try:
        files = resolve_reference_files(target)
        if not files:
            print(f"No .mat files found in {target}", file=sys.stderr)
            return 2

        for path in files:
            describe_mat(path)

        output_dir = Path(args.output_dir).expanduser().resolve()
        while True:
            for idx, ref_path in enumerate(files):
                generated_path = generate_random_mat(ref_path, output_dir, sample_index=idx)
                print(f"[gen] {generated_path.name}")
                response = send_one(args.host, args.port, generated_path, args)
                print(f"[resp] {response or '<empty>'}")

                if not args.keep_generated:
                    generated_path.unlink(missing_ok=True)

                if args.once:
                    return 0

                time.sleep(max(1, args.interval))

        return 0
    except KeyboardInterrupt:
        print("[stop] interrupted by user")
        return 130
    except Exception as exc:  # noqa: BLE001
        print(f"[error] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
