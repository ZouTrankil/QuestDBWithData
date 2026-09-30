package com.zoutrankil.data.repository;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class StockDetailInfoStagingLiveTest {
    @Test void realSourceBuildsTwoVerifiedStagesAndRepeatedRowsDoNotPublish() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var ctx=app.run()) {
            String suffix=UUID.randomUUID().toString().replace("-","");String empty="java_d002_empty_"+suffix;
            var tables=new ArrayList<String>();tables.add(empty);boolean verified=false;
            var jdbc=ctx.getBean(JdbcTemplate.class);var columns=StockDetailInfoDataset.DEFINITION.columns();
            jdbc.execute("CREATE TABLE "+empty+" ("+String.join(",",columns.stream().map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
            try {
                var folder=Path.of("artifacts/java-migration/D002","stage-"+suffix);
                var source=new StockDetailInfoSource(ctx.getBean(TusharePageService.class),folder);
                var observed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                var listed=source.fetch("000001.SZ",observed,()->false);assertEquals(1,listed.rows().size());
                var removed=source.fetch("000003.SZ",observed,()->false);assertEquals(1,removed.rows().size());
                var before=new StockDetailInfoStorage(jdbc,empty).snapshot();
                var writer=new StockDetailInfoStaging(jdbc);
                var first=writer.write(StockDetailInfoStaging.prepare(before,listed.rows()),folder);tables.add(first.table());
                var prepared=StockDetailInfoStaging.prepare(first.snapshot(),removed.rows());
                assertEquals(1,prepared.merge().insertedRows());
                var second=writer.write(prepared,folder);tables.add(second.table());
                assertEquals(2,second.snapshot().rows().size());
                assertEquals(first.snapshot().rows().getFirst(),second.snapshot().rows().getFirst());
                assertEquals(List.of(listed.rows().getFirst(),removed.rows().getFirst()),second.snapshot().businessRows());
                var repeat=StockDetailInfoStaging.prepare(second.snapshot(),second.snapshot().businessRows());
                assertFalse(repeat.merge().requiresPublication());assertEquals(2,repeat.merge().unchangedRows());
                assertThrows(IllegalArgumentException.class,()->writer.write(repeat,folder));
                assertTrue(new StockDetailInfoStorage(jdbc,empty).snapshot().rows().isEmpty(),"Original target must remain untouched");
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("stage-readback.json").toFile(),
                        Map.of("sourceRows",2,"first",first,"second",second,"unchangedRows",2,"originalUntouched",true));
                verified=true;
            } finally { if(verified) for(String table:tables) jdbc.execute("DROP TABLE "+table); }
        }
    }
}
