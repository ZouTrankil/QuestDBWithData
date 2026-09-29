package com.zoutrankil.questdbwithdata.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.StockBasic;
import com.zoutrankil.questdbwithdata.domain.StockBasicSnapshot;
import com.zoutrankil.questdbwithdata.client.TushareClient;
import com.zoutrankil.questdbwithdata.client.dto.TushareRequest;
import com.zoutrankil.questdbwithdata.client.dto.TushareStockBasicDto;
import com.zoutrankil.questdbwithdata.mapper.StockBasicMapper;
import com.zoutrankil.questdbwithdata.service.VerifiedBatchExecutor;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "QUESTDB_WRITE_LIVE", matches = "1")
class StockBasicWriteLiveTest {
    @Test void boundedQwpBatchesAreReadBackByExactKeyAndValue() throws Exception {
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String table = "java_f008_write_" + UUID.randomUUID().toString().replace("-", "");
            System.out.println("F008 owned table=" + table);
            jdbc.execute("CREATE TABLE " + table + " (snapshot_ts TIMESTAMP, ts_code SYMBOL, "
                    + "symbol SYMBOL, name STRING, area SYMBOL, industry SYMBOL, list_date STRING) "
                    + "TIMESTAMP(snapshot_ts) PARTITION BY DAY WAL DEDUP UPSERT KEYS(snapshot_ts, ts_code)");
            boolean verified = false;
            try {
                var port = new StockBasicWritePort(table, jdbc, context.getBean(QuestDB.class));
                var policy = new VerifiedBatchExecutor.Policy(1, 4096, 4,
                        Duration.ofSeconds(20), Duration.ofMillis(100));
                var executor = new VerifiedBatchExecutor<>(policy, StockBasicWritePort.CODEC, port);
                Instant timestamp = Instant.parse("2026-09-29T00:00:00Z");
                var first = new StockBasicSnapshot(timestamp, new StockBasic("F008A.TEST", "F008A",
                        "first", null, "bank", LocalDate.of(1991, 4, 3)));
                var second = new StockBasicSnapshot(timestamp, new StockBasic("F008B.TEST", "F008B",
                        "second", "SZ", null, null));
                var run = executor.execute(List.of(first, second).iterator());
                System.out.println("F008 first batch result=" + run);
                assertEquals(VerifiedBatchExecutor.Status.VERIFIED, run.status(), run.reason());
                assertEquals(2, run.receipts().size());
                assertEquals(2, run.verifiedRows());
                var rerun = executor.execute(List.of(first, second).iterator());
                assertEquals(VerifiedBatchExecutor.Status.VERIFIED, rerun.status(), rerun.reason());
                assertEquals(2, jdbc.queryForObject("SELECT count() FROM " + table, Integer.class));
                var corrected = new StockBasicSnapshot(timestamp, new StockBasic("F008A.TEST", "F008A",
                        "corrected", null, "bank", null));
                var correction = executor.execute(List.of(corrected).iterator());
                assertEquals(VerifiedBatchExecutor.Status.VERIFIED, correction.status(), correction.reason());
                assertEquals(2, jdbc.queryForObject("SELECT count() FROM " + table, Integer.class));
                assertEquals(corrected, port.readback(List.of(corrected.key())).getFirst());
                assertTrue(port.walSettled());
                var sourcePage = context.getBean(TushareClient.class).request(new TushareRequest(
                        "stock_basic", Map.of("ts_code", "000001.SZ", "list_status", "L"),
                        TushareClient.STOCK_FIELDS, 2));
                assertEquals(1, sourcePage.rows().size());
                var sourceRow = sourcePage.rows().getFirst();
                var dto = new TushareStockBasicDto(
                        sourceText(sourceRow, "ts_code"), sourceText(sourceRow, "symbol"),
                        sourceText(sourceRow, "name"), sourceText(sourceRow, "area"),
                        sourceText(sourceRow, "industry"), sourceText(sourceRow, "list_date"));
                var normalized = context.getBean(StockBasicMapper.class).toDomain(dto);
                var sourceSnapshot = new StockBasicSnapshot(timestamp, normalized);
                var sourceWrite = executor.execute(List.of(sourceSnapshot).iterator());
                assertEquals(VerifiedBatchExecutor.Status.VERIFIED, sourceWrite.status(), sourceWrite.reason());
                var sourceReadback = port.readback(List.of(sourceSnapshot.key())).getFirst();
                assertEquals(sourceSnapshot, sourceReadback);
                assertEquals(3, jdbc.queryForObject("SELECT count() FROM " + table, Integer.class));
                Path out = Path.of("artifacts/java-migration/F008/live-write.json");
                Files.createDirectories(out.getParent());
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), Map.of(
                        "target", "configured QuestDB isolated WAL table", "table", table,
                        "source", "bounded declared test rows", "sourceRows", 2,
                        "firstRun", Map.of("submitted", run.submittedRows(), "verified", run.verifiedRows(),
                                "batches", run.receipts().size(), "digests", run.receipts().stream().map(r -> r.digest()).toList()),
                        "rerun", Map.of("submitted", rerun.submittedRows(), "verified", rerun.verifiedRows(),
                                "physicalRows", 2),
                        "correction", Map.of("submitted", correction.submittedRows(), "verified", correction.verifiedRows(),
                                "physicalRows", 2, "readbackName", corrected.stock().name(), "readbackListDate", "null"),
                        "realSource", Map.of("endpoint", "stock_basic", "code", "000001.SZ",
                                "returned", sourcePage.rows().size(), "normalized", 1,
                                "submitted", sourceWrite.submittedRows(), "verified", sourceWrite.verifiedRows(),
                                "readbackKey", normalized.tsCode(), "physicalRowsAfter", 3,
                                "normalizedValues", safe(sourceSnapshot), "readbackValues", safe(sourceReadback)),
                        "walSettled", true));
                verified = true;
            } finally {
                if (verified) jdbc.execute("DROP TABLE " + table);
            }
        }
    }
    private static String sourceText(Map<String, com.fasterxml.jackson.databind.JsonNode> row, String field) {
        var value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
    private static Map<String, Object> safe(StockBasicSnapshot snapshot) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("snapshot_ts", snapshot.snapshotTimestamp().toString());
        row.put("ts_code", snapshot.stock().tsCode());
        row.put("symbol", snapshot.stock().symbol());
        row.put("name", snapshot.stock().name());
        row.put("area", snapshot.stock().area());
        row.put("industry", snapshot.stock().industry());
        row.put("list_date", snapshot.stock().listDate() == null ? null : snapshot.stock().listDate().toString());
        return row;
    }
}
