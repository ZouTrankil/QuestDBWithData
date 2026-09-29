package com.zoutrankil.questdbwithdata.service;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class ScheduleDispatchLiveTest {
    @Test void scheduledSlotLinksRealSourceRunnerAndExactQuestDbValuesOnce() throws Exception {
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String table = "java_f016_" + suffix, runId = "schedule-run-" + suffix;
            Path evidence = Path.of("artifacts/java-migration/F016", suffix);
            Files.createDirectories(evidence);
            Path path = Path.of("var", "F016-" + suffix + ".sqlite");
            var definition = StockBasicSyncAdapter.definition(true);
            var jobs = new SyncJobRegistry(List.of(definition),
                    new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)),
                    Map.of(definition.datasetId(), definition.supportedModes()),
                    new SyncJobRegistry.Policies(Set.of(definition.ratePolicyRef()),
                            Set.of(definition.slicePolicyRef()), Set.of(definition.verificationPolicyRef())));
            var groups = new SyncGroupRegistry(List.of(), jobs);
            var store = new SyncScheduleStore(path);
            Instant due = Instant.parse("2026-09-28T16:30:00Z");
            var manager = new SyncScheduleManager(store, jobs, groups, d -> true,
                    Clock.fixed(due.plusSeconds(60), ZoneOffset.UTC));
            manager.put(new SyncScheduleDefinition("live.schedule", SyncScheduleDefinition.Target.JOB,
                    definition.jobId(), definition.version(), true, ZoneId.of("Asia/Shanghai"),
                    SyncScheduleDefinition.Kind.DAILY, LocalTime.of(0,30), Set.of(),
                    SyncScheduleDefinition.DayRule.CALENDAR, SyncScheduleDefinition.Misfire.RUN_ONCE,
                    Duration.ofMinutes(5), Map.of("codes", "000001.SZ")));
            jdbc.execute("CREATE TABLE " + table + " (snapshot_ts TIMESTAMP,ts_code SYMBOL,symbol SYMBOL,name STRING,"
                    + "area SYMBOL,industry SYMBOL,list_date STRING) TIMESTAMP(snapshot_ts) PARTITION BY DAY "
                    + "WAL DEDUP UPSERT KEYS(snapshot_ts,ts_code)");
            boolean verified = false;
            try {
                var port = new StockBasicWritePort(table, jdbc, context.getBean(QuestDB.class));
                var delegate = new StockBasicSyncAdapter(context.getBean(TusharePageService.class),
                        context.getBean(StockBasicMapper.class), port, evidence);
                var expected = new ArrayList<StockBasicSnapshot>();
                var adapter = new SyncJobRunner.Adapter<StockBasicSnapshot, StockBasicSnapshotKey>() {
                    public void preflight(SyncJobDefinition.FrozenRequest r) throws Exception { delegate.preflight(r); }
                    public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest r,
                            SyncJobRunner.PageConsumer<StockBasicSnapshot> consumer, BooleanSupplier cancel) throws Exception {
                        return delegate.fetch(r, page -> { expected.addAll(page.rows()); consumer.accept(page); }, cancel);
                    }
                    public VerifiedBatchExecutor.Codec<StockBasicSnapshot, StockBasicSnapshotKey> codec() { return delegate.codec(); }
                    public VerifiedBatchExecutor.Port<StockBasicSnapshot, StockBasicSnapshotKey> port() { return port; }
                };
                var calls = new AtomicInteger();
                var history = manager.tick((schedule, slot) -> {
                    calls.incrementAndGet();
                    var request = jobs.prepare(schedule.targetId(), schedule.targetVersion(), null,
                            Map.of("codes", List.of(schedule.parameters().get("codes"))), null, null, slot.localDate());
                    var result = new SyncJobRunner<StockBasicSnapshot, StockBasicSnapshotKey>(
                            new SyncRunLedger(path), new DatasetIntervalLock(path))
                            .run(runId, null, table, request, adapter, () -> false);
                    return new SyncScheduleManager.RunResult(result.runId(), result.state());
                });
                assertEquals(SyncScheduleStore.State.VERIFIED, history.getFirst().state());
                assertEquals(runId, history.getFirst().runId());
                assertEquals(1, expected.size());
                var row = expected.getFirst();
                var actual = jdbc.queryForList("SELECT cast(snapshot_ts as long) AS snapshot_micros,ts_code,symbol,name,"
                        + "area,industry,list_date FROM " + table + " LIMIT 2");
                assertEquals(1, actual.size());
                var found = actual.getFirst();
                assertEquals(row.snapshotTimestamp().getEpochSecond()*1000000L,
                        ((Number) found.get("snapshot_micros")).longValue());
                assertEquals(row.stock().tsCode(), found.get("ts_code"));
                assertEquals(row.stock().symbol(), found.get("symbol"));
                assertEquals(row.stock().name(), found.get("name"));
                assertEquals(row.stock().area(), found.get("area"));
                assertEquals(row.stock().industry(), found.get("industry"));
                assertEquals(row.stock().listDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), found.get("list_date"));
                var reopened = new SyncScheduleManager(new SyncScheduleStore(path), jobs, groups, d -> true,
                        Clock.fixed(due.plusSeconds(120), ZoneOffset.UTC));
                reopened.tick((d,s) -> { throw new AssertionError("completed slot dispatched twice"); });
                assertEquals(1, calls.get());
                var run = SyncRunLedger.openReadOnly(path).getRun(runId);
                assertEquals("2026-09-29", run.logicalDate());
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                        evidence.resolve("schedule-readback.json").toFile(), Map.of("history", history,
                                "run", run, "expected", expected, "readback", actual,
                                "table", table, "dispatchCalls", calls.get(), "ledgerPath", path.toString()));
                verified = true;
            } finally { if (verified) jdbc.execute("DROP TABLE " + table); }
        }
    }
}
