# 数据库脚本分层

生产环境由 Flyway 记录版本状态。现有库首次上线时使用
`baseline-version=2026041700`，随后执行 Java 迁移
`V2026062301__ProductionHardening`。若缺少 `sys_user` 基线表，迁移会拒绝
启动，防止得到只有业务增量表的半成品数据库。

## 全新安装基线

- `ry_20260417.sql`：若依系统库基线。
- `vibration_data.sql`、`temperature_data.sql`、`vibration_analysis.sql`、`enhanced_inference_record.sql`：历史空库基线，禁止用于生产升级。
- `phm_platform.sql`：PHM 业务表。该文件包含 `DROP TABLE`，仅允许空库安装使用。
- `sensor_monitoring_module.sql`：监测模块基线补充。

## 现有数据库无损升级

生产环境不再手工串行执行这些脚本。应用启动前由 Flyway 按版本执行
`ruoyi-admin/src/main/resources/db/migration`，现有数据库须先完成结构预检并 baseline。
历史 SQL 只用于溯源或空库开发安装。
7. `attachment_security_upgrade.sql`
8. `telemetry_stream_upgrade.sql`
9. `collector_credential_upgrade.sql`

两个脚本均可重复执行。升级脚本不会删除业务表；发现测点通道、设备编码或收藏记录存在重复数据时，会保留数据并跳过对应唯一索引，需先处理脚本末尾校验结果后再执行一次。

## 回滚与数据修复

- `sensor_module_menu_rollback.sql`：只移除本模块迁移创建的菜单和角色菜单关系，不删除业务数据。
- 活动告警去重不会删除记录：保留最新记录为活动状态，其余记录转为已恢复并写入迁移说明。

生产执行前应完成数据库备份；先在数据库副本连续执行两次升级脚本，确认第二次无错误、无重复菜单，再在正式库执行。
