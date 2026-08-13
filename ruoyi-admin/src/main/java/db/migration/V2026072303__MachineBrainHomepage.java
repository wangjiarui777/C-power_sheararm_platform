package db.migration;

import java.sql.PreparedStatement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Hides the duplicate PHM machine-brain menu after the static homepage takes over that role. */
public class V2026072303__MachineBrainHomepage extends BaseJavaMigration
{
    @Override
    public void migrate(Context context) throws Exception
    {
        try (PreparedStatement statement = context.getConnection().prepareStatement(
            "UPDATE sys_menu SET visible='1' "
                + "WHERE menu_type='C' AND path='brain' AND component='phm/brain/index'"))
        {
            statement.executeUpdate();
        }
    }
}
