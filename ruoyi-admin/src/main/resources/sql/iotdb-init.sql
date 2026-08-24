CREATE DATABASE IF NOT EXISTS monitoring;
USE monitoring;

CREATE TABLE IF NOT EXISTS telemetry_metric ("org_code" STRING TAG, "line_code" STRING TAG, "device_code" STRING TAG, "point_code" STRING TAG, "metric_code" STRING TAG, "signal_type" STRING ATTRIBUTE, "unit" STRING ATTRIBUTE, "channel_id" INT32 FIELD, "value" DOUBLE FIELD, "quality" STRING FIELD, "receive_time" TIMESTAMP FIELD, "sequence" INT64 FIELD);
ALTER TABLE telemetry_metric SET PROPERTIES TTL=315360000000;

CREATE TABLE IF NOT EXISTS vibration_frame ("device_code" STRING TAG, "point_code" STRING TAG, "axis" STRING ATTRIBUTE, "unit" STRING ATTRIBUTE, "channel_id" INT32 FIELD, "sample_rate" INT32 FIELD, "sample_count" INT32 FIELD, "waveform" BLOB FIELD, "spectrum" BLOB FIELD, "freq_step" DOUBLE FIELD, "fft_size" INT32 FIELD, "rpm" DOUBLE FIELD, "load" DOUBLE FIELD, "fault_type" STRING FIELD, "fault_size" DOUBLE FIELD, "quality" STRING FIELD, "receive_time" TIMESTAMP FIELD, "sequence" INT64 FIELD);
ALTER TABLE vibration_frame SET PROPERTIES TTL=315360000000;

CREATE TABLE IF NOT EXISTS diagnosis_result ("record_id" STRING TAG, "device_code" STRING TAG, "point_key" STRING TAG, "analysis_mode" STRING TAG, "model_version" STRING TAG, "batch_id" INT64 FIELD, "task_id" INT64 FIELD, "source_type" STRING FIELD, "point_id" INT64 FIELD, "channel_id" INT32 FIELD, "model_release_id" INT64 FIELD, "source_file" STRING FIELD, "sample_rate" DOUBLE FIELD, "diagnosis_result" STRING FIELD, "closed_prediction" STRING FIELD, "confidence" DOUBLE FIELD, "health_index" INT32 FIELD, "risk_level" STRING FIELD, "alarm_level" STRING FIELD, "diagnosis_detail" STRING FIELD, "decision_reason" STRING FIELD, "unknown_ratio" DOUBLE FIELD, "segment_consistency" DOUBLE FIELD, "mean_mahalanobis" DOUBLE FIELD, "mean_entropy" DOUBLE FIELD, "rms" DOUBLE FIELD, "peak" DOUBLE FIELD, "top_probabilities" STRING FIELD, "evidence" STRING FIELD, "timeseries_ref" STRING FIELD, "sample_time" TIMESTAMP FIELD, "update_time" TIMESTAMP FIELD, "remark" STRING FIELD);
ALTER TABLE diagnosis_result SET PROPERTIES TTL=315360000000;
