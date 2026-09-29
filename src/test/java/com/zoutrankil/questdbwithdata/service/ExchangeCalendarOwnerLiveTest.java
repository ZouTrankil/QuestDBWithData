package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.config.QuestDbProperties;
import com.zoutrankil.questdbwithdata.domain.*;
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
class ExchangeCalendarOwnerLiveTest {
    @Test void ownerAutomaticallyReopensReadsAndPlansIncrementalCoverage() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String id=UUID.randomUUID().toString().replace("-","");String table="java_d001_owner_"+id;
            Path evidence=Path.of("artifacts/java-migration/D001","owner-"+id),ledger=Path.of("var","D001-owner-"+id+".sqlite");
            var jdbc=ctx.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE "+table+" (exchange SYMBOL,cal_date TIMESTAMP,is_open INT,pretrade_date STRING) "
                    +"TIMESTAMP(cal_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(exchange,cal_date)");
            boolean verified=false;
            try {
                var owner=new ExchangeCalendarJobService(ctx.getBean(TusharePageService.class),jdbc,ctx.getBean(QuestDB.class),
                        ctx.getBean(QuestDbProperties.class),ledger.toString(),table);
                LocalDate from=LocalDate.of(2026,9,25),end=from.plusDays(3);var exchanges=List.of("SSE","SZSE");
                var first=owner.run(exchanges,from,from.plusDays(1),end,null);
                assertEquals(SyncRunState.VERIFIED,first.state(),first.errorCode());
                var reopened=new ExchangeCalendarJobService(ctx.getBean(TusharePageService.class),jdbc,ctx.getBean(QuestDB.class),
                        ctx.getBean(QuestDbProperties.class),ledger.toString(),table);
                var plan=reopened.plan(exchanges,from,end,end,null);
                assertEquals(4,plan.checkedTargetRows());assertEquals(from,plan.request().from());
                assertEquals(Map.of("SSE",from.plusDays(1),"SZSE",from.plusDays(1)),plan.checkpointCandidates());
                var second=reopened.execute(plan,null);
                assertEquals(SyncRunState.VERIFIED,second.state(),second.errorCode());assertEquals(8,second.verifiedRows());
                var confirmed=reopened.plan(exchanges,from,end,end,null);
                assertEquals(8,confirmed.checkedTargetRows());
                assertEquals(Map.of("SSE",end,"SZSE",end),confirmed.checkpointCandidates());
                Files.createDirectories(evidence);
                var rows=jdbc.queryForList("SELECT exchange,cast(cal_date as long) AS date_micros,is_open,pretrade_date FROM "+table
                        +" ORDER BY exchange,cal_date LIMIT 9");assertEquals(8,rows.size());
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("owner-readback.json").toFile(),
                        Map.of("table",table,"targetId",confirmed.targetId(),"ledger",ledger.toString(),"first",first,"second",second,
                                "checkpointBefore",plan.checkpointCandidates(),"checkpointAfter",confirmed.checkpointCandidates(),
                                "checkedBefore",plan.checkedTargetRows(),"checkedAfter",confirmed.checkedTargetRows(),"readback",rows));
                verified=true;
            } finally { if(verified) jdbc.execute("DROP TABLE "+table); }
        }
    }
}
