package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.QuestDbBoundedReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "QUESTDB_BOUNDED_READ", matches = "1")
class ReadGroupReaderLiveTest {
    record MarketRow(String instrument, LocalDate date, Double close) {}
    private DatasetDefinition definition(String table, String dateColumn, StorageType timestampType) {
        return new DatasetDefinition("probe_" + table, 1, "existing_questdb", "F014_read_only_probe", table,
                ObjectKind.TABLE, List.of(
                    new Column("ts_code", "instrument", "ts_code", StorageType.SYMBOL, false, "Instrument", null),
                    new Column(dateColumn, "date", dateColumn, timestampType, false, "Trading day",
                            new TemporalContract(TemporalKind.BUSINESS_DATE, "ISO", "calendar", "DAY", "Trading day")),
                    new Column("close", "close", "close", StorageType.DOUBLE, true, "Close", null)),
                List.of("date", "instrument"), List.of(), dateColumn, Partition.NONE, false,
                Set.of(Capability.READ), List.of(), "Read-only probe of existing data; not dataset migration admission");
    }
    private MarketRow map(DatasetValues values) {
        return new MarketRow(values.get("instrument", String.class), values.get("date", LocalDate.class),
                values.get("close", Double.class));
    }
    @Test void twoRealDatasetsPageIndependentlyAndBadProjectionIsNotAnEmptyPage() throws Exception {
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var ctx = app.run()) {
            var daily = definition("daily", "trade_date", StorageType.TIMESTAMP);
            var etf = definition("etf_daily", "timestamp", StorageType.TIMESTAMP_NS);
            var backend = ctx.getBean(QuestDbBoundedReader.class);
            var reader = new ReadGroupReader(new DatasetRegistry(List.of(() -> daily, () -> etf)), backend,
                    List.of(new ReadGroupReader.Binding<>(daily, MarketRow.class, this::map, () -> null),
                            new ReadGroupReader.Binding<>(etf, MarketRow.class, this::map, () -> null)));
            var definitions = Map.of("daily", daily, "etf", etf);
            var codes = Map.of("daily", "000001.SZ", "etf", "159949.SZ");
            var queries = new LinkedHashMap<String, DatasetReadQuery>();
            for (String id : List.of("daily", "etf")) queries.put(id, new DatasetReadQuery(
                    List.of("close", "instrument", "date"), Map.of("instrument", codes.get(id)), "date",
                    LocalDate.of(2018, 1, 2), LocalDate.of(2018, 1, 6), id.equals("daily") ? 1 : 2, null));
            var actual = new LinkedHashMap<String, List<MarketRow>>();
            actual.put("daily", new ArrayList<>()); actual.put("etf", new ArrayList<>());
            var pages = new ArrayList<ReadGroupReader.Result>();
            var sql = new ArrayList<String>();
            for (int page = 0; page < 8 && !queries.isEmpty(); page++) {
                var members = new ArrayList<ReadGroupRequest.Member>();
                for (var entry : queries.entrySet()) {
                    var definition = definitions.get(entry.getKey());
                    members.add(new ReadGroupRequest.Member(entry.getKey(), definition.datasetId(), 1, entry.getValue()));
                    sql.add(backend.prepare(definition, entry.getValue(), null).sql());
                }
                if (page == 0) members.add(new ReadGroupRequest.Member("bad", daily.datasetId(), 1,
                        new DatasetReadQuery(List.of("unknown_column"), Map.of(), null, null, null, 1, null)));
                var groupRequest = new ReadGroupRequest(members, Duration.ofMinutes(1));
                if (page > 0) {
                    Path requestFile = Path.of("var", "F014-read-request-" + UUID.randomUUID() + ".json");
                    Files.createDirectories(requestFile.getParent());
                    JobDefinitionJson.mapper().writeValue(requestFile.toFile(),
                            Map.of("timeoutMillis", 60000, "members", members));
                    groupRequest = reader.readRequest(requestFile);
                }
                var result = reader.read(groupRequest, () -> false);
                pages.add(result); assertFalse(result.atomicSnapshot());
                if (page == 0) {
                    assertFalse(result.complete());
                    assertEquals(ReadGroupReader.Status.FAILED, result.require("bad").status());
                    assertNull(result.require("bad").page());
                } else assertTrue(result.complete());
                for (String id : new ArrayList<>(queries.keySet())) {
                    var typed = result.require(id).typedPage(MarketRow.class);
                    assertNull(typed.sourceVersion(), "No versioned snapshot is available for these probes");
                    actual.get(id).addAll(typed.rows());
                    if (typed.hasMore()) queries.put(id, queries.get(id).after(typed.nextCursor()));
                    else queries.remove(id);
                }
            }
            assertTrue(queries.isEmpty(), "Both independent cursors must terminate within explicit budget");
            var jdbc = ctx.getBean(JdbcTemplate.class); jdbc.setQueryTimeout(20);
            var reference = new LinkedHashMap<String, List<MarketRow>>();
            for (String id : List.of("daily", "etf")) {
                String table = id.equals("daily") ? "daily" : "etf_daily";
                String date = id.equals("daily") ? "trade_date" : "timestamp";
                String query = "SELECT ts_code, cast(" + date + " as string) AS day_text, close FROM " + table
                        + " WHERE ts_code=? AND " + date + ">='2018-01-02' AND " + date
                        + "<'2018-01-06' ORDER BY " + date + ",ts_code LIMIT 20";
                var rows = jdbc.query(query, (rs, n) -> new MarketRow(rs.getString("ts_code"),
                        LocalDate.parse(rs.getString("day_text").substring(0, 10)), rs.getObject("close", Double.class)), codes.get(id));
                assertFalse(rows.isEmpty()); assertEquals(rows, actual.get(id)); reference.put(id, rows); sql.add(query);
            }
            Path evidence = Path.of("artifacts/java-migration/F014"); Files.createDirectories(evidence);
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writerWithDefaultPrettyPrinter().writeValue(
                    evidence.resolve("read-group-" + UUID.randomUUID() + ".json").toFile(),
                    Map.of("pages", pages, "actual", actual, "independentReference", reference,
                            "sql", sql, "codes", codes, "atomicSnapshot", false, "writesPerformed", false));
        }
    }
}
