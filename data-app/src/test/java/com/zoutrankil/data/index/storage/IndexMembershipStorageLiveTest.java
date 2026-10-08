package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ",matches="1")
class IndexMembershipStorageLiveTest {
    @Test void physicalSnapshotKeepsAllLegacyStringsAndMatchesActualTypedReadback() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var snapshot=new IndexMembershipStorage(context.getBean(JdbcTemplate.class),"index_member").snapshot();
            var json=JobDefinitionJson.mapper();Path base=Path.of("artifacts/java-migration/D005");
            List<IndexMembership> typed=json.convertValue(json.readTree(base.resolve("read-live.json").toFile()).path("rows"),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            assertEquals(typed,snapshot.businessRows());assertEquals(5902,snapshot.rows().size());
            assertEquals(5902,snapshot.rows().stream().filter(r->"None".equals(r.outDate())).count());
            assertTrue(snapshot.rows().stream().anyMatch(r->r.tsCode().equals("T00018.SH")));
            assertEquals(snapshot,new IndexMembershipStorage(context.getBean(JdbcTemplate.class),"index_member").snapshot());
            json.writerWithDefaultPrettyPrinter().writeValue(base.resolve("storage-readback.json").toFile(),
                    Map.of("snapshot",snapshot,"rawLegacyNonePreserved",true,"typedAllFieldsMatch",true,"questdbWrites",0));
        }
    }
}
