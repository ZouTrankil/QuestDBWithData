package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlanningStartupTest {
    @TempDir Path temp;
    @Test void actualApplicationPlanningDoesNotInitializeScheduleLedger() {
        Path ledger = temp.resolve("must-not-exist.sqlite");
        var application = new SpringApplication(QuestDbWithDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("planning-test", Map.of(
                        "app.sync.ledger-path", ledger.toString(),
                        "spring.main.banner-mode", "off"))));
        try (var context = application.run("plan-sync-job", "--job", "data.stock_basic", "--version", "2",
                "--logical-date", "2026-09-29", "--parameters", "{\"codes\":[\"000001.SZ\"]}")) {
            assertFalse(context.getBeanFactory().containsSingleton("stockBasicScheduleService"));
            assertFalse(Files.exists(ledger));
            assertFalse(Files.exists(Path.of(ledger + "-wal")));
        }
        assertFalse(Files.exists(ledger));
    }
}
