-- Additive, idempotent migration for asynchronous diagnosis tasks.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `sensor_inference_task` (
  `id` bigint NOT NULL COMMENT 'Snowflake task ID',
  `request_id` varchar(64) NOT NULL COMMENT 'Cross-service request ID',
  `idempotency_key` varchar(128) DEFAULT NULL COMMENT 'Client idempotency key',
  `device_code` varchar(64) NOT NULL,
  `point_id` bigint DEFAULT NULL,
  `channel_id` int DEFAULT NULL,
  `model_type` varchar(32) NOT NULL,
  `requested_model_version` varchar(128) DEFAULT NULL,
  `input_type` varchar(32) NOT NULL,
  `input_ref` varchar(512) NOT NULL,
  `input_sha256` char(64) DEFAULT NULL,
  `status` varchar(16) NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/INVALID',
  `error_code` varchar(64) DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `input_json` longtext,
  `result_json` longtext,
  `created_by` varchar(64) NOT NULL,
  `create_time` datetime NOT NULL,
  `start_time` datetime DEFAULT NULL,
  `finish_time` datetime DEFAULT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inference_task_request` (`request_id`),
  UNIQUE KEY `uk_inference_task_idempotency` (`idempotency_key`),
  KEY `idx_inference_task_device_time` (`device_code`,`create_time`),
  KEY `idx_inference_task_status_time` (`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Asynchronous model inference task';
