package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.StockBasicMapper;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
class PersistentWriteGroupLiveTest {
    private static String physicalTarget(JdbcTemplate jdbc, String table) {
        var rows = jdbc.queryForList("SELECT id, directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null)
            throw new IllegalStateException("Exact physical test target identity required");
        String identity = table + ":" + rows.getFirst().get("id") + ":" + rows.getFirst().get("directoryName");
        try {
            return "questdb-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    static class Port implements VerifiedBatchExecutor.Port<StockBasicSnapshot,StockBasicSnapshotKey> {
        final StockBasicWritePort delegate; int preflights, sends; boolean failAfterInitialCheck;
        Port(StockBasicWritePort delegate) { this.delegate = delegate; }
        public void preflight() throws Exception {
            delegate.preflight();
            if (++preflights > 1 && failAfterInitialCheck) throw new IllegalStateException("Injected target outage after preflight");
        }
        public void send(List<StockBasicSnapshot> rows) throws Exception { sends++; delegate.send(rows); }
        public List<StockBasicSnapshot> readback(List<StockBasicSnapshotKey> keys) throws Exception { return delegate.readback(keys); }
        public boolean walSettled() throws Exception { return delegate.walSettled(); }
        public boolean uncertainSenderStopped() throws Exception { return delegate.uncertainSenderStopped(); }
    }
    @Test void realSourceWritesSurvivePartialGroupAndRepeatedRecoveryWithoutResending() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class); app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var ctx = app.run()) {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            var a = PersistentWriteGroupRunnerTest.definition("java_f015_a_" + suffix);
            var b = PersistentWriteGroupRunnerTest.definition("java_f015_b_" + suffix);
            var registry = new DatasetRegistry(List.of(() -> a, () -> b));
            var jdbc = ctx.getBean(JdbcTemplate.class); jdbc.setQueryTimeout(20);
            Path evidence = Path.of("artifacts/java-migration/F015", suffix); Files.createDirectories(evidence);
            Path ledger = Path.of("var", "F015-" + suffix + ".sqlite3");
            for (var d : List.of(a,b)) jdbc.execute("CREATE TABLE " + d.objectName()
                    + " (snapshot_ts TIMESTAMP,ts_code SYMBOL,symbol SYMBOL,name STRING,area SYMBOL,industry SYMBOL,list_date STRING)"
                    + " TIMESTAMP(snapshot_ts) PARTITION BY DAY WAL DEDUP UPSERT KEYS(snapshot_ts,ts_code)");
            boolean verified = false;
            try {
                var firstPort = new Port(new StockBasicWritePort(a.objectName(), jdbc, ctx.getBean(QuestDB.class)));
                var secondPort = new Port(new StockBasicWritePort(b.objectName(), jdbc, ctx.getBean(QuestDB.class)));
                var source = new StockBasicSyncAdapter(ctx.getBean(TusharePageService.class), ctx.getBean(StockBasicMapper.class),
                        firstPort.delegate, evidence.resolve("tushare-source"));
                var sourceRequest = StockBasicSyncAdapter.definition(true).freeze(null,
                        Map.of("codes", List.of("000001.SZ", "600000.SH")), null, null, LocalDate.of(2026,9,29));
                var rows = new ArrayList<StockBasicSnapshot>();
                source.preflight(sourceRequest);
                var sourceCompletion = source.fetch(sourceRequest, page -> rows.addAll(page.rows()), () -> false);
                assertTrue(sourceCompletion.complete()); assertEquals(2, rows.size());
                assertEquals(List.of("000001.SZ", "600000.SH"), rows.stream().map(r -> r.stock().tsCode()).toList());
                var request = new WriteGroupRequest("batch-" + suffix, sourceRequest.logicalDate(), List.of(
                        new WriteGroupRequest.Member("a", a.datasetId(), 1, "batch-a", List.of(StockBasicWriteGroupService.encode(rows.get(0)))),
                        new WriteGroupRequest.Member("b", b.datasetId(), 1, "batch-b", List.of(StockBasicWriteGroupService.encode(rows.get(1))))));
                Path requestFile = evidence.resolve("write-request.json");
                JobDefinitionJson.mapper().writeValue(requestFile.toFile(), request);
                request = new WriteGroupJson(registry).read(requestFile);
                String targetA = physicalTarget(jdbc, a.objectName());
                String targetB = physicalTarget(jdbc, b.objectName());
                var plan = WriteGroupPlan.prepare(request, registry, Map.of(a.datasetId(), targetA, b.datasetId(), targetB));
                Map<String,PreparedWriteAdapter<?,?>> adapters = Map.of(
                        "a", new PreparedWriteAdapter<>(plan,"a",StockBasicWriteGroupService::decode,StockBasicWriteGroupService::encode,
                                StockBasicWritePort.CODEC,firstPort,()->physicalTarget(jdbc,a.objectName()),evidence.resolve("member-a")),
                        "b", new PreparedWriteAdapter<>(plan,"b",StockBasicWriteGroupService::decode,StockBasicWriteGroupService::encode,
                                StockBasicWritePort.CODEC,secondPort,()->physicalTarget(jdbc,b.objectName()),evidence.resolve("member-b")));
                secondPort.failAfterInitialCheck = true;
                var first = new PersistentWriteGroupRunner(ledger,evidence,registry).run("first-"+suffix,plan,adapters,null);
                assertEquals(SyncRunState.PARTIAL, first.state());
                assertEquals(1, firstPort.sends); assertEquals(0, secondPort.sends);
                assertEquals(1, jdbc.queryForList("SELECT ts_code FROM " + a.objectName() + " LIMIT 2").size());
                assertTrue(jdbc.queryForList("SELECT ts_code FROM " + b.objectName() + " LIMIT 2").isEmpty());
                secondPort.failAfterInitialCheck = false;
                var resumed = new PersistentWriteGroupRunner(ledger,evidence,registry)
                        .run("resumed-"+suffix,plan,adapters,first.runId());
                assertEquals(SyncRunState.VERIFIED, resumed.state()); assertTrue(resumed.members().getFirst().reused());
                var repeated = new PersistentWriteGroupRunner(ledger,evidence,registry)
                        .run("repeated-"+suffix,plan,adapters,resumed.runId());
                assertEquals(SyncRunState.VERIFIED, repeated.state());
                assertTrue(repeated.members().stream().allMatch(SyncGroupRunner.MemberOutcome::reused));
                assertEquals(1, firstPort.sends); assertEquals(1, secondPort.sends);
                assertEquals(List.of(rows.get(0)), firstPort.readback(List.of(rows.get(0).key())));
                assertEquals(List.of(rows.get(1)), secondPort.readback(List.of(rows.get(1).key())));
                assertTrue(firstPort.walSettled()); assertTrue(secondPort.walSettled());
                var actual = new LinkedHashMap<String,Object>();
                for (var d : List.of(a,b)) actual.put(d.objectName(), jdbc.queryForList(
                        "SELECT cast(snapshot_ts as long) AS snapshot_micros,ts_code,symbol,name,area,industry,list_date FROM "
                                + d.objectName() + " ORDER BY snapshot_ts,ts_code LIMIT 3"));
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("write-group-readback.json").toFile(),
                        Map.of("first",first,"resumed",resumed,"repeated",repeated,"sourceCompletion",sourceCompletion,
                                "sourceRows",rows,"actual",actual,"physicalSendCalls",Map.of("a",firstPort.sends,"b",secondPort.sends),
                                "ledger",ledger.toString(),"targets",List.of(a.objectName(),b.objectName())));
                var reopened = SyncRunLedger.openReadOnly(ledger);
                assertEquals(SyncRunState.PARTIAL, reopened.get(first.runId()).state());
                assertEquals(SyncRunState.VERIFIED, reopened.get(repeated.runId()).state());
                verified = true;
            } finally {
                if (verified) for (var d : List.of(a,b)) jdbc.execute("DROP TABLE " + d.objectName());
            }
        }
    }
}
