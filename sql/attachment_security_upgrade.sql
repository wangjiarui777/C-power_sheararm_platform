-- Additive secure attachment metadata. Binary files live outside the web root.
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_attachment_column;
DELIMITER //
CREATE PROCEDURE add_attachment_column(IN p_name VARCHAR(64), IN p_definition TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'phm_attachment'
      AND column_name = p_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `phm_attachment` ADD COLUMN `', p_name, '` ', p_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_attachment_column('object_name', 'varchar(128) DEFAULT NULL AFTER `file_url`');
CALL add_attachment_column('storage_path', 'varchar(1000) DEFAULT NULL AFTER `object_name`');
CALL add_attachment_column('mime_type', 'varchar(128) DEFAULT NULL AFTER `file_ext`');
CALL add_attachment_column('file_size', 'bigint DEFAULT NULL AFTER `mime_type`');
CALL add_attachment_column('sha256', 'char(64) DEFAULT NULL AFTER `file_size`');
CALL add_attachment_column('scan_status', 'varchar(16) DEFAULT NULL AFTER `sha256`');
CALL add_attachment_column('purpose', 'varchar(32) DEFAULT NULL AFTER `scan_status`');

DROP PROCEDURE IF EXISTS add_attachment_column;

-- Legacy URL-only rows are retained for audit, but the new download endpoint will not trust them.
SELECT id, file_name, file_url
FROM phm_attachment
WHERE storage_path IS NULL;
