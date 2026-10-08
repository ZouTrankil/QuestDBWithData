package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.storage.QuestDbThsMemberTarget;

import com.zoutrankil.data.index.storage.ThsMemberBoardStorage;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.stock.application.StockBasicJobService;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.mapper.ThsMemberMapper;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE", matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE", matches="1")
class ThsMemberPreparedWriteLiveTest {
    @Test void preparedBoardUsesSameOwnerAndResumesWithoutSecondPublication() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String table = "java_d006_prepared_" + nonce;
            Path folder = Path.of("artifacts/java-migration/D006", "prepared-" + nonce);
            Files.createDirectories(folder);
            Path path = folder.resolve("ledger.sqlite");
            jdbc.execute("CREATE TABLE \"" + table + "\" AS (SELECT * FROM ths_member WHERE ts_code IN "
                    + "('700001.TI','885800.TI')) TIMESTAMP(update_time) PARTITION BY MONTH WAL "
                    + "DEDUP UPSERT KEYS(ts_code,con_code,update_time)");
            awaitWal(jdbc, table);
            var owner = new ThsMemberJobService(new QuestDbThsMemberTarget(table,jdbc),context.getBean(TusharePageService.class),path);
            var source = new ThsMemberSource(context.getBean(TusharePageService.class), folder)
                    .fetchBoard("885800.TI", Instant.now().truncatedTo(ChronoUnit.MICROS), () -> false);
            var mapper = new ThsMemberMapper();
            Path input = folder.resolve("write-group.json");
            JobDefinitionJson.mapper().writeValue(input.toFile(), Map.of("batchId", "ths-member-sample",
                    "logicalDate", "2026-09-29", "members", List.of(Map.of("memberId", "board-member",
                            "datasetId", "ths_member", "definitionVersion", 1, "batchId", "board-rows",
                            "rows", source.rows().stream().map(row -> mapper.values(row).asMap()).toList()))));
            var service = new StockBasicWriteGroupService(context.getBean(DatasetRegistry.class),
                    context.getBean(StockBasicJobService.class), null, null, null, null, null, owner,new com.zoutrankil.data.group.storage.QuestDbWriteGroupWriters(
                    jdbc, context.getBean(QuestDB.class)), path.toString());
            var first = service.run(input, null);
            assertEquals(SyncRunState.VERIFIED, first.state(), first.toString());
            var child = first.members().getFirst().childRunId();
            var actual = new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI");
            assertEquals(source.rows().size(), actual.boardRows().size());
            assertEquals(5565, actual.otherRows());
            var previous = SyncRunLedger.openReadOnly(path).getRun(child);
            var plan = WriteGroupPlan.prepare(new WriteGroupJson(context.getBean(DatasetRegistry.class)).read(input),
                    context.getBean(DatasetRegistry.class), Map.of("ths_member", previous.targetId()));
            var adapter = new ThsMemberPreparedWriteAdapter(plan, "board-member", owner, folder, true);
            owner.revalidateGroupChild(child, previous.targetId(), adapter.request());
            var resumed = service.run(input, first.runId());
            assertEquals(SyncRunState.VERIFIED, resumed.state());
            assertTrue(resumed.members().getFirst().reused());
            assertEquals(actual.contentFingerprint(),
                    new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI").contentFingerprint());
            var rerun = service.run(input, null);
            assertEquals(SyncRunState.VERIFIED, rerun.state());
            assertEquals(actual.contentFingerprint(),
                    new ThsMemberBoardStorage(jdbc, table).snapshot("885800.TI").contentFingerprint());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(folder.resolve("prepared-review.json").toFile(),
                    Map.of("first", first, "resumed", resumed, "rerun", rerun,
                            "sourceRows", source.rows().size(), "copiedOtherRows", actual.otherRows(),
                            "formalTableWrites", 0));
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
