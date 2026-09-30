package com.zoutrankil.questdbwithdata.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.mapper.BacktestDailyCacheCoverageMapper;
import com.zoutrankil.questdbwithdata.service.DatasetRegistry;
import com.zoutrankil.questdbwithdata.service.ReadGroupReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only live proof for one published D091 receipt and its matching cache slice. */
class BacktestDailyCacheCoverageLiveReadAcceptanceTest {
    private record StorageMetadata(String partitionBy, boolean walEnabled,
                                   Map<String, String> columnTypes, Set<String> upsertKeys,
                                   String designatedTimestamp) {}

    private static final int COVERAGE_PAGE_SIZE = 100;
    private static final int MAX_CACHE_ROWS = 10_000;
    private static final List<String> CACHE_FIELDS = List.of("trade_date", "ts_code", "open", "high", "low",
            "close", "vol", "amount", "adj_factor", "up_limit", "down_limit", "is_suspended", "is_st");

    @Test void typedCoverageReceiptMatchesBoundedPhysicalCacheSliceWithoutWrites() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D091_LIVE_READ")),
                "Set D091_LIVE_READ=true for the bounded local QuestDB receipt check");
        String host = required("APP_QUESTDB_HOST");
        String username = required("APP_QUESTDB_USERNAME");
        String password = required("APP_QUESTDB_PASSWORD");
        int port = Integer.parseInt(System.getenv().getOrDefault("APP_QUESTDB_PGPORT", "8812"));
        String database = System.getenv().getOrDefault("APP_QUESTDB_DATABASE", "qdb");

        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + database + "?sslmode=disable");
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(30);

        long[] coverageBefore = tableIdentity(jdbc, "backtest_daily_cache_coverage");
        long[] cacheBefore = tableIdentity(jdbc, "backtest_daily_cache");
        var storage = coverageStorage(jdbc);
        assertEquals("MONTH", storage.partitionBy());
        assertTrue(storage.walEnabled());
        assertEquals(Map.of("trade_date", "TIMESTAMP", "source_version", "SYMBOL",
                "row_count", "LONG", "content_digest", "STRING"), storage.columnTypes());
        assertEquals(Set.of("trade_date", "source_version"), storage.upsertKeys());
        assertEquals("trade_date", storage.designatedTimestamp());
        long maxMicros = jdbc.queryForObject(
                "SELECT cast(max(trade_date) AS long) FROM backtest_daily_cache_coverage", Long.class);
        LocalDate latestDate = TemporalValues.CalendarTimestamp
                .fromStorageEpoch(maxMicros, TemporalValues.EpochUnit.MICROS).date();

        var boundedReader = new QuestDbBoundedReader(jdbc);
        var repository = new BacktestDailyCacheCoverageReadRepository(boundedReader);
        var coveragePage = repository.findForDate(latestDate, COVERAGE_PAGE_SIZE, null);
        assertFalse(coveragePage.rows().isEmpty(), "latest coverage date must have a published receipt");
        assertTrue(coveragePage.rows().size() <= COVERAGE_PAGE_SIZE);
        var receipt = coveragePage.rows().getFirst();

        var cacheSql = "SELECT cast(trade_date AS long) AS trade_date_micros, ts_code, open, high, low, close, "
                + "vol, amount, adj_factor, up_limit, down_limit, is_suspended, is_st, source_version "
                + "FROM backtest_daily_cache WHERE trade_date = cast(? AS TIMESTAMP) AND source_version = ? "
                + "ORDER BY trade_date, ts_code LIMIT " + (MAX_CACHE_ROWS + 1);
        long dayMicros = new TemporalValues.CalendarTimestamp(receipt.tradeDate())
                .storageEpoch(TemporalValues.EpochUnit.MICROS);
        var cacheRows = jdbc.query(cacheSql, (rs, rowNum) -> cacheRow(rs), dayMicros, receipt.sourceVersion());
        assertTrue(cacheRows.size() <= MAX_CACHE_ROWS, "cache slice exceeds this verifier's bounded row budget");
        assertEquals(receipt.rowCount(), cacheRows.size(), "published row_count must match actual cache rows");
        assertTrue(cacheRows.stream().allMatch(row -> row.keySet().containsAll(CACHE_FIELDS)));
        assertTrue(cacheRows.stream().allMatch(row -> receipt.sourceVersion().equals(row.get("source_version"))));
        var keys = cacheRows.stream().map(row -> row.get("ts_code").toString()).toList();
        assertEquals(keys.size(), Set.copyOf(keys).size(), "one versioned cache slice must have unique stock keys");

        var group = new ReadGroupReader(new DatasetRegistry(List.of(repository)), boundedReader,
                List.of(new ReadGroupReader.Binding<>(BacktestDailyCacheCoverageDataset.DEFINITION,
                        BacktestDailyCacheCoverage.class, new BacktestDailyCacheCoverageMapper()::fromValues, () -> null)));
        var query = new DatasetReadQuery(BacktestDailyCacheCoverageDataset.DEFINITION.storageColumns(),
                Map.of("trade_date", latestDate), null, null, null, COVERAGE_PAGE_SIZE, null);
        var groupResult = group.read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                "d091-live-receipt", "backtest_daily_cache_coverage", 1, query)), java.time.Duration.ofSeconds(30)),
                () -> false);
        assertTrue(groupResult.complete());
        assertEquals(coveragePage.rows(),
                groupResult.require("d091-live-receipt").typedPage(BacktestDailyCacheCoverage.class).rows());

        long[] coverageAfter = tableIdentity(jdbc, "backtest_daily_cache_coverage");
        long[] cacheAfter = tableIdentity(jdbc, "backtest_daily_cache");
        assertArrayEquals(coverageBefore, coverageAfter, "coverage table changed during read-only validation");
        assertArrayEquals(cacheBefore, cacheAfter, "cache table changed during read-only validation");

        Path digestInput = Path.of("artifacts/java-migration/D091/commands/cache-slice-for-digest-20260930.json.gz");
        Files.createDirectories(digestInput.getParent());
        var input = new LinkedHashMap<String, Object>();
        input.put("coverage", Map.of("trade_date", receipt.tradeDate().toString(),
                "source_version", receipt.sourceVersion(), "row_count", receipt.rowCount(),
                "content_digest", receipt.contentDigest()));
        input.put("cache_rows", cacheRows);
        try (var output = new GZIPOutputStream(Files.newOutputStream(digestInput))) {
            new ObjectMapper().findAndRegisterModules().writeValue(output, input);
        }

        Path evidence = Path.of("artifacts/java-migration/D091/commands/java-live-read-20260930.json");
        Files.createDirectories(evidence.getParent());
        var report = new LinkedHashMap<String, Object>();
        report.put("task_id", "D091");
        report.put("checked_at", Instant.now().toString());
        report.put("mode", "read_only");
        report.put("coverage_table", "backtest_daily_cache_coverage");
        report.put("cache_table", "backtest_daily_cache");
        report.put("physical_partition", storage.partitionBy());
        report.put("wal_enabled", storage.walEnabled());
        report.put("physical_column_types", storage.columnTypes());
        report.put("physical_upsert_keys", storage.upsertKeys());
        report.put("designated_timestamp", storage.designatedTimestamp());
        report.put("coverage_table_row_count_before_after", new long[]{coverageBefore[0], coverageAfter[0]});
        report.put("coverage_table_txn_before_after", new long[]{coverageBefore[1], coverageAfter[1]});
        report.put("cache_table_row_count_before_after", new long[]{cacheBefore[0], cacheAfter[0]});
        report.put("cache_table_txn_before_after", new long[]{cacheBefore[1], cacheAfter[1]});
        report.put("coverage_date", latestDate.toString());
        report.put("coverage_page_size", COVERAGE_PAGE_SIZE);
        report.put("coverage_rows_read", coveragePage.rows().size());
        report.put("selected_receipt", Map.of("trade_date", receipt.tradeDate().toString(),
                "source_version", receipt.sourceVersion(), "row_count", receipt.rowCount(),
                "content_digest", receipt.contentDigest()));
        report.put("matching_cache_rows_read", cacheRows.size());
        report.put("unique_cache_keys", keys.size());
        report.put("all_cache_rows_match_source_version", true);
        report.put("read_group_typed_binding", "verified");
        report.put("digest_verification_input", digestInput.toString().replace('\\', '/'));
        report.put("sample_cache_rows", cacheRows.subList(0, Math.min(3, cacheRows.size())));
        report.put("writes", "none");
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), report);
    }

    private static Map<String, Object> cacheRow(ResultSet rs) throws java.sql.SQLException {
        long micros = rs.getLong("trade_date_micros");
        var row = new LinkedHashMap<String, Object>();
        row.put("trade_date", TemporalValues.CalendarTimestamp
                .fromStorageEpoch(micros, TemporalValues.EpochUnit.MICROS).date().toString());
        row.put("ts_code", rs.getString("ts_code"));
        row.put("open", nullableDouble(rs, "open"));
        row.put("high", nullableDouble(rs, "high"));
        row.put("low", nullableDouble(rs, "low"));
        row.put("close", nullableDouble(rs, "close"));
        row.put("vol", nullableDouble(rs, "vol"));
        row.put("amount", nullableDouble(rs, "amount"));
        row.put("adj_factor", nullableDouble(rs, "adj_factor"));
        row.put("up_limit", nullableDouble(rs, "up_limit"));
        row.put("down_limit", nullableDouble(rs, "down_limit"));
        row.put("is_suspended", nullableInteger(rs, "is_suspended"));
        row.put("is_st", nullableInteger(rs, "is_st"));
        row.put("source_version", rs.getString("source_version"));
        return row;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static long[] tableIdentity(JdbcTemplate jdbc, String table) {
        if (!Set.of("backtest_daily_cache_coverage", "backtest_daily_cache").contains(table))
            throw new IllegalArgumentException("Unexpected D091 live object");
        return jdbc.query("SELECT table_row_count, table_txn FROM tables() WHERE table_name='" + table + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException(table + " is absent from QuestDB tables()");
            return new long[]{((Number) rs.getObject(1)).longValue(), ((Number) rs.getObject(2)).longValue()};
        });
    }

    private static StorageMetadata coverageStorage(JdbcTemplate jdbc) {
        var table = jdbc.query("SELECT partitionBy, walEnabled FROM tables() "
                        + "WHERE table_name='backtest_daily_cache_coverage'", rs -> {
                    if (!rs.next()) throw new IllegalStateException("Coverage table metadata is absent");
                    return new Object[]{rs.getString("partitionBy"), rs.getBoolean("walEnabled")};
                });
        var types = new LinkedHashMap<String, String>();
        var keys = new java.util.LinkedHashSet<String>();
        String[] designated = {null};
        jdbc.query("SELECT \"column\", \"type\", designated, upsertKey "
                        + "FROM table_columns('backtest_daily_cache_coverage') ORDER BY \"column\"",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                    while (rs.next()) {
                    String column = rs.getString("column");
                    types.put(column, rs.getString("type"));
                    if (rs.getBoolean("upsertKey")) keys.add(column);
                    if (rs.getBoolean("designated")) designated[0] = column;
                    }
                    return null;
                });
        return new StorageMetadata((String) table[0], (Boolean) table[1], Map.copyOf(types),
                Set.copyOf(keys), designated[0]);
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required environment setting: " + name);
        return value;
    }
}
