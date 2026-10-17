package com.zoutrankil.data.client;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.client.dto.TushareRequest;
import com.zoutrankil.data.domain.JobDefinitionJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only source capability check; this test never opens a QuestDB writer. */
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class ThsMemberSourcePreflightLiveTest {
    @Test void explicitLargeAndOrdinaryBoardsExposeBoundedFullFields() throws Exception {
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f).removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var client=context.getBean(TushareClient.class);var json=JobDefinitionJson.mapper();
            Path folder=Path.of("artifacts/java-migration/D006","source-preflight-"+UUID.randomUUID());
            Files.createDirectories(folder);
            var summaries=new ArrayList<Map<String,Object>>();
            var fields=List.of("ts_code","con_code","con_name","weight","in_date","out_date","is_new");
            for(String board:List.of("700001.TI","885800.TI")) {
                var page=client.request(new TushareRequest("ths_member",Map.of("ts_code",board),fields,10000));
                assertEquals(fields,page.fields());assertFalse(page.rows().isEmpty());
                assertTrue(page.rows().size()<10000,"Declared response cap reached");
                var keys=new HashSet<String>();var suffixes=new TreeMap<String,Integer>();
                var nulls=new LinkedHashMap<String,Integer>();for(String field:fields) nulls.put(field,0);
                for(var row:page.rows()) {
                    assertEquals(board,row.get("ts_code").asText());
                    String code=row.get("con_code").asText();assertTrue(keys.add(code),"Duplicate board/constituent key");
                    suffixes.merge(code.substring(code.lastIndexOf('.')+1),1,Integer::sum);
                    for(String field:fields) if(row.get(field)==null || row.get(field).isNull()) nulls.merge(field,1,Integer::sum);
                }
                byte[] raw=json.writeValueAsBytes(page.rows());
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
                summaries.add(Map.of("board",board,"rows",page.rows().size(),"fields",page.fields(),
                        "sourceSha256",hash,"suffixCounts",suffixes,"nullCounts",nulls,
                        "firstRows",page.rows().subList(0,Math.min(3,page.rows().size()))));
            }
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("source-review.json").toFile(),
                    Map.of("endpoint","ths_member","kind","real-read-only-preflight","requests",summaries,
                            "questdbWrites",0));
        }
    }
}
