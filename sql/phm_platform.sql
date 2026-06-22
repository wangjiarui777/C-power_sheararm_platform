-- PHM platform business schema and demo seed data.
-- Target database: MySQL 8 / RuoYi default datasource.

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS phm_alarm_handle_record;
DROP TABLE IF EXISTS phm_alarm_event;
DROP TABLE IF EXISTS phm_alarm_rule;
DROP TABLE IF EXISTS phm_device_event;
DROP TABLE IF EXISTS phm_device_favorite;
DROP TABLE IF EXISTS phm_measure_point;
DROP TABLE IF EXISTS phm_feature_config;
DROP TABLE IF EXISTS phm_attachment;
DROP TABLE IF EXISTS phm_system_config;
DROP TABLE IF EXISTS phm_device;

CREATE TABLE phm_device (
  id BIGINT NOT NULL COMMENT 'Primary key',
  device_code VARCHAR(64) NOT NULL COMMENT 'Device code',
  device_name VARCHAR(128) NOT NULL COMMENT 'Device name',
  device_type VARCHAR(64) DEFAULT NULL COMMENT 'Device type',
  org_name VARCHAR(128) DEFAULT NULL COMMENT 'Organization path/name',
  location VARCHAR(128) DEFAULT NULL COMMENT 'Installation location',
  model_name VARCHAR(128) DEFAULT NULL COMMENT 'Model/nameplate model',
  manufacturer VARCHAR(128) DEFAULT NULL COMMENT 'Manufacturer',
  status VARCHAR(32) NOT NULL DEFAULT 'normal' COMMENT 'normal/stopped/level1/level2/level3/level4/level5',
  health_index INT DEFAULT 100 COMMENT 'Health index 0-100',
  fault_type VARCHAR(64) DEFAULT NULL COMMENT 'Current fault type',
  run_hours DECIMAL(12,2) DEFAULT 0 COMMENT 'Accumulated run hours',
  last_alarm_time DATETIME DEFAULT NULL COMMENT 'Latest alarm time',
  nameplate_json TEXT DEFAULT NULL COMMENT 'Electronic nameplate JSON',
  process_json TEXT DEFAULT NULL COMMENT 'Process parameters JSON',
  morphology_url VARCHAR(500) DEFAULT NULL COMMENT 'Morphology image URL',
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_phm_device_code (device_code),
  KEY idx_phm_device_status (status),
  KEY idx_phm_device_org (org_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM device asset';

CREATE TABLE phm_feature_config (
  id BIGINT NOT NULL COMMENT 'Primary key',
  feature_code VARCHAR(64) NOT NULL COMMENT 'Feature code',
  feature_name VARCHAR(128) NOT NULL COMMENT 'Feature name',
  unit VARCHAR(32) DEFAULT NULL COMMENT 'Unit',
  signal_type VARCHAR(32) NOT NULL DEFAULT 'vibration' COMMENT 'vibration/temperature/acceleration',
  display_order INT NOT NULL DEFAULT 0,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_phm_feature_code (feature_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM feature display config';

CREATE TABLE phm_measure_point (
  id BIGINT NOT NULL COMMENT 'Primary key',
  device_id BIGINT NOT NULL COMMENT 'Device ID',
  device_code VARCHAR(64) NOT NULL COMMENT 'Device code',
  point_code VARCHAR(64) NOT NULL COMMENT 'Measure point code',
  point_name VARCHAR(128) NOT NULL COMMENT 'Measure point name',
  channel_id INT DEFAULT NULL COMMENT 'Acquisition channel',
  signal_type VARCHAR(32) NOT NULL DEFAULT 'vibration',
  feature_codes VARCHAR(255) DEFAULT NULL COMMENT 'Comma separated feature codes',
  card_x DECIMAL(8,4) DEFAULT NULL COMMENT 'Card X percent',
  card_y DECIMAL(8,4) DEFAULT NULL COMMENT 'Card Y percent',
  point_x DECIMAL(8,4) DEFAULT NULL COMMENT 'Point X percent',
  point_y DECIMAL(8,4) DEFAULT NULL COMMENT 'Point Y percent',
  display_order INT NOT NULL DEFAULT 0,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_phm_point_code (point_code),
  KEY idx_phm_point_device (device_id),
  KEY idx_phm_point_device_channel (device_code, channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM device measure point';

CREATE TABLE phm_alarm_rule (
  id BIGINT NOT NULL COMMENT 'Primary key',
  rule_name VARCHAR(128) NOT NULL COMMENT 'Rule name',
  device_id BIGINT DEFAULT NULL COMMENT 'Device ID, null means global',
  point_id BIGINT DEFAULT NULL COMMENT 'Measure point ID, null means device/global',
  feature_code VARCHAR(64) NOT NULL COMMENT 'Feature code',
  alarm_type VARCHAR(32) NOT NULL DEFAULT 'threshold' COMMENT 'threshold/trend/device',
  high_limit DECIMAL(12,4) DEFAULT NULL COMMENT 'High alarm threshold',
  high_high_limit DECIMAL(12,4) DEFAULT NULL COMMENT 'High-high alarm threshold',
  growth_period INT DEFAULT NULL COMMENT 'Growth period count',
  growth_high_limit DECIMAL(12,4) DEFAULT NULL COMMENT 'Growth high threshold',
  growth_high_high_limit DECIMAL(12,4) DEFAULT NULL COMMENT 'Growth high-high threshold',
  consecutive_count INT NOT NULL DEFAULT 1 COMMENT 'Continuous trigger count',
  device_alarm_level INT NOT NULL DEFAULT 1 COMMENT 'Mapped device alarm level 1-5',
  description VARCHAR(500) DEFAULT NULL,
  action_advice VARCHAR(500) DEFAULT NULL COMMENT 'Handling advice',
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_phm_rule_scope (device_id, point_id, feature_code),
  KEY idx_phm_rule_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM alarm rule';

CREATE TABLE phm_alarm_event (
  id BIGINT NOT NULL COMMENT 'Primary key',
  alarm_no VARCHAR(64) NOT NULL COMMENT 'Alarm number',
  device_id BIGINT DEFAULT NULL,
  device_code VARCHAR(64) NOT NULL,
  device_name VARCHAR(128) DEFAULT NULL,
  point_id BIGINT DEFAULT NULL,
  point_name VARCHAR(128) DEFAULT NULL,
  feature_code VARCHAR(64) DEFAULT NULL,
  alarm_scope VARCHAR(32) NOT NULL DEFAULT 'point' COMMENT 'device/point',
  alarm_type VARCHAR(32) NOT NULL DEFAULT 'threshold' COMMENT 'threshold/trend/diagnosis/manual',
  alarm_level INT NOT NULL DEFAULT 1 COMMENT 'Device alarm level 1-5',
  point_alarm_level VARCHAR(32) DEFAULT NULL COMMENT 'normal/high/high_high',
  alarm_value DECIMAL(12,4) DEFAULT NULL,
  diagnosis_result VARCHAR(200) DEFAULT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'unhandled' COMMENT 'unhandled/handled/ignored/expired',
  handler VARCHAR(64) DEFAULT NULL,
  handle_time DATETIME DEFAULT NULL,
  ignore_reason VARCHAR(128) DEFAULT NULL,
  handle_remark VARCHAR(500) DEFAULT NULL,
  related_record_id BIGINT DEFAULT NULL COMMENT 'Related diagnosis record ID',
  alarm_time DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_phm_alarm_no (alarm_no),
  KEY idx_phm_alarm_device_time (device_code, alarm_time),
  KEY idx_phm_alarm_status (status),
  KEY idx_phm_alarm_level (alarm_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM alarm event';

CREATE TABLE phm_alarm_handle_record (
  id BIGINT NOT NULL COMMENT 'Primary key',
  alarm_id BIGINT NOT NULL COMMENT 'Alarm event ID',
  action_type VARCHAR(32) NOT NULL COMMENT 'handle/ignore/adjust_rule',
  operator_name VARCHAR(64) DEFAULT NULL,
  ignore_reason VARCHAR(128) DEFAULT NULL,
  before_status VARCHAR(32) DEFAULT NULL,
  after_status VARCHAR(32) DEFAULT NULL,
  remark VARCHAR(500) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_phm_alarm_handle_alarm (alarm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM alarm handling record';

CREATE TABLE phm_device_event (
  id BIGINT NOT NULL COMMENT 'Primary key',
  device_id BIGINT NOT NULL,
  device_code VARCHAR(64) NOT NULL,
  event_time DATETIME NOT NULL,
  event_type VARCHAR(32) NOT NULL COMMENT 'access/repair/maintenance/diagnosis/alarm_handle/other',
  event_content VARCHAR(1000) NOT NULL,
  operator_name VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_phm_event_device_time (device_code, event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM device lifecycle event';

CREATE TABLE phm_device_favorite (
  id BIGINT NOT NULL COMMENT 'Primary key',
  device_id BIGINT NOT NULL,
  user_name VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_phm_favorite_user_device (user_name, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM device favorite';

CREATE TABLE phm_attachment (
  id BIGINT NOT NULL COMMENT 'Primary key',
  biz_type VARCHAR(32) NOT NULL COMMENT 'morphology/system/report',
  biz_id BIGINT DEFAULT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_url VARCHAR(500) NOT NULL,
  file_ext VARCHAR(32) DEFAULT NULL,
  report_type VARCHAR(32) DEFAULT NULL COMMENT 'diagnosis/run',
  upload_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_phm_attachment_biz (biz_type, biz_id),
  KEY idx_phm_attachment_report (report_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM attachment and service report';

CREATE TABLE phm_system_config (
  id BIGINT NOT NULL COMMENT 'Primary key',
  config_key VARCHAR(128) NOT NULL,
  config_value VARCHAR(500) DEFAULT NULL,
  config_name VARCHAR(128) DEFAULT NULL,
  config_type VARCHAR(32) DEFAULT 'string',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_phm_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PHM system display config';

INSERT INTO phm_device
(id, device_code, device_name, device_type, org_name, location, model_name, manufacturer, status, health_index, fault_type, run_hours, last_alarm_time, nameplate_json, process_json, morphology_url, create_by, remark)
VALUES
(190000000000000001, 'DEV-001', '一号主轴承试验台', '轴承试验台', '实验中心/PHM实训线', 'A区-01', 'BRG-CWRU-01', 'RuoYi Lab', 'level2', 76, '滚动轴承', 1280.5, '2026-05-08 09:40:00', '{"额定转速":"1797 rpm","功率":"2.2 kW","采样频率":"12 kHz"}', '{"负载":"中等","转速":"1797 rpm"}', '', 'admin', 'Demo PHM device'),
(190000000000000002, 'DEV-002', '二号齿轮箱综合台', '齿轮箱', '实验中心/PHM实训线', 'A区-02', 'GEAR-DG-02', 'RuoYi Lab', 'normal', 92, NULL, 860.0, NULL, '{"额定转速":"1500 rpm","传动比":"3.2","采样频率":"10 kHz"}', '{"负载":"轻载","转速":"1500 rpm"}', '', 'admin', 'Demo PHM device'),
(190000000000000003, 'FAN-MAIN-001', '主排风机', '离心风机', '生产车间/风机组', 'B区-主线', 'FAN-75KW', 'RuoYi Lab', 'level3', 63, '联轴器', 3221.2, '2026-05-09 10:10:00', '{"功率":"75 kW","转速":"1480 rpm","轴承型号":"SKF-6312"}', '{"风量":"72000 m3/h","出口压力":"2.1 kPa"}', '', 'admin', 'Demo PHM device');

INSERT INTO phm_feature_config
(id, feature_code, feature_name, unit, signal_type, display_order, remark)
VALUES
(190000000000001001, 'vibration', '振动速度', 'mm/s', 'vibration', 1, 'Realtime vibration value'),
(190000000000001002, 'acceleration', '加速度', 'g', 'acceleration', 2, 'Acceleration value'),
(190000000000001003, 'temperature', '温度', '℃', 'temperature', 3, 'Temperature value'),
(190000000000001004, 'rms', 'RMS', 'g', 'vibration', 4, 'Root mean square'),
(190000000000001005, 'peak', '峰值', 'g', 'vibration', 5, 'Peak value');

INSERT INTO phm_measure_point
(id, device_id, device_code, point_code, point_name, channel_id, signal_type, feature_codes, card_x, card_y, point_x, point_y, display_order, remark)
VALUES
(190000000000002001, 190000000000000001, 'DEV-001', 'DEV-001-CH1', '驱动端轴承', 1, 'vibration', 'vibration,acceleration,rms,peak,temperature', 68.00, 28.00, 52.00, 46.00, 1, 'Demo point'),
(190000000000002002, 190000000000000001, 'DEV-001', 'DEV-001-CH2', '非驱动端轴承', 2, 'vibration', 'vibration,acceleration,rms,peak,temperature', 18.00, 38.00, 36.00, 48.00, 2, 'Demo point'),
(190000000000002003, 190000000000000003, 'FAN-MAIN-001', 'FAN-MAIN-001-CH1', '风机驱动端', 1, 'vibration', 'vibration,acceleration,rms,peak,temperature', 64.00, 36.00, 50.00, 50.00, 1, 'Demo point');

INSERT INTO phm_alarm_rule
(id, rule_name, device_id, point_id, feature_code, alarm_type, high_limit, high_high_limit, growth_period, growth_high_limit, growth_high_high_limit, consecutive_count, device_alarm_level, description, action_advice, remark)
VALUES
(190000000000003001, '振动速度通用阈值', NULL, NULL, 'vibration', 'threshold', 0.2000, 0.3000, 5, 0.0500, 0.0800, 2, 2, '振动速度超过高报/高高报阈值触发测点告警', '复核传感器安装状态并查看频谱图', 'Global demo rule'),
(190000000000003002, '温度通用阈值', NULL, NULL, 'temperature', 'threshold', 65.0000, 75.0000, 5, 3.0000, 5.0000, 2, 1, '温度超过阈值触发告警', '检查润滑、负载和散热条件', 'Global demo rule');

INSERT INTO phm_alarm_event
(id, alarm_no, device_id, device_code, device_name, point_id, point_name, feature_code, alarm_scope, alarm_type, alarm_level, point_alarm_level, alarm_value, diagnosis_result, status, handler, handle_time, handle_remark, related_record_id, alarm_time, remark)
VALUES
(190000000000004001, 'ALM202605080001', 190000000000000001, 'DEV-001', '一号主轴承试验台', 190000000000002001, '驱动端轴承', 'vibration', 'point', 'threshold', 2, 'high', 0.2380, '轴承外圈故障趋势', 'unhandled', NULL, NULL, NULL, NULL, '2026-05-08 09:40:00', 'Seed alarm'),
(190000000000004002, 'ALM202605090001', 190000000000000003, 'FAN-MAIN-001', '主排风机', 190000000000002003, '风机驱动端', 'rms', 'device', 'diagnosis', 3, 'high_high', 0.9156, '联轴器不平衡风险', 'unhandled', NULL, NULL, NULL, NULL, '2026-05-09 10:10:00', 'Seed alarm'),
(190000000000004003, 'ALM202605100001', 190000000000000002, 'DEV-002', '二号齿轮箱综合台', NULL, NULL, 'temperature', 'device', 'threshold', 1, 'high', 68.5000, '齿轮箱温度偏高', 'handled', 'admin', '2026-05-10 15:05:00', '已检查润滑和散热状态，调整巡检频率并持续观察温度趋势。', NULL, '2026-05-10 14:20:00', 'Seed handled alarm');

INSERT INTO phm_alarm_handle_record
(id, alarm_id, action_type, operator_name, ignore_reason, before_status, after_status, remark, create_time)
VALUES
(190000000000004101, 190000000000004003, 'handle', 'admin', NULL, 'unhandled', 'handled', '已检查润滑和散热状态，调整巡检频率并持续观察温度趋势。', '2026-05-10 15:05:00');

INSERT INTO phm_device_event
(id, device_id, device_code, event_time, event_type, event_content, operator_name, remark)
VALUES
(190000000000005001, 190000000000000001, 'DEV-001', '2026-04-20 08:30:00', 'access', '设备接入 PHM 平台，完成测点和采集通道绑定。', 'system', 'Auto seed'),
(190000000000005002, 190000000000000001, 'DEV-001', '2026-05-08 11:00:00', 'maintenance', '复核驱动端轴承振动升高，建议持续跟踪 RMS 与峰值因子。', 'admin', 'Demo event'),
(190000000000005003, 190000000000000002, 'DEV-002', '2026-05-10 15:05:00', 'alarm_handle', '处理告警 ALM202605100001：已检查润滑和散热状态，调整巡检频率并持续观察温度趋势。', 'admin', 'Demo handled alarm event');

INSERT INTO phm_system_config
(id, config_key, config_value, config_name, config_type, remark)
VALUES
(190000000000006001, 'system.name', '设备预测性维护 PHM 平台', '系统名称', 'string', 'Header title'),
(190000000000006002, 'refresh.interval', '60', '页面刷新周期（秒）', 'number', 'Default auto refresh interval'),
(190000000000006003, 'default.display.mode', 'list', '默认显示模式', 'string', 'list/card'),
(190000000000006004, 'alarm.sound.enabled', 'true', '告警声音开关', 'boolean', 'Enable alarm sound'),
(190000000000006005, 'system.logo', '', '系统Logo', 'image', 'Logo image URL');

SET FOREIGN_KEY_CHECKS = 1;
