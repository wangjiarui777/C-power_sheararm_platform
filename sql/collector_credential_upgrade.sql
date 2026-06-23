SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS `sensor_collector_credential` (
  `id` bigint NOT NULL,
  `collector_id` varchar(64) NOT NULL,
  `collector_name` varchar(128) DEFAULT NULL,
  `encrypted_secret` varchar(512) NOT NULL,
  `secret_hash` char(64) NOT NULL,
  `allowed_devices` text NOT NULL COMMENT 'Comma separated device codes or *',
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `expire_time` datetime DEFAULT NULL,
  `last_online_time` datetime DEFAULT NULL,
  `last_ip` varchar(64) DEFAULT NULL,
  `created_by` varchar(64) NOT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  `remark` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_collector_id` (`collector_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-gateway acquisition credentials';
