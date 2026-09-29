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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in explicit schedule tick through the existing single-dataset runner and isolated WAL target. */
@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class SyncScheduleLiveTest {
    @Test void dueSlotDispatchesRealSourceAndVerifiesIsolatedQuestDbOnce() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try (var ctx=app.run()) {
            String suffix=UUID.randomUUID().toString().replace("-","");
            String table="java_f016_schedule_"+suffix;
            Path ledgerPath=Path.of("var","F016-"+suffix+".sqlite3");
            Path evidence=Path.of("artifacts/java-migration/F016",suffix);
            Files.createDirectories(evidence);
            var jdbc=ctx.getBean(JdbcTemplate.class); jdbc.setQueryTimeout(20);
            jdbc.execute("CREATE TABLE "+table+" (snapshot_ts TIMESTAMP,ts_code SYMBOL,symbol SYMBOL,name STRING,"
                    +"area SYMBOL,industry SYMBOL,list_date STRING) TIMESTAMP(snapshot_ts) PARTITION BY DAY "
                    +"WAL DEDUP UPSERT KEYS(snapshot_ts,ts_code)");
            boolean verified=false;
            try {
                var identity=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
                assertEquals(1,identity.size());
                String physical=table+":"+identity.getFirst().get("id")+":"+identity.getFirst().get("directoryName");
                String target="questdb-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(physical.getBytes(StandardCharsets.UTF_8)));
                var definition=StockBasicSyncAdapter.definition(true);
                var datasets=new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION));
                var jobs=new SyncJobRegistry(List.of(definition),datasets,
                        Map.of(definition.datasetId(),definition.supportedModes()),new SyncJobRegistry.Policies(
                        Set.of(definition.ratePolicyRef()),Set.of(definition.slicePolicyRef()),
                        Set.of(definition.verificationPolicyRef())));
                var store=new SyncScheduleStore(ledgerPath);
                var clock=Clock.fixed(Instant.parse("2026-09-29T01:30:00Z"),ZoneOffset.UTC);
                var manager=new SyncScheduleManager(store,jobs,new SyncGroupRegistry(List.of(),jobs),d -> true,clock);
                manager.put(new SyncScheduleDefinition("f016.live",SyncScheduleDefinition.Target.JOB,
                        "data.stock_basic",2,true,ZoneId.of("Asia/Shanghai"),SyncScheduleDefinition.Kind.DAILY,
                        LocalTime.of(9,30),Set.of(),SyncScheduleDefinition.DayRule.EXCHANGE_SESSION,
                        SyncScheduleDefinition.Misfire.RUN_ONCE,Duration.ofMinutes(5),Map.of("codes","000001.SZ")));
                var port=new StockBasicWritePort(table,jdbc,ctx.getBean(QuestDB.class));
                var calls=new int[1];
                SyncScheduleManager.Dispatcher dispatcher=(schedule,slot) -> {
                    calls[0]++;
                    var frozen=jobs.prepare(schedule.targetId(),schedule.targetVersion(),null,
                            Map.of("codes",List.of("000001.SZ")),null,null,slot.localDate());
                    var adapter=new StockBasicSyncAdapter(ctx.getBean(TusharePageService.class),
                            ctx.getBean(StockBasicMapper.class),port,evidence.resolve("source"));
                    var result=new SyncJobRunner<StockBasicSnapshot,StockBasicSnapshotKey>(
                            new SyncRunLedger(ledgerPath),new DatasetIntervalLock(ledgerPath))
                            .run("scheduled-"+suffix,null,target,frozen,adapter,() -> false);
                    return new SyncScheduleManager.RunResult(result.runId(),result.state());
                };
                var first=manager.tick(dispatcher);
                assertEquals(1,calls[0]); assertEquals(SyncScheduleStore.State.VERIFIED,first.getFirst().state());
                manager.tick(dispatcher); assertEquals(1,calls[0]);
                var rows=jdbc.queryForList("SELECT cast(snapshot_ts as long) AS snapshot_micros,"
                        +"ts_code,symbol,name,area,industry,list_date FROM "+table+" ORDER BY ts_code LIMIT 2");
                assertEquals(1,rows.size()); assertEquals("000001.SZ",rows.getFirst().get("ts_code"));
                assertTrue(port.walSettled());
                var run=new SyncRunLedger(ledgerPath);
                assertEquals(SyncRunState.VERIFIED,run.get("scheduled-"+suffix).state());
                assertEquals("scheduled-"+suffix,store.history("f016.live",10).getFirst().runId());
                new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(
                        evidence.resolve("schedule-readback.json").toFile(),Map.of("table",table,"targetId",target,
                                "schedule",store.get("f016.live"),"history",store.history("f016.live",10),
                                "rows",rows,"ledger",ledgerPath.toString(),"dispatchCalls",calls[0]));
                verified=true;
            } finally {
                if (verified) jdbc.execute("DROP TABLE "+table);
            }
        }
    }
}
