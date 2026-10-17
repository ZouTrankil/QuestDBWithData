package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.index.domain.policy.IndexMembershipMerge;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class IndexMembershipStagingLiveTest {
    @Test void realHistoricalRowsStageIncrementallyAndPreserveRawExistingValues() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            Path source=Path.of("artifacts/java-migration/D005/source-adapter-7ec60463-be52-45b5-bcf2-bebdee34fea3/source-review.json");
            List<IndexMembership> rows=json.convertValue(json.readTree(source.toFile()).path("result").path("rows"),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            assertEquals(7,rows.size());var before=new IndexMembershipStorage(jdbc,"index_member").snapshot();
            var prepared=IndexMembershipStaging.prepare(before,rows,"801011.SI");
            assertEquals(3,prepared.merge().inserted());assertEquals(0,prepared.merge().revised());assertEquals(4,prepared.merge().unchanged());
            Path folder=Path.of("artifacts/java-migration/D005","stage-"+UUID.randomUUID());
            var stage=new IndexMembershipStaging(jdbc).write(prepared,folder,()->false);
            assertEquals(5905,stage.snapshot().rows().size());assertEquals(24,stage.batches());
            assertTrue(stage.snapshot().rows().containsAll(before.rows()));
            assertEquals(5902,stage.snapshot().rows().stream().filter(r->"None".equals(r.outDate())).count());
            var indexed=new HashMap<IndexMembership.Key,IndexMembership>();stage.snapshot().businessRows().forEach(r->indexed.put(r.key(),r));
            for(var row:rows) assertTrue(IndexMembershipMerge.sameBusinessValues(row,indexed.get(row.key())));
            var repeat=IndexMembershipStaging.prepare(stage.snapshot(),rows,"801011.SI");assertFalse(repeat.merge().requiresWrite());
            assertThrows(IllegalArgumentException.class,()->new IndexMembershipStaging(jdbc).write(repeat,folder.resolve("repeat"),()->false));
            assertEquals(before,new IndexMembershipStorage(jdbc,"index_member").snapshot());
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("stage-readback.json").toFile(),Map.of(
                    "sourceReceipt",source.toString(),"sourceRows",rows,"before",before,"stage",stage,
                    "inserted",3,"revised",0,"unchanged",4,"repeatRequiresWrite",false,"productionUnchanged",true));
            jdbc.execute("DROP TABLE "+stage.table());
        }
    }
}
