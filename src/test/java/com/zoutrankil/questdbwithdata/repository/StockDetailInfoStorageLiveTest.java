package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ",matches="1")
class StockDetailInfoStorageLiveTest {
    @Test void boundedPhysicalSnapshotPreservesLegacyValuesAndMatchesKeyReads() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var storage=new StockDetailInfoStorage(jdbc,"stock_detail_info");
            var snapshot=storage.snapshot();assertFalse(snapshot.rows().isEmpty());
            assertEquals(snapshot.rows().size(),snapshot.businessRows().size());
            var codes=List.of("000001.SZ","000003.SZ","600000.SH");
            var selected=storage.readKeys(codes);assertEquals(3,selected.size());
            assertEquals(snapshot.rows().stream().filter(r->codes.contains(r.tsCode())).toList(),selected);
            assertEquals("None",selected.getFirst().delistDate());
            assertEquals(jdbc.queryForObject("SELECT count() FROM stock_detail_info",Long.class),Long.valueOf(snapshot.rows().size()));
            var proof=new LinkedHashMap<String,Object>();proof.put("readOnly",true);proof.put("table","stock_detail_info");
            proof.put("identity",snapshot.identity());proof.put("rows",snapshot.rows().size());proof.put("bytes",snapshot.bytes());
            proof.put("fingerprint",snapshot.fingerprint());proof.put("selected",selected);proof.put("matchedRows",3);
            var path=Path.of("artifacts/java-migration/D002/static-read.json");Files.createDirectories(path.getParent());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),proof);
        }
    }
}
