package com.zoutrankil.data.repository;

import com.zoutrankil.data.stock.application.StockBasicJobDefinition;
import com.zoutrankil.data.stock.storage.StockBasicWritePort;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.client.dto.TushareRequest;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.service.*;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "QUESTDB_WRITE_LIVE", matches = "1")
class SyncRunLedgerLiveTest {
    @Test void realSourceWriteReadbackReceiptSurvivesLedgerReopen() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String table = "java_f010_" + suffix;
            Path ledgerPath = Path.of("var", "F010-" + suffix + ".sqlite3");
            Path evidencePath = Path.of("artifacts/java-migration/F010/live-ledger-" + suffix + ".json");
            var ledger = new SyncRunLedger(ledgerPath);
            LocalDate day = LocalDate.now(ZoneOffset.UTC);
            var request = StockBasicJobDefinition.DEFINITION.freeze(null, Map.of(), null, null, day);
            ledger.createRun("run-" + suffix, null, "isolated-questdb", request);
            String run = "run-" + suffix, attempt = "attempt-" + suffix, slice = "slice-" + suffix;
            ledger.transition(run, 0, SyncRunState.RUNNING, "{}");
            ledger.createChild(attempt, SyncRunLedger.Kind.ATTEMPT, run, run);
            ledger.transition(attempt, 0, SyncRunState.RUNNING, "{}");
            ledger.createChild(slice, SyncRunLedger.Kind.SLICE, run, attempt);
            ledger.transition(slice, 0, SyncRunState.RUNNING, "{}");
            var page = context.getBean(TushareClient.class).request(new TushareRequest("stock_basic",
                    Map.of("ts_code", "000001.SZ", "list_status", "L"), TushareClient.STOCK_FIELDS, 2));
            assertEquals(1, page.rows().size());
            var source = page.rows().getFirst();
            var stock = new StockBasic(source.get("ts_code").asText(), source.get("symbol").asText(),
                    source.get("name").asText(), source.get("area").isNull() ? null : source.get("area").asText(),
                    source.get("industry").isNull() ? null : source.get("industry").asText(),
                    TemporalValues.businessDate(source.get("list_date").asText(), TemporalValues.DateFormat.BASIC));
            var row = new StockBasicSnapshot(day.atStartOfDay().toInstant(ZoneOffset.UTC), stock);
            ledger.transition(slice, 1, SyncRunState.FETCHED, "{\"returnedRows\":1}");
            ledger.transition(slice, 2, SyncRunState.VALIDATED, "{\"normalizedRows\":1}");
            jdbc.execute("CREATE TABLE " + table + " (snapshot_ts TIMESTAMP, ts_code SYMBOL, symbol SYMBOL, "
                    + "name STRING, area SYMBOL, industry SYMBOL, list_date STRING) TIMESTAMP(snapshot_ts) "
                    + "PARTITION BY DAY WAL DEDUP UPSERT KEYS(snapshot_ts,ts_code)");
            boolean complete = false;
            try {
                var port = new StockBasicWritePort(table, jdbc, context.getBean(QuestDB.class));
                ledger.transition(slice, 3, SyncRunState.SUBMITTED, "{\"submittedRows\":1}");
                var result = new VerifiedBatchExecutor<>(new VerifiedBatchExecutor.Policy(1, 4096, 1,
                        Duration.ofSeconds(20), Duration.ofMillis(100)), StockBasicWritePort.CODEC, port)
                        .execute(List.of(row).iterator());
                assertEquals(VerifiedBatchExecutor.Status.VERIFIED, result.status(), result.reason());
                assertEquals(List.of(row), port.readback(List.of(row.key())));
                ledger.transition(slice, 4, SyncRunState.ACKNOWLEDGED, "{\"acknowledged\":true}");
                var proof = Map.of("passed", true, "expectedRows", 1, "actualRows", 1, "matchedRows", 1,
                        "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0,
                        "readbackEvidence", evidencePath.toString(), "sourceFingerprint", result.receipts().getFirst().digest());
                var evidence = new LinkedHashMap<String, Object>();
                evidence.put("run", run); evidence.put("table", table); evidence.put("source", source);
                evidence.put("readback", jdbc.queryForList("SELECT cast(snapshot_ts as long) AS snapshot_micros, "
                        + "ts_code,symbol,name,area,industry,list_date FROM " + table + " LIMIT 2"));
                evidence.put("verification", proof); evidence.put("writeReceipt", result);
                evidence.put("ledgerPath", ledgerPath.toString());
                Files.createDirectories(evidencePath.getParent());
                var json = new ObjectMapper();
                json.writerWithDefaultPrettyPrinter().writeValue(evidencePath.toFile(), evidence);
                String payload = json.writeValueAsString(Map.of("verification", proof, "writeReceipt", result));
                ledger.transition(slice, 5, SyncRunState.VERIFIED, payload);
                ledger.transition(attempt, 1, SyncRunState.VERIFIED, payload);
                ledger.transition(run, 1, SyncRunState.VERIFIED, payload);
                var reopened = SyncRunLedger.openReadOnly(ledgerPath);
                assertEquals(SyncRunState.VERIFIED, reopened.get(run).state());
                assertEquals(result.receipts().getFirst().digest(),
                        json.readTree(reopened.get(slice).payloadJson())
                                .path("writeReceipt").path("receipts").get(0).path("digest").asText());
                assertEquals(7, reopened.events(slice, -1, 20).size());
                assertEquals(1, reopened.getRun(run).jobVersion());
                evidence.put("reopenedRun", reopened.getRun(run));
                evidence.put("reopenedEntries", reopened.entries(run, null, 10));
                evidence.put("sliceEvents", reopened.events(slice, -1, 20));
                evidence.put("ownedTableCleaned", true);
                jdbc.execute("DROP TABLE " + table);
                complete = true;
                json.writerWithDefaultPrettyPrinter().writeValue(evidencePath.toFile(), evidence);
            } finally {
                if (!complete) System.out.println("F010 retained isolated table=" + table + "; ledger=" + ledgerPath);
            }
        }
    }
}
