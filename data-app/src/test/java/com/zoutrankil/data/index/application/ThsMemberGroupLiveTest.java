package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbThsMemberTarget;

import com.zoutrankil.data.index.storage.ThsMemberBoardStorage;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.stock.application.StockBasicJobService;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE", matches="1")
class ThsMemberGroupLiveTest {
    @Test void registeredManualGroupExecutesAndReusesVerifiedBoardChild() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String id = UUID.randomUUID().toString().replace("-", "");
            String table = "java_d006_group_" + id;
            Path folder = Path.of("artifacts/java-migration/D006", "group-" + id);
            Files.createDirectories(folder); Path path = folder.resolve("ledger.sqlite");
            jdbc.execute("CREATE TABLE \"" + table + "\" AS (SELECT * FROM ths_member WHERE ts_code IN "
                    + "('700001.TI','885800.TI')) TIMESTAMP(update_time) PARTITION BY MONTH WAL "
                    + "DEDUP UPSERT KEYS(ts_code,con_code,update_time)");
            awaitWal(jdbc, table);
            var owner = new ThsMemberJobService(new QuestDbThsMemberTarget(table,jdbc),context.getBean(TusharePageService.class),path);
            var groups = new StockBasicGroupService(context.getBean(SyncJobRegistry.class),
                    context.getBean(StockBasicJobService.class), null, null, null, null, null,
                    owner, path.toString());
            var day = LocalDate.of(2026, 9, 29);
            var input = new SyncGroupRunner.MemberInput(null, Map.of("board_code", "885800.TI"),
                    new SyncGroupRunner.Window(day, day), owner.targetId());
            var request = new SyncGroupRunner.Request(day, SyncGroupRunner.Window.none(),
                    Map.of("data.ths_member", input));
            var first = groups.execute("group.ths_member_manual", 1, request, null);
            assertEquals(SyncRunState.VERIFIED, first.state(), first.toString());
            var actual = new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI");
            assertEquals(506, actual.boardRows().size());
            assertEquals(5565, actual.otherRows());
            var resumed = groups.execute("group.ths_member_manual", 1, request, first.runId());
            assertEquals(SyncRunState.VERIFIED, resumed.state(), resumed.toString());
            assertTrue(resumed.members().getFirst().reused());
            assertEquals(actual.contentFingerprint(),
                    new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI").contentFingerprint());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("group-review.json").toFile(),
                    Map.of("first", first, "resumed", resumed, "sourceRows", actual.boardRows().size(),
                            "copiedOtherRows", actual.otherRows(), "formalTableWrites", 0));
            String child = first.members().getFirst().childRunId();
            String backup = new ReferencePublicationJournal(path, "ths_member").forRun(child).intent().backup();
            jdbc.execute("DROP TABLE \"" + table + "\"");
            jdbc.execute("DROP TABLE \"" + backup + "\"");
        }
    }

    private static void awaitWal(JdbcTemplate jdbc, String table) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Test WAL unresolved; retain isolated target");
            Thread.sleep(50);
        }
    }
}
