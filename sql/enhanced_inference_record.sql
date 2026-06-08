-- 增强推理诊断结果记录表
-- 存储 enhanced_inference_service.py 中 map_result_to_frontend() 的完整输出
-- 数据库: ry-yue

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS enhanced_inference_record;

CREATE TABLE enhanced_inference_record (
  id                  BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  batch_id            BIGINT(20)    DEFAULT NULL              COMMENT '批次ID',
  device_code         VARCHAR(64)   DEFAULT NULL              COMMENT '设备编码',
  source_file         VARCHAR(255)  DEFAULT NULL              COMMENT '数据源文件名',
  analysis_mode       VARCHAR(32)   DEFAULT NULL              COMMENT '分析模式(v6_latest/v6_specified/v6_upload/v6_infer/v6_auto)',
  sample_rate         DOUBLE        DEFAULT NULL              COMMENT '采样频率(Hz)',

  -- 诊断核心结果
  diagnosis_result    VARCHAR(200)  DEFAULT NULL              COMMENT '最终诊断结果',
  closed_prediction   VARCHAR(200)  DEFAULT NULL              COMMENT '闭集预测结果',
  confidence          DECIMAL(6,2)  DEFAULT NULL              COMMENT '置信度(%)',
  health_index        INT(11)       DEFAULT NULL              COMMENT '健康指数(0-100)',
  risk_level          VARCHAR(16)   DEFAULT NULL              COMMENT '风险等级(低/中/高)',
  alarm_level         VARCHAR(32)   DEFAULT NULL              COMMENT '告警等级(normal/attention/warning/alarm)',
  diagnosis_detail    TEXT          DEFAULT NULL              COMMENT '诊断详情',
  decision_reason     TEXT          DEFAULT NULL              COMMENT '决策原因',

  -- 诊断中间指标
  unknown_ratio       DECIMAL(8,6)  DEFAULT NULL              COMMENT '未知样本比例',
  segment_consistency DECIMAL(8,6)  DEFAULT NULL              COMMENT '片段一致性',
  mean_mahalanobis    DECIMAL(12,6) DEFAULT NULL              COMMENT '平均马氏距离',
  mean_entropy        DECIMAL(12,6) DEFAULT NULL              COMMENT '平均熵值',

  -- 信号统计量
  rms                 DOUBLE        DEFAULT NULL              COMMENT '均方根值(RMS)',
  peak                DOUBLE        DEFAULT NULL              COMMENT '峰值',

  -- JSON 结构化数据
  top_probabilities   JSON          DEFAULT NULL              COMMENT 'Top-3故障类别概率分布',
  evidence            JSON          DEFAULT NULL              COMMENT '决策证据列表',

  -- 可视化数据
  wave_json           LONGTEXT      DEFAULT NULL              COMMENT '时域波形JSON数组',
  spectrum_json       LONGTEXT      DEFAULT NULL              COMMENT '频谱JSON数组',

  -- 时间与元数据
  sample_time         DATETIME      DEFAULT NULL              COMMENT '采样时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  remark              VARCHAR(500)  DEFAULT NULL              COMMENT '备注',

  PRIMARY KEY (id),
  KEY idx_batch_id (batch_id),
  KEY idx_device_code (device_code),
  KEY idx_create_time (create_time),
  KEY idx_device_time (device_code, create_time),
  KEY idx_diagnosis_result (diagnosis_result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='增强推理诊断结果记录表';

SET FOREIGN_KEY_CHECKS = 1;
