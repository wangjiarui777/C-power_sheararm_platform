-- Industrial monitoring domain upgrade (one-time additive migration).
-- Run after the existing vibration_data.sql, temperature_data.sql and phm_platform.sql.

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
DROP PROCEDURE IF EXISTS add_unique_if_clean;
DELIMITER //
CREATE PROCEDURE add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
CREATE PROCEDURE add_index_if_missing(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_columns TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = p_table AND index_name = p_index
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_columns, ')');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
CREATE PROCEDURE add_unique_if_clean(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_columns TEXT, IN p_group_columns TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = p_table AND index_name = p_index
  ) THEN
    SET @duplicate_count = 0;
    SET @check_sql = CONCAT(
      'SELECT COUNT(*) INTO @duplicate_count FROM (SELECT 1 FROM `', p_table,
      '` WHERE ', p_group_columns, ' IS NOT NULL GROUP BY ', p_group_columns,
      ' HAVING COUNT(*) > 1) duplicate_rows'
    );
    PREPARE check_stmt FROM @check_sql;
    EXECUTE check_stmt;
    DEALLOCATE PREPARE check_stmt;
    IF @duplicate_count = 0 THEN
      SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD UNIQUE INDEX `', p_index, '` (', p_columns, ')');
      PREPARE stmt FROM @ddl;
      EXECUTE stmt;
      DEALLOCATE PREPARE stmt;
    END IF;
  END IF;
END//
DELIMITER ;

CALL add_column_if_missing('device_vibration_data', 'point_id', 'BIGINT NULL COMMENT ''PHM measure point ID'' AFTER `channel_id`');
CALL add_column_if_missing('device_vibration_data', 'quality', 'VARCHAR(16) NOT NULL DEFAULT ''GOOD'' COMMENT ''GOOD/STALE/BAD/OFFLINE'' AFTER `sample_time`');
CALL add_column_if_missing('device_vibration_data', 'receive_time', 'DATETIME NULL COMMENT ''Platform receive time'' AFTER `quality`');
CALL add_index_if_missing('device_vibration_data', 'idx_vibration_point_time', '`point_id`,`sample_time`');

CALL add_column_if_missing('device_temperature_data', 'point_id', 'BIGINT NULL COMMENT ''PHM measure point ID'' AFTER `device_code`');
CALL add_column_if_missing('device_temperature_data', 'channel_id', 'INT NULL COMMENT ''Acquisition channel'' AFTER `point_id`');
CALL add_column_if_missing('device_temperature_data', 'quality', 'VARCHAR(16) NOT NULL DEFAULT ''GOOD'' COMMENT ''GOOD/STALE/BAD/OFFLINE'' AFTER `collection_time`');
CALL add_column_if_missing('device_temperature_data', 'receive_time', 'DATETIME NULL COMMENT ''Platform receive time'' AFTER `quality`');
CALL add_index_if_missing('device_temperature_data', 'idx_temperature_device_channel_time', '`device_code`,`channel_id`,`collection_time`');
CALL add_index_if_missing('device_temperature_data', 'idx_temperature_point_time', '`point_id`,`collection_time`');

CALL add_column_if_missing('phm_measure_point', 'unit', 'VARCHAR(32) NULL COMMENT ''Engineering unit'' AFTER `feature_codes`');
CALL add_column_if_missing('phm_measure_point', 'quality_policy', 'VARCHAR(64) NOT NULL DEFAULT ''300'' COMMENT ''Offline timeout in seconds'' AFTER `unit`');
CALL add_unique_if_clean('phm_measure_point', 'uk_phm_point_device_channel',
  '`device_code`,`channel_id`', 'CONCAT(`device_code`, '':'' , `channel_id`)');

