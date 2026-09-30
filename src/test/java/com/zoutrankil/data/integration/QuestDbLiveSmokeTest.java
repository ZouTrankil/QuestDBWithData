package com.zoutrankil.data.integration;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.StockBasic;
import com.zoutrankil.data.repository.QuestDbStockBasicRepository;
import com.zoutrankil.data.service.StockBasicSyncService;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: contacts configured Tushare/QuestDB and cleans only owned test objects. */
@EnabledIfEnvironmentVariable(named = "QUESTDB_LIVE_SMOKE", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuestDbLiveSmokeTest {
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private QuestDB qdb;

    @BeforeAll
    void connect() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        // Run each service independently so a JDBC failure does not hide source API results.
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(factory ->
                ((org.springframework.beans.factory.support.BeanDefinitionRegistry) factory)
                        .removeBeanDefinition("commandLineRunner")));
        context = app.run();
        jdbc = context.getBean(JdbcTemplate.class);
        jdbc.setQueryTimeout(15);
    }

    @AfterAll
    void close() {
        if (context != null) context.close();
    }

    @Test
    void sourceSyncToCsv() throws Exception {
        Path output = Path.of("var/live-smoke/stock_basic.csv");
        int count = context.getBean(StockBasicSyncService.class).syncToCsv(output);
        assertTrue(count > 0, "Tushare must return a nonempty current universe");
        assertTrue(Files.size(output) > 0);
        System.out.println("SMOKE sourceSyncToCsv rows=" + count);
    }

    @Test
    void applicationMigrationAndSync() throws Exception {
        String table = QuestDbStockBasicRepository.TABLE;
        String view = QuestDbStockBasicRepository.LATEST_VIEW;
        String history = "java_smoke_history_" + UUID.randomUUID().toString().replace("-", "");
        // The application repository has fixed names. Never overwrite existing objects.
        Assumptions.assumeFalse(exists(table) || viewExists(view),
                "Application test objects already exist; preserve their data");
        var dataSource = context.getBean(javax.sql.DataSource.class);
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/questdb").table(history)
                .validateOnMigrate(true).baselineOnMigrate(false).cleanDisabled(true)
                .executeInTransaction(false).load();
        boolean migrated = false;
        try {
            assertEquals(2, flyway.migrate().migrationsExecuted);
            migrated = true;
            assertEquals(0, flyway.migrate().migrationsExecuted);
            var repo = context.getBean(QuestDbStockBasicRepository.class);
            StockBasic original = new StockBasic("SMOKE.TEST", "SMOKE", "before",
                    "test", "test", LocalDate.of(2020, 1, 2));
            repo.storeAndVerify(List.of(original));
            await(() -> repo.findLatest().stream().anyMatch(r -> r.name().equals("before")));
            StockBasic corrected = new StockBasic("SMOKE.TEST", "SMOKE", "after",
                    "test", "test", null);
            repo.storeAndVerify(List.of(corrected));
            await(() -> repo.findLatest().stream().anyMatch(r -> r.name().equals("after")
                    && r.listDate() == null));
            assertEquals(1, repo.findLatest().size());
            System.out.println("SMOKE application migration/repeat/write/update/null/latest PASS");
            jdbc.execute("TRUNCATE TABLE " + table);
            await(() -> count(table) == 0);
            var service = new StockBasicSyncService(
                    context.getBean(com.zoutrankil.data.client.TushareClient.class),
                    context.getBean(com.zoutrankil.data.mapper.StockBasicMapper.class),
                    repo, repo,
                    new com.zoutrankil.data.service.SchemaMigrationService(flyway));
            var report = service.syncToQuestDb();
            assertTrue(report.submittedRows() > 0);
            assertEquals(report.submittedRows(), report.visibleRows());
            assertEquals(report.submittedRows(), service.loadLatestStocks().size());
            System.out.println("SMOKE application Tushare-to-QuestDB rows=" + report.submittedRows());
        } finally {
            if (migrated || viewExists(view)) jdbc.execute("DROP VIEW IF EXISTS " + view);
            if (exists(table)) jdbc.execute("DROP TABLE " + table);
            if (exists(history)) jdbc.execute("DROP TABLE " + history);
        }
    }

    @Test
    void isolatedTableViewAndMaterializedView() throws Exception {
        String table = "java_smoke_" + UUID.randomUUID().toString().replace("-", "");
        String view = table + "_v";
        String mv = table + "_mv";
        boolean madeTable = false;
        boolean madeView = false;
        boolean madeMv = false;
        try {
            assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
            qdb = context.getBean(QuestDB.class);
            jdbc.execute("CREATE TABLE " + table
                    + " (ts TIMESTAMP, sym SYMBOL, value DOUBLE) TIMESTAMP(ts)"
                    + " PARTITION BY DAY WAL DEDUP UPSERT KEYS(ts, sym)");
            madeTable = true;
            jdbc.execute("CREATE VIEW " + view + " AS (SELECT ts, sym, value FROM " + table + ")");
            madeView = true;
            Instant first = Instant.parse("2026-09-28T00:00:00Z");
            write(table, first, 10);
            write(table, first.plusSeconds(60), 20);
            await(() -> count(table) == 2);
            assertEquals(30.0, sum(view));
            jdbc.execute("CREATE MATERIALIZED VIEW " + mv + " REFRESH IMMEDIATE AS ("
                    + "SELECT ts, sym, sum(value) AS total FROM " + table
                    + " SAMPLE BY 1d) PARTITION BY DAY");
            madeMv = true;
            await(() -> materializedReady(mv) && aggregate(mv) == 30.0);
            write(table, first, 15);
            await(() -> count(table) == 2 && sum(view) == 35.0);
            await(() -> materializedReady(mv) && aggregate(mv) == 35.0);
            write(table, first.plusSeconds(120), 5);
            await(() -> count(table) == 3 && sum(view) == 40.0);
            await(() -> materializedReady(mv) && aggregate(mv) == 40.0);
            System.out.println("SMOKE isolated QWP/JDBC/table/view/dedup/MV initial/update/append PASS");
        } finally {
            if (madeMv) jdbc.execute("DROP MATERIALIZED VIEW " + mv);
            if (madeView) jdbc.execute("DROP VIEW " + view);
            if (madeTable) jdbc.execute("DROP TABLE " + table);
        }
    }

    private void write(String table, Instant ts, double value) {
        try (Sender sender = qdb.borrowSender()) {
            sender.table(table).symbol("sym", "TEST").doubleColumn("value", value).at(ts);
        }
    }

    private boolean exists(String name) {
        return jdbc.queryForObject("SELECT count() FROM tables() WHERE table_name = ?",
                Long.class, name) > 0;
    }

    private boolean viewExists(String name) {
        return jdbc.queryForObject("SELECT count() FROM views() WHERE view_name = ?",
                Long.class, name) > 0;
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count() FROM " + table, Long.class);
    }

    private double sum(String table) {
        return jdbc.queryForObject("SELECT sum(value) FROM " + table, Double.class);
    }

    private double aggregate(String mv) {
        Double result = jdbc.queryForObject("SELECT sum(total) FROM " + mv, Double.class);
        return result == null ? Double.NaN : result;
    }

    private boolean materializedReady(String name) {
        return jdbc.queryForObject("SELECT count() FROM materialized_views() WHERE view_name = ?"
                + " AND view_status = 'valid' AND refresh_base_table_txn = base_table_txn",
                Long.class, name) == 1;
    }

    private void await(BooleanSupplier check) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        do {
            if (check.getAsBoolean()) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("Data/content/refresh condition did not converge within 30 seconds");
    }
}
