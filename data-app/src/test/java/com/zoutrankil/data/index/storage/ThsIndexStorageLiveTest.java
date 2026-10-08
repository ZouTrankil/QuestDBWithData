package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.table.ThsIndexRow;
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
class ThsIndexStorageLiveTest {
    @Test void fullTypedPhysicalSnapshotMatchesIndependentBaseline() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);
            var actual=new ThsIndexStorage(jdbc,"ths_index").snapshot();
            var root=Path.of("artifacts/java-migration/D004");
            var baseline=JobDefinitionJson.mapper().readTree(root.resolve("physical-baseline.json").toFile());
            var expected=new ArrayList<ThsIndexRow>();
            for(var row:baseline.path("catalog").path("rows")) expected.add(new ThsIndexRow(
                    row.path("ts_code").asText(),row.path("name").isNull()?null:row.path("name").asText(),
                    row.path("count").isNull()?null:row.path("count").intValue(),
                    row.path("exchange").isNull()?null:row.path("exchange").asText(),
                    row.path("list_date").isNull()?null:row.path("list_date").asText(),
                    row.path("type").isNull()?null:row.path("type").asText(),
                    Instant.parse(row.path("update_time").asText())));
            assertEquals(1754,actual.identity().id());assertEquals(2517,actual.rows().size());
            assertEquals(expected,actual.rows());assertEquals(2517,actual.businessRows().size());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(root.resolve("storage-readback.json").toFile(),
                    Map.of("identity",actual.identity(),"rows",actual.rows().size(),
                            "fingerprint",actual.fingerprint(),"baselineRows",expected.size(),"fieldDifferences",0));
        }
    }
}
