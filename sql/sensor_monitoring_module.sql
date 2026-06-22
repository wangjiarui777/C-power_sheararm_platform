-- 锟斤拷锟斤拷锟借备锟斤拷锟铰度硷拷锟侥ｏ拷锟�
-- MySQL 锟斤拷锟斤拷锟脚憋拷

DROP TABLE IF EXISTS sensor_feature;
CREATE TABLE sensor_feature (
    id BIGINT NOT NULL PRIMARY KEY,
    device_code VARCHAR(64) NOT NULL,
    sample_time DATETIME NOT NULL,
    rms DOUBLE NULL,
    peak DOUBLE NULL,
    alarm_flag TINYINT(1) NOT NULL DEFAULT 0,
    alarm_message VARCHAR(500) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_device_time (device_code, sample_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='锟斤拷锟斤拷锟斤拷锟街碉拷锟�';

DROP TABLE IF EXISTS sensor_alarm;
CREATE TABLE sensor_alarm (
    id BIGINT NOT NULL PRIMARY KEY,
    device_code VARCHAR(64) NOT NULL,
    alarm_type VARCHAR(64) NOT NULL,
    alarm_message VARCHAR(500) NULL,
    sample_time DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_alarm_device_time (device_code, sample_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='锟斤拷獗拷锟斤拷锟铰硷拷锟�';

-- Apache IoTDB 时序结构请参考:
-- ruoyi-admin/src/main/resources/sql/iotdb-init.sql