CALL add_column_if_missing('phm_alarm_event', 'condition_status', 'VARCHAR(32) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''ACTIVE/RETURNED_TO_NORMAL'' AFTER `status`');
CALL add_column_if_missing('phm_alarm_event', 'workflow_status', 'VARCHAR(32) NOT NULL DEFAULT ''NEW'' COMMENT ''NEW/ACKNOWLEDGED/ASSIGNED/CLOSED'' AFTER `condition_status`');
CALL add_column_if_missing('phm_alarm_event', 'assignee', 'VARCHAR(64) NULL AFTER `workflow_status`');
CALL add_column_if_missing('phm_alarm_event', 'acknowledged_by', 'VARCHAR(64) NULL AFTER `assignee`');
CALL add_column_if_missing('phm_alarm_event', 'acknowledged_time', 'DATETIME NULL AFTER `acknowledged_by`');
CALL add_column_if_missing('phm_alarm_event', 'closed_by', 'VARCHAR(64) NULL AFTER `acknowledged_time`');
CALL add_column_if_missing('phm_alarm_event', 'closed_time', 'DATETIME NULL AFTER `closed_by`');
CALL add_column_if_missing('phm_alarm_event', 'resolution', 'VARCHAR(500) NULL AFTER `closed_time`');
CALL add_column_if_missing('phm_alarm_event', 'occurrence_count', 'INT NOT NULL DEFAULT 1 AFTER `resolution`');
CALL add_column_if_missing('phm_alarm_event', 'first_trigger_time', 'DATETIME NULL AFTER `occurrence_count`');
CALL add_column_if_missing('phm_alarm_event', 'last_trigger_time', 'DATETIME NULL AFTER `first_trigger_time`');
CALL add_column_if_missing('phm_alarm_event', 'active_alarm_key',
  'VARCHAR(255) GENERATED ALWAYS AS (CASE WHEN `condition_status` = ''ACTIVE'' THEN CONCAT(`device_code`, '':'', COALESCE(`point_id`, 0), '':'', COALESCE(`feature_code`, '''')) ELSE NULL END) STORED');
CALL add_index_if_missing('phm_alarm_event', 'idx_phm_alarm_workflow', '`workflow_status`,`condition_status`');
CALL add_index_if_missing('phm_alarm_event', 'idx_phm_alarm_active_key', '`device_code`,`point_id`,`feature_code`,`condition_status`');

CALL add_column_if_missing('phm_alarm_handle_record', 'assignee', 'VARCHAR(64) NULL AFTER `after_status`');

UPDATE phm_measure_point
SET unit = CASE WHEN signal_type = 'temperature' THEN '℃' ELSE 'mm/s' END
WHERE unit IS NULL;

UPDATE device_vibration_data d
JOIN phm_measure_point p
  ON BINARY p.device_code = BINARY d.device_code AND p.channel_id = d.channel_id
SET d.point_id = p.id,
    d.receive_time = COALESCE(d.receive_time, d.create_time, d.sample_time)
WHERE d.point_id IS NULL;

UPDATE device_temperature_data d
JOIN (
  SELECT device_code, MIN(id) AS point_id, MIN(channel_id) AS channel_id
  FROM phm_measure_point
  GROUP BY device_code
) p ON BINARY p.device_code = BINARY d.device_code
SET d.point_id = p.point_id,
    d.channel_id = COALESCE(d.channel_id, p.channel_id),
    d.receive_time = COALESCE(d.receive_time, d.create_time, d.collection_time)
WHERE d.point_id IS NULL;

UPDATE phm_alarm_event
SET condition_status = CASE WHEN status IN ('handled', 'ignored', 'expired') THEN 'RETURNED_TO_NORMAL' ELSE 'ACTIVE' END,
    workflow_status = CASE WHEN status IN ('handled', 'ignored', 'expired') THEN 'CLOSED' ELSE 'NEW' END,
    occurrence_count = COALESCE(occurrence_count, 1),
    first_trigger_time = COALESCE(first_trigger_time, alarm_time),
    last_trigger_time = COALESCE(last_trigger_time, alarm_time);

-- Preserve duplicate records, but only the newest equivalent alarm remains active.
UPDATE phm_alarm_event a
JOIN (
  SELECT device_code, COALESCE(point_id, 0) point_key, COALESCE(feature_code, '') feature_key, MAX(id) keep_id
  FROM phm_alarm_event
  WHERE condition_status = 'ACTIVE'
  GROUP BY device_code, COALESCE(point_id, 0), COALESCE(feature_code, '')
  HAVING COUNT(*) > 1
) d ON d.device_code = a.device_code
   AND d.point_key = COALESCE(a.point_id, 0)
   AND d.feature_key = COALESCE(a.feature_code, '')
   AND a.id <> d.keep_id
SET a.condition_status = 'RETURNED_TO_NORMAL',
    a.workflow_status = CASE WHEN a.workflow_status = 'NEW' THEN 'CLOSED' ELSE a.workflow_status END,
    a.resolution = COALESCE(a.resolution, '升级迁移：合并重复活动告警');

CALL add_unique_if_clean('phm_alarm_event', 'uk_phm_active_alarm',
  '`active_alarm_key`', '`active_alarm_key`');
CALL add_unique_if_clean('phm_device', 'uk_phm_device_code',
  '`device_code`', '`device_code`');
CALL add_unique_if_clean('phm_device_favorite', 'uk_phm_favorite_user_device',
  '`user_name`,`device_id`', 'CONCAT(`user_name`, '':'' , `device_id`)');

-- Apache IoTDB table-model schema is maintained separately in:
-- ruoyi-admin/src/main/resources/sql/iotdb-init.sql

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
DROP PROCEDURE IF EXISTS add_unique_if_clean;
