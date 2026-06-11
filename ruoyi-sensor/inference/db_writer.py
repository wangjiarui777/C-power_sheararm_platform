"""
MySQL database writer for inference results — with connection pooling.

Uses a simple thread-safe connection pool to avoid creating a new TCP
connection for every inference result (saves ~20-50 ms per request).
"""
from __future__ import annotations

import json
import logging
import os
import threading
from queue import Queue, Empty
from typing import Any, Dict, Optional
from contextlib import contextmanager

import pymysql

logger = logging.getLogger("db_writer")

# =============================================================================
# Connection pool
# =============================================================================
DB_CONFIG = {
    "host": os.environ.get("DB_HOST", "localhost"),
    "port": int(os.environ.get("DB_PORT", "3306")),
    "user": os.environ.get("DB_USER", "root"),
    "password": os.environ.get("DB_PASSWORD", "admin123"),
    "database": os.environ.get("DB_NAME", "ry-yue"),
    "charset": "utf8mb4",
    "autocommit": False,
}
POOL_MIN_SIZE = int(os.environ.get("DB_POOL_MIN", "2"))
POOL_MAX_SIZE = int(os.environ.get("DB_POOL_MAX", "8"))

_pool: Queue[pymysql.Connection] = Queue(maxsize=POOL_MAX_SIZE)
_pool_lock = threading.Lock()
_pool_count = 0  # how many connections currently exist (in pool + checked out)


def _create_conn() -> pymysql.Connection:
    """Create a new MySQL connection."""
    conn = pymysql.connect(**DB_CONFIG)  # type: ignore[arg-type]
    conn.ping(reconnect=True)
    return conn


def _fill_pool() -> None:
    """Fill pool to minimum size (called under _pool_lock)."""
    global _pool_count
    # 确保至少有 POOL_MIN_SIZE 个连接存在（含池内 + 已检出）
    needed = max(0, POOL_MIN_SIZE - _pool_count)
    for _ in range(needed):
        if _pool_count >= POOL_MAX_SIZE:
            break
        try:
            conn = _create_conn()
            _pool.put_nowait(conn)
            _pool_count += 1
        except Exception:
            logger.exception("Failed to create DB connection for pool")
            break


# Pre-fill pool on import
with _pool_lock:
    _fill_pool()


@contextmanager
def get_connection():
    """Context manager that yields a pymysql.Connection from the pool.

    Creates a new connection if pool is empty but under max size.
    Blocks (with timeout) if at max size.
    """
    global _pool_count
    conn = None
    try:
        conn = _pool.get_nowait()
    except Empty:
        with _pool_lock:
            if _pool_count < POOL_MAX_SIZE:
                try:
                    conn = _create_conn()
                    _pool_count += 1
                except Exception:
                    logger.exception("Failed to create DB connection")
                    raise
            else:
                # Pool exhausted — block until one is returned
                conn = _pool.get(timeout=5.0)

    # Verify connection is still alive
    try:
        conn.ping(reconnect=True)
    except Exception:
        try:
            conn.close()
        except Exception:
            pass
        conn = _create_conn()

    try:
        yield conn
    finally:
        try:
            _pool.put_nowait(conn)
        except Exception:
            # Pool full (shouldn't happen), close the connection
            try:
                conn.close()
            except Exception:
                pass
            with _pool_lock:
                _pool_count -= 1


def _close_pool() -> None:
    """Close all connections in the pool (called on shutdown)."""
    global _pool_count
    while True:
        try:
            conn = _pool.get_nowait()
            try:
                conn.close()
            except Exception:
                pass
            _pool_count -= 1
        except Empty:
            break
    logger.info("DB connection pool closed, %d connections freed", _pool_count)


# =============================================================================
# CRUD operations
# =============================================================================

def _execute_save(conn: pymysql.Connection, sql: str, params: Dict[str, Any]) -> int:
    """Execute the INSERT and return lastrowid. Rolls back on failure."""
    try:
        with conn.cursor() as cursor:
            cursor.execute(sql, params)
            row_id = cursor.lastrowid
        conn.commit()
        logger.debug("Saved inference result id=%s for %s", row_id, params["source_file"])
        return row_id
    except Exception:
        conn.rollback()
        raise


