package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class IndexMembershipSourcePreflightLiveTest {
    @Test void explicitSingleIndustryCurrentAndHistoricalRequestsAreRecordedSeparately() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var pages=context.getBean(TusharePageService.class);var json=JobDefinitionJson.mapper();
            Path folder=Path.of("artifacts/java-migration/D005","source-preflight-"+UUID.randomUUID());Files.createDirectories(folder);
            var fields=List.of("l1_code","l1_name","l2_code","l2_name","l3_code","l3_name","ts_code","name","in_date","out_date","is_new");
            var contract=new PageContract("index_member_all",fields,List.of("l2_code","l3_code","ts_code","in_date"),
                    Set.of("l2_code","is_new"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
                    null,null,2000,2000,1,2000,"https://tushare.pro/document/2?doc_id=335; explicit Y/N, no documented paging");
            var summaries=new ArrayList<Map<String,Object>>();
            for(String current:List.of("Y","N")) {
                var rows=new ArrayList<Map<String,JsonNode>>();var params=Map.<String,Object>of("l2_code","801011.SI","is_new",current);
                var done=pages.execute(contract,params,(page,receipt)->rows.addAll(page.rows()),row->{
                    assertEquals("801011.SI",row.get("l2_code").asText());assertEquals(current,row.get("is_new").asText());
                },()->false);
                assertEquals(1,done.pages());assertTrue(rows.size()<2000);
                byte[] bytes=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("endpoint","index_member_all",
                        "parameters",params,"fields",fields,"observedAt",Instant.now(),"completion",done,"rows",rows));
                Files.write(folder.resolve(current+"-response.json"),bytes,StandardOpenOption.CREATE_NEW);
                summaries.add(Map.of("is_new",current,"rows",rows.size(),"sha256",HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes))));
            }
            assertTrue((int)summaries.getFirst().get("rows")>0);
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("source-review.json").toFile(),
                    Map.of("requests",summaries,"scope","one explicit L2, Y and N separately","questdbWrites",0));
        }
    }
}
