package com.zoutrankil.data.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.stock.application.DailyBasicJobService;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class FrozenRequestJsonSerializationTest {
    @Test void planOutputSerializesTheWholeFrozenRequestWithoutDisablingEmptyBeanFailures() throws Exception {
        var from = LocalDate.of(2026, 9, 25);
        var to = LocalDate.of(2026, 9, 29);
        var logicalDate = LocalDate.of(2026, 9, 30);
        var definition = new SyncJobDefinition("data.frozen_json", 3, "frozen_dataset", 2, "frozen_owner",
                Set.of(Mode.INCREMENTAL, Mode.BACKFILL), Mode.INCREMENTAL,
                Map.of("codes", new Parameter(ParameterType.STRING_LIST, true, 12, 3, Set.of()),
                        "includeInactive", new Parameter(ParameterType.BOOLEAN, false, 16, 1, Set.of())),
                "tushare.shared", "trade_date.bounded", "questdb.full_key_values",
                new RetryPolicy(2, Duration.ofSeconds(2), Duration.ofSeconds(20)), Duration.ofMinutes(1),
                new Budget(5, 3, 3, 300, 4096), 2,
                List.of(new JobRef("data.dependency", 1)), Frequency.DAILY, ZoneId.of("Asia/Shanghai"), true, false);
        var request = definition.freeze(Mode.BACKFILL,
                Map.of("codes", List.of("000001.SZ", "600000.SH"), "includeInactive", true),
                from, to, logicalDate);
        var plan = new DailyBasicJobService.Plan(request, "questdb-target-7", from.minusDays(2));

        var json = JobDefinitionJson.mapper();
        assertTrue(json.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS),
                "request serialization must work without weakening empty-bean checks");
        String planOutput = json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("status", "PLANNED", "plan", plan));
        JsonNode tree = json.readTree(planOutput);
        JsonNode frozen = tree.path("plan").path("request");
        assertTrue(frozen.isObject());
        assertFalse(frozen.isEmpty(), "FrozenRequest must never serialize as {}");
        assertEquals("data.frozen_json", frozen.path("definition").path("jobId").asText());
        assertEquals(3, frozen.path("definition").path("version").asInt());
        assertEquals("frozen_dataset", frozen.path("definition").path("datasetId").asText());
        assertEquals(2, frozen.path("definition").path("datasetVersion").asInt());
        assertEquals(Set.of("jobId", "version", "datasetId", "datasetVersion", "owner", "supportedModes",
                        "defaultMode", "parameters", "ratePolicyRef", "slicePolicyRef", "verificationPolicyRef",
                        "retry", "timeout", "budget", "revisionDays", "dependencies", "frequency", "zone",
                        "enabled", "dailyEligible"),
                iterableToSet(frozen.path("definition").fieldNames()));
        assertEquals("BACKFILL", frozen.path("mode").asText());
        assertEquals(List.of("000001.SZ", "600000.SH"),
                json.convertValue(frozen.path("parameters").path("codes"), json.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));
        assertTrue(frozen.path("parameters").path("includeInactive").asBoolean());
        assertEquals(from.toString(), frozen.path("from").asText());
        assertEquals(to.toString(), frozen.path("to").asText());
        assertEquals(logicalDate.toString(), frozen.path("logicalDate").asText());

        assertEquals(frozen, json.readTree(SyncRequestIdentity.snapshotJson(request)),
                "stored identity snapshots and nested plan JSON must use the same request shape");
    }

    private static Set<String> iterableToSet(java.util.Iterator<String> values) {
        var result = new java.util.HashSet<String>();
        values.forEachRemaining(result::add);
        return result;
    }
}
