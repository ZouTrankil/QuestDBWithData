package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.BacktestDailyCacheMapper;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadGroupReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Bounded, read-only full-slice comparison against the Python-digest-verified D091 capture. */
class BacktestDailyCacheLiveReadAcceptanceTest {
    private static final Path INPUT = Path.of(
            "artifacts/java-migration/D091/commands/cache-slice-for-digest-20260930.json.gz");

    @Test void typedReadMatchesEveryFieldInVerifiedVersionedSliceWithoutWrites() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D092_LIVE_READ")),
                "Set D092_LIVE_READ=true for bounded local QuestDB acceptance");
        assertTrue(Files.isRegularFile(INPUT), "D091 digest-verified captured slice required");

        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + required("APP_QUESTDB_HOST") + ":"
                + System.getenv().getOrDefault("APP_QUESTDB_PGPORT", "8812") + "/"
                + System.getenv().getOrDefault("APP_QUESTDB_DATABASE", "qdb") + "?sslmode=disable");
        dataSource.setUsername(required("APP_QUESTDB_USERNAME"));
        dataSource.setPassword(required("APP_QUESTDB_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(30);

        long[] cacheBefore = tableIdentity(jdbc, "backtest_daily_cache");
        long[] coverageBefore = tableIdentity(jdbc, "backtest_daily_cache_coverage");
        var table = jdbc.query("SELECT partitionBy, walEnabled FROM tables() "
                        + "WHERE table_name='backtest_daily_cache'", rs -> {
                    if (!rs.next()) throw new IllegalStateException("Cache table is absent");
                    return new Object[]{rs.getString("partitionBy"), rs.getBoolean("walEnabled")};
                });
        var types = new LinkedHashMap<String, String>();
        var keys = new java.util.LinkedHashSet<String>();
        String[] designated = {null};
        jdbc.query("SELECT \"column\", \"type\", designated, upsertKey "
                        + "FROM table_columns('backtest_daily_cache') ORDER BY \"column\"",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                    while (rs.next()) {
                        var name = rs.getString("column");
                        types.put(name, rs.getString("type"));
                        if (rs.getBoolean("upsertKey")) keys.add(name);
                        if (rs.getBoolean("designated")) designated[0] = name;
                    }
                    return null;
                });
        assertEquals("MONTH", table[0]);
        assertEquals(true, table[1]);
        assertEquals("trade_date", designated[0]);
        assertEquals(14, types.size());
        assertEquals("TIMESTAMP", types.get("trade_date"));
        assertEquals("SYMBOL", types.get("ts_code"));
        assertEquals("SYMBOL", types.get("source_version"));
        assertEquals("INT", types.get("is_suspended"));
        assertEquals("INT", types.get("is_st"));
        assertEquals(Set.of("trade_date", "ts_code", "source_version"), keys);

        Map<String, Object> captured;
        try (var input = new GZIPInputStream(Files.newInputStream(INPUT))) {
            captured = new ObjectMapper().readValue(input, new TypeReference<>() {});
        }
        @SuppressWarnings("unchecked")
        var receipt = (Map<String, Object>) captured.get("coverage");
        @SuppressWarnings("unchecked")
        var rawRows = (List<Map<String, Object>>) captured.get("cache_rows");
        var date = LocalDate.parse((String) receipt.get("trade_date"));
        var version = (String) receipt.get("source_version");
        int expectedCount = ((Number) receipt.get("row_count")).intValue();
        assertEquals(expectedCount, rawRows.size());
        assertTrue(expectedCount <= 10_000, "Read must stay within the bounded page limit");

        var reader = new QuestDbBoundedReader(jdbc);
        var repository = new BacktestDailyCacheReadRepository(reader);
        var page = repository.findVersion(date, version, 10_000, null);
        assertEquals(expectedCount, page.rows().size());
        assertNull(page.nextCursor());
        var expectedByKey = new HashMap<String, Map<String, Object>>();
        for (var row : rawRows) {
            assertNull(expectedByKey.put((String) row.get("ts_code"), row), "Captured stock key duplicated");
        }
        var mapper = new BacktestDailyCacheMapper();
        long comparedValues = 0;
        var sampleKeys = new ArrayList<String>();
        for (var row : page.rows()) {
            assertEquals(date, row.daily().tradeDate());
            assertEquals(version, row.sourceVersion());
            var expected = expectedByKey.remove(row.daily().tsCode());
            assertNotNull(expected, "Typed row missing from Python-digest-verified capture");
            var actual = new LinkedHashMap<>(mapper.values(row).asMap());
            actual.put("trade_date", row.daily().tradeDate().toString());
            assertEquals(expected, actual, "All 14 physical fields must match for " + row.daily().tsCode());
            comparedValues += actual.size();
            if (sampleKeys.size() < 3) sampleKeys.add(row.daily().tsCode());
        }
        assertTrue(expectedByKey.isEmpty(), "Captured rows absent from typed repository");
        assertEquals(expectedCount * 14L, comparedValues);

        var group = new ReadGroupReader(new DatasetRegistry(List.of(repository)), reader,
                List.of(new ReadGroupReader.Binding<>(BacktestDailyCacheDataset.DEFINITION,
                        BacktestDailyCache.class, mapper::fromValues, () -> null)));
        var query = new DatasetReadQuery(BacktestDailyCacheDataset.DEFINITION.storageColumns(),
                Map.of("trade_date", date, "source_version", version), null, null, null, 10_000, null);
        var grouped = group.read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                "d092-live-slice", "backtest_daily_cache", 1, query)), java.time.Duration.ofSeconds(30)),
                () -> false);
        assertTrue(grouped.complete());
        assertEquals(page.rows(), grouped.require("d092-live-slice").typedPage(BacktestDailyCache.class).rows());

        long[] cacheAfter = tableIdentity(jdbc, "backtest_daily_cache");
        long[] coverageAfter = tableIdentity(jdbc, "backtest_daily_cache_coverage");
        assertArrayEquals(cacheBefore, cacheAfter);
        assertArrayEquals(coverageBefore, coverageAfter);

        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("task_id", "D092");
        evidence.put("checked_at", Instant.now().toString());
        evidence.put("mode", "read_only");
        evidence.put("table", "backtest_daily_cache");
        evidence.put("physical_partition", table[0]);
        evidence.put("wal_enabled", table[1]);
        evidence.put("physical_column_types", types);
        evidence.put("physical_upsert_keys", keys);
        evidence.put("designated_timestamp", designated[0]);
        evidence.put("receipt_date", date.toString());
        evidence.put("source_version", version);
        evidence.put("receipt_row_count", expectedCount);
        evidence.put("typed_rows_compared", page.rows().size());
        evidence.put("field_values_compared", comparedValues);
        evidence.put("missing_or_duplicate_keys", 0);
        evidence.put("typed_read_group", "verified");
        evidence.put("cache_rows_before_after", new long[]{cacheBefore[0], cacheAfter[0]});
        evidence.put("cache_txn_before_after", new long[]{cacheBefore[1], cacheAfter[1]});
        evidence.put("coverage_rows_before_after", new long[]{coverageBefore[0], coverageAfter[0]});
        evidence.put("coverage_txn_before_after", new long[]{coverageBefore[1], coverageAfter[1]});
        evidence.put("sample_keys", sampleKeys);
        evidence.put("writes", "none");
        Path output = Path.of("artifacts/java-migration/D092/commands/java-live-read-20260930.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence);
    }

    private static long[] tableIdentity(JdbcTemplate jdbc, String table) {
        if (!Set.of("backtest_daily_cache", "backtest_daily_cache_coverage").contains(table))
            throw new IllegalArgumentException("Unexpected D092 live table");
        return jdbc.query("SELECT table_row_count, table_txn FROM tables() WHERE table_name='" + table + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException(table + " is absent");
            return new long[]{((Number) rs.getObject(1)).longValue(), ((Number) rs.getObject(2)).longValue()};
        });
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required setting: " + name);
        return value;
    }
}
