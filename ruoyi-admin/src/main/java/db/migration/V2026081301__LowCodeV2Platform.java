package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Creates the versioned low-code metadata store without changing legacy generator tables. */
public class V2026081301__LowCodeV2Platform extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement())
        {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lc_project (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  app_code VARCHAR(64) NOT NULL,
                  project_name VARCHAR(128) NOT NULL,
                  description VARCHAR(500) NULL,
                  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
                  draft_version_id BIGINT NULL,
                  active_version_id BIGINT NULL,
                  create_by VARCHAR(64) NOT NULL,
                  create_time DATETIME NOT NULL,
                  update_by VARCHAR(64) NULL,
                  update_time DATETIME NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_lc_project_app_code (app_code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='低代码项目'
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lc_version (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  project_id BIGINT NOT NULL,
                  version_no INT NOT NULL,
                  version_state VARCHAR(16) NOT NULL,
                  metadata_json LONGTEXT NOT NULL,
                  checksum CHAR(64) NOT NULL,
                  base_version_id BIGINT NULL,
                  validation_json LONGTEXT NULL,
                  create_by VARCHAR(64) NOT NULL,
                  create_time DATETIME NOT NULL,
                  publish_by VARCHAR(64) NULL,
                  publish_time DATETIME NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_lc_version_project_no (project_id, version_no),
                  KEY idx_lc_version_state (project_id, version_state)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='低代码不可变版本'
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lc_connector (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  connector_code VARCHAR(64) NOT NULL,
                  connector_name VARCHAR(128) NOT NULL,
                  connector_type VARCHAR(32) NOT NULL,
                  base_url VARCHAR(512) NULL,
                  allowed_paths VARCHAR(1000) NULL,
                  timeout_ms INT NOT NULL DEFAULT 5000,
                  retry_count INT NOT NULL DEFAULT 0,
                  auth_ref VARCHAR(256) NULL,
                  config_json LONGTEXT NULL,
                  status VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
                  create_by VARCHAR(64) NOT NULL,
                  create_time DATETIME NOT NULL,
                  update_by VARCHAR(64) NULL,
                  update_time DATETIME NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_lc_connector_code (connector_code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='低代码受控连接器'
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lc_publish_audit (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  project_id BIGINT NOT NULL,
                  from_version_id BIGINT NULL,
                  to_version_id BIGINT NOT NULL,
                  operation VARCHAR(16) NOT NULL,
                  operator VARCHAR(64) NOT NULL,
                  detail_json LONGTEXT NULL,
                  create_time DATETIME NOT NULL,
                  PRIMARY KEY (id),
                  KEY idx_lc_publish_project_time (project_id, create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='低代码发布审计'
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lc_action_log (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  app_code VARCHAR(64) NOT NULL,
                  action_code VARCHAR(64) NOT NULL,
                  idempotency_key VARCHAR(128) NULL,
                  status VARCHAR(16) NOT NULL,
                  duration_ms BIGINT NOT NULL,
                  request_digest CHAR(64) NULL,
                  response_json LONGTEXT NULL,
                  error_message VARCHAR(1000) NULL,
                  create_by VARCHAR(64) NOT NULL,
                  create_time DATETIME NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_lc_action_idempotency (app_code, action_code, idempotency_key),
                  KEY idx_lc_action_time (app_code, create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='低代码动作审计'
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lc_template (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  template_code VARCHAR(64) NOT NULL,
                  template_name VARCHAR(128) NOT NULL,
                  metadata_json LONGTEXT NOT NULL,
                  builtin TINYINT(1) NOT NULL DEFAULT 0,
                  create_time DATETIME NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_lc_template_code (template_code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='低代码预置模板'
                """);
        }
        seedTemplates(connection);
        seedMenu(connection);
    }

    private void seedTemplates(Connection connection) throws Exception
    {
        String generic = """
            {"schemaVersion":2,"preset":"generic-crud","dataSource":"mysql","model":{"table":"","primaryKey":"id","fields":[],"relations":[]},"pages":[{"code":"index","type":"crud","regions":["query","list","form","detail"]}],"rules":[],"actions":[],"permissions":{"dataScope":"NONE"}}
            """.trim();
        String sensor = """
            {"schemaVersion":2,"preset":"sensor-diagnosis","dataSource":"mysql","model":{"table":"phm_diagnosis_binding","primaryKey":"id","fields":[{"name":"id","type":"long","readOnly":true,"generated":"long"},{"name":"device_id","type":"entity","required":true},{"name":"device_code","type":"text","required":true,"query":true},{"name":"point_id","type":"entity","required":true},{"name":"channel_id","type":"entity","required":true},{"name":"model_type","type":"dict","required":true,"query":true},{"name":"model_version","type":"remote"},{"name":"input_mode","type":"dict","defaultValue":"ATTACHMENT"},{"name":"window_size","type":"number","defaultValue":2048},{"name":"stride","type":"number"},{"name":"trigger_policy","type":"dict","defaultValue":"MANUAL"},{"name":"min_confidence","type":"number"},{"name":"enabled","type":"switch","defaultValue":true},{"name":"create_time","type":"datetime","readOnly":true,"generated":"nowOnCreate","list":false},{"name":"update_time","type":"datetime","readOnly":true,"generated":"now","list":false}],"relations":[]},"pages":[{"code":"binding","type":"crud","regions":["query","list","form","detail","diagnosis"]}],"rules":[{"code":"confidence-range","effect":"VALIDATE","condition":{"op":"or","args":[{"op":"lt","args":[{"field":"min_confidence"},0]},{"op":"gt","args":[{"field":"min_confidence"},100]}]},"message":"最低置信度必须在0到100之间"}],"actions":[{"code":"runDiagnosis","label":"运行诊断","event":"MANUAL","handler":"sensor.diagnosis.run","inputFields":["attachmentId"]}],"permissions":{"dataScope":"NONE"}}
            """.trim();
        insertTemplate(connection, "generic-crud", "通用 CRUD", generic);
        insertTemplate(connection, "sensor-diagnosis", "测点诊断配置", sensor);
    }

    private void insertTemplate(Connection connection, String code, String name, String metadata) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT IGNORE INTO lc_template(template_code,template_name,metadata_json,builtin,create_time)
            VALUES(?,?,?,1,NOW())
            """))
        {
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, metadata);
            statement.executeUpdate();
        }
    }

    private void seedMenu(Connection connection) throws Exception
    {
        Long toolId = null;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE path='tool' AND menu_type='M' ORDER BY menu_id LIMIT 1");
             ResultSet result = statement.executeQuery())
        {
            if (result.next()) toolId = result.getLong(1);
        }
        if (toolId == null) return;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,
              menu_type,visible,status,perms,icon,create_by,create_time,remark)
            SELECT '低代码工作台',?,6,'lowcode','tool/lowcode/index','','LowCodeWorkbench',1,0,
              'C','0','0','tool:lowcode:design','build','admin',NOW(),'版本化低代码设计与发布'
            WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=? AND path='lowcode')
            """))
        {
            statement.setLong(1, toolId);
            statement.setLong(2, toolId);
            statement.executeUpdate();
        }
        Long menuId = null;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT menu_id FROM sys_menu WHERE parent_id=? AND path='lowcode' ORDER BY menu_id LIMIT 1"))
        {
            statement.setLong(1, toolId);
            try (ResultSet result = statement.executeQuery()) { if (result.next()) menuId = result.getLong(1); }
        }
        if (menuId != null)
        {
            String[][] permissions = {
                {"低代码设计", "tool:lowcode:design"}, {"低代码校验", "tool:lowcode:validate"},
                {"低代码发布", "tool:lowcode:publish"}, {"低代码回滚", "tool:lowcode:rollback"},
                {"连接器管理", "tool:lowcode:connector"}, {"运行时查询", "lowcode:runtime:query"},
                {"运行时新增", "lowcode:runtime:add"}, {"运行时修改", "lowcode:runtime:edit"},
                {"运行时删除", "lowcode:runtime:remove"}, {"运行时动作", "lowcode:runtime:action"}
            };
            for (int i = 0; i < permissions.length; i++) insertPermission(connection, menuId, i + 1, permissions[i][0], permissions[i][1]);
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
                SELECT role_id,? FROM sys_role_menu WHERE menu_id=?
                """))
            {
                statement.setLong(1, menuId); statement.setLong(2, toolId); statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
                SELECT parent_role.role_id,child.menu_id
                FROM sys_role_menu parent_role
                JOIN sys_menu child ON child.parent_id=? AND child.menu_type='F'
                WHERE parent_role.menu_id=?
                """))
            {
                statement.setLong(1, menuId); statement.setLong(2, menuId); statement.executeUpdate();
            }
        }
    }

    private void insertPermission(Connection connection, Long parentId, int order, String name, String permission) throws Exception
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,
              menu_type,visible,status,perms,icon,create_by,create_time,remark)
            SELECT ?,?,?,'','','','',1,0,'F','0','0',?, '#', 'admin',NOW(),'低代码权限'
            WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=? AND perms=?)
            """))
        {
            statement.setString(1, name); statement.setLong(2, parentId); statement.setInt(3, order);
            statement.setString(4, permission); statement.setLong(5, parentId); statement.setString(6, permission);
            statement.executeUpdate();
        }
    }
}
