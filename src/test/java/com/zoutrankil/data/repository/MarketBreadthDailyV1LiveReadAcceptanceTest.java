package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MarketBreadthDailyV1Mapper;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadGroupReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real isolated QuestDB MV values, dynamic physical versions and typed read groups. */
class MarketBreadthDailyV1LiveReadAcceptanceTest {
    private static final Path INPUT = Path.of(
            "artifacts/java-migration/D095/commands/isolated-acceptance-20261006.json");

    @Test void isolatedValidMvTypedReadsMatchPythonSourceAggregatesAndGroup() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D095_LIVE_READ")));
        Map<String, Object> audit = new ObjectMapper().readValue(Files.newInputStream(INPUT), new TypeReference<>() {});
        var jdbc = jdbc("127.0.0.1", "18812", "admin", "quest");
        var repository = new MarketBreadthDailyV1ReadRepository(new QuestDbBoundedReader(jdbc));
        var page = repository.findRange(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 19), 1, null);
        assertEquals(1, page.rows().size());
        assertNotNull(page.nextCursor());
        var second = repository.findRange(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 19),
                1, page.nextCursor());
        assertEquals(1, second.rows().size());
        assertNull(second.nextCursor());
        assertTrue(page.sourceVersion().startsWith("base-id:"));
        assertEquals(page.sourceVersion(), second.sourceVersion());
        @SuppressWarnings("unchecked")
        var expected = (List<Map<String, Object>>) audit.get("final_mv_rows");
        var actual = List.of(page.rows().getFirst(), second.rows().getFirst());
        for (int i = 0; i < expected.size(); i++) {
            var row = actual.get(i);
            var source = expected.get(i);
            assertEquals(source.get("trade_date").toString().substring(0, 10), row.tradeDate().toString());
            for (String field : List.of("stock_count", "up_count", "down_count", "flat_count")) {
                assertEquals(((Number) source.get(field)).longValue(),
                        ((Number) new MarketBreadthDailyV1Mapper().values(row).get(field, Long.class)).longValue());
            }
            assertEquals(((Number) source.get("avg_pct_change")).doubleValue(), row.avgPctChange(), 1e-10);
            assertEquals(((Number) source.get("total_amount_yi")).doubleValue(), row.totalAmountYi(), 1e-8);
        }
        var mapper = new MarketBreadthDailyV1Mapper();
        var reader = new QuestDbBoundedReader(jdbc);
        var group = new ReadGroupReader(new DatasetRegistry(List.of(repository, new StockFactorReadRepository(reader))), reader,
                List.of(new ReadGroupReader.Binding<>(MarketBreadthDailyV1Dataset.DEFINITION,
                        MarketBreadthDailyV1.class, mapper::fromValues, () -> null)));
        var query = new DatasetReadQuery(repository.definition().storageColumns(), Map.of(), "trade_date",
                LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 19), 2, null);
        var result = group.read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                "breadth", repository.definition().datasetId(), 1, query)), Duration.ofSeconds(30)), () -> false);
        assertTrue(result.complete());
        assertEquals(actual, result.require("breadth").typedPage(MarketBreadthDailyV1.class).rows());
    }

    private static JdbcTemplate jdbc(String host, String port, String user, String password) {
        var source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl("jdbc:postgresql://" + host + ":" + port + "/qdb?sslmode=disable");
        source.setUsername(user);
        source.setPassword(password);
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(30);
        return jdbc;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing setting: " + name);
        return value;
    }
}
