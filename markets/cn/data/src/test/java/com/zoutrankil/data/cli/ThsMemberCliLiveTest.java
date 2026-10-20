package com.zoutrankil.data.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.service.SyncJobRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "QUESTDB_BOUNDED_READ", matches = "1")
class ThsMemberCliLiveTest {
    @Test
    void planIsReadOnlyAndFormalTargetRunIsRejected() throws Exception {
        Path ledger = Path.of("var", "D006-cli-plan-" + UUID.randomUUID() + ".sqlite");
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(
                c ->
                        c.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new MapPropertySource(
                                                "ths-member-cli-test",
                                                Map.of(
                                                        "app.sync.ledger-path",
                                                        ledger.toString()))));
        try (var context =
                app.run(
                        "plan-ths-member-job",
                        "--board-code",
                        "885800.TI",
                        "--logical-date",
                        "2026-09-29")) {
            assertFalse(Files.exists(ledger));
            assertEquals(
                    "ths_member",
                    context.getBean(SyncJobRegistry.class)
                            .require("data.ths_member", 1)
                            .datasetId());
            var cli = context.getBean(CommandLineRunner.class);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            cli.run(
                                    new DefaultApplicationArguments(
                                            "run-ths-member-job",
                                            "--board-code",
                                            "885800.TI",
                                            "--logical-date",
                                            "2026-09-29")));
            assertFalse(Files.exists(ledger));
        }
    }
}
