package com.zoutrankil.data.index.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.index.IndexMembershipDataset;
import com.zoutrankil.data.index.storage.IndexMembershipStorage;
import com.zoutrankil.data.index.storage.QuestDbIndexMembershipTarget;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.StockBasicGroupService;
import com.zoutrankil.data.service.SyncGroupPlan;
import com.zoutrankil.data.service.SyncGroupRegistry;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.stock.application.StockBasicJobService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** The saved response originated from a real source receipt; publication and readback are live. */
@EnabledIfEnvironmentVariable(named = "QUESTDB_WRITE_LIVE", matches = "1")
class IndexMembershipGroupLiveTest {
    @Test
    void manualSyncGroupPublishesAndReusesVerifiedMembershipChild() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(
                c ->
                        c.addBeanFactoryPostProcessor(
                                f ->
                                        ((BeanDefinitionRegistry) f)
                                                .removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var json = JobDefinitionJson.mapper();
            String nonce = UUID.randomUUID().toString().replace("-", "");
            Path folder = Path.of("artifacts/java-migration/D005", "group-" + nonce);
            Files.createDirectories(folder);
            Path path = folder.resolve("ledger.sqlite");
            String table = "java_d005_group_" + nonce;
            var formal = new IndexMembershipStorage(jdbc, "index_member").snapshot();
            jdbc.execute(
                    "CREATE TABLE "
                            + table
                            + " ("
                            + String.join(
                                    ",",
                                    IndexMembershipDataset.DEFINITION.columns().stream()
                                            .map(
                                                    c ->
                                                            "\""
                                                                    + c.storageName()
                                                                    + "\" "
                                                                    + c.storageType().name())
                                            .toList())
                            + ") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
            var catalog =
                    json.treeToValue(
                            json.readTree(
                                            Path.of(
                                                            "artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/discovery-readback.json")
                                                    .toFile())
                                    .path("catalog"),
                            IndexMembershipClassificationSource.Catalog.class);
            var source =
                    json.readTree(
                            Path.of("artifacts/java-migration/D005/two-industry-source-sample.json")
                                    .toFile());
            List<Map<String, JsonNode>> raw = null;
            for (var response : source.path("requests"))
                if (response.path("l2Code").asText().equals("801011.SI"))
                    raw = json.convertValue(response.path("rows"), new TypeReference<>() {});
            assertNotNull(raw);
            var savedRows = raw;
            var calls = new AtomicInteger();
            var pages =
                    new TusharePageService(null) {
                        @Override
                        public PageExecutor.Completed execute(
                                PageContract contract,
                                Map<String, Object> params,
                                PageExecutor.Consumer consumer,
                                PageExecutor.Validator validator,
                                BooleanSupplier cancelled)
                                throws Exception {
                            calls.incrementAndGet();
                            assertEquals("801011.SI", params.get("l2_code"));
                            assertEquals("Y", params.get("is_new"));
                            return new PageExecutor()
                                    .execute(
                                            contract,
                                            params,
                                            p ->
                                                    new PageExecutor.Page(
                                                            savedRows, null, false, null),
                                            consumer,
                                            validator,
                                            cancelled);
                        }
                    };
            var owner =
                    new IndexMembershipJobService(
                            new QuestDbIndexMembershipTarget(jdbc, table), pages, path);
            var groups =
                    new StockBasicGroupService(
                            context.getBean(SyncJobRegistry.class),
                            context.getBean(StockBasicJobService.class),
                            null,
                            null,
                            null,
                            null,
                            owner,
                            path.toString());
            var request =
                    IndexMembershipJobPlan.freeze(
                            catalog,
                            List.of("801011.SI"),
                            IndexMembershipSource.Selection.CURRENT,
                            LocalDate.of(2026, 9, 29));
            var parameters = request.parameters();
            var plan =
                    SyncGroupPlan.prepare(
                            new SyncGroupRegistry(
                                    groups.definitions(), context.getBean(SyncJobRegistry.class)),
                            context.getBean(SyncJobRegistry.class),
                            "group.index_member_manual",
                            1,
                            request.logicalDate(),
                            request.mode(),
                            new SyncGroupPlan.Window(request.from(), request.to()),
                            parameters,
                            Map.of());
            var first = groups.runPlan(plan, null);
            assertEquals(SyncRunState.VERIFIED, first.state(), first.toString());
            assertEquals(1, calls.get());
            var actual = new IndexMembershipStorage(jdbc, table).snapshot();
            assertEquals(4, actual.rows().size());
            var resumed = groups.runPlan(plan, first.runId());
            assertEquals(SyncRunState.VERIFIED, resumed.state(), resumed.toString());
            assertEquals(
                    first.members().getFirst().childRunId(),
                    resumed.members().getFirst().childRunId());
            assertEquals(1, calls.get());
            assertEquals(actual, new IndexMembershipStorage(jdbc, table).snapshot());
            assertEquals(formal, new IndexMembershipStorage(jdbc, "index_member").snapshot());
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            folder.resolve("group-readback.json").toFile(),
                            Map.of(
                                    "first",
                                    first,
                                    "resumed",
                                    resumed,
                                    "actual",
                                    actual,
                                    "sourceCalls",
                                    calls.get(),
                                    "formalUnchanged",
                                    true));
            var child = first.members().getFirst().childRunId();
            String backup =
                    new ReferencePublicationJournal(path, "index_member")
                            .forRun(child + "-child-0")
                            .intent()
                            .backup();
            jdbc.execute("DROP TABLE " + table);
            jdbc.execute("DROP TABLE " + backup);
        }
    }
}
