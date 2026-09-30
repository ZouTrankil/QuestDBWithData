package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MarketBarometerCacheCoverageMapper;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadGroupReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Three-product live receipt comparison against the exact Python owner digest audit. */
class MarketBarometerCacheCoverageLiveReadAcceptanceTest {
    private static final Path INPUT = Path.of(
            "artifacts/java-migration/D094/commands/python-owner-audit-20260930.json");
    private static final List<String> TABLES = List.of("market_barometer_cache_coverage",
            "market_breadth_daily_cache", "etf_market_overview_daily_cache", "retail_sentiment_daily_cache");

    @Test void typedReceiptsMatchPythonVerifiedCacheRowsWithoutWrites() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D094_LIVE_READ")),
                "Set D094_LIVE_READ=true for bounded local QuestDB acceptance");
        assertTrue(Files.isRegularFile(INPUT), "Python owner receipt/digest audit required");
        Map<String, Object> audit = new ObjectMapper().readValue(Files.newInputStream(INPUT), new TypeReference<>() {});
        assertEquals("VERIFIED", audit.get("status"));
        @SuppressWarnings("unchecked")
        var selected = (List<Map<String, Object>>) audit.get("selected_receipts");
        assertEquals(3, selected.size());

        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + required("APP_QUESTDB_HOST") + ":"
                + System.getenv().getOrDefault("APP_QUESTDB_PGPORT", "8812") + "/"
                + System.getenv().getOrDefault("APP_QUESTDB_DATABASE", "qdb") + "?sslmode=disable");
        dataSource.setUsername(required("APP_QUESTDB_USERNAME"));
        dataSource.setPassword(required("APP_QUESTDB_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(30);

        var before = tableIdentities(jdbc);
        var metadata = jdbc.query("SELECT partitionBy,walEnabled FROM tables() "
                        + "WHERE table_name='market_barometer_cache_coverage'", rs -> {
                    if (!rs.next()) throw new IllegalStateException("D094 coverage table is absent");
                    return new Object[]{rs.getString(1), rs.getBoolean(2)};
                });
        assertEquals("MONTH", metadata[0]);
        assertEquals(true, metadata[1]);
        var types = new LinkedHashMap<String, String>();
        var keys = new java.util.LinkedHashSet<String>();
        String[] designated = {null};
        jdbc.query("SELECT \"column\",\"type\",designated,upsertKey "
                        + "FROM table_columns('market_barometer_cache_coverage') ORDER BY \"column\"",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                    while (rs.next()) {
                        var name = rs.getString("column");
                        types.put(name, rs.getString("type"));
                        if (rs.getBoolean("upsertKey")) keys.add(name);
                        if (rs.getBoolean("designated")) designated[0] = name;
                    }
                    return null;
                });
        assertEquals(Map.of("trade_date", "TIMESTAMP", "dataset_id", "SYMBOL",
                "source_version", "SYMBOL", "row_count", "LONG", "content_digest", "STRING"), types);
        assertEquals(Set.of("trade_date", "dataset_id", "source_version"), keys);
        assertEquals("trade_date", designated[0]);

        var reader = new QuestDbBoundedReader(jdbc);
        var repository = new MarketBarometerCacheCoverageReadRepository(reader);
        var mapper = new MarketBarometerCacheCoverageMapper();
        var members = new ArrayList<ReadGroupRequest.Member>();
        var expectedByAlias = new LinkedHashMap<String, MarketBarometerCacheCoverage>();
        for (var receipt : selected) {
            var key = new MarketBarometerCacheCoverageKey(LocalDate.parse((String) receipt.get("trade_date")),
                    (String) receipt.get("dataset_id"), (String) receipt.get("source_version"));
            var page = repository.findKey(key);
            assertEquals(1, page.rows().size());
            var actual = page.rows().getFirst();
            assertEquals(((Number) receipt.get("receipt_row_count")).longValue(), actual.rowCount());
            assertEquals(receipt.get("expected_content_digest"), actual.contentDigest());
            assertEquals(receipt.get("actual_python_content_digest"), actual.contentDigest());
            assertEquals(key, actual.key());
            var alias = "d094-" + key.datasetId();
            expectedByAlias.put(alias, actual);
            var query = new DatasetReadQuery(MarketBarometerCacheCoverageDataset.DEFINITION.storageColumns(),
                    Map.of("trade_date", key.tradeDate(), "dataset_id", key.datasetId(),
                            "source_version", key.sourceVersion()), null, null, null, 1, null);
            members.add(new ReadGroupRequest.Member(alias, "market_barometer_cache_coverage", 1, query));
        }
        var group = new ReadGroupReader(new DatasetRegistry(List.of(repository)), reader,
                List.of(new ReadGroupReader.Binding<>(MarketBarometerCacheCoverageDataset.DEFINITION,
                        MarketBarometerCacheCoverage.class, mapper::fromValues, () -> null)));
        var grouped = group.read(new ReadGroupRequest(members, java.time.Duration.ofSeconds(30)), () -> false);
        assertTrue(grouped.complete());
        for (var entry : expectedByAlias.entrySet()) {
            assertEquals(List.of(entry.getValue()),
                    grouped.require(entry.getKey()).typedPage(MarketBarometerCacheCoverage.class).rows());
        }

        var after = tableIdentities(jdbc);
        assertEquals(before, after, "Coverage and three caches must remain unchanged");
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("task_id", "D094");
        evidence.put("checked_at", Instant.now().toString());
        evidence.put("mode", "read_only");
        evidence.put("coverage_table", "market_barometer_cache_coverage");
        evidence.put("physical_partition", metadata[0]);
        evidence.put("wal_enabled", metadata[1]);
        evidence.put("physical_types", types);
        evidence.put("physical_upsert_keys", keys);
        evidence.put("designated_timestamp", designated[0]);
        evidence.put("selected_dataset_ids", expectedByAlias.values().stream()
                .map(MarketBarometerCacheCoverage::datasetId).toList());
        evidence.put("typed_receipts", expectedByAlias.size());
        evidence.put("python_digest_matches", expectedByAlias.size());
        evidence.put("read_group_typed_binding", "verified");
        evidence.put("tables_before_after", Map.of("before", before, "after", after));
        evidence.put("writes", "none");
        Path output = Path.of("artifacts/java-migration/D094/commands/java-live-read-20260930.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence);
    }

    private static Map<String, List<Long>> tableIdentities(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<String, List<Long>>();
        for (var table : TABLES) {
            var identity = jdbc.query("SELECT table_row_count,table_txn FROM tables() WHERE table_name='" + table + "'", rs -> {
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
