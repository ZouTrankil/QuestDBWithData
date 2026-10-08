package com.zoutrankil.data.repository;

import com.zoutrankil.data.stock.storage.StockDetailInfoReadRepository;
import com.zoutrankil.data.stock.storage.StockDetailInfoStorage;
import com.zoutrankil.data.stock.mapper.StockDetailInfoMapper;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.ReadGroupRequest;
import com.zoutrankil.data.domain.StockDetailInfoDataset;
import com.zoutrankil.data.service.ReadGroupReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ", matches="1")
class StockDetailInfoReadLiveTest {
    @Test void typedAndGroupPagesNormalizeOnlyDeclaredLegacyDateSentinel() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var ctx = app.run()) {
            var repository = ctx.getBean(StockDetailInfoReadRepository.class);
            var jdbc = ctx.getBean(JdbcTemplate.class);
            var columns = StockDetailInfoDataset.DEFINITION.columns().stream()
                    .map(DatasetDefinition.Column::logicalName).toList();
            var query = new DatasetReadQuery(columns, Map.of(), "ts_code",
                    "000001.SZ", "000010.SZ", 2, null);
            var first = repository.findPage(query);
            assertEquals(2,first.rows().size());
            assertNotNull(first.nextCursor());
            assertNull(first.rows().getFirst().delistingDate());
            var second = repository.findPage(query.after(first.nextCursor()));
            assertEquals(2,second.rows().size());
            var selected = new ArrayList<>(first.rows()); selected.addAll(second.rows());
            var physical = new StockDetailInfoStorage(jdbc,"stock_detail_info").readKeys(
                    selected.stream().map(r -> r.tsCode()).toList());
            assertEquals(selected,physical.stream().map(new com.zoutrankil.data.stock.mapper.StockDetailInfoMapper()::fromStorage).toList());
            assertEquals(LocalDate.of(2002,6,14),selected.stream()
                    .filter(r -> r.tsCode().equals("000003.SZ")).findFirst().orElseThrow().delistingDate());

            var projection = new DatasetReadQuery(List.of("ts_code","listing_date","delisting_date"),
                    java.util.Collections.singletonMap("delisting_date",null),
                    "ts_code","000001.SZ","000003.SZ",2,null);
            var group = ctx.getBean(ReadGroupReader.class);
            var grouped = group.read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                    "listed","stock_detail_info",1,projection)),Duration.ofSeconds(20)),()->false);
            assertTrue(grouped.complete());
            var values = grouped.require("listed").typedPage(DatasetValues.class).rows();
            assertEquals(2,values.size());
            assertEquals(LocalDate.of(1991,4,3),values.getFirst().get("listing_date",LocalDate.class));
            assertNull(values.getFirst().get("delisting_date",LocalDate.class));
            assertEquals(List.of("000001.SZ","000002.SZ"),values.stream()
                    .map(v -> v.get("ts_code",String.class)).toList());
            var evidence = new LinkedHashMap<String,Object>();
            evidence.put("target","stock_detail_info"); evidence.put("readOnly",true);
            evidence.put("typedPageRows",selected.size()); evidence.put("groupNullFilterRows",values.size());
            evidence.put("firstCursor",first.nextCursor());
            evidence.put("comparedPhysicalKeys",selected.stream().map(r -> r.tsCode()).toList());
            Path out=Path.of("artifacts/java-migration/D002/read-live.json");Files.createDirectories(out.getParent());
            com.zoutrankil.data.domain.JobDefinitionJson.mapper()
                    .writerWithDefaultPrettyPrinter().writeValue(out.toFile(),evidence);
        }
    }
}
