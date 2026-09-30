package com.zoutrankil.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "QUESTDB_BOUNDED_READ", matches = "1")
class QuestDbBoundedReaderLiveTest {
    record MarketRow(String instrument, LocalDate date, Double close, Double extra) {}
    private DatasetDefinition definition(String table, String dateColumn, StorageType type, boolean extra) {
        var columns = new ArrayList<Column>(List.of(
                new Column("ts_code", "instrument", "ts_code", StorageType.SYMBOL, false, "Instrument code", null),
                new Column(dateColumn, "date", dateColumn, type, false, "Trading date",
                        new TemporalContract(TemporalKind.BUSINESS_DATE, "ISO", "calendar", "DAY", "Trading calendar date")),
                new Column("close", "close", "close", StorageType.DOUBLE, true, "Closing price", null)));
        if (extra) columns.add(new Column("ah_amount", "extra", "ah_amount", StorageType.DOUBLE, true, "After-hours amount", null));
        return new DatasetDefinition("probe_" + table, 1, "existing_questdb", "F007_read_only_probe", table,
                ObjectKind.TABLE, columns, List.of("date", "instrument"), List.of(), dateColumn,
                Partition.NONE, false, Set.of(Capability.READ), List.of(), "Read-only projection; not a dataset migration declaration");
    }
    private MarketRow map(DatasetValues row, boolean extra) {
        return new MarketRow(row.get("instrument", String.class), row.get("date", LocalDate.class),
                row.get("close", Double.class), extra ? row.get("extra", Double.class) : null);
    }
    private List<Map<String, Object>> safe(List<MarketRow> rows) {
        return rows.stream().map(r -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("instrument", r.instrument()); value.put("date", r.date().toString());
            value.put("close", r.close()); value.put("extra", r.extra());
            return (Map<String, Object>) value;
        }).toList();
    }
    @Test void actualQuestDbPagesMatchIndependentExplicitQueriesWithoutGapsOrTimezoneLoss() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        var evidence = new ArrayList<Map<String, Object>>();
        try (var ctx = app.run()) {
            var reader = ctx.getBean(QuestDbBoundedReader.class);
            var jdbc = ctx.getBean(JdbcTemplate.class);
            jdbc.setQueryTimeout(20);
            for (var table : List.of("daily", "etf_daily")) {
                boolean extra = table.equals("daily");
                String physicalDate = extra ? "trade_date" : "timestamp";
                String code = extra ? "000001.SZ" : "159949.SZ";
                var definition = definition(table, physicalDate, extra ? StorageType.TIMESTAMP : StorageType.TIMESTAMP_NS, extra);
                // Deliberately reorder projection relative to storage and model definition.
                var projection = extra ? List.of("close", "extra", "instrument", "date") : List.of("close", "instrument", "date");
                var query = new DatasetReadQuery(projection, Map.of("instrument", code), "date",
                        LocalDate.of(2018, 1, 2), LocalDate.of(2018, 1, 6), 2, null);
                var paged = new ArrayList<MarketRow>();
                var sqlPages = new ArrayList<String>();
                for (int page = 0; page < 10; page++) {
                    sqlPages.add(reader.prepare(definition, query, null).sql());
                    var result = reader.read(definition, query, null, row -> map(row, extra));
                    assertNull(result.sourceVersion());
                    paged.addAll(result.rows());
                    if (!result.hasMore()) break;
                    assertTrue(page < 9, "Read must finish within bounded page budget");
                    query = query.after(result.nextCursor());
                }
                String referenceSql = "SELECT ts_code, cast(" + physicalDate + " as string) AS date_text, close"
                        + (extra ? ", ah_amount" : "") + " FROM " + table + " WHERE ts_code=? AND " + physicalDate
                        + " >= '2018-01-02' AND " + physicalDate + " < '2018-01-06' ORDER BY " + physicalDate + ", ts_code LIMIT 100";
                var reference = jdbc.query(referenceSql, (rs, n) -> new MarketRow(rs.getString("ts_code"),
                        LocalDate.parse(rs.getString("date_text").substring(0, 10)), rs.getObject("close", Double.class),
                        extra ? rs.getObject("ah_amount", Double.class) : null), code);
                assertFalse(reference.isEmpty());
                assertEquals(reference, paged);
                assertTrue(sqlPages.size() >= 2);
                evidence.add(Map.of("table", table, "page_sql", sqlPages, "reference_sql", referenceSql,
                        "params", Map.of("instrument", code), "matched_rows", paged.size(), "actual_rows", safe(paged)));
            }
            // Exercise the second component of the compound key with many instruments on one date.
            var definition = definition("daily", "trade_date", StorageType.TIMESTAMP, true);
            var query = new DatasetReadQuery(List.of("instrument", "date", "close", "extra"), Map.of(), "date",
                    LocalDate.of(2018, 1, 2), LocalDate.of(2018, 1, 3), 2, null);
            var prefix = new ArrayList<String>();
            for (int i = 0; i < 3; i++) {
                var result = reader.read(definition, query, null, row -> row.get("instrument", String.class));
                prefix.addAll(result.rows());
                assertTrue(result.hasMore());
                query = query.after(result.nextCursor());
            }
            var expectedPrefix = jdbc.queryForList("SELECT ts_code FROM daily WHERE trade_date >= '2018-01-02' "
                    + "AND trade_date < '2018-01-03' ORDER BY trade_date, ts_code LIMIT 6", String.class);
            assertEquals(expectedPrefix, prefix);
            evidence.add(Map.of("case", "same_date_compound_key_prefix", "matched_rows", prefix.size(), "instruments", prefix));
            long nanos = jdbc.queryForObject("SELECT cast(cast('2026-09-29T01:02:03.123456789Z' as timestamp_ns) as long)", Long.class);
            var expected = Instant.parse("2026-09-29T01:02:03.123456789Z");
            assertEquals(expected.getEpochSecond() * 1000000000L + expected.getNano(), nanos);
            evidence.add(Map.of("case", "server_literal_nanosecond_transport_probe", "value", Long.toString(nanos),
                    "scope", "SQL literal precision probe, not a source record"));
        }
        var out = Path.of("artifacts/java-migration/F007"); Files.createDirectories(out);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("live-read.json").toFile(),
                Map.of("observed_at", Instant.now().toString(), "checks", evidence, "writes", "none",
                        "consistency", "Unversioned live SELECTs; no cross-query atomic snapshot guarantee"));
    }
}
