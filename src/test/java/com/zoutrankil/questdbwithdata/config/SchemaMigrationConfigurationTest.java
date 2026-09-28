package com.zoutrankil.questdbwithdata.config;

import javax.sql.DataSource;
import org.flywaydb.core.internal.database.DatabaseType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchemaMigrationConfigurationTest {
    @Test
    void loadsQuestDbPluginAndPackagedMigrationsWithoutOpeningDatabase() {
        DataSource dataSource = mock(DataSource.class);
        var flyway = new SchemaMigrationConfiguration().questDbFlyway(dataSource,
                new SchemaMigrationProperties("classpath:db/migration/questdb",
                        "java_tushare_schema_history"));
        var configuration = flyway.getConfiguration();
        assertTrue(configuration.getPluginRegister().getPlugins(DatabaseType.class).stream()
                .anyMatch(type -> "QuestDB".equals(type.getName())
                        && type.handlesJDBCUrl("jdbc:postgresql://localhost:8812/qdb")));
        assertTrue(configuration.isValidateOnMigrate());
        assertTrue(configuration.isCleanDisabled());
        assertFalse(configuration.isBaselineOnMigrate());
        assertFalse(configuration.isExecuteInTransaction());
        assertNotNull(getClass().getResource(
                "/db/migration/questdb/V1__create_stock_basic_table.sql"));
        assertNotNull(getClass().getResource(
                "/db/migration/questdb/V2__create_stock_basic_latest_view.sql"));
        verifyNoInteractions(dataSource);
    }
}
