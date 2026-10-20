package com.zoutrankil.data.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.zoutrankil.data.QuestDataApplication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

class PlanningStartupTest {
    @TempDir Path temp;

    @Test
    void actualApplicationPlanningDoesNotInitializeScheduleLedger() {
        Path ledger = temp.resolve("must-not-exist.sqlite");
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(
                context ->
                        context.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new MapPropertySource(
                                                "planning-test",
                                                Map.of(
                                                        "app.sync.ledger-path",
                                                        ledger.toString(),
                                                        "spring.main.banner-mode",
                                                        "off"))));
        try (var context =
                application.run(
                        "plan-sync-job",
                        "--job",
                        "data.stock_basic",
                        "--version",
                        "2",
                        "--logical-date",
                        "2026-09-29",
                        "--parameters",
                        "{\"codes\":[\"000001.SZ\"]}")) {
            assertFalse(context.getBeanFactory().containsSingleton("stockBasicScheduleService"));
            assertFalse(Files.exists(ledger));
            assertFalse(Files.exists(Path.of(ledger + "-wal")));
        }
        assertFalse(Files.exists(ledger));
    }
}