def save_inference_result(data: Dict[str, Any], conn: Optional[pymysql.Connection] = None) -> int:
    """Insert a single inference result into enhanced_inference_record.

    Args:
        data: The full result dict from map_result_to_frontend().
        conn: Optional existing connection. If None, a connection is obtained
              from the pool and returned automatically.

    Returns:
        The auto-generated id of the inserted row.

    Raises:
        pymysql.MySQLError: On database write failure.
    """
    sql = """
        INSERT INTO enhanced_inference_record (
            batch_id, device_code, source_file, analysis_mode, sample_rate,
            diagnosis_result, closed_prediction, confidence, health_index,
            risk_level, alarm_level, diagnosis_detail, decision_reason,
            unknown_ratio, segment_consistency, mean_mahalanobis, mean_entropy,
            rms, peak,
            top_probabilities, evidence,
            wave_json, spectrum_json,
            sample_time
        ) VALUES (
            %(batch_id)s, %(device_code)s, %(source_file)s, %(analysis_mode)s, %(sample_rate)s,
            %(diagnosis_result)s, %(closed_prediction)s, %(confidence)s, %(health_index)s,
            %(risk_level)s, %(alarm_level)s, %(diagnosis_detail)s, %(decision_reason)s,
            %(unknown_ratio)s, %(segment_consistency)s, %(mean_mahalanobis)s, %(mean_entropy)s,
            %(rms)s, %(peak)s,
            %(top_probabilities)s, %(evidence)s,
            %(wave_json)s, %(spectrum_json)s,
            %(sample_time)s
        )
    """

    params = {
        "batch_id": data.get("batchId"),
        "device_code": data.get("deviceCode"),
        "source_file": data.get("sourceName") or data.get("source_name"),
        "analysis_mode": data.get("analysis_mode"),
        "sample_rate": data.get("sampleRate") or data.get("sample_rate"),

        "diagnosis_result": data.get("diagnosisResult") or data.get("label"),
        "closed_prediction": data.get("closedPrediction"),
        "confidence": data.get("confidence"),
        "health_index": data.get("healthIndex"),
        "risk_level": data.get("riskLevel"),
        "alarm_level": data.get("alarmLevel"),
        "diagnosis_detail": data.get("diagnosisDetail") or data.get("diagnosis_detail"),
        "decision_reason": data.get("decision_reason"),

        "unknown_ratio": data.get("unknownRatio"),
        "segment_consistency": data.get("segmentConsistency"),
        "mean_mahalanobis": data.get("meanMahalanobis"),
        "mean_entropy": data.get("meanEntropy"),

        "rms": data.get("rms"),
        "peak": data.get("peak"),

        "top_probabilities": json.dumps(data.get("topProbabilities", []), ensure_ascii=False),
        "evidence": json.dumps(data.get("evidence", []), ensure_ascii=False),

        "wave_json": json.dumps(data.get("waveform", []), ensure_ascii=False),
        "spectrum_json": json.dumps(data.get("spectrum", []), ensure_ascii=False),

        "sample_time": data.get("sampleTime"),
    }

    if conn is not None:
        return _execute_save(conn, sql, params)

    with get_connection() as pooled_conn:
        return _execute_save(pooled_conn, sql, params)


def query_history(
    start_time: str,
    end_time: str,
    device_code: Optional[str] = None,
    limit: int = 10000,
) -> list:
    """Query historical inference records within a time range."""
    sql = """
        SELECT
            id, batch_id, device_code, source_file, analysis_mode, sample_rate,
            diagnosis_result, closed_prediction, confidence, health_index,
            risk_level, alarm_level, diagnosis_detail, decision_reason,
            unknown_ratio, segment_consistency, mean_mahalanobis, mean_entropy,
            rms, peak,
            sample_time, create_time
        FROM enhanced_inference_record
        WHERE create_time >= %(start_time)s AND create_time <= %(end_time)s
    """
    params: Dict[str, Any] = {"start_time": start_time, "end_time": end_time}

    if device_code:
        sql += " AND device_code = %(device_code)s\n"
        params["device_code"] = device_code
    else:
        sql += "\n"

    sql += " ORDER BY create_time DESC LIMIT %(limit)s"
    params["limit"] = limit

    with get_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(sql, params)
            rows = cursor.fetchall()
            cols = [d[0] for d in cursor.description]
        return [dict(zip(cols, row)) for row in rows]
