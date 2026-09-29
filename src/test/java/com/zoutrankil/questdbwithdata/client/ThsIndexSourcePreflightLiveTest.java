package com.zoutrankil.questdbwithdata.client;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.client.dto.TushareRequest;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** One exact upstream catalog call; the documented 5000-row ceiling is an incomplete response. */
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class ThsIndexSourcePreflightLiveTest {
    private static final List<String> FIELDS=List.of("ts_code","name","count","exchange","list_date","type");
    @Test void boundedCurrentCatalogIsNonemptyAndHasUniqueCodes() throws Exception {
        var app=new SpringApplication(QuestDbWithDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var client=context.getBean(TushareClient.class);
            var page=client.request(new TushareRequest("ths_index",Map.of(),FIELDS,5000));
            assertTrue(page.rows().size()>0 && page.rows().size()<5000,"Source must be nonempty and below hard cap");
            var codes=new HashSet<String>();
            for(var row:page.rows()) {
                var code=row.get("ts_code");
                assertNotNull(code);assertTrue(code.isTextual());
                assertTrue(codes.add(code.asText()),"Duplicate source code");
                var count=row.get("count");
                assertNotNull(count);
                assertTrue(count.isNull() || count.isIntegralNumber(),"THS count must be integral or null");
            }
            Path folder=Path.of("artifacts/java-migration/D004");Files.createDirectories(folder);
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("source-preflight.json").toFile(),
                    Map.of("api","ths_index","parameters",Map.of(),"requestedFields",FIELDS,
                            "observedAt",Instant.now().toString(),"rowLimit",5000,"returnedRows",page.rows().size(),
                            "uniqueCodes",codes.size(),"fullResponseBelowLimit",true,"rows",page.rows()));
        }
    }
}
