package db.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds an explicit first-login password gate without destructive schema changes. */
public class V2026081302__BrowserSessionAndPasswordGate extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        boolean columnExists;
        try (PreparedStatement statement = context.getConnection().prepareStatement(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'sys_user' "
                + "AND column_name = 'must_change_password'"))
        {
            try (ResultSet resultSet = statement.executeQuery())
            {
                resultSet.next();
                columnExists = resultSet.getInt(1) > 0;
            }
        }

        try (Statement statement = context.getConnection().createStatement())
        {
            // MySQL does not support `ADD COLUMN IF NOT EXISTS`; inspect metadata first
            // so this migration remains idempotent across supported MySQL versions.
            if (!columnExists)
            {
                statement.execute("ALTER TABLE sys_user ADD COLUMN must_change_password "
                    + "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '强制改密' AFTER pwd_update_date");
            }
            statement.execute("UPDATE sys_user SET must_change_password=1 WHERE pwd_update_date IS NULL");
            statement.execute("UPDATE sys_user SET must_change_password=1 WHERE password='$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2'");
        }
    }
}
