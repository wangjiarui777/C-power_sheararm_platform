-- Vibration data record table
-- The data includes: data ID, device ID, vibration value, sample time, created time, updated time, and remark

DROP TABLE IF EXISTS device_vibration_data;
CREATE TABLE device_vibration_data (
  data_id         BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'Data ID',
  device_code     VARCHAR(64)    NOT NULL                COMMENT 'Device ID',
  channel_id      INT(11)        NOT NULL DEFAULT 1      COMMENT 'Channel ID (1-8)',
  temperature_value DECIMAL(10,2) DEFAULT NULL            COMMENT 'Temperature Value',
  vibration_value DECIMAL(12,4)  NOT NULL                COMMENT 'Vibration Value',
  acceleration_value DECIMAL(12,4) DEFAULT NULL           COMMENT 'Acceleration Value',
  sample_time     DATETIME       NOT NULL                COMMENT 'Sample Time',
  create_time     DATETIME       DEFAULT NULL            COMMENT 'Created Time',
  update_time     DATETIME       DEFAULT NULL            COMMENT 'Updated Time',
  remark          VARCHAR(500)   DEFAULT NULL            COMMENT 'Remark',
  PRIMARY KEY (data_id),
  KEY idx_device_channel_time (device_code, channel_id, sample_time)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='Device Vibration Data Record Table';

INSERT INTO device_vibration_data
(data_id, device_code, channel_id, temperature_value, vibration_value, acceleration_value, sample_time, create_time, update_time, remark)
VALUES
(1, 'DEV-001', 1, 36.20, 0.1250, 0.1250, '2026-04-28 08:00:00', NOW(), NULL, 'Initial record'),
(2, 'DEV-002', 2, 36.80, 0.2380, 0.2380, '2026-04-28 08:05:00', NOW(), NULL, 'Initial record'),
(3, 'DEV-001', 1, 37.10, 0.1420, 0.1420, '2026-04-28 08:10:00', NOW(), NULL, 'Initial record');
