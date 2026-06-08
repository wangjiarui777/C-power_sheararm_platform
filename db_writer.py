"""
MySQL database writer for enhanced inference results.

Writes the output of map_result_to_frontend() into the
enhanced_inference_record table in the ry-yue database.
"""
from __future__ import annotations

import json
import logging
import os
from typing import Any, Dict, Optional

import pymysql

logger = logging.getLogger("db_writer")

DB_CONFIG = {
    "host": os.environ.get("DB_HOST", "localhost"),
    "port": int(os.environ.get("DB_PORT", "3306")),
    "user": os.environ.get("DB_USER", "root"),
    "password": os.environ.get("DB_PASSWORD", "admin123"),
    "database": os.environ.get("DB_NAME", "ry-yue"),
    "charset": "utf8mb4",
}


def get_connection() -> pymysql.Connection:
    """Create a new MySQL connection using DB_CONFIG."""
    return pymysql.connect(**DB_CONFIG)  # type: ignore[arg-type]


def save_inference_result(data: Dict[str, Any], conn: Optional[pymysql.Connection] = None) -> int:
    """
    Insert a single inference result into enhanced_inference_record.

    Args:
        data: The full result dict from map_result_to_frontend().
        conn: Optional existing connection. If None, a new connection is created
              and closed automatically.

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

    own_conn = conn is None
    if own_conn:
        conn = get_connection()

    try:
        with conn.cursor() as cursor:
            cursor.execute(sql, params)
            row_id = cursor.lastrowid
        if own_conn:
            conn.commit()
        logger.info("Saved inference result id=%s for %s", row_id, params["source_file"])
        return row_id
    except Exception:
        if own_conn:
            conn.rollback()
        raise
    finally:
        if own_conn and conn:
            conn.close()


def query_history(
    start_time: str,
    end_time: str,
    device_code: Optional[str] = None,
    limit: int = 10000,
) -> list:
    """
    Query historical inference records within a time range.

    Args:
        start_time: Start of the time range (inclusive), format 'YYYY-MM-DD HH:MM:SS'.
        end_time: End of the time range (inclusive), format 'YYYY-MM-DD HH:MM:SS'.
        device_code: Optional device code filter.
        limit: Maximum number of records to return (default 10000).

    Returns:
        List of dicts, each representing one row.
    """
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

    conn = get_connection()
    try:
        with conn.cursor() as cursor:
            cursor.execute(sql, params)
            rows = cursor.fetchall()
            cols = [d[0] for d in cursor.description]
        return [dict(zip(cols, row)) for row in rows]
    finally:
        conn.close()
