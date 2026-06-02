-- ���ȷ���񶯷�����ʷ���α�
-- ���������� RuoYi-Vue ��Ŀ���������α�����������
-- ����ʹ�� UTF-8 ���뱣��

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS vibration_analysis_record;
DROP TABLE IF EXISTS vibration_analysis_batch;

-- =========================================
-- 1. ��ʷ�ɼ����α�
-- =========================================
CREATE TABLE vibration_analysis_batch (
  batch_id      BIGINT(20)   NOT NULL COMMENT '����ID',
  device_code   VARCHAR(64)  NOT NULL COMMENT '�豸���',
  sample_rate   DOUBLE       NOT NULL COMMENT '����Ƶ��(Hz)',
  sample_count  INT(11)      NOT NULL COMMENT '��������',
  collect_time  DATETIME     NOT NULL COMMENT '�ɼ�ʱ��',
  status        TINYINT(4)   NOT NULL DEFAULT 0 COMMENT '״̬��0���� 1Ԥ�� 2����',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '����ʱ��',
  update_time   DATETIME     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '����ʱ��',
  remark        VARCHAR(500) NULL DEFAULT NULL COMMENT '��ע',
  PRIMARY KEY (batch_id),
  KEY idx_device_code (device_code),
  KEY idx_collect_time (collect_time),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='�񶯷�����ʷ�ɼ����α�';

-- =========================================
-- 2. �񶯷��������
-- =========================================
CREATE TABLE vibration_analysis_record (
  id                  BIGINT(20)    NOT NULL COMMENT '���ID',
  batch_id            BIGINT(20)    NOT NULL COMMENT '����ID',
  device_code         VARCHAR(64)   NOT NULL COMMENT '�豸���',
  channel_id          INT(11)       NOT NULL DEFAULT 1 COMMENT 'ͨ������(1-8)',
  rms                 DOUBLE        DEFAULT NULL COMMENT '������ֵ',
  peak                DOUBLE        DEFAULT NULL COMMENT '��ֵ',
  crest_factor        DOUBLE        DEFAULT NULL COMMENT '��ֵ����',
  kurtosis            DOUBLE        DEFAULT NULL COMMENT '�Ͷ�',
  centroid_frequency  DOUBLE        DEFAULT NULL COMMENT '����Ƶ��',
  rms_frequency       DOUBLE        DEFAULT NULL COMMENT '������Ƶ��',
  diagnosis_result    VARCHAR(200)  DEFAULT NULL COMMENT '��Ͻ���',
  wave_json           LONGTEXT      DEFAULT NULL COMMENT 'ʱ����JSON',
  spectrum_json       LONGTEXT      DEFAULT NULL COMMENT 'Ƶ��Ƶ��JSON',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '����ʱ��',
  PRIMARY KEY (id),
  KEY idx_batch_id (batch_id),
  KEY idx_device_code (device_code),
  KEY idx_device_channel_time (device_code, channel_id, create_time),
  KEY idx_create_time (create_time),
  CONSTRAINT fk_vibration_record_batch FOREIGN KEY (batch_id) REFERENCES vibration_analysis_batch (batch_id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='�񶯷��������';

-- =========================================
-- 3. ��ʼ�����ݣ���ʷ����
-- =========================================
INSERT INTO vibration_analysis_batch
(batch_id, device_code, sample_rate, sample_count, collect_time, status, create_time, update_time, remark) VALUES
(10001, 'FAN-MAIN-001', 1000, 1024, '2026-05-01 08:00:00', 0, '2026-05-01 08:00:10', NULL, '����Ѳ������'),
(10002, 'FAN-MAIN-001', 2000, 2048, '2026-05-01 10:00:00', 1, '2026-05-01 10:00:11', NULL, '��΢����������'),
(10003, 'FAN-MAIN-002', 5000, 4096, '2026-05-01 12:00:00', 2, '2026-05-01 12:00:12', NULL, '���ĥ��Ԥ��'),
(10004, 'FAN-MAIN-003', 10000, 8192, '2026-05-02 09:30:00', 0, '2026-05-02 09:30:10', NULL, '��׼��������'),
(10005, 'FAN-MAIN-002', 2000, 2048, '2026-05-03 14:20:00', 1, '2026-05-03 14:20:08', NULL, 'ת�Ӳ�ƽ����������');

-- =========================================
-- 4. ��ʼ�����ݣ��������
-- ������Ƶ�׽�����ǰ����������ʵҵ���н���д��������������
-- =========================================
INSERT INTO vibration_analysis_record
(id, batch_id, device_code, channel_id, rms, peak, crest_factor, kurtosis, centroid_frequency, rms_frequency, diagnosis_result, wave_json, spectrum_json, create_time) VALUES
(20001, 10001, 'FAN-MAIN-001', 1, 0.1265, 0.4128, 3.2624, 3.1450, 49.8, 58.7, '״̬����',
 '[0.01,0.02,0.03,0.04,0.03,0.02,0.01]',
 '[0.01,0.06,0.12,0.05,0.03,0.02,0.01]',
 '2026-05-01 08:00:12'),

(20002, 10002, 'FAN-MAIN-001', 2, 0.1852, 0.7821, 4.2224, 4.2156, 50.2, 76.3, '���ƶ��в����������һ�����',
 '[0.02,0.05,0.07,0.10,0.08,0.06,0.03]',
 '[0.02,0.10,0.18,0.08,0.05,0.03,0.02]',
 '2026-05-01 10:00:12'),

(20003, 10003, 'FAN-MAIN-002', 3, 0.2687, 1.4523, 5.4028, 5.8911, 49.9, 92.4, '�������ĥ�𣬴��ڳ������',
 '[0.03,0.07,0.14,0.21,0.18,0.11,0.05]',
 '[0.03,0.12,0.26,0.11,0.07,0.04,0.02]',
 '2026-05-01 12:00:13'),

(20004, 10004, 'FAN-MAIN-003', 4, 0.1104, 0.3312, 2.9991, 3.0254, 49.7, 55.2, '״̬����',
 '[0.01,0.01,0.02,0.03,0.02,0.02,0.01]',
 '[0.01,0.04,0.08,0.03,0.02,0.02,0.01]',
 '2026-05-02 09:30:12'),

(20005, 10005, 'FAN-MAIN-002', 5, 0.2015, 0.9156, 4.5432, 4.6820, 50.1, 81.6, '����ת�Ӳ�ƽ�⣬��˲�����',
 '[0.02,0.04,0.09,0.15,0.13,0.09,0.04]',
 '[0.02,0.11,0.20,0.09,0.06,0.03,0.02]',
 '2026-05-03 14:20:09');

SET FOREIGN_KEY_CHECKS = 1;
