-- 清理侧边栏冗余业务菜单
-- 背景：监测与数据、诊断分析已由 ruoyi-ui/src/router/index.js 的 constantRoutes 管理；
--      数据库中旧的“监测中心”“专业分析工具包”动态菜单会造成侧边栏重复。

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
