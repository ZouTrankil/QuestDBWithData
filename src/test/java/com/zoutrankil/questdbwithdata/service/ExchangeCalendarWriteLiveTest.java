package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.ExchangeCalendarWritePort;
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
class ExchangeCalendarWriteLiveTest {
    @Test void boundedRealSourceWritesAndRereadsAllCalendarValuesIdempotently() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->
                ((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            String suffix=UUID.randomUUID().toString().replace("-","");
            String table="java_d001_calendar_"+suffix;
            Path evidence=Path.of("artifacts/java-migration/D001",suffix);
            var jdbc=context.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE "+table+" (exchange SYMBOL,cal_date TIMESTAMP,is_open INT,pretrade_date STRING) "
                    +"TIMESTAMP(cal_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(exchange,cal_date)");
            boolean verified=false;
            try {
                var source=new ExchangeCalendarSource(context.getBean(TusharePageService.class),evidence);
                var expected=new ArrayList<ExchangeCalendar>();
                for(var slice:ExchangeCalendarSlices.bounded(List.of("SSE","SZSE"),
                        LocalDate.of(2026,9,25),LocalDate.of(2026,9,28)))
                    expected.addAll(source.fetch(slice,()->false).rows());
                assertEquals(8,expected.size());
                var port=new ExchangeCalendarWritePort(table,jdbc,context.getBean(QuestDB.class));
                var writer=new VerifiedBatchExecutor<>(new VerifiedBatchExecutor.Policy(366,1024*1024,2,
                        Duration.ofSeconds(20),Duration.ofMillis(100)),ExchangeCalendarWritePort.CODEC,port);
                var first=writer.execute(expected.iterator());
                assertEquals(VerifiedBatchExecutor.Status.VERIFIED,first.status(),first.reason());
                assertEquals(8,first.verifiedRows());
                var repeated=writer.execute(expected.iterator());
                assertEquals(VerifiedBatchExecutor.Status.VERIFIED,repeated.status(),repeated.reason());
                var actual=port.readback(expected.stream().map(ExchangeCalendar::key).toList());
                assertEquals(expected,actual);
                assertTrue(port.walSettled());
                var sql="SELECT exchange,cast(cal_date as long) AS date_micros,is_open,pretrade_date FROM "+table
                        +" ORDER BY exchange,cal_date LIMIT 9";
                var raw=jdbc.queryForList(sql);
                assertEquals(8,raw.size());
                for(int i=0;i<expected.size();i++) {
                    var e=expected.get(i);var a=raw.get(i);
                    assertEquals(e.exchange(),a.get("exchange"));
                    assertEquals(e.calendarDate().atStartOfDay(ZoneOffset.UTC).toEpochSecond()*1000000L,
                            ((Number)a.get("date_micros")).longValue());
                    assertEquals(e.open()?1:0,((Number)a.get("is_open")).intValue());
                    assertEquals(e.previousTradeDate()==null?null:e.previousTradeDate().format(
                            java.time.format.DateTimeFormatter.BASIC_ISO_DATE),a.get("pretrade_date"));
                }
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("write-readback.json").toFile(),
                        Map.of("table",table,"expected",expected,"first",first,"repeated",repeated,"query",sql,
                                "readback",raw,"matchedRows",8,"mismatchedRows",0,"duplicateKeys",0));
                verified=true;
            } finally { if(verified) jdbc.execute("DROP TABLE "+table); }
        }
    }
}
