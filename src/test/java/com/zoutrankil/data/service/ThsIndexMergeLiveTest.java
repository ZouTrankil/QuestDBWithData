package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.client.dto.TushareThsIndexDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.ThsIndexMapper;
import com.zoutrankil.data.repository.ThsIndexStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ",matches="1")
class ThsIndexMergeLiveTest {
    @Test void savedRealFullSourceDoesNotRewriteActualUnchangedDirectoryForNewObservationClock() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var storage=new ThsIndexStorage(jdbc,"ths_index");
            var before=storage.snapshot();var mapper=new ThsIndexMapper();var json=JobDefinitionJson.mapper();
            var source=json.readTree(Path.of("artifacts/java-migration/D004/source-preflight.json").toFile());
            assertTrue(source.path("fullResponseBelowLimit").asBoolean());var incoming=new ArrayList<ThsIndex>();
            Instant observation=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            for(var row:source.path("rows")) incoming.add(mapper.fromSource(new TushareThsIndexDto(text(row,"ts_code"),text(row,"name"),
                    row.path("count").isNull()?null:row.path("count").intValue(),text(row,"exchange"),text(row,"list_date"),text(row,"type")),observation));
            assertEquals(2517,incoming.size());var merged=ThsIndexMerge.merge(before.businessRows(),incoming,ThsIndexSource.Scope.all());
            assertEquals(2517,merged.unchanged());assertEquals(0,merged.inserted());assertEquals(0,merged.revised());
            assertEquals(before.businessRows(),merged.rows());assertFalse(merged.requiresWrite());
            assertEquals(before,storage.snapshot());
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of("artifacts/java-migration/D004/merge-readonly.json").toFile(),
                    Map.of("sourceReceipt","source-preflight.json","sourceRows",incoming.size(),"sourceObservation",observation,
                            "targetFingerprint",before.fingerprint(),"targetIdentity",before.identity(),"merge",merged,
                            "questdbWrites",0,"newSourceRequestDuringThisTest",false));
        }
    }
    private static String text(com.fasterxml.jackson.databind.JsonNode row,String field) {
        return row.path(field).isNull()?null:row.path(field).asText();
    }
}
