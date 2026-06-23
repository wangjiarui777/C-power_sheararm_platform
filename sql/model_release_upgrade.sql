-- Additive, idempotent model registry and release gate.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `sensor_model_release` (
  `id` bigint NOT NULL,
  `model_name` varchar(128) NOT NULL,
  `model_type` varchar(32) NOT NULL,
  `semantic_version` varchar(64) NOT NULL,
  `file_sha256` char(64) NOT NULL,
  `training_data_version` varchar(128) NOT NULL,
  `validation_data_version` varchar(128) NOT NULL,
  `threshold_version` varchar(128) NOT NULL,
  `precision_score` decimal(8,6) DEFAULT NULL,
  `recall_score` decimal(8,6) DEFAULT NULL,
  `severe_recall_score` decimal(8,6) DEFAULT NULL,
  `false_positive_per_device_day` decimal(10,4) DEFAULT NULL,
  `confidence_threshold` decimal(8,4) DEFAULT NULL,
  `consecutive_hits` int NOT NULL DEFAULT 1,
  `shadow_days` int NOT NULL DEFAULT 0,
  `cooldown_minutes` int NOT NULL DEFAULT 60,
  `status` varchar(16) NOT NULL COMMENT 'DRAFT/ACTIVE/RETIRED',
  `artifact_uri` varchar(512) DEFAULT NULL,
  `created_by` varchar(64) NOT NULL,
  `activated_by` varchar(64) DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `activate_time` datetime DEFAULT NULL,
  `update_time` datetime NOT NULL,
  `remark` varchar(1000) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_release_version` (`model_type`,`semantic_version`),
  UNIQUE KEY `uk_model_release_sha` (`file_sha256`),
  KEY `idx_model_release_status` (`model_type`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Validated model release registry';
