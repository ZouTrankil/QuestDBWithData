package com.zoutrankil.data.repository;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.service.ReadGroupReader;
import com.zoutrankil.data.service.IndexCatalogFileSource;
import com.zoutrankil.data.service.IndexCatalogMerge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ",matches="1")
class IndexCatalogReadLiveTest {
    @Test void typedReadAndGroupReadActualCatalogWithoutWriting() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);
            var repository=context.getBean(IndexCatalogReadRepository.class);
            var snapshot=new IndexCatalogStorage(jdbc,"index").snapshot();
            assertEquals(2274,snapshot.rows().size());
            assertEquals(2274,snapshot.businessRows().stream().map(IndexCatalogEntry::indexCode).distinct().count());
            var candidate=new IndexCatalogFileSource().read(Path.of("artifacts/java-migration/D003/source-catalog.csv"),
                    Instant.EPOCH,()->false);
            var merge=IndexCatalogMerge.merge(snapshot.businessRows(),candidate.rows());
            assertEquals(529,merge.inserted());
            assertEquals(1814,merge.revised());
            assertEquals(0,merge.unchanged());
            assertEquals(460,merge.retainedAbsent());
            assertEquals(2803,merge.rows().size());
            var columns=IndexCatalogDataset.DEFINITION.columns().stream()
                    .map(DatasetDefinition.Column::logicalName).toList();
            var query=new DatasetReadQuery(columns,Map.of(),"index_code","000001","000010",2,null);
            var first=repository.findPage(query);
            assertEquals(2,first.rows().size());
            assertNotNull(first.nextCursor());
            var second=repository.findPage(query.after(first.nextCursor()));
            assertEquals(2,second.rows().size());
            var rows=new ArrayList<>(first.rows());rows.addAll(second.rows());
            assertEquals(4,rows.stream().map(IndexCatalogEntry::indexCode).distinct().count());
            for(var row:rows) {
                var actual=jdbc.queryForMap("SELECT index_code,index_short_name,index_full_name,base_date,"
                        +"base_point,index_series,sample_count,latest_close,return_1m,asset_class,"
                        +"index_hotspot,currency,is_cooperation,has_tracking_product,compliance_status,"
                        +"index_category,publish_date,cast(import_time as long) AS import_us "
                        +"FROM \"index\" WHERE index_code=? LIMIT 2",row.indexCode());
                assertEquals(row.indexCode(),actual.get("index_code"));
                assertEquals(row.shortName(),actual.get("index_short_name"));
                assertEquals(row.fullName(),actual.get("index_full_name"));
                assertEquals(row.baseDate().toString(),actual.get("base_date"));
                assertEquals(row.basePoint(),actual.get("base_point"));
                assertEquals(row.series(),actual.get("index_series"));
                assertEquals(row.sampleCount(),actual.get("sample_count"));
                assertEquals(row.latestClose(),actual.get("latest_close"));
                assertEquals(row.return1m(),actual.get("return_1m"));
                assertEquals(row.assetClass(),actual.get("asset_class"));
                assertEquals(row.hotspot(),actual.get("index_hotspot"));
                assertEquals(row.currency(),actual.get("currency"));
                assertEquals(row.cooperation(),actual.get("is_cooperation"));
                assertEquals(row.trackingProduct(),actual.get("has_tracking_product"));
                assertEquals(row.complianceStatus(),actual.get("compliance_status"));
                assertEquals(row.category(),actual.get("index_category"));
                assertEquals(row.publishDate().toString(),actual.get("publish_date"));
                long micros=((Number)actual.get("import_us")).longValue();
                assertEquals(micros,row.importTime().getEpochSecond()*1_000_000+row.importTime().getNano()/1000);
            }
            var grouped=context.getBean(ReadGroupReader.class).read(new ReadGroupRequest(List.of(
                    new ReadGroupRequest.Member("catalog","index",1,new DatasetReadQuery(
                            List.of("index_code","short_name","base_date","import_time"),Map.of(),
                            "index_code","000001","000010",2,null))),Duration.ofSeconds(20)),()->false);
            assertTrue(grouped.complete());
            assertEquals(first.rows().stream().map(IndexCatalogEntry::indexCode).toList(),
                    grouped.require("catalog").typedPage(DatasetValues.class).rows().stream()
                            .map(v->v.get("index_code",String.class)).toList());
            var evidence=new LinkedHashMap<String,Object>();
            evidence.put("table","index");evidence.put("readOnly",true);
            evidence.put("physicalRows",jdbc.queryForObject("SELECT count() FROM \"index\"",Long.class));
            evidence.put("physicalIdentity",snapshot.identity());
            evidence.put("completeSnapshotFingerprint",snapshot.fingerprint());
            evidence.put("candidateRows",candidate.rows().size());
            evidence.put("candidateSha256",candidate.sha256());
            evidence.put("mergeInserted",merge.inserted());
            evidence.put("mergeRevised",merge.revised());
            evidence.put("mergeRetainedAbsent",merge.retainedAbsent());
            evidence.put("typedComparedKeys",rows.stream().map(IndexCatalogEntry::indexCode).toList());
            evidence.put("groupPageRows",2);evidence.put("sourceFileUsedInThisReadTest",false);
            Path output=Path.of("artifacts/java-migration/D003/read-live.json");
            Files.createDirectories(output.getParent());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),evidence);
        }
    }
}
