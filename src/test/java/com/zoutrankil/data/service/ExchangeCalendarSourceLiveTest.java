package com.zoutrankil.data.service;

import com.zoutrankil.data.calendar.application.ExchangeCalendarSlices;
import com.zoutrankil.data.calendar.application.ExchangeCalendarSource;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.config.TushareProperties;
import com.zoutrankil.data.domain.JobDefinitionJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class ExchangeCalendarSourceLiveTest {
    @Test void realBoundedSourceIncludesClosedDaysForBothExchanges() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->
                ((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            Path evidence=Path.of("artifacts/java-migration/D001","source-"+UUID.randomUUID());
            var source=new ExchangeCalendarSource(context.getBean(TusharePageService.class),evidence);
            int rate=context.getBean(TushareProperties.class).effectiveEndpointLimits().get("trade_cal");
            assertTrue(rate>0 && rate<=20);
            var pages=new ArrayList<Object>();
            for(String exchange:List.of("SSE","SZSE")) {
                var slice=new ExchangeCalendarSlices.Slice(exchange,LocalDate.of(2026,9,25),LocalDate.of(2026,9,28));
                var page=source.fetch(slice,()->false);
                assertEquals(4,page.rows().size());
                assertTrue(page.rows().stream().anyMatch(row->!row.open()));
                assertTrue(page.rows().stream().anyMatch(row->row.open()));
                assertTrue(page.rows().stream().allMatch(row->row.exchange().equals(exchange)));
                assertTrue(Files.isRegularFile(Path.of(page.responseEvidence())));
                pages.add(Map.of("exchange",exchange,"rows",page.rows(),"sourceFingerprint",page.sourceFingerprint(),
                        "responseEvidence",page.responseEvidence()));
            }
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("source-validation.json").toFile(),
                    Map.of("passed",true,"effectiveEndpointPerMinute",rate,"pages",pages,"sourceRows",8,"writes",0));
        }
    }
}
