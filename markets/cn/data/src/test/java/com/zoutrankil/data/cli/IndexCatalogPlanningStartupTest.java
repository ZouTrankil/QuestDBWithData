package com.zoutrankil.data.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.index.application.IndexCatalogJobService;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;

class IndexCatalogPlanningStartupTest {
    @TempDir Path temp;

    @Tag("retained-parity")
    @Test
    void realFilePlanningFreezesHashWithoutCreatingLedgerAndRejectsRecoveryWithoutProof()
            throws Exception {
        Path ledger = temp.resolve("not-created.sqlite");
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(
                c ->
                        c.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new MapPropertySource(
                                                "catalog-plan-test",
                                                Map.of(
                                                        "app.sync.ledger-path",
                                                        ledger.toString()))));
        Path retained = Path.of("artifacts/java-migration/D003/source-catalog.csv");
        byte[] original = Files.readAllBytes(retained);
        var normalized = new ByteArrayOutputStream(original.length);
        for (int i = 0; i < original.length; i++) {
            if (original[i] == '\r' && i + 1 < original.length && original[i + 1] == '\n') continue;
            normalized.write(original[i]);
        }
        Path fixture = Files.write(temp.resolve("source-catalog-lf.csv"), normalized.toByteArray());
        assertEquals(
                "5eb2dc5e25329d626aed89d4320882e1f92e96ebb61e9d2d6815ce4f23506e81",
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(fixture))));
        String file = fixture.toString();
        try (var context =
                application.run(
                        "plan-index-catalog-job", "--file", file, "--logical-date", "2026-09-29")) {
            assertFalse(Files.exists(ledger));
            var owner = context.getBean(IndexCatalogJobService.class);
            var plan = owner.plan(Path.of(file), LocalDate.of(2026, 9, 29));
            assertEquals(SyncJobDefinition.Mode.INCREMENTAL, plan.mode());
            assertEquals(
                    "5eb2dc5e25329d626aed89d4320882e1f92e96ebb61e9d2d6815ce4f23506e81",
                    plan.parameters().get("sha256"));
            assertEquals(
                    Path.of(file).toAbsolutePath().normalize().toString(),
                    plan.parameters().get("file"));
            var mapper = JobDefinitionJson.mapper();
            var frozen = mapper.readTree(SyncRequestIdentity.snapshotJson(plan));
            assertEquals("data.index", frozen.path("definition").path("jobId").asText());
            assertEquals("INCREMENTAL", frozen.path("mode").asText());
            assertEquals(
                    plan.parameters().get("sha256"),
                    frozen.path("parameters").path("sha256").asText());
            var cli = context.getBean(CommandLineRunner.class);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            cli.run(
                                    new DefaultApplicationArguments(
                                            "finish-index-catalog-publication",
                                            "--run",
                                            "absent",
                                            "--writer-stopped",
                                            "false")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            cli.run(
                                    new DefaultApplicationArguments(
                                            "plan-index-catalog-job",
                                            "--file",
                                            file,
                                            "--logical-date",
                                            "2026-09-29",
                                            "--resume-from",
                                            "old")));
            assertFalse(Files.exists(ledger));
            assertArrayEquals(
                    original,
                    Files.readAllBytes(retained),
                    "Retained D003 artifact must remain byte-for-byte unchanged");
        }
    }
}
