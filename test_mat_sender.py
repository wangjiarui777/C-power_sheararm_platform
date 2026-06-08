"""
Test script: Randomly pick a .mat file from target_unlabeled/ and send it
to CwruMatReceiver via TCP (protocol CWRU_MAT_V1). Repeats every 10 seconds.

Reference: mock/cwru_mat_sender.py
"""

import hashlib
import json
import random
import socket
import struct
import sys
import time
from pathlib import Path

# =============================================================================
# Configuration
# =============================================================================

MAT_DIR = Path(r"C:\Users\123\Desktop\BiShe\RuoYi-Vue-master\target_unlabeled")
HOST = "127.0.0.1"
PORT = 8889

MAGIC = b"CWRU_MAT_V1\n"
CHUNK_SIZE = 64 * 1024
SEND_INTERVAL = 10

# =============================================================================
# Protocol helpers (matching mock/cwru_mat_sender.py)
# =============================================================================


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_header(path: Path) -> dict:
    stat = path.stat()
    return {
        "filename": path.name,
        "filesize": stat.st_size,
        "sha256": sha256_file(path),
    }


def send_one(host: str, port: int, path: Path) -> str:
    header = build_header(path)
    header_bytes = json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    with socket.create_connection((host, port), timeout=10) as sock:
        sock.settimeout(30)
        sock.sendall(MAGIC)
        sock.sendall(struct.pack(">I", len(header_bytes)))
        sock.sendall(header_bytes)

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


# =============================================================================
# Main loop
# =============================================================================


def main() -> int:
    mat_files = sorted(MAT_DIR.glob("*.mat"))
    if not mat_files:
        print(f"[FATAL] No .mat files found in: {MAT_DIR}")
        return 1

    print(f"[INFO] Found {len(mat_files)} .mat file(s) in target_unlabeled/")
    print(f"[INFO] Target: {HOST}:{PORT}")
    print(f"[INFO] Sending every {SEND_INTERVAL} seconds. Press Ctrl+C to stop.\n")

    count = 0
    try:
        while True:
            count += 1
            chosen = random.choice(mat_files)
            print(f"[#{count}] {time.strftime('%H:%M:%S')}  Sending: {chosen.name}")
            try:
                response = send_one(HOST, PORT, chosen)
                print(f"  -> Server: {response or '<empty>'}")
            except ConnectionRefusedError:
                print("  -> ERROR: Connection refused. Is CwruMatReceiver running?")
            except socket.timeout:
                print("  -> ERROR: Connection timed out.")
            except Exception as exc:
                print(f"  -> ERROR: {exc}")
            print()
            time.sleep(SEND_INTERVAL)

    except KeyboardInterrupt:
        print(f"\n[DONE] Sent {count} file(s). Exiting.")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
