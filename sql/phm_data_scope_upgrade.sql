-- Additive department ownership for PHM devices.
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_phm_dept_column;
DELIMITER //
CREATE PROCEDURE add_phm_dept_column()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'phm_device'
      AND column_name = 'dept_id'
  ) THEN
    ALTER TABLE `phm_device`
      ADD COLUMN `dept_id` bigint DEFAULT NULL COMMENT 'Owning RuoYi department' AFTER `id`;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'phm_device'
      AND index_name = 'idx_phm_device_dept'
  ) THEN
    ALTER TABLE `phm_device` ADD INDEX `idx_phm_device_dept` (`dept_id`);
  END IF;
END//
DELIMITER ;

CALL add_phm_dept_column();
DROP PROCEDURE IF EXISTS add_phm_dept_column;

-- Existing rows must be assigned to a real production department before non-admin rollout.
SELECT id, device_code, device_name
FROM phm_device
WHERE dept_id IS NULL;
