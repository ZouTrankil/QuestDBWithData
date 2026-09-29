package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.StockBasicMapper;
import com.zoutrankil.questdbwithdata.repository.*;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "QUESTDB_WRITE_LIVE", matches = "1")
class SyncJobRecoveryLiveTest {
    @Test void secondPageFailureResumesWithRealSourceAndOnlyWritesMissingPage() throws Exception {
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String table = "java_f012_" + suffix;
            String firstRun = "run-" + suffix;
            String resumedRun = "run-" + UUID.randomUUID().toString().replace("-", "");
            Path ledgerPath = Path.of("var", "F012-" + suffix + ".sqlite3");
            Path evidence = Path.of("artifacts/java-migration/F012");
            Files.createDirectories(evidence);
            var ledger = new SyncRunLedger(ledgerPath);
            var locks = new DatasetIntervalLock(ledgerPath);
            var runner = new SyncJobRunner<StockBasicSnapshot, StockBasicSnapshotKey>(ledger, locks);
            var request = StockBasicSyncAdapter.definition(true).freeze(null,
                    Map.of("codes", List.of("000001.SZ", "600000.SH")), null, null,
                    LocalDate.of(2026, 9, 29));
            jdbc.execute("CREATE TABLE " + table + " (snapshot_ts TIMESTAMP,ts_code SYMBOL,symbol SYMBOL,name STRING,"
                    + "area SYMBOL,industry SYMBOL,list_date STRING) TIMESTAMP(snapshot_ts) PARTITION BY DAY "
                    + "WAL DEDUP UPSERT KEYS(snapshot_ts,ts_code)");
            boolean verified = false;
            try {
                var port = new StockBasicWritePort(table, jdbc, context.getBean(QuestDB.class));
                var firstDelegate = new StockBasicSyncAdapter(context.getBean(TusharePageService.class),
                        context.getBean(StockBasicMapper.class), port, evidence.resolve(firstRun));
                var failing = new SyncJobRunner.Adapter<StockBasicSnapshot, StockBasicSnapshotKey>() {
                    public void preflight(SyncJobDefinition.FrozenRequest frozen) throws Exception {
                        firstDelegate.preflight(frozen);
                    }
                    public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest frozen,
                            SyncJobRunner.PageConsumer<StockBasicSnapshot> consumer,
                            BooleanSupplier cancelled) throws Exception {
                        int[] page = {0};
                        return firstDelegate.fetch(frozen, emitted -> {
                            if (++page[0] == 2) throw new IllegalStateException("Injected second page source failure");
                            consumer.accept(emitted);
                        }, cancelled);
                    }
                    public VerifiedBatchExecutor.Codec<StockBasicSnapshot, StockBasicSnapshotKey> codec() {
                        return firstDelegate.codec();
                    }
                    public VerifiedBatchExecutor.Port<StockBasicSnapshot, StockBasicSnapshotKey> port() {
                        return port;
                    }
                };
                var first = runner.run(firstRun, null, "isolated-" + suffix, request, failing, () -> false);
                assertEquals(SyncRunState.PARTIAL, first.state());
                assertEquals(1, first.verifiedRows());
                var before = jdbc.queryForList("SELECT ts_code FROM " + table + " LIMIT 2");
                assertEquals(1, before.size());
                var secondDelegate = new StockBasicSyncAdapter(context.getBean(TusharePageService.class),
                        context.getBean(StockBasicMapper.class), port, evidence.resolve(resumedRun));
                var reopenedLedger = new SyncRunLedger(ledgerPath);
                var reopenedRunner = new SyncJobRunner<StockBasicSnapshot, StockBasicSnapshotKey>(
                        reopenedLedger, new DatasetIntervalLock(ledgerPath));
                int[] resumedSends = {0};
                var counted = new SyncJobRunner.Adapter<StockBasicSnapshot, StockBasicSnapshotKey>() {
                    public void preflight(SyncJobDefinition.FrozenRequest frozen) throws Exception {
                        secondDelegate.preflight(frozen);
                    }
                    public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest frozen,
                            SyncJobRunner.PageConsumer<StockBasicSnapshot> consumer,
                            BooleanSupplier cancelled) throws Exception {
                        return secondDelegate.fetch(frozen, consumer, cancelled);
                    }
                    public VerifiedBatchExecutor.Codec<StockBasicSnapshot, StockBasicSnapshotKey> codec() {
                        return secondDelegate.codec();
                    }
                    public VerifiedBatchExecutor.Port<StockBasicSnapshot, StockBasicSnapshotKey> port() {
                        return new VerifiedBatchExecutor.Port<>() {
                            public void preflight() throws Exception { port.preflight(); }
                            public void send(List<StockBasicSnapshot> rows) throws Exception {
                                resumedSends[0]++;
                                port.send(rows);
                            }
                            public List<StockBasicSnapshot> readback(List<StockBasicSnapshotKey> keys)
                                    throws Exception { return port.readback(keys); }
                            public boolean walSettled() throws Exception { return port.walSettled(); }
                            public boolean uncertainSenderStopped() throws Exception {
                                return port.uncertainSenderStopped();
                            }
                        };
                    }
                };
                var resumed = reopenedRunner.resume(resumedRun, firstRun, "isolated-" + suffix, request,
                        counted, () -> false);
                assertEquals(SyncRunState.VERIFIED, resumed.state(), resumed.errorCode());
                assertEquals(2, resumed.sourceRows());
                assertEquals(2, resumed.verifiedRows());
                assertEquals(1, resumed.reusedRows());
                assertEquals(1, resumedSends[0], "Only the missing page may reach the physical writer");
                var actual = jdbc.queryForList("SELECT cast(snapshot_ts as long) AS snapshot_micros,"
                        + "ts_code,symbol,name,area,industry,list_date FROM " + table + " ORDER BY ts_code LIMIT 3");
                assertEquals(2, actual.size());
                assertEquals(List.of("000001.SZ", "600000.SH"), actual.stream().map(r -> r.get("ts_code")).toList());
                assertTrue(port.walSettled());
                var entries = reopenedLedger.entries(resumedRun, null, 10);
                assertEquals(4, entries.size());
                assertTrue(entries.stream().allMatch(e -> e.state() == SyncRunState.VERIFIED));
                assertEquals(SyncRunState.PARTIAL, ledger.get(firstRun).state());
                var checkpoint = entries.stream().filter(e -> e.kind() == SyncRunLedger.Kind.SLICE)
                        .map(SyncRunLedger.Entry::payloadJson).filter(p -> p.contains("reusedCheckpoint"))
                        .findFirst().orElseThrow();
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                        evidence.resolve("live-recovery-" + suffix + ".json").toFile(),
                        Map.of("first", first, "resumed", resumed, "target", table,
                                "readback", actual, "firstRunEntries", ledger.entries(firstRun, null, 10),
                                "resumedRunEntries", entries, "reusedProof", checkpoint,
                                "ledgerPath", ledgerPath.toString(), "sourceEvidence", evidence.resolve(resumedRun).toString(),
                                "resumedPhysicalSendCalls", resumedSends[0]));
                verified = true;
            } finally {
                if (verified) jdbc.execute("DROP TABLE " + table);
            }
        }
    }
}
