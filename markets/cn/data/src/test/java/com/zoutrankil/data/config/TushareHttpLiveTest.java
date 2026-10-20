package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.client.HttpObservations;
import com.zoutrankil.data.client.SharedRequestBudget;
import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.client.dto.TushareRequest;
import com.zoutrankil.data.client.dto.TushareStockBasicDto;
import com.zoutrankil.data.domain.temporal.TemporalValues;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@EnabledIfEnvironmentVariable(named = "TUSHARE_HTTP_LIVE", matches = "1")
class TushareHttpLiveTest {
    @Test
    void boundedRealStockBasicRequestsRecordProtocolAndReuse() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(
                c ->
                        c.addBeanFactoryPostProcessor(
                                f ->
                                        ((BeanDefinitionRegistry) f)
                                                .removeBeanDefinition("commandLineRunner")));
        try (var ctx = app.run()) {
            var client = ctx.getBean(TushareClient.class);
            var request =
                    new TushareRequest(
                            "stock_basic",
                            Map.of("ts_code", "000001.SZ", "list_status", "L"),
                            TushareStockBasicDto.FIELDS,
                            2);
            var first = client.request(request);
            assertEquals(1, first.rows().size());
            assertEquals("000001.SZ", first.rows().getFirst().get("ts_code").asText());
            TemporalValues.businessDate(
                    first.rows().getFirst().get("list_date").asText(),
                    TemporalValues.DateFormat.BASIC);
            // Submit immediately: F005 must enforce pacing itself.
            var second = client.request(request);
            assertEquals(first, second);
            var observations = ctx.getBean(HttpObservations.class).snapshot();
            assertEquals(2, observations.size());
            var events =
                    observations.stream()
                            .map(
                                    e ->
                                            Map.of(
                                                    "time",
                                                    e.observedAt().toString(),
                                                    "protocol",
                                                    e.protocol(),
                                                    "connection_id",
                                                    e.connectionId(),
                                                    "http_status",
                                                    e.status()))
                            .toList();
            var budget = ctx.getBean(SharedRequestBudget.class);
            var attempts = budget.observations();
            assertEquals(2, attempts.size());
            long expectedMillis =
                    Math.max(
                            Math.ceilDiv(60000, budget.policy().globalPerMinute()),
                            Math.ceilDiv(
                                    60000,
                                    budget.policy()
                                            .endpointLimits()
                                            .getOrDefault(
                                                    "stock_basic",
                                                    budget.policy().endpointPerMinute())));
            long actualMillis =
                    Duration.between(
                                    attempts.getFirst().startedAt(), attempts.getLast().startedAt())
                            .toMillis();
            assertTrue(
                    actualMillis >= expectedMillis - 1,
                    "Shared budget must pace actual attempts (millisecond rounding)");
            var out = Path.of("artifacts/java-migration/F005");
            Files.createDirectories(out);
            new ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            out.resolve("source-http.json").toFile(),
                            Map.ofEntries(
                                    Map.entry("observed_at", Instant.now().toString()),
                                    Map.entry("endpoint", request.apiName()),
                                    Map.entry("params", request.params()),
                                    Map.entry("fields", request.fields()),
                                    Map.entry("first_rows", first.rows()),
                                    Map.entry("second_rows", second.rows()),
                                    Map.entry("transport_events", events),
                                    Map.entry("expected_interval_ms", expectedMillis),
                                    Map.entry("actual_attempt_interval_ms", actualMillis),
                                    Map.entry("charged_attempts", attempts.size()),
                                    Map.entry(
                                            "connection_reused",
                                            observations
                                                    .getFirst()
                                                    .connectionId()
                                                    .equals(
                                                            observations
                                                                    .getLast()
                                                                    .connectionId()))));
        }
    }
}
