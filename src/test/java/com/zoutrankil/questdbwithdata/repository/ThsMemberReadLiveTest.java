package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.ReadGroupReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ", matches="1")
class ThsMemberReadLiveTest {
    @Test void boundedBoardPagesMatchAllEightPhysicalFieldsAndReadGroup() throws Exception {
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var repo = context.getBean(ThsMemberReadRepository.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var columns = ThsMemberDataset.DEFINITION.columns().stream()
                    .map(DatasetDefinition.Column::logicalName).toList();
            var filter = Map.<String, Object>of("board_code", "885800.TI");
            var query = new DatasetReadQuery(columns, filter, null, null, null, 97, null);
            var rows = new ArrayList<ThsMember>();
            var keys = new HashSet<ThsMember.Key>();
            int pages = 0;
            do {
                var page = repo.findPage(query);
                assertTrue(++pages <= 60);
                for (var row : page.rows()) {
                    assertTrue(keys.add(row.key()));
                    rows.add(row);
                }
                if (!page.hasMore()) break;
                query = query.after(page.nextCursor());
            } while (true);
            var physical = jdbc.queryForList("SELECT ts_code,con_code,con_name,weight,in_date,out_date,is_new,"
                    + "cast(update_time AS long) AS observed_us FROM ths_member "
                    + "WHERE ts_code='885800.TI' ORDER BY ts_code,con_code LIMIT 10001");
            assertEquals(physical.size(), rows.size());
            assertTrue(rows.size() > 0 && pages > 1);
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                var p = physical.get(i);
                assertEquals(p.get("ts_code"), row.boardCode());
                assertEquals(p.get("con_code"), row.constituentCode());
                assertEquals(p.get("con_name"), row.constituentName());
                assertEquals(p.get("weight"), row.weight());
                assertEquals(p.get("in_date"), date(row.inDate()));
                assertEquals(p.get("out_date"), date(row.outDate()));
                assertEquals(p.get("is_new"), row.isNew());
                assertEquals(((Number)p.get("observed_us")).longValue(),
                        row.observedAt().getEpochSecond() * 1_000_000 + row.observedAt().getNano() / 1000);
            }
            var grouped = context.getBean(ReadGroupReader.class).read(new ReadGroupRequest(List.of(
                    new ReadGroupRequest.Member("members", "ths_member", 1,
                            new DatasetReadQuery(List.of("board_code", "constituent_code", "constituent_name"),
                                    filter, null, null, null, 2, null))), Duration.ofSeconds(20)), () -> false);
            assertTrue(grouped.complete());
            assertEquals(2, grouped.require("members").typedPage(DatasetValues.class).rows().size());
            Path output = Path.of("artifacts/java-migration/D006/read-live.json");
            Files.createDirectories(output.getParent());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),
                    Map.of("board", "885800.TI", "physicalRows", physical.size(), "typedRows", rows.size(),
                            "pages", pages, "comparedFields", 8, "mismatches", 0, "questdbWrites", 0));
        }
    }

    private static String date(java.time.LocalDate value) {
        return value == null ? null : value.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
