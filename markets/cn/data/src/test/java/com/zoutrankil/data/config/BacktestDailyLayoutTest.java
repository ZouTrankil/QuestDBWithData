package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.derived.storage.BacktestDailyViewReadRepository;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.data.domain.derived.BacktestDailyViewDataset;
import com.zoutrankil.data.domain.derived.BacktestDailyViewValue;
import com.zoutrankil.data.repository.BacktestDailyMaterializationReadGuard;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

class BacktestDailyLayoutTest {
    @TempDir Path root;

    private StandardEnvironment environment(Map<String, Object> values) {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("fixture", values));
        return env;
    }

    @Test
    void oldDefinitionAndSqlRemainExactAndWriterSwitchDoesNotRebindTheOldReader() {
        var scope =
                BacktestDailyLayoutConfiguration.resolve(
                        environment(
                                Map.of(
                                        "app.sync.stk-suspend-schema-version", 2,
                                        "app.sync.stk-st-daily-schema-version", 2)));
        assertSame(BacktestDailyScope.LEGACY, scope);
        assertSame(BacktestDailyViewDataset.DEFINITION, scope.definition());
        assertEquals(
                BacktestDailyMaterializedViewPort.CREATE_SQL,
                BacktestDailyMaterializedViewPort.createSql(scope));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BacktestDailyLayoutConfiguration.resolve(
                                environment(
                                        Map.of(
                                                "app.materialized-views.backtest.suspend-layout-version",
                                                2))));
    }

    @Test
    void allFourLayoutsRetainTheExactAlgorithmAndCompleteReadBinding() {
        for (int s : List.of(1, 2))
            for (int t : List.of(1, 2)) {
                var scope =
                        new BacktestDailyScope(
                                "java_d011_stk_suspend_case",
                                s,
                                "java_d012_stk_st_daily_case",
                                t,
                                "java_backtest_v2_case");
                assertEquals(scope, BacktestDailyScope.fromDefinition(scope.definition()));
                assertEquals(
                        BacktestDailyViewDataset.DEFINITION.businessKey(),
                        scope.definition().businessKey());
                assertEquals(
                        BacktestDailyViewDataset.DEFINITION.dependencies(),
                        scope.definition().dependencies());
                String sql = BacktestDailyMaterializedViewPort.createSql(scope);
                assertTrue(sql.contains("x.trade_date=s." + scope.suspendDate()));
                assertTrue(sql.contains("x.trade_date=st." + scope.stDate()));
                assertTrue(sql.contains("s." + scope.suspendDate() + " IS NOT NULL"));
                // 把本协议的名称和日期列逆映射后，SQL 必须与原件逐字相同。
                assertEquals(
                        BacktestDailyMaterializedViewPort.CREATE_SQL,
                        sql.replace(scope.outputTable(), "mv_backtest_daily")
                                .replace(scope.suspendTable(), "stk_suspend")
                                .replace(scope.stTable(), "stk_st_daily")
                                .replace("s." + scope.suspendDate(), "s.timestamp")
                                .replace("st." + scope.stDate(), "st.timestamp"));
                var backend = mock(QuestDbBoundedReader.class);
                var repository = new BacktestDailyViewReadRepository(backend, scope);
                var catalog =
                        new ReadBindingCatalog(
                                DefaultReadBindings.registrations(1, 1, 1, 1, 1, 1, 1, 2));
                assertEquals(
                        BacktestDailyViewValue.class,
                        catalog.registrations().stream()
                                .filter(r -> r.datasetId().equals(scope.definition().datasetId()))
                                .findFirst()
                                .orElseThrow()
                                .rowType());
                assertEquals(scope.definition(), repository.definition());
                verifyNoInteractions(backend);
            }
    }

    @Test
    void explicitReadConfigurationSelectsMixedLayoutWithoutQueryingOrCreatingAnything() {
        var scope =
                BacktestDailyLayoutConfiguration.resolve(
                        environment(
                                Map.of(
                                        "app.materialized-views.backtest.layout-version", 2,
                                        "app.materialized-views.backtest.suspend-layout-version",
                                                2)));
        assertEquals(2, scope.suspendVersion());
        assertEquals(1, scope.stVersion());
        assertEquals("stk_suspend", scope.suspendTable());
        assertEquals("java_backtest_v2_current", scope.outputTable());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BacktestDailyScope(
                                "stk_suspend", 2, "stk_st_daily", 1, "mv_backtest_daily"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BacktestDailyScope(
                                "other_table", 1, "stk_st_daily", 1, "java_backtest_v2_x"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BacktestDailyScope(
                                "stk_suspend", 3, "stk_st_daily", 1, "java_backtest_v2_x"));
    }

    @Test
    void relabeledGenericDefinitionCannotBypassTheGuardWithAnotherObjectName() {
        var old = BacktestDailyViewDataset.DEFINITION;
        var forged =
                new DatasetDefinition(
                        old.datasetId(),
                        old.schemaVersion(),
                        old.provider(),
                        old.owner(),
                        "other_mv",
                        old.objectKind(),
                        old.columns(),
                        old.businessKey(),
                        old.dedupKey(),
                        old.designatedTimestamp(),
                        old.partition(),
                        old.wal(),
                        old.capabilities(),
                        old.dependencies(),
                        old.storageRationale());
        var jdbc = mock(JdbcTemplate.class);
        assertTrue(BacktestDailyMaterializationReadGuard.applies(forged));
        assertThrows(
                IllegalArgumentException.class,
                () -> BacktestDailyMaterializationReadGuard.version(jdbc, forged));
        verifyNoInteractions(jdbc);
    }

    @Test
    void wholeApplicationBindsTheSelectedMvWithoutOpeningTheDatabaseOrLedger() throws Exception {
        var dataSource = mock(DataSource.class);
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        var ledger = root.resolve("not-created.sqlite");
        application.addInitializers(
                context -> {
                    context.getBeanFactory().registerSingleton("dataSource", dataSource);
                    context.getEnvironment()
                            .getPropertySources()
                            .addFirst(
                                    new MapPropertySource(
                                            "backtest-layout",
                                            Map.of(
                                                    "app.materialized-views.backtest.layout-version",
                                                    2,
                                                    "app.materialized-views.backtest.suspend-layout-version",
                                                    2,
                                                    "app.sync.ledger-path",
                                                    ledger.toString(),
                                                    "spring.main.banner-mode",
                                                    "off")));
                });
        try (var context =
                application.run(
                        "plan-sync-job",
                        "--job",
                        "data.stock_basic",
                        "--version",
                        "2",
                        "--logical-date",
                        "2026-10-07",
                        "--parameters",
                        "{\"codes\":[\"000001.SZ\"]}")) {
            var scope = context.getBean(BacktestDailyScope.class);
            assertEquals(2, scope.suspendVersion());
            assertEquals(1, scope.stVersion());
            var repository = context.getBean(BacktestDailyViewReadRepository.class);
            assertEquals(scope.definition(), repository.definition());
            assertFalse(
                    context.getBean(ReadBindingCatalog.class)
                            .bind(context.getBean(DatasetRegistry.class))
                            .isEmpty());
            verifyNoInteractions(dataSource);
            assertFalse(Files.exists(ledger));
        }
    }
}
