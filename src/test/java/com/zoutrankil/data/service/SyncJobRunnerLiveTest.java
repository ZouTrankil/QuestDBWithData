package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class SyncJobRunnerLiveTest {
    @Test void realSourcePagesConvergeThroughRunnerIntoQuestDbAndLedger() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String suffix=UUID.randomUUID().toString().replace("-","");
            String table="java_f011_"+suffix, run="run-"+suffix;
            Path evidence=Path.of("artifacts/java-migration/F011",run);
            Path ledgerPath=Path.of("var","F011-"+suffix+".sqlite3");
            var ledger=new SyncRunLedger(ledgerPath);
            var locks=new DatasetIntervalLock(ledgerPath);
            LocalDate logicalDate=LocalDate.of(2026,9,29);
            var request=StockBasicSyncAdapter.definition(true).freeze(null,
                    Map.of("codes",List.of("000001.SZ","600000.SH")),null,null,logicalDate);
            jdbc.execute("CREATE TABLE "+table+" (snapshot_ts TIMESTAMP,ts_code SYMBOL,symbol SYMBOL,name STRING,"
                    +"area SYMBOL,industry SYMBOL,list_date STRING) TIMESTAMP(snapshot_ts) PARTITION BY DAY "
                    +"WAL DEDUP UPSERT KEYS(snapshot_ts,ts_code)");
            boolean verified=false;
            try {
                var adapter=new StockBasicSyncAdapter(context.getBean(TusharePageService.class),
                        context.getBean(StockBasicMapper.class),new StockBasicWritePort(table,jdbc,
                        context.getBean(QuestDB.class)),evidence);
                var result=new SyncJobRunner<StockBasicSnapshot,StockBasicSnapshotKey>(ledger,locks)
                        .run(run,null,"isolated-questdb",request,adapter,()->false);
                assertEquals(SyncRunState.VERIFIED,result.state(),result.errorCode());
                assertEquals(2,result.sourceRows()); assertEquals(2,result.verifiedRows());
                String rerun="run-"+UUID.randomUUID().toString().replace("-","");
                var replay=new SyncJobRunner<StockBasicSnapshot,StockBasicSnapshotKey>(ledger,locks)
                        .run(rerun,null,"isolated-questdb",request,adapter,()->false);
                assertEquals(SyncRunState.VERIFIED,replay.state(),replay.errorCode());
                assertEquals(2,replay.verifiedRows());
                var actual=jdbc.queryForList("SELECT cast(snapshot_ts as long) AS snapshot_micros,ts_code,symbol,name,area,industry,list_date FROM "
                        +table+" ORDER BY ts_code LIMIT 3");
                assertEquals(2,actual.size());
                assertEquals(List.of("000001.SZ","600000.SH"),actual.stream().map(r->r.get("ts_code")).toList());
                long frozenMicros=logicalDate.atStartOfDay().toInstant(ZoneOffset.UTC).getEpochSecond()*1000000L;
                assertTrue(actual.stream().allMatch(r->((Number)r.get("snapshot_micros")).longValue()==frozenMicros));
                var reopened=SyncRunLedger.openReadOnly(ledgerPath);
                assertEquals(logicalDate.toString(),reopened.getRun(run).logicalDate());
                var entries=reopened.entries(run,null,10);
                assertEquals(4,entries.size());
                assertTrue(entries.stream().allMatch(e->e.state()==SyncRunState.VERIFIED));
                assertEquals(SyncRunState.VERIFIED,reopened.get(rerun).state());
                var releaseProbe=locks.acquire(run,DatasetIntervalLock.Scope.allDates("stock_basic_snapshot"));
                assertNotNull(releaseProbe,
                        "Verified runner must have released its interval");
                locks.releaseVerified(releaseProbe);
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("runner-readback.json").toFile(),
                        Map.of("result",result,"rerun",replay,"readback",actual,"ledgerEntries",entries,
                                "runSnapshot",reopened.getRun(run),"table",table,"ledgerPath",ledgerPath.toString()));
                verified=true;
            } finally { if(verified) jdbc.execute("DROP TABLE "+table); }
        }
    }
}
