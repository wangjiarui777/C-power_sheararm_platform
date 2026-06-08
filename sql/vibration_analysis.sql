-- 锟斤拷锟饺凤拷锟斤拷穸锟斤拷锟斤拷锟绞凤拷锟斤拷伪锟�
-- 锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷 RuoYi-Vue 锟斤拷目锟斤拷锟斤拷锟斤拷锟斤拷锟轿憋拷锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷
-- 锟斤拷锟斤拷使锟斤拷 UTF-8 锟斤拷锟诫保锟斤拷

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS vibration_analysis_record;
DROP TABLE IF EXISTS vibration_analysis_batch;

-- =========================================
-- 1. 锟斤拷史锟缴硷拷锟斤拷锟轿憋拷
-- =========================================
CREATE TABLE vibration_analysis_batch (
  batch_id      BIGINT(20)   NOT NULL COMMENT '锟斤拷锟斤拷ID',
  device_code   VARCHAR(64)  NOT NULL COMMENT '锟借备锟斤拷锟�',
  sample_rate   DOUBLE       NOT NULL COMMENT '锟斤拷锟斤拷频锟斤拷(Hz)',
  sample_count  INT(11)      NOT NULL COMMENT '锟斤拷锟斤拷锟斤拷锟斤拷',
  collect_time  DATETIME     NOT NULL COMMENT '锟缴硷拷时锟斤拷',
  status        TINYINT(4)   NOT NULL DEFAULT 0 COMMENT '状态锟斤拷0锟斤拷锟斤拷 1预锟斤拷 2锟斤拷锟斤拷',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '锟斤拷锟斤拷时锟斤拷',
  update_time   DATETIME     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '锟斤拷锟斤拷时锟斤拷',
  remark        VARCHAR(500) NULL DEFAULT NULL COMMENT '锟斤拷注',
  PRIMARY KEY (batch_id),
  KEY idx_device_code (device_code),
  KEY idx_collect_time (collect_time),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='锟今动凤拷锟斤拷锟斤拷史锟缴硷拷锟斤拷锟轿憋拷';

-- =========================================
-- 2. 锟今动凤拷锟斤拷锟斤拷锟斤拷锟�
-- =========================================
CREATE TABLE vibration_analysis_record (
  id                  BIGINT(20)    NOT NULL COMMENT '锟斤拷锟絀D',
  batch_id            BIGINT(20)    NOT NULL COMMENT '锟斤拷锟斤拷ID',
  device_code         VARCHAR(64)   NOT NULL COMMENT '锟借备锟斤拷锟�',
  channel_id          INT(11)       NOT NULL DEFAULT 1 COMMENT '通锟斤拷锟斤拷锟斤拷(1-8)',
  rms                 DOUBLE        DEFAULT NULL COMMENT '锟斤拷锟斤拷锟斤拷值',
  peak                DOUBLE        DEFAULT NULL COMMENT '锟斤拷值',
  crest_factor        DOUBLE        DEFAULT NULL COMMENT '锟斤拷值锟斤拷锟斤拷',
  kurtosis            DOUBLE        DEFAULT NULL COMMENT '锟酵讹拷',
  centroid_frequency  DOUBLE        DEFAULT NULL COMMENT '锟斤拷锟斤拷频锟斤拷',
  rms_frequency       DOUBLE        DEFAULT NULL COMMENT '锟斤拷锟斤拷锟斤拷频锟斤拷',
  diagnosis_result    VARCHAR(200)  DEFAULT NULL COMMENT '锟斤拷辖锟斤拷锟�',
  wave_json           LONGTEXT      DEFAULT NULL COMMENT '时锟斤拷锟斤拷JSON',
  spectrum_json       LONGTEXT      DEFAULT NULL COMMENT '频锟斤拷频锟斤拷JSON',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '锟斤拷锟斤拷时锟斤拷',
  PRIMARY KEY (id),
  KEY idx_batch_id (batch_id),
  KEY idx_device_code (device_code),
  KEY idx_device_channel_time (device_code, channel_id, create_time),
  KEY idx_create_time (create_time),
  CONSTRAINT fk_vibration_record_batch FOREIGN KEY (batch_id) REFERENCES vibration_analysis_batch (batch_id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='锟今动凤拷锟斤拷锟斤拷锟斤拷锟�';

-- =========================================
-- 3. 锟斤拷始锟斤拷锟斤拷锟捷ｏ拷锟斤拷史锟斤拷锟斤拷
-- =========================================
INSERT INTO vibration_analysis_batch
(batch_id, device_code, sample_rate, sample_count, collect_time, status, create_time, update_time, remark) VALUES
(10001, 'FAN-MAIN-001', 1000, 1024, '2026-05-01 08:00:00', 0, '2026-05-01 08:00:10', NULL, '锟斤拷锟斤拷巡锟斤拷锟斤拷锟斤拷'),
(10002, 'FAN-MAIN-001', 2000, 2048, '2026-05-01 10:00:00', 1, '2026-05-01 10:00:11', NULL, '锟斤拷微锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷'),
(10003, 'FAN-MAIN-002', 5000, 4096, '2026-05-01 12:00:00', 2, '2026-05-01 12:00:12', NULL, '锟斤拷锟侥ワ拷锟皆わ拷锟�'),
(10004, 'FAN-MAIN-003', 10000, 8192, '2026-05-02 09:30:00', 0, '2026-05-02 09:30:10', NULL, '锟斤拷准锟斤拷锟斤拷锟斤拷锟斤拷'),
(10005, 'FAN-MAIN-002', 2000, 2048, '2026-05-03 14:20:00', 1, '2026-05-03 14:20:08', NULL, '转锟接诧拷平锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷');

-- =========================================
-- 4. 锟斤拷始锟斤拷锟斤拷锟捷ｏ拷锟斤拷锟斤拷锟斤拷锟�
-- 锟斤拷锟斤拷锟斤拷频锟阶斤拷锟斤拷锟斤拷前锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷实业锟斤拷锟叫斤拷锟斤拷写锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷
-- =========================================
INSERT INTO vibration_analysis_record
(id, batch_id, device_code, channel_id, rms, peak, crest_factor, kurtosis, centroid_frequency, rms_frequency, diagnosis_result, wave_json, spectrum_json, create_time) VALUES
(20001, 10001, 'FAN-MAIN-001', 1, 0.1265, 0.4128, 3.2624, 3.1450, 49.8, 58.7, '状态锟斤拷锟斤拷',
 '[0.01,0.02,0.03,0.04,0.03,0.02,0.01]',
 '[0.01,0.06,0.12,0.05,0.03,0.02,0.01]',
 '2026-05-01 08:00:12'),

(20002, 10002, 'FAN-MAIN-001', 2, 0.1852, 0.7821, 4.2224, 4.2156, 50.2, 76.3, '锟斤拷锟狡讹拷锟叫诧拷锟斤拷锟斤拷锟斤拷锟斤拷锟揭伙拷锟斤拷锟斤拷',
 '[0.02,0.05,0.07,0.10,0.08,0.06,0.03]',
 '[0.02,0.10,0.18,0.08,0.05,0.03,0.02]',
 '2026-05-01 10:00:12'),

(20003, 10003, 'FAN-MAIN-002', 3, 0.2687, 1.4523, 5.4028, 5.8911, 49.9, 92.4, '锟斤拷锟斤拷锟斤拷锟侥ワ拷穑锟斤拷诔锟斤拷锟斤拷锟斤拷',
 '[0.03,0.07,0.14,0.21,0.18,0.11,0.05]',
 '[0.03,0.12,0.26,0.11,0.07,0.04,0.02]',
 '2026-05-01 12:00:13'),

(20004, 10004, 'FAN-MAIN-003', 4, 0.1104, 0.3312, 2.9991, 3.0254, 49.7, 55.2, '状态锟斤拷锟斤拷',
 '[0.01,0.01,0.02,0.03,0.02,0.02,0.01]',
 '[0.01,0.04,0.08,0.03,0.02,0.02,0.01]',
 '2026-05-02 09:30:12'),

(20005, 10005, 'FAN-MAIN-002', 5, 0.2015, 0.9156, 4.5432, 4.6820, 50.1, 81.6, '锟斤拷锟斤拷转锟接诧拷平锟解，锟斤拷瞬锟斤拷锟斤拷锟�',
 '[0.02,0.04,0.09,0.15,0.13,0.09,0.04]',
 '[0.02,0.11,0.20,0.09,0.06,0.03,0.02]',
 '2026-05-03 14:20:09');

SET FOREIGN_KEY_CHECKS = 1;
