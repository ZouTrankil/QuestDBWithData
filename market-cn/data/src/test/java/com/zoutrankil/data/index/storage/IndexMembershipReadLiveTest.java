package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.mapper.IndexMembershipMapper;
import com.zoutrankil.data.service.ReadGroupReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ",matches="1")
class IndexMembershipReadLiveTest {
    @Test void typedPaginationMatchesEveryPhysicalValueAndSupportsReadGroup() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var jdbc=context.getBean(JdbcTemplate.class);var repo=context.getBean(IndexMembershipReadRepository.class);
            var definition=IndexMembershipDataset.DEFINITION;
            var columns=definition.columns().stream().map(DatasetDefinition.Column::logicalName).toList();
            var query=new DatasetReadQuery(columns,Map.of(),"index_code","000000.SI","999999.SI",256,null);
            var rows=new ArrayList<IndexMembership>();var keys=new HashSet<IndexMembership.Key>();int pages=0;
            do {
                var page=repo.findPage(query);assertTrue(++pages<=50);
                for(var row:page.rows()) { assertTrue(keys.add(row.key()));rows.add(row); }
                if(!page.hasMore()) break;query=query.after(page.nextCursor());
            } while(true);
            var physical=jdbc.queryForList("SELECT index_code,ts_code,cast(update_time AS long) observed_us,index_name,con_code,con_name,"
                    +"in_date,out_date,is_new,weight,level,l1_name,l2_name,l3_name FROM index_member ORDER BY index_code,ts_code,in_date LIMIT 10001");
            assertFalse(rows.isEmpty());assertTrue(rows.size()<10000);assertEquals(physical.size(),rows.size());
            int legacyNulls=0;var mapper=new IndexMembershipMapper();
            for(int i=0;i<rows.size();i++) {
                var values=mapper.values(rows.get(i));var p=physical.get(i);
                for(var column:definition.columns()) {
                    Object expected=p.get(column.storageName());
                    if(column.storageName().equals("update_time")) {
                        long micros=((Number)p.get("observed_us")).longValue();
                        expected=Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000),Math.floorMod(micros,1_000_000)*1000);
                    } else if(Set.of("in_date","out_date").contains(column.storageName())) {
                        if("None".equals(expected)) { expected=null;legacyNulls++; }
                        else if(expected!=null) expected=LocalDate.parse((String)expected,DateTimeFormatter.BASIC_ISO_DATE);
                    }
                    assertEquals(expected,values.get(column.logicalName(),Object.class),column.storageName()+" at row "+i);
                }
            }
            var grouped=context.getBean(ReadGroupReader.class).read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                    "members","index_member",1,new DatasetReadQuery(List.of("index_code","ts_code","membership_start_date","membership_end_date"),
                    Map.of("index_code","801011.SI"),"ts_code","000000.BJ","999999.SZ",10,null))),Duration.ofSeconds(20)),()->false);
            assertTrue(grouped.complete());assertFalse(grouped.require("members").typedPage(DatasetValues.class).rows().isEmpty());
            Path file=Path.of("artifacts/java-migration/D005/read-live.json");Files.createDirectories(file.getParent());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),Map.of(
                    "rows",rows,"pages",pages,"comparedFields",14,"mismatches",0,"legacyNoneDates",legacyNulls,"questdbWrites",0,"groupVerified",true));
        }
    }
}
