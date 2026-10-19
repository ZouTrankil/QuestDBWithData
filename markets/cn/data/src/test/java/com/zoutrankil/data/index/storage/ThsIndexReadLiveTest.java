package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.service.ReadGroupReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ",matches="1")
class ThsIndexReadLiveTest {
    @Test void typedPagesAndReadGroupMatchAllSevenActualPhysicalFields() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var repo=context.getBean(ThsIndexReadRepository.class);
            var columns=ThsIndexDataset.DEFINITION.columns().stream().map(DatasetDefinition.Column::logicalName).toList();
            var query=new DatasetReadQuery(columns,Map.of(),"ts_code","000000.TI","ZZZZZZZZZZZZ.TI",256,null);
            var rows=new ArrayList<ThsIndex>();var codes=new HashSet<String>();int pages=0;
            do {
                var page=repo.findPage(query);assertTrue(++pages<=20);
                for(var row:page.rows()) { assertTrue(codes.add(row.tsCode()));rows.add(row); }
                if(!page.hasMore()) break;
                query=query.after(page.nextCursor());
            } while(true);
            assertEquals(2517,rows.size());assertTrue(pages>1);
            var actual=jdbc.queryForList("SELECT ts_code,name,count,exchange,list_date,type,cast(update_time AS long) AS observed_us "
                    +"FROM ths_index ORDER BY ts_code LIMIT 5001");assertEquals(rows.size(),actual.size());
            for(int i=0;i<rows.size();i++) {
                var row=rows.get(i);var physical=actual.get(i);
                assertEquals(row.tsCode(),physical.get("ts_code"));assertEquals(row.name(),physical.get("name"));
                assertEquals(row.memberCount(),physical.get("count"));assertEquals(row.exchange(),physical.get("exchange"));
                assertEquals(row.indexType(),physical.get("type"));
                assertEquals(row.listingDate()==null?null:row.listingDate().format(DateTimeFormatter.BASIC_ISO_DATE),physical.get("list_date"));
                assertEquals(row.observedAt().getEpochSecond()*1_000_000+row.observedAt().getNano()/1000,
                        ((Number)physical.get("observed_us")).longValue());
            }
            var grouped=context.getBean(ReadGroupReader.class).read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                    "ths","ths_index",1,new DatasetReadQuery(List.of("ts_code","member_count","listing_date","observed_at"),Map.of(),
                    "ts_code","000000.TI","ZZZZZZZZZZZZ.TI",2,null))),Duration.ofSeconds(20)),()->false);
            assertTrue(grouped.complete());assertEquals(rows.subList(0,2).stream().map(ThsIndex::tsCode).toList(),
                    grouped.require("ths").typedPage(DatasetValues.class).rows().stream().map(v->v.get("ts_code",String.class)).toList());
            Path output=Path.of("artifacts/java-migration/D004/read-live.json");Files.createDirectories(output.getParent());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),
                    Map.of("physicalTable","ths_index","rows",rows,"pages",pages,"comparedFields",7,"mismatches",0,
                            "readGroupRows",2,"questdbWrites",0));
        }
    }
}
