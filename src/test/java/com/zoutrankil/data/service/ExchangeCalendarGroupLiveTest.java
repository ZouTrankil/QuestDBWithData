package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class ExchangeCalendarGroupLiveTest {
    @Test void registeredGroupWritesAndRevalidatesCompletedCalendarChild() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");String table="java_d001_group_"+id;
            Path evidence=Path.of("artifacts/java-migration/D001","group-"+id),ledgerPath=Path.of("var","D001-group-"+id+".sqlite");
            var jdbc=ctx.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE "+table+" (exchange SYMBOL,cal_date TIMESTAMP,is_open INT,pretrade_date STRING) "
                    +"TIMESTAMP(cal_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(exchange,cal_date)");
            boolean verified=false;
            try {
                var calendar=new ExchangeCalendarJobService(ctx.getBean(TusharePageService.class),jdbc,ctx.getBean(QuestDB.class),
                        ctx.getBean(QuestDbProperties.class),ledgerPath.toString(),table);
                var jobs=ctx.getBean(SyncJobRegistry.class);
                var groups=new StockBasicGroupService(jobs,ctx.getBean(StockBasicJobService.class),calendar,ledgerPath.toString());
                var registry=new SyncGroupRegistry(groups.definitions(),jobs);
                var day=LocalDate.of(2026,9,28);
                var plan=SyncGroupPlan.prepare(registry,jobs,"group.exchange_calendar_manual",1,day,null,
                        new SyncGroupPlan.Window(day.minusDays(3),day),Map.of("exchanges",List.of("SSE","SZSE")),Map.of());
                var first=groups.runPlan(plan,null);
                assertEquals(SyncRunState.VERIFIED,first.state());assertEquals(8,first.members().getFirst().verifiedRows());
                var ledger=new SyncRunLedger(ledgerPath);
                assertEquals(first.runId(),ledger.getRun(first.members().getFirst().childRunId()).parentRunId());
                var resumed=groups.runPlan(plan,first.runId());
                assertEquals(SyncRunState.VERIFIED,resumed.state());assertTrue(resumed.members().getFirst().reused());
                var rows=jdbc.queryForList("SELECT exchange,cast(cal_date as long) AS date_micros,is_open,pretrade_date FROM "+table
                        +" ORDER BY exchange,cal_date LIMIT 9");assertEquals(8,rows.size());
                Files.createDirectories(evidence);
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("group-readback.json").toFile(),
                        Map.of("table",table,"ledger",ledgerPath.toString(),"first",first,"resumed",resumed,"readback",rows));
                verified=true;
            } finally { if(verified) jdbc.execute("DROP TABLE "+table); }
        }
    }
}
