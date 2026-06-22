-- Roll back only the industrial monitoring / diagnosis / PHM menus installed by
-- sensor_module_menu_migration.sql. Business tables and collected data are untouched.

START TRANSACTION;

CREATE TEMPORARY TABLE tmp_sensor_menu_ids (menu_id BIGINT PRIMARY KEY);

INSERT IGNORE INTO tmp_sensor_menu_ids
SELECT menu_id
FROM sys_menu
WHERE parent_id = 0
  AND path IN ('monitoring-center', 'analysis-toolkit', 'phm')
  AND remark IN ('工业监测动态菜单', '故障诊断动态菜单', 'PHM动态菜单');

INSERT IGNORE INTO tmp_sensor_menu_ids
SELECT child.menu_id
FROM sys_menu child
JOIN sys_menu root ON root.menu_id = child.parent_id
WHERE root.parent_id = 0
  AND root.path IN ('monitoring-center', 'analysis-toolkit', 'phm')
  AND root.remark IN ('工业监测动态菜单', '故障诊断动态菜单', 'PHM动态菜单');

INSERT IGNORE INTO tmp_sensor_menu_ids
SELECT button.menu_id
FROM sys_menu button
JOIN sys_menu child ON child.menu_id = button.parent_id
JOIN sys_menu root ON root.menu_id = child.parent_id
WHERE root.parent_id = 0
  AND root.path IN ('monitoring-center', 'analysis-toolkit', 'phm')
  AND root.remark IN ('工业监测动态菜单', '故障诊断动态菜单', 'PHM动态菜单');

DELETE FROM sys_role_menu
WHERE menu_id IN (SELECT menu_id FROM tmp_sensor_menu_ids);

DELETE FROM sys_menu
WHERE menu_id IN (SELECT menu_id FROM tmp_sensor_menu_ids);

DROP TEMPORARY TABLE tmp_sensor_menu_ids;

COMMIT;
