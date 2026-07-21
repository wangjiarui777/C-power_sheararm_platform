-- Industrial monitoring / PHM dynamic menu migration.
-- Safe to execute repeatedly on an existing RuoYi database.

START TRANSACTION;

-- Remove obsolete duplicates that previously mirrored frontend constant routes.
CREATE TEMPORARY TABLE tmp_sensor_duplicate_menus AS
SELECT m.menu_id
FROM sys_menu m
JOIN (
    SELECT parent_id, path, COALESCE(component, '') component, MIN(menu_id) keep_id
    FROM sys_menu
    WHERE path IN ('monitoring-center', 'analysis-toolkit', 'phm', 'index', 'vibration',
                   'temperature', 'bearing-diagnosis', 'cluster', 'alarms', 'events',
                   'reports', 'config', 'brain')
    GROUP BY parent_id, path, COALESCE(component, '')
    HAVING COUNT(*) > 1
) d ON d.parent_id = m.parent_id
   AND d.path = m.path
   AND d.component = COALESCE(m.component, '')
   AND m.menu_id <> d.keep_id;

DELETE FROM sys_role_menu
WHERE menu_id IN (SELECT menu_id FROM tmp_sensor_duplicate_menus);
DELETE FROM sys_menu
WHERE menu_id IN (SELECT menu_id FROM tmp_sensor_duplicate_menus);
DROP TEMPORARY TABLE tmp_sensor_duplicate_menus;

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame,
     is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '监测与数据', 0, 4, 'monitoring-center', NULL, '', 'MonitoringCenter', 1,
       0, 'M', '0', '0', '', 'chart', 'admin', NOW(), '工业监测动态菜单'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 0 AND path = 'monitoring-center');
SET @monitor_menu = (SELECT MIN(menu_id) FROM sys_menu WHERE parent_id = 0 AND path = 'monitoring-center');

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame,
     is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '实时监测', @monitor_menu, 1, 'index', 'monitoring-center/index', '', 'MonitoringCenterIndex',
       1, 0, 'C', '0', '0', 'sensor:monitoring:view', 'dashboard', 'admin', NOW(), '实时监测工作台'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = @monitor_menu AND path = 'index');
SELECT menu_id INTO @monitor_index FROM sys_menu WHERE parent_id = @monitor_menu AND path = 'index' LIMIT 1;

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame,
     is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '振动分析', @monitor_menu, 2, 'vibration', 'system/vibration/index', '', 'VibrationData',
       1, 0, 'C', '0', '0', 'sensor:vibration:list', 'chart', 'admin', NOW(), '振动数据与分析'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = @monitor_menu AND path = 'vibration');
SELECT menu_id INTO @vibration_menu FROM sys_menu WHERE parent_id = @monitor_menu AND path = 'vibration' LIMIT 1;

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame,
     is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '温度分析', @monitor_menu, 3, 'temperature', 'system/temperature/index', '', 'TemperatureData',
       1, 0, 'C', '0', '0', 'sensor:temperature:list', 'chart', 'admin', NOW(), '温度数据与分析'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = @monitor_menu AND path = 'temperature');
SELECT menu_id INTO @temperature_menu FROM sys_menu WHERE parent_id = @monitor_menu AND path = 'temperature' LIMIT 1;

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame,
     is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '诊断分析', 0, 5, 'analysis-toolkit', NULL, '', 'AnalysisToolkit', 1,
       0, 'M', '0', '0', '', 'chart', 'admin', NOW(), '故障诊断动态菜单'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 0 AND path = 'analysis-toolkit');
SET @diagnosis_parent = (SELECT MIN(menu_id) FROM sys_menu WHERE parent_id = 0 AND path = 'analysis-toolkit');

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame,
     is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '诊断分析', @diagnosis_parent, 1, 'bearing-diagnosis', 'monitor/diagnosis/index', '', 'BearingDiagnosis',
       1, 0, 'C', '0', '0', 'sensor:diagnosis:view', 'chart', 'admin', NOW(), '轴承与齿轮诊断'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = @diagnosis_parent AND path = 'bearing-diagnosis');
SELECT menu_id INTO @diagnosis_menu FROM sys_menu WHERE parent_id = @diagnosis_parent AND path = 'bearing-diagnosis' LIMIT 1;

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame,
     is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 'PHM中心', 0, 6, 'phm', NULL, '', 'PhmCenter', 1,
       0, 'M', '0', '0', '', 'monitor', 'admin', NOW(), 'PHM动态菜单'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 0 AND path = 'phm');
SET @phm_parent = (SELECT MIN(menu_id) FROM sys_menu WHERE parent_id = 0 AND path = 'phm');

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, query, route_name, is_frame,
     is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT x.menu_name, @phm_parent, x.order_num, x.path, x.component, '', x.route_name,
       1, 0, 'C', '0', '0', x.perms, x.icon, 'admin', NOW(), x.remark
