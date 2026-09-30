package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.BacktestDailyViewMapper;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadGroupReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only typed comparison against the Python-authoritative live view SQL and QWP sample. */
class BacktestDailyViewLiveReadAcceptanceTest {
    private static final Path INPUT = Path.of(
            "artifacts/java-migration/D093/commands/python-view-audit-20260930.json");
    private static final List<String> SOURCES = List.of("stk_factor", "stk_limit", "stk_suspend", "stk_st_daily");
    private static final List<String> METRICS = List.of("open", "high", "low", "close", "vol", "amount",
            "adj_factor", "up_limit", "down_limit");

    @Test void typedViewMatchesAuthoritativePythonSqlAndAllThirteenFieldsWithoutWrites() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D093_LIVE_READ")),
                "Set D093_LIVE_READ=true for bounded local QuestDB view acceptance");
        assertTrue(Files.isRegularFile(INPUT), "Python owner SQL and QWP sample audit required");
        Map<String, Object> audit = new ObjectMapper().readValue(Files.newInputStream(INPUT), new TypeReference<>() {});
        assertEquals(true, audit.get("view_sql_matches_python"));

        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + required("APP_QUESTDB_HOST") + ":"
                + System.getenv().getOrDefault("APP_QUESTDB_PGPORT", "8812") + "/"
                + System.getenv().getOrDefault("APP_QUESTDB_DATABASE", "qdb") + "?sslmode=disable");
        dataSource.setUsername(required("APP_QUESTDB_USERNAME"));
        dataSource.setPassword(required("APP_QUESTDB_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(30);

        var before = sourceIdentities(jdbc);
        var sql = jdbc.query("SELECT view_sql FROM views() WHERE view_name='v_backtest_daily'", rs -> {
            if (!rs.next()) throw new IllegalStateException("Current view is absent");
            var value = rs.getString(1);
            if (rs.next()) throw new IllegalStateException("Duplicate view catalog entry");
            return value;
        });
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(sql.trim().getBytes(StandardCharsets.UTF_8)));
        assertEquals(audit.get("view_sql_sha256"), sha, "View SQL changed after Python owner audit");

        var reader = new QuestDbBoundedReader(jdbc);
        var repository = new BacktestDailyViewReadRepository(reader);
        var day = LocalDate.parse((String) audit.get("sample_date"));
        var page = repository.findForDate(day, 200, null);
        assertEquals(200, page.rows().size());
        @SuppressWarnings("unchecked")
        var raw = (List<Map<String, Object>>) audit.get("sample_rows");
        var expected = new HashMap<String, Map<String, Object>>();
        for (var row : raw) assertNull(expected.put((String) row.get("ts_code"), normalize(row)));
        var mapper = new BacktestDailyViewMapper();
        long comparedValues = 0;
        for (var row : page.rows()) {
            var captured = expected.remove(row.tsCode());
            assertNotNull(captured, "Typed view returned an uncaptured stock key");
            var actual = new LinkedHashMap<>(mapper.values(row).asMap());
            actual.put("trade_date", row.tradeDate().toString());
            assertEquals(captured, actual, "All 13 view fields must match " + row.tsCode());
            comparedValues += actual.size();
        }
        assertTrue(expected.isEmpty(), "Python QWP sample key absent from typed JDBC view read");
        assertEquals(2600, comparedValues);

        var group = new ReadGroupReader(new DatasetRegistry(List.of(repository)), reader,
                List.of(new ReadGroupReader.Binding<>(BacktestDailyViewDataset.DEFINITION,
                        BacktestDailyViewValue.class, mapper::fromValues, () -> null)));
        var query = new DatasetReadQuery(BacktestDailyViewDataset.DEFINITION.storageColumns(),
                Map.of("trade_date", day), null, null, null, 200, null);
        var grouped = group.read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                "d093-live-view", "v_backtest_daily", 1, query)), java.time.Duration.ofSeconds(45)),
                () -> false);
        assertTrue(grouped.complete());
        assertEquals(page.rows(), grouped.require("d093-live-view").typedPage(BacktestDailyViewValue.class).rows());

        var after = sourceIdentities(jdbc);
        assertEquals(before, after, "Source tables changed during D093 read-only acceptance");
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("task_id", "D093");
        evidence.put("checked_at", Instant.now().toString());
        evidence.put("mode", "read_only");
        evidence.put("view", "v_backtest_daily");
        evidence.put("python_view_sql_sha256", audit.get("view_sql_sha256"));
        evidence.put("java_catalog_view_sql_sha256", sha);
        evidence.put("sample_date", day.toString());
        evidence.put("python_qwp_rows", raw.size());
        evidence.put("java_jdbc_typed_rows", page.rows().size());
        evidence.put("matched_field_values", comparedValues);
        evidence.put("mismatched_field_values", 0);
        evidence.put("missing_or_duplicate_keys", 0);
        evidence.put("read_group_typed_binding", "verified");
        evidence.put("source_tables_before_after", Map.of("before", before, "after", after));
        evidence.put("writes", "none");
        Path output = Path.of("artifacts/java-migration/D093/commands/java-live-read-20260930.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence);
    }

    private static Map<String, Object> normalize(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("trade_date", row.get("trade_date").toString().substring(0, 10));
        result.put("ts_code", row.get("ts_code"));
        for (var field : METRICS) {
            var value = row.get(field);
            result.put(field, value == null ? null : ((Number) value).doubleValue());
        }
        var suspended = row.get("is_suspended");
        var special = row.get("is_st");
        result.put("is_suspended", suspended == null ? null : ((Number) suspended).longValue());
        result.put("is_st", special == null ? null : ((Number) special).intValue());
        return result;
    }

    private static Map<String, List<Long>> sourceIdentities(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<String, List<Long>>();
        for (var table : SOURCES) {
            var identity = jdbc.query("SELECT table_row_count, table_txn FROM tables() WHERE table_name='" + table + "'", rs -> {
                if (!rs.next()) throw new IllegalStateException(table + " is absent");
                return List.of(((Number) rs.getObject(1)).longValue(), ((Number) rs.getObject(2)).longValue());
            });
            result.put(table, identity);
        }
        return result;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required setting: " + name);
        return value;
    }
}
