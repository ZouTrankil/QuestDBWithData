package com.zoutrankil.questdbwithdata.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.config.QuestDbProperties;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.service.DatasetRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** Read-only projection used to exercise the contract; not a registration or migration of daily. */
@EnabledIfEnvironmentVariable(named = "QUESTDB_DEFINITION_READ", matches = "1")
class DatasetDefinitionLiveTest {
    @Test void declaredProjectionMatchesActualSchemaAndRows() throws Exception {
        var definition = new DatasetDefinition("daily_read_probe", 1, "questdb_existing", "F003_read_only_probe",
                "daily", ObjectKind.TABLE, List.of(
                new Column("ts_code", "instrument", "ts_code", StorageType.SYMBOL, false, "Instrument code", null),
                new Column("trade_date", "trading_date", "trade_date", StorageType.TIMESTAMP, false, "Trading calendar date",
                        new TemporalContract(TemporalKind.BUSINESS_DATE, "ISO", "calendar", "DAY", "Trading date")),
                new Column("close", "close_price", "close", StorageType.DOUBLE, true, "Closing price", null)),
                List.of("instrument", "trading_date"), List.of(), "trade_date", Partition.NONE, false,
                Set.of(Capability.READ), List.of(), "Read-only three-column projection; no DDL or full daily contract claim");
        var app = new SpringApplication(QuestDbWithDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        var json = new ObjectMapper();
        try (var ctx = app.run(); var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var p = ctx.getBean(QuestDbProperties.class);
            var registered = ctx.getBean(DatasetRegistry.class).definitions();
            assertEquals(List.of(StockBasicDataset.DEFINITION), registered);
            var queries = List.of("SELECT \"column\", \"type\" FROM table_columns('daily')",
                    "SELECT " + String.join(", ", definition.storageColumns()) + " FROM daily LIMIT 3");
            var responses = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            for (var sql : queries) {
                var uri = URI.create("http://" + p.getHost() + ":" + p.getQwpPort() + "/exec?query="
                        + URLEncoder.encode(sql, StandardCharsets.UTF_8));
                var auth = Base64.getEncoder().encodeToString((p.getUsername() + ":" + p.getPassword())
                        .getBytes(StandardCharsets.UTF_8));
                var response = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Basic " + auth)
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var body = json.readTree(response.body());
                assertFalse(body.has("error"));
                responses.add(body);
            }
            var actualTypes = new HashMap<String, String>();
            responses.getFirst().path("dataset").forEach(row -> actualTypes.put(row.get(0).asText(), row.get(1).asText()));
            for (var column : definition.columns()) assertEquals(column.storageType().name(), actualTypes.get(column.storageName()));
            var actualRows = responses.get(1).path("dataset");
            assertFalse(actualRows.isEmpty());
            var keys = new HashSet<String>();
            for (var row : actualRows) {
                assertFalse(row.get(0).asText().isBlank());
                var date = TemporalValues.CalendarTimestamp.fromStorage(Instant.parse(row.get(1).asText())).date();
                assertTrue(keys.add(row.get(0).asText() + ":" + date));
                assertTrue(row.get(2).isNumber() || row.get(2).isNull());
            }
            var out = Path.of("artifacts/java-migration/F003");
            Files.createDirectories(out);
            json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("definition-live-read.json").toFile(),
                    Map.of("observed_at", Instant.now().toString(), "registered", registered, "test_projection", definition,
                            "queries", queries, "actual_responses", responses, "matched_rows", actualRows.size(),
                            "scope", "Definition/projection read only; full daily migration not implemented"));
        }
    }
}
