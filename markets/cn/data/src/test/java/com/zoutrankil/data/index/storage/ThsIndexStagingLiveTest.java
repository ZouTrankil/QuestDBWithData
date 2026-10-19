package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.index.domain.ThsIndexState;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.client.dto.TushareThsIndexDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.mapper.ThsIndexMapper;
import com.zoutrankil.data.index.application.ThsIndexSource;
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
class ThsIndexStagingLiveTest {
    @Test void realSourceStagesOneThenFullDirectoryAndRepeatedContentDoesNotWrite() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();var mapper=new ThsIndexMapper();
            String nonce=UUID.randomUUID().toString().replace("-","");String target="java_d004_stage_target_"+nonce;
            Path folder=Path.of("artifacts/java-migration/D004","staging-"+nonce);Files.createDirectories(folder);
            var production=new ThsIndexStorage(jdbc,"ths_index").snapshot();
            jdbc.execute("CREATE TABLE "+target+" ("+String.join(",",ThsIndexDataset.DEFINITION.columns().stream()
                    .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())
                    +") TIMESTAMP(update_time) PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
            var before=new ThsIndexStorage(jdbc,target).snapshot();var source=new ArrayList<ThsIndex>();
            Path sourceFile=Path.of("artifacts/java-migration/D004/source-preflight.json");byte[] bytes=Files.readAllBytes(sourceFile);
            var root=json.readTree(bytes);assertTrue(root.path("fullResponseBelowLimit").asBoolean());
            var instant=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            for(var row:root.path("rows")) source.add(mapper.fromSource(new TushareThsIndexDto(text(row,"ts_code"),text(row,"name"),
                    row.path("count").isNull()?null:row.path("count").intValue(),text(row,"exchange"),text(row,"list_date"),text(row,"type")),instant));
            assertEquals(2517,source.size());var one=List.of(source.getFirst());
            var firstPrepared=ThsIndexStaging.prepare(before,one,new ThsIndexState.Scope(one.getFirst().tsCode(),null,null));
            var first=new ThsIndexStaging(jdbc).write(firstPrepared,folder.resolve("first"),()->false);
            assertEquals(1,first.snapshot().rows().size());assertEquals(1,firstPrepared.merge().inserted());
            var secondPrepared=ThsIndexStaging.prepare(first.snapshot(),source,ThsIndexState.Scope.all());
            assertEquals(2516,secondPrepared.merge().inserted());assertEquals(1,secondPrepared.merge().unchanged());
            var second=new ThsIndexStaging(jdbc).write(secondPrepared,folder.resolve("second"),()->false);
            assertEquals(2517,second.snapshot().rows().size());assertEquals(11,second.batches());
            var repeated=ThsIndexStaging.prepare(second.snapshot(),source,ThsIndexState.Scope.all());assertFalse(repeated.merge().requiresWrite());
            assertThrows(IllegalArgumentException.class,()->new ThsIndexStaging(jdbc).write(repeated,folder.resolve("repeat"),()->false));
            // Fault scenario over captured real rows, not evidence of an upstream deletion.
            var omitted=ThsIndexStaging.prepare(second.snapshot(),source.subList(0,source.size()-1),ThsIndexState.Scope.all());
            assertEquals(1,omitted.merge().retainedAbsent());assertEquals(0,omitted.merge().removed());
            assertFalse(omitted.merge().requiresWrite());
            assertEquals(second.snapshot().rows(),omitted.rows());
            assertEquals(omitted.rows(),omitted.merge().rows().stream().map(mapper::toStorage).toList());
            assertThrows(IllegalArgumentException.class,()->new ThsIndexStaging(jdbc).write(omitted,folder.resolve("omitted"),()->false));
            var afterOmission=new ThsIndexStorage(jdbc,second.table()).snapshot();
            assertEquals(second.snapshot(),afterOmission);
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("omission-readback.json").toFile(),Map.of(
                    "scenario","controlled missing source row; not a real upstream deletion",
                    "sourceRows",omitted.source().size(),"retainedAbsent",omitted.merge().retainedAbsent(),
                    "removed",omitted.merge().removed(),"requiresWrite",omitted.merge().requiresWrite(),
                    "actual",afterOmission,"allSevenFieldsUnchanged",true));
            assertEquals(production,new ThsIndexStorage(jdbc,"ths_index").snapshot());assertEquals(before,new ThsIndexStorage(jdbc,target).snapshot());
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("stage-readback.json").toFile(),Map.of("first",first,
                    "second",second,"insertedOnSecond",2516,"unchangedOnSecond",1,"sourceReceipt",sourceFile.toString(),
                    "sourceReceiptSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "sourceRequestedAgain",false,"productionUnchanged",true,"targetUnchanged",true));
            jdbc.execute("DROP TABLE "+first.table());jdbc.execute("DROP TABLE "+second.table());
            jdbc.execute("DROP TABLE "+target);
        }
    }
    private static String text(com.fasterxml.jackson.databind.JsonNode row,String field) {
        return row.path(field).isNull()?null:row.path(field).asText();
    }
}
