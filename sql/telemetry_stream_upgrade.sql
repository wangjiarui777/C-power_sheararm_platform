-- Idempotency keys for reliable Redis Stream replay.
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_event_id;
DELIMITER //
CREATE PROCEDURE add_event_id(IN p_table VARCHAR(64), IN p_index VARCHAR(64))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = 'event_id'
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table,
      '` ADD COLUMN `event_id` varchar(64) DEFAULT NULL COMMENT ''Collector idempotency event ID'' AFTER `data_id`');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = p_table AND index_name = p_index
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD UNIQUE INDEX `', p_index, '` (`event_id`)');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_event_id('device_vibration_data', 'uk_vibration_event_id');
CALL add_event_id('device_temperature_data', 'uk_temperature_event_id');
DROP PROCEDURE IF EXISTS add_event_id;
