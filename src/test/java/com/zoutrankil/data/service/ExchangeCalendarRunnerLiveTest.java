package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
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
class ExchangeCalendarRunnerLiveTest {
    @Test void reopenedVerifiedLedgerAndActualCoverageDriveOverlappingIncrementalRun() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");String table="java_d001_runner_"+id;
            Path evidence=Path.of("artifacts/java-migration/D001","runner-"+id), db=Path.of("var","D001-"+id+".sqlite");
            var jdbc=context.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE "+table+" (exchange SYMBOL,cal_date TIMESTAMP,is_open INT,pretrade_date STRING) "
                    +"TIMESTAMP(cal_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(exchange,cal_date)");
            boolean verified=false;
            try {
                var port=new ExchangeCalendarWritePort(table,jdbc,context.getBean(QuestDB.class));
                var ledger=new SyncRunLedger(db);var exchanges=List.of("SSE","SZSE");
                LocalDate from=LocalDate.of(2026,9,25),firstEnd=from.plusDays(1),end=from.plusDays(3);
                var definition=ExchangeCalendarSyncAdapter.definition(true);
                var firstRequest=definition.freeze(null,Map.of("exchanges",exchanges),from,firstEnd,end);
                var firstAdapter=new ExchangeCalendarSyncAdapter(new ExchangeCalendarSource(context.getBean(TusharePageService.class),
                        evidence.resolve("first")),port,evidence.resolve("first"));
                var first=new SyncJobRunner<ExchangeCalendar,ExchangeCalendar.Key>(ledger,new DatasetIntervalLock(db))
                        .run("first-"+id,null,table,firstRequest,firstAdapter,()->false);
                assertEquals(SyncRunState.VERIFIED,first.state(),first.errorCode());assertEquals(4,first.verifiedRows());
                var reopened=SyncRunLedger.openReadOnly(db);
                var before=ExchangeCalendarCoverage.load(reopened,table,exchanges,from);
                assertEquals(Map.of("SSE",firstEnd,"SZSE",firstEnd),before);
                for(String exchange:exchanges) {
                    var keys=from.datesUntil(firstEnd.plusDays(1)).map(d->new ExchangeCalendar.Key(exchange,d)).toList();
                    ExchangeCalendarSlices.complete(new ExchangeCalendarSlices.Slice(exchange,from,firstEnd),port.readback(keys));
                }
                var slices=ExchangeCalendarSlices.incremental(exchanges,from,end,before,definition.revisionDays());
                assertEquals(from,slices.getFirst().from());
                var secondRequest=definition.freeze(null,Map.of("exchanges",exchanges),slices.getFirst().from(),end,end);
                var secondAdapter=new ExchangeCalendarSyncAdapter(new ExchangeCalendarSource(context.getBean(TusharePageService.class),
                        evidence.resolve("second")),port,evidence.resolve("second"));
                var second=new SyncJobRunner<ExchangeCalendar,ExchangeCalendar.Key>(new SyncRunLedger(db),new DatasetIntervalLock(db))
                        .run("second-"+id,null,table,secondRequest,secondAdapter,()->false);
                assertEquals(SyncRunState.VERIFIED,second.state(),second.errorCode());assertEquals(8,second.verifiedRows());
                var after=ExchangeCalendarCoverage.load(SyncRunLedger.openReadOnly(db),table,exchanges,from);
                assertEquals(Map.of("SSE",end,"SZSE",end),after);
                var rows=jdbc.queryForList("SELECT exchange,cast(cal_date as long) AS date_micros,is_open,pretrade_date FROM "+table
                        +" ORDER BY exchange,cal_date LIMIT 9");assertEquals(8,rows.size());
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("runner-readback.json").toFile(),
                        Map.of("first",first,"second",second,"checkpointBefore",before,"checkpointAfter",after,
                                "table",table,"ledger",db.toString(),"readback",rows,"revisionDays",definition.revisionDays()));
                verified=true;
            } finally { if(verified) jdbc.execute("DROP TABLE "+table); }
        }
    }
}
