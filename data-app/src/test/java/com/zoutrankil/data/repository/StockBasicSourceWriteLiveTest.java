package com.zoutrankil.data.repository;

import com.zoutrankil.data.stock.storage.StockBasicWritePort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.client.dto.TushareStockBasicDto;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.domain.StockBasicSnapshot;
import com.zoutrankil.data.stock.mapper.StockBasicMapper;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "QUESTDB_WRITE_LIVE", matches = "1")
class StockBasicSourceWriteLiveTest {
    @Test void rejectsWrongPhysicalContractBeforeAnyWrite() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        var checked = new ArrayList<String>();
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            for (String scenario : List.of("wrong_partition", "wrong_key", "extra_column")) {
                String table = "java_f008_reject_" + UUID.randomUUID().toString().replace("-", "");
                String extra = scenario.equals("extra_column") ? ", unexpected STRING" : "";
                String partition = scenario.equals("wrong_partition") ? "MONTH" : "DAY";
                String keys = scenario.equals("wrong_key") ? "snapshot_ts, ts_code, symbol" : "snapshot_ts, ts_code";
                jdbc.execute("CREATE TABLE " + table + " (snapshot_ts TIMESTAMP, ts_code SYMBOL, "
                        + "symbol SYMBOL, name STRING, area SYMBOL, industry SYMBOL, list_date STRING" + extra + ") "
                        + "TIMESTAMP(snapshot_ts) PARTITION BY " + partition + " WAL DEDUP UPSERT KEYS(" + keys + ")");
                boolean checkedEmpty = false;
                try {
                    var port = new StockBasicWritePort(table, jdbc, context.getBean(QuestDB.class));
                    assertThrows(IllegalStateException.class, port::preflight, scenario);
                    assertEquals(0, jdbc.queryForObject("SELECT count() FROM " + table, Integer.class));
                    checked.add(scenario);
                    checkedEmpty = true;
                } finally {
                    if (checkedEmpty) jdbc.execute("DROP TABLE " + table);
                }
            }
        }
        Path output = Path.of("artifacts/java-migration/F008/schema-rejections.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),
                Map.of("checked_at", Instant.now().toString(), "rejected_before_send", checked,
                        "submitted_rows", 0, "all_owned_tables_cleaned", true));
    }

    @Test void realBoundedSourceRowsWriteAndReadBackWithoutDuplicateKeys() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        var evidence = new LinkedHashMap<String, Object>();
        Path output = Path.of("artifacts/java-migration/F008/source-write.json");
        Files.createDirectories(output.getParent());
        var json = new ObjectMapper();
        try (var context = app.run()) {
            var contract = new PageContract("stock_basic", TushareClient.STOCK_FIELDS,
                    List.of("ts_code"), Set.of("ts_code", "list_status"),
                    PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
                    null, null, 2, 2, 1, 2, "Exact-code bounded stock directory validation");
            Instant snapshot = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            var rows = new ArrayList<StockBasicSnapshot>();
            var source = new ArrayList<Map<String, JsonNode>>();
            var requests = new ArrayList<Map<String, Object>>();
            var mapper = context.getBean(StockBasicMapper.class);
            for (String code : List.of("000001.SZ", "600000.SH")) {
                Map<String, Object> params = Map.of("ts_code", code, "list_status", "L");
                requests.add(params);
                var completed = context.getBean(TusharePageService.class).execute(contract, params,
                        (page, receipt) -> {
                            for (var row : page.rows()) {
                                source.add(row);
                                rows.add(new StockBasicSnapshot(snapshot, mapper.toDomain(new TushareStockBasicDto(
                                        value(row, "ts_code"), value(row, "symbol"), value(row, "name"),
                                        value(row, "area"), value(row, "industry"), value(row, "list_date")))));
                            }
                        }, row -> assertEquals(code, value(row, "ts_code")), () -> false);
                assertEquals(1, completed.rows());
            }
            assertEquals(2, rows.size());
            evidence.put("observed_at", Instant.now().toString());
            evidence.put("logical_date", snapshot.toString());
            evidence.put("endpoint", "stock_basic");
            evidence.put("requests", requests);
            evidence.put("source_rows", source);
            evidence.put("source_version", null);
            evidence.put("status", "source_received");
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence);
            var jdbc = context.getBean(JdbcTemplate.class);
            String table = "java_f008_source_" + UUID.randomUUID().toString().replace("-", "");
            evidence.put("table", table);
            jdbc.execute("CREATE TABLE " + table + " (snapshot_ts TIMESTAMP, ts_code SYMBOL, "
                    + "symbol SYMBOL, name STRING, area SYMBOL, industry SYMBOL, list_date STRING) "
                    + "TIMESTAMP(snapshot_ts) PARTITION BY DAY WAL DEDUP UPSERT KEYS(snapshot_ts, ts_code)");
            boolean verified = false;
            try {
                var port = new StockBasicWritePort(table, jdbc, context.getBean(QuestDB.class));
                var executor = new VerifiedBatchExecutor<>(new VerifiedBatchExecutor.Policy(1, 4096, 2,
                        Duration.ofSeconds(20), Duration.ofMillis(100)), StockBasicWritePort.CODEC, port);
                var initial = executor.execute(rows.iterator());
                evidence.put("first_write", initial);
                assertEquals(VerifiedBatchExecutor.Status.VERIFIED, initial.status(), initial.reason());
                var keys = rows.stream().map(StockBasicSnapshot::key).toList();
                var readback = port.readback(keys);
                assertEquals(rows, readback);
                var repeat = executor.execute(rows.iterator());
                evidence.put("repeat_write", repeat);
                assertEquals(VerifiedBatchExecutor.Status.VERIFIED, repeat.status(), repeat.reason());
                assertEquals(rows, port.readback(keys));
                int physicalRows = jdbc.queryForObject("SELECT count() FROM " + table, Integer.class);
                assertEquals(2, physicalRows);
                evidence.put("physical_rows", physicalRows);
                evidence.put("readback", jdbc.queryForList("SELECT cast(snapshot_ts as long) AS snapshot_micros, "
                        + "ts_code, symbol, name, area, industry, list_date FROM " + table + " ORDER BY ts_code LIMIT 3"));
                evidence.put("compared_columns", List.of("snapshot_ts", "ts_code", "symbol", "name", "area", "industry", "list_date"));
                evidence.put("wal_settled", port.walSettled());
                evidence.put("status", "verified");
                verified = true;
            } finally {
                if (verified) {
                    jdbc.execute("DROP TABLE " + table);
                    evidence.put("owned_table_cleaned", true);
                }
                json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence);
            }
        }
    }

    private static String value(Map<String, JsonNode> row, String field) {
        var node = row.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
