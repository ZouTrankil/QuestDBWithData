package com.zoutrankil.strategy;

import com.zoutrankil.batch.strategy.MainStrategyDailyBatchRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MainStrategyApplicationTest {
    @TempDir Path root;

    @Test void dataApiDoesNotOwnStrategyEvenWhenItsClassesAreAvailable() throws Exception {
        var app = com.zoutrankil.data.QuestDataApplication.application(true, "data");
        app.setRegisterShutdownHook(false);
        try (var context = app.run("--server.port=0", "--app.materialized-views.etf-overview.enabled=false",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unused",
                "--spring.datasource.hikari.initialization-fail-timeout=-1")) {
            assertTrue(context.getBeansOfType(MainStrategyDailyBatchRuntime.class).isEmpty());
            assertFalse(context.containsBean("mainStrategyDailyBatchController"));
            assertEquals("questdb-data", context.getEnvironment().getProperty("spring.application.name"));
        }
    }

    @Test void standaloneStartsSchedulerAndControlApiWithoutAccessingBusinessServices() throws Exception {
        var app = MainStrategyApplication.application();
        app.setRegisterShutdownHook(false);
        try (var context = app.run("--server.port=0", "--app.web.api-token=fixture-token-at-least-24-characters", "--app.sync.batch.startup-catchup=false",
                "--app.sync.batch.ledger-path=" + root.resolve("strategy.sqlite"),
                "--app.sync.ledger-path=" + root.resolve("sources.sqlite"),
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unused",
                "--spring.datasource.hikari.initialization-fail-timeout=-1")) {
            assertEquals("main-strategy", context.getEnvironment().getProperty("spring.application.name"));
            assertEquals("8081", com.zoutrankil.runtime.ProjectConfiguration.load("main-strategy").getProperty("server.port"));
            var runtime = context.getBean(MainStrategyDailyBatchRuntime.class);
            var status = runtime.status();
            assertEquals("main_strategy_daily", status.get("job"));
            assertEquals(false, status.get("startupCatchup"));
            assertEquals(true, status.get("enabled"));
            assertFalse(context.containsBean("startEtfOverviewRefresh"));
            assertTrue(context.containsBean("mainStrategyDailyBatchController"));
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            try (var http = java.net.http.HttpClient.newHttpClient()) {
                var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/batch/main-strategy")).header("Authorization", "Bearer fixture-token-at-least-24-characters").build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("main_strategy_daily"));
            }
        }
    }
}
