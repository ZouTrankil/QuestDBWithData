package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.StockBasicSyncAdapter;
import com.zoutrankil.data.stock.storage.StockBasicWritePort;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.mapper.StockBasicMapper;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Two test-only job aliases exercise composition using the already admitted sample adapter. */
@EnabledIfEnvironmentVariable(named = "QUESTDB_WRITE_LIVE", matches = "1")
class SyncGroupRecoveryLiveTest {
    private static SyncJobDefinition job(String id) {
        var d = StockBasicSyncAdapter.definition(true);
        return new SyncJobDefinition(id, d.version(), d.datasetId(), d.datasetVersion(), d.owner(),
                d.supportedModes(), d.defaultMode(), d.parameters(), d.ratePolicyRef(), d.slicePolicyRef(),
                d.verificationPolicyRef(), d.retry(), d.timeout(), d.budget(), d.revisionDays(),
                d.dependencies(), d.frequency(), d.zone(), true, false);
    }

    @Test void partialGroupReopensLedgerAndOnlyExecutesMissingChildWithRealReadback() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String table = "java_f013_group_" + suffix;
            String target = "isolated-" + suffix;
            Path ledgerPath = Path.of("var", "F013-group-" + suffix + ".sqlite3");
            Path evidence = Path.of("artifacts/java-migration/F013", suffix);
            Files.createDirectories(evidence);
            var catalog = new SyncJobRegistry(List.of(job("sample.first"), job("sample.second")),
                    new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)),
                    Map.of("stock_basic_snapshot", Set.of(SyncJobDefinition.Mode.SNAPSHOT)),
                    new SyncJobRegistry.Policies(Set.of("tushare.shared"), Set.of("stock_basic.snapshot"),
                            Set.of("questdb.full_key_values")));
            var firstRef = new SyncJobDefinition.JobRef("sample.first", 2);
            var group = new SyncGroupDefinition("group.live", 1, List.of(
                    new SyncGroupDefinition.Member(firstRef, List.of()),
                    new SyncGroupDefinition.Member(new SyncJobDefinition.JobRef("sample.second", 2),
                            List.of(firstRef))), true, false);
            var groups = new SyncGroupRegistry(List.of(group), catalog);
            // Common parameters reach the first job; the second member overrides its code.
            var request = new SyncGroupRunner.Request(LocalDate.of(2026, 9, 29), SyncGroupRunner.Window.none(),
                    Map.of("sample.first", new SyncGroupRunner.MemberInput(null, Map.of(), null, target),
                            "sample.second", new SyncGroupRunner.MemberInput(null,
                                    Map.of("codes", List.of("600000.SH")), null, target)),
                    Map.of("codes", List.of("000001.SZ")));
            jdbc.execute("CREATE TABLE " + table + " (snapshot_ts TIMESTAMP,ts_code SYMBOL,symbol SYMBOL,name STRING,"
                    + "area SYMBOL,industry SYMBOL,list_date STRING) TIMESTAMP(snapshot_ts) PARTITION BY DAY "
                    + "WAL DEDUP UPSERT KEYS(snapshot_ts,ts_code)");
            boolean verified = false;
            try {
                var ledger = new SyncRunLedger(ledgerPath);
                var port = new StockBasicWritePort(table, jdbc, context.getBean(QuestDB.class));
                var called = new ArrayList<String>();
                boolean[] injectFailure = {true};
                SyncGroupRunner.ChildExecutor executor = new SyncGroupRunner.ChildExecutor() {
                  public SyncJobRunner.Result execute(String childId, String parentId, String prior, String childTarget,
                          SyncJobDefinition.FrozenRequest frozen) throws Exception {
                    called.add(frozen.definition().jobId());
                    var delegate = new StockBasicSyncAdapter(context.getBean(TusharePageService.class),
                            context.getBean(StockBasicMapper.class), port, evidence.resolve(childId));
                    var adapter = new SyncJobRunner.Adapter<StockBasicSnapshot, StockBasicSnapshotKey>() {
                        public void preflight(SyncJobDefinition.FrozenRequest f) { delegate.preflight(f); }
                        public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest f,
                                SyncJobRunner.PageConsumer<StockBasicSnapshot> consumer,
                                BooleanSupplier cancelled) throws Exception {
                            if (injectFailure[0] && f.definition().jobId().equals("sample.second"))
                                throw new IllegalStateException("Injected second member source failure before request");
                            return delegate.fetch(f, consumer, cancelled);
                        }
                        public VerifiedBatchExecutor.Codec<StockBasicSnapshot, StockBasicSnapshotKey> codec() {
                            return delegate.codec();
                        }
                        public VerifiedBatchExecutor.Port<StockBasicSnapshot, StockBasicSnapshotKey> port() {
                            return port;
                        }
                    };
                    var childLedger = new SyncRunLedger(ledgerPath);
                    var childRunner = new SyncJobRunner<StockBasicSnapshot, StockBasicSnapshotKey>(
                            childLedger, new DatasetIntervalLock(ledgerPath));
                    return prior == null ? childRunner.run(childId, parentId, childTarget, frozen, adapter, () -> false)
                            : childRunner.resume(childId, parentId, prior, childTarget, frozen, adapter, () -> false);
                  }
                  public String revalidateCompleted(String prior, String childTarget,
                          SyncJobDefinition.FrozenRequest frozen) throws Exception {
                      var recheck = new StockBasicSyncAdapter(context.getBean(TusharePageService.class),
                              context.getBean(StockBasicMapper.class), port, evidence.resolve("recheck-" + prior));
                      return VerifiedRunRecovery.revalidate(new SyncRunLedger(ledgerPath), prior, childTarget,
                              frozen, recheck, () -> false, evidence.resolve("rechecks"));
                  }
                };
                var first = new SyncGroupRunner(groups, catalog, ledger).run("group-first-" + suffix,
                        "group.live", 1, request, executor);
                assertEquals(SyncRunState.PARTIAL, first.state());
                assertEquals(List.of("sample.first", "sample.second"), called);
                assertEquals(1, jdbc.queryForList("SELECT ts_code FROM " + table + " LIMIT 3").size());
                var originalSlots = ledger.groupMembers(first.runId());
                called.clear(); injectFailure[0] = false;
                var reopened = new SyncRunLedger(ledgerPath);
                var resumed = new SyncGroupRunner(groups, catalog, reopened).resume("group-resumed-" + suffix,
                        first.runId(), "group.live", 1, request, executor);
                assertEquals(SyncRunState.VERIFIED, resumed.state());
                assertEquals(List.of("sample.second"), called);
                assertTrue(resumed.members().getFirst().reused());
                assertEquals(originalSlots.getFirst().childRunId(), resumed.members().getFirst().childRunId());
                assertEquals(resumed.runId(), reopened.getRun(resumed.members().getLast().childRunId()).parentRunId());
                var actual = jdbc.queryForList("SELECT cast(snapshot_ts as long) AS snapshot_micros,"
                        + "ts_code,symbol,name,area,industry,list_date FROM " + table + " ORDER BY ts_code LIMIT 3");
                assertEquals(2, actual.size());
                assertEquals(List.of("000001.SZ", "600000.SH"), actual.stream().map(r -> r.get("ts_code")).toList());
                assertTrue(port.walSettled());
                assertEquals(SyncRunState.PARTIAL, reopened.get(first.runId()).state());
                for (var member : resumed.members())
                    assertEquals(SyncRunState.VERIFIED, reopened.get(member.childRunId()).state());
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("group-readback.json").toFile(),
                        Map.of("first", first, "resumed", resumed, "target", table, "readback", actual,
                                "resumedExecutedJobs", called, "ledger", ledgerPath.toString(),
                                "firstSlots", originalSlots, "resumedSlots", reopened.groupMembers(resumed.runId()),
                                "parentProof", reopened.get(resumed.runId()).payloadJson()));
                // A later target correction must invalidate reuse rather than silently repairing it.
                var key = new StockBasicSnapshotKey(request.logicalDate().atStartOfDay()
                        .toInstant(java.time.ZoneOffset.UTC), "000001.SZ");
                var original = port.readback(List.of(key)).getFirst();
                var stock = original.stock();
                var changed = new StockBasicSnapshot(original.snapshotTimestamp(), new StockBasic(stock.tsCode(),
                        stock.symbol(), "F013 injected drift", stock.area(), stock.industry(), stock.listDate()));
                var mutation = new VerifiedBatchExecutor<>(new VerifiedBatchExecutor.Policy(1, 4096, 1,
                        java.time.Duration.ofSeconds(20), java.time.Duration.ofMillis(50)),
                        StockBasicWritePort.CODEC, port).execute(List.of(changed).iterator());
                assertEquals(VerifiedBatchExecutor.Status.VERIFIED, mutation.status());
                called.clear();
                var rejected = new SyncGroupRunner(groups, catalog, reopened).resume("group-drift-" + suffix,
                        resumed.runId(), "group.live", 1, request, executor);
                assertEquals(SyncRunState.FAILED, rejected.state());
                assertTrue(called.isEmpty(), "Target drift must stop before executing or writing a child");
                assertEquals("F013 injected drift", port.readback(List.of(key)).getFirst().stock().name());
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("drift-rejected.json").toFile(),
                        Map.of("result", rejected, "parentEvidence", reopened.get(rejected.runId()).payloadJson(),
                                "executedJobs", called, "targetValueAfterRejection", "F013 injected drift",
                                "injectedMutation", mutation, "oldParentState", reopened.get(resumed.runId()).state()));
                verified = true;
            } finally {
                if (verified) jdbc.execute("DROP TABLE " + table);
            }
        }
    }
}
