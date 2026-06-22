CREATE DATABASE IF NOT EXISTS monitoring;
USE monitoring;

CREATE TABLE IF NOT EXISTS telemetry_metric ("org_code" STRING TAG, "line_code" STRING TAG, "device_code" STRING TAG, "point_code" STRING TAG, "metric_code" STRING TAG, "signal_type" STRING ATTRIBUTE, "unit" STRING ATTRIBUTE, "channel_id" INT32 FIELD, "value" DOUBLE FIELD, "quality" STRING FIELD, "receive_time" TIMESTAMP FIELD, "sequence" INT64 FIELD);
ALTER TABLE telemetry_metric SET PROPERTIES TTL=315360000000;

CREATE TABLE IF NOT EXISTS vibration_frame ("device_code" STRING TAG, "point_code" STRING TAG, "axis" STRING ATTRIBUTE, "unit" STRING ATTRIBUTE, "channel_id" INT32 FIELD, "sample_rate" INT32 FIELD, "sample_count" INT32 FIELD, "waveform" BLOB FIELD, "spectrum" BLOB FIELD, "freq_step" DOUBLE FIELD, "fft_size" INT32 FIELD, "rpm" DOUBLE FIELD, "load" DOUBLE FIELD, "fault_type" STRING FIELD, "fault_size" DOUBLE FIELD, "quality" STRING FIELD, "receive_time" TIMESTAMP FIELD, "sequence" INT64 FIELD);
ALTER TABLE vibration_frame SET PROPERTIES TTL=315360000000;
