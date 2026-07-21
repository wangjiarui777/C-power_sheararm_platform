-- Additive migration for multi-point diagnosis batches. Safe for existing installations.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `sensor_diagnosis_batch` (
  `id` bigint NOT NULL COMMENT 'Snowflake batch ID',
  `client_request_id` varchar(128) NOT NULL COMMENT 'Client idempotency request ID',
  `request_hash` char(64) NOT NULL COMMENT 'Canonical request SHA-256',
  `device_code` varchar(64) NOT NULL,
  `model_type` varchar(32) NOT NULL,
  `model_version` varchar(128) NOT NULL,
  `status` varchar(16) NOT NULL COMMENT 'PENDING/RUNNING/PARTIAL/SUCCEEDED/FAILED',
  `total_count` int NOT NULL,
  `success_count` int NOT NULL DEFAULT 0,
  `failed_count` int NOT NULL DEFAULT 0,
  `created_by` varchar(64) NOT NULL,
  `create_time` datetime NOT NULL,
  `start_time` datetime DEFAULT NULL,
  `finish_time` datetime DEFAULT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_diagnosis_batch_user_request` (`created_by`,`client_request_id`),
  KEY `idx_diagnosis_batch_device_time` (`device_code`,`create_time`),
  KEY `idx_diagnosis_batch_status_time` (`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Multi-point diagnosis batch';

DROP PROCEDURE IF EXISTS add_multi_point_column;
DELIMITER //
CREATE PROCEDURE add_multi_point_column(IN p_table VARCHAR(64), IN p_name VARCHAR(64), IN p_definition TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_name, '` ', p_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_multi_point_column('sensor_inference_task', 'batch_id', 'bigint DEFAULT NULL AFTER `idempotency_key`');
CALL add_multi_point_column('sensor_inference_task', 'attempt_no', 'int NOT NULL DEFAULT 1 AFTER `batch_id`');
CALL add_multi_point_column('sensor_inference_task', 'supersedes_task_id', 'bigint DEFAULT NULL AFTER `attempt_no`');
CALL add_multi_point_column('phm_attachment', 'point_id', 'bigint DEFAULT NULL AFTER `biz_id`');
CALL add_multi_point_column('phm_attachment', 'channel_id', 'int DEFAULT NULL AFTER `point_id`');
CALL add_multi_point_column('enhanced_inference_record', 'task_id', 'bigint DEFAULT NULL AFTER `id`');
CALL add_multi_point_column('enhanced_inference_record', 'point_id', 'bigint DEFAULT NULL AFTER `device_code`');
CALL add_multi_point_column('enhanced_inference_record', 'channel_id', 'int DEFAULT NULL AFTER `point_id`');
CALL add_multi_point_column('enhanced_inference_record', 'model_version', 'varchar(128) DEFAULT NULL AFTER `channel_id`');

DROP PROCEDURE IF EXISTS add_multi_point_column;

DROP PROCEDURE IF EXISTS add_multi_point_index;
DELIMITER //
CREATE PROCEDURE add_multi_point_index(IN p_table VARCHAR(64), IN p_name VARCHAR(64), IN p_columns TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = p_table AND index_name = p_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_name, '` (', p_columns, ')');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_multi_point_index('sensor_inference_task', 'idx_inference_task_batch_point', '`batch_id`,`point_id`,`attempt_no`');
CALL add_multi_point_index('phm_attachment', 'idx_phm_attachment_point', '`biz_id`,`point_id`,`create_time`');
CALL add_multi_point_index('enhanced_inference_record', 'idx_device_point_time', '`device_code`,`point_id`,`create_time`');
CALL add_multi_point_index('enhanced_inference_record', 'idx_enhanced_task_id', '`task_id`');

DROP PROCEDURE IF EXISTS add_multi_point_index;
