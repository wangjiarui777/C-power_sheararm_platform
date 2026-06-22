-- Deprecated compatibility cleanup.
-- New installations and upgrades should execute sensor_module_menu_migration.sql,
-- because industrial monitoring, diagnosis and PHM menus are now backend-managed.

START TRANSACTION;

CREATE TEMPORARY TABLE tmp_redundant_menu_ids AS
SELECT menu_id
FROM sys_menu
WHERE menu_name IN (
  '监测中心',
  '专业分析工具包',
  '总览监测',
  '振动数据',
  '温度数据',
  '8路实时监控',
  '振动分析',
  '历史批次'
)
OR path IN (
  'monitoring-center',
  'analysis-toolkit',
  'overview',
  'vibration',
  'temperature',
  'multi-channel',
  'bearing-diagnosis',
  'python-sidecar'
)
OR component IN (
  'monitoring-center/index',
  'system/vibration/index',
  'system/temperature/index',
  'system/vibration/multiChannelIndex',
  'monitor/diagnosis/index',
  'system/vibration/analysis'
);

DELETE FROM sys_role_menu
WHERE menu_id IN (SELECT menu_id FROM tmp_redundant_menu_ids);

DELETE FROM sys_menu
WHERE menu_id IN (SELECT menu_id FROM tmp_redundant_menu_ids);

DROP TEMPORARY TABLE tmp_redundant_menu_ids;

COMMIT;
