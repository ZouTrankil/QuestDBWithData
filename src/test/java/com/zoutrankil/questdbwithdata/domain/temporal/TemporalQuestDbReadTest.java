package com.zoutrankil.questdbwithdata.domain.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.config.QuestDbProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.*;

/** Read only: no Flyway, source sync, DDL or inserts. */
@EnabledIfEnvironmentVariable(named = "QUESTDB_TEMPORAL_READ", matches = "1")
class TemporalQuestDbReadTest {
    @Test void actualQuestDbCalendarCarriersRoundTripInJava() throws Exception {
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        var mapper = new ObjectMapper();
        var evidence = new ArrayList<Map<String, Object>>();
        try (var context = app.run(); var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build()) {
            var p = context.getBean(QuestDbProperties.class);
            var auth = Base64.getEncoder().encodeToString(
                    (p.getUsername() + ":" + p.getPassword()).getBytes(StandardCharsets.UTF_8));
            for (var spec : List.of(new String[]{"daily", "trade_date"},
                    new String[]{"etf_daily", "timestamp"}, new String[]{"exchange_calendar", "cal_date"})) {
                var sql = "SELECT " + spec[1] + " FROM " + spec[0] + " LIMIT 3";
                var uri = URI.create("http://" + p.getHost() + ":" + p.getQwpPort() + "/exec?query="
                        + URLEncoder.encode(sql, StandardCharsets.UTF_8));
                var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Basic " + auth).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), "QuestDB read failed");
                var body = mapper.readTree(response.body());
                assertFalse(body.has("error"), "QuestDB returned query error");
                var rows = body.path("dataset");
                assertFalse(rows.isEmpty(), "Actual nonempty sample required");
                for (var row : rows) {
                    var raw = row.get(0).asText();
                    var carrier = offsetInstant(raw, Precision.NANOS);
                    var calendar = CalendarTimestamp.fromStorage(carrier);
                    assertEquals(raw.substring(0, 10), formatDate(calendar.date(), DateFormat.ISO));
                    assertEquals(carrier, calendar.storageCarrier());
                    evidence.add(Map.of("sql", sql, "raw", raw, "business_date", calendar.date().toString(),
                            "round_trip", true));
                }
            }
        }
        var out = Path.of("artifacts/java-migration/F002");
        Files.createDirectories(out);
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("live-read.json").toFile(),
                Map.of("observed_at", Instant.now().toString(), "access", "read_only_http_select",
                        "samples", evidence));
    }
}
