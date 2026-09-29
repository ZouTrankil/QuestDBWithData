package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.service.SyncJobRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ", matches="1")
class ThsMemberCliLiveTest {
    @Test void planIsReadOnlyAndFormalTargetRunIsRejected() throws Exception {
        Path ledger = Path.of("var", "D006-cli-plan-" + UUID.randomUUID() + ".sqlite");
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("ths-member-cli-test", Map.of("app.sync.ledger-path", ledger.toString()))));
        try (var context = app.run("plan-ths-member-job", "--board-code", "885800.TI",
                "--logical-date", "2026-09-29")) {
            assertFalse(Files.exists(ledger));
            assertEquals("ths_member", context.getBean(SyncJobRegistry.class)
                    .require("data.ths_member", 1).datasetId());
            var cli = context.getBean(CommandLineRunner.class);
            assertThrows(IllegalStateException.class, () -> cli.run(new DefaultApplicationArguments(
                    "run-ths-member-job", "--board-code", "885800.TI", "--logical-date", "2026-09-29")));
            assertFalse(Files.exists(ledger));
        }
    }
}
