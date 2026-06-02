-- Temperature data record table
-- The data includes: data ID, device ID, temperature value, collection time, created time, updated time, and remark

DROP TABLE IF EXISTS device_temperature_data;
CREATE TABLE device_temperature_data (
  data_id           BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'Data ID',
  device_code       VARCHAR(64)    NOT NULL                COMMENT 'Device ID',
  temperature_value DECIMAL(10,2)  NOT NULL                COMMENT 'Temperature Value',
  collection_time   DATETIME       NOT NULL                COMMENT 'Collection Time',
  create_time       DATETIME       DEFAULT NULL            COMMENT 'Created Time',
  update_time       DATETIME       DEFAULT NULL            COMMENT 'Updated Time',
  remark            VARCHAR(500)   DEFAULT NULL            COMMENT 'Remark',
  PRIMARY KEY (data_id)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='Device Temperature Data Record Table';

INSERT INTO device_temperature_data VALUES
(1, 'DEV-001', 36.50, '2026-04-28 08:00:00', NOW(), NULL, 'Initial record'),
(2, 'DEV-002', 37.20, '2026-04-28 08:05:00', NOW(), NULL, 'Initial record'),
(3, 'DEV-001', 36.80, '2026-04-28 08:10:00', NOW(), NULL, 'Initial record');