FROM (
    SELECT '设备集群' menu_name, 1 order_num, 'cluster' path, 'phm/cluster/index' component,
           'PhmCluster' route_name, 'phm:device:list' perms, 'dashboard' icon, 'PHM设备管理' remark
    UNION ALL SELECT '机器大脑', 2, 'brain', 'phm/brain/index', 'PhmBrain',
           'phm:device:query', 'component', 'PHM设备级健康与诊断工作台'
    UNION ALL SELECT '告警中心', 3, 'alarms', 'phm/alarms/index', 'PhmAlarms',
           'phm:alarm:list', 'message', 'PHM告警闭环'
    UNION ALL SELECT '设备大事记', 4, 'events', 'phm/events/index', 'PhmEvents',
           'phm:event:list', 'time', 'PHM设备事件'
    UNION ALL SELECT '报表中心', 5, 'reports', 'phm/reports/index', 'PhmReports',
           'phm:report:view', 'documentation', 'PHM报表'
    UNION ALL SELECT '配置管理', 6, 'config', 'phm/config/index', 'PhmConfig',
           'phm:config:list', 'edit', 'PHM配置'
) x
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu m WHERE m.parent_id = @phm_parent AND m.path = x.path
);

UPDATE sys_menu
SET order_num = CASE path
    WHEN 'cluster' THEN 1 WHEN 'brain' THEN 2 WHEN 'alarms' THEN 3
    WHEN 'events' THEN 4 WHEN 'reports' THEN 5 WHEN 'config' THEN 6
    ELSE order_num END
WHERE parent_id = @phm_parent
  AND path IN ('cluster','brain','alarms','events','reports','config');

SELECT menu_id INTO @phm_device_menu FROM sys_menu WHERE parent_id = @phm_parent AND path = 'cluster' LIMIT 1;
SELECT menu_id INTO @phm_alarm_menu FROM sys_menu WHERE parent_id = @phm_parent AND path = 'alarms' LIMIT 1;
SELECT menu_id INTO @phm_event_menu FROM sys_menu WHERE parent_id = @phm_parent AND path = 'events' LIMIT 1;
SELECT menu_id INTO @phm_report_menu FROM sys_menu WHERE parent_id = @phm_parent AND path = 'reports' LIMIT 1;
SELECT menu_id INTO @phm_config_menu FROM sys_menu WHERE parent_id = @phm_parent AND path = 'config' LIMIT 1;
SELECT menu_id INTO @phm_brain_menu FROM sys_menu WHERE parent_id = @phm_parent AND path = 'brain' LIMIT 1;

-- Button permissions. The NOT EXISTS predicate makes every row idempotent.
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT p.menu_name,p.parent_id,p.order_num,'#','', '', '',1,0,'F','0','0',p.perms,'#','admin',NOW(),'业务操作权限'
FROM (
    SELECT '振动查询' menu_name,@vibration_menu parent_id,1 order_num,'sensor:vibration:query' perms
    UNION ALL SELECT '振动新增',@vibration_menu,2,'sensor:vibration:add'
    UNION ALL SELECT '振动修改',@vibration_menu,3,'sensor:vibration:edit'
    UNION ALL SELECT '振动删除',@vibration_menu,4,'sensor:vibration:remove'
    UNION ALL SELECT '振动导出',@vibration_menu,5,'sensor:vibration:export'
    UNION ALL SELECT '温度查询',@temperature_menu,1,'sensor:temperature:query'
    UNION ALL SELECT '温度新增',@temperature_menu,2,'sensor:temperature:add'
    UNION ALL SELECT '温度修改',@temperature_menu,3,'sensor:temperature:edit'
    UNION ALL SELECT '温度删除',@temperature_menu,4,'sensor:temperature:remove'
    UNION ALL SELECT '温度导出',@temperature_menu,5,'sensor:temperature:export'
    UNION ALL SELECT '执行诊断',@diagnosis_menu,1,'sensor:diagnosis:run'
    UNION ALL SELECT '设备查询',@phm_device_menu,1,'phm:device:query'
    UNION ALL SELECT '设备新增',@phm_device_menu,2,'phm:device:add'
    UNION ALL SELECT '设备修改',@phm_device_menu,3,'phm:device:edit'
    UNION ALL SELECT '设备删除',@phm_device_menu,4,'phm:device:remove'
    UNION ALL SELECT '告警查询',@phm_alarm_menu,1,'phm:alarm:query'
    UNION ALL SELECT '告警处置',@phm_alarm_menu,2,'phm:alarm:handle'
    UNION ALL SELECT '事件新增',@phm_event_menu,1,'phm:event:add'
    UNION ALL SELECT '事件修改',@phm_event_menu,2,'phm:event:edit'
    UNION ALL SELECT '事件删除',@phm_event_menu,3,'phm:event:remove'
    UNION ALL SELECT '报表导出',@phm_report_menu,1,'phm:report:export'
    UNION ALL SELECT '报表维护',@phm_report_menu,2,'phm:report:edit'
    UNION ALL SELECT '配置新增',@phm_config_menu,1,'phm:config:add'
    UNION ALL SELECT '配置修改',@phm_config_menu,2,'phm:config:edit'
    UNION ALL SELECT '配置删除',@phm_config_menu,3,'phm:config:remove'
) p
WHERE p.parent_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.parent_id = p.parent_id AND m.perms = p.perms);

-- Existing roles that can already view/query PHM devices receive the new page.
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, @phm_brain_menu
FROM sys_role_menu rm
WHERE @phm_brain_menu IS NOT NULL
  AND rm.menu_id IN (@phm_parent, @phm_device_menu)
  AND rm.role_id IS NOT NULL;

COMMIT;

-- Duplicate validation: this query must return zero rows after migration.
SELECT parent_id, path, route_name, COALESCE(component, '') component, menu_type, COUNT(*) duplicate_count
FROM sys_menu
WHERE menu_type <> 'F'
GROUP BY parent_id, path, route_name, COALESCE(component, ''), menu_type
HAVING COUNT(*) > 1;
