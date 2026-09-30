package com.zoutrankil.data.service;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.ExchangeCalendarMapper;
import com.zoutrankil.data.repository.ExchangeCalendarWritePort;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "QUESTDB_WRITE_LIVE", matches = "1")
class ExchangeCalendarWriteGroupLiveTest {
    @Test void preparedCalendarMemberUsesIsolatedTypedWriterAndIsIdempotent() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> context.addBeanFactoryPostProcessor(factory ->
                ((BeanDefinitionRegistry) factory).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String table = "java_d001_group_" + suffix;
            Path evidence = Path.of("artifacts/java-migration/D001", "group-" + suffix);
            Path ledger = Path.of("var", "D001-group-" + suffix + ".sqlite");
            var jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE " + table + " (exchange SYMBOL,cal_date TIMESTAMP,is_open INT,pretrade_date STRING) "
                    + "TIMESTAMP(cal_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(exchange,cal_date)");
            boolean verified = false;
            try {
                var source = new ExchangeCalendarSource(context.getBean(TusharePageService.class), evidence);
                var slice = new ExchangeCalendarSlices.Slice("SSE", LocalDate.of(2026, 9, 25),
                        LocalDate.of(2026, 9, 28));
                var expected = source.fetch(slice, () -> false).rows();
                assertEquals(4, expected.size());
                var mapper = new ExchangeCalendarMapper();
                Files.createDirectories(evidence);
                Path request = evidence.resolve("write-request.json");
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(request.toFile(), Map.of(
                        "batchId", "calendar_group_" + suffix, "logicalDate", "2026-09-29",
                        "members", List.of(Map.of("memberId", "calendar", "datasetId", "exchange_calendar",
                                "definitionVersion", 1, "batchId", "calendar_member_" + suffix,
                                "rows", expected.stream().map(row -> mapper.values(row).asMap()).toList()))));
                var calendar = new ExchangeCalendarJobService(context.getBean(TusharePageService.class), jdbc,
                        context.getBean(QuestDB.class), context.getBean(QuestDbProperties.class), ledger.toString(), table);
                var group = new StockBasicWriteGroupService(context.getBean(DatasetRegistry.class),
                        mock(StockBasicJobService.class), calendar, jdbc, context.getBean(QuestDB.class), ledger.toString());
                var first = group.run(request, null);
                assertEquals(SyncRunState.VERIFIED, first.state());
                var second = group.run(request, null);
                assertEquals(SyncRunState.VERIFIED, second.state());
                var actual = new ExchangeCalendarWritePort(table, jdbc, context.getBean(QuestDB.class))
                        .readback(expected.stream().map(ExchangeCalendar::key).toList());
                assertEquals(expected, actual);
                var physical = jdbc.queryForList("SELECT exchange,cast(cal_date as long) AS date_micros,is_open,pretrade_date "
                        + "FROM " + table + " ORDER BY exchange,cal_date LIMIT 5");
                assertEquals(4, physical.size());
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                        evidence.resolve("group-readback.json").toFile(), Map.of("table", table, "first", first,
                                "second", second, "expected", expected, "physical", physical,
                                "matchedRows", 4, "duplicateKeys", 0));
                verified = true;
            } finally {
                if (verified) jdbc.execute("DROP TABLE " + table);
            }
        }
    }
}
