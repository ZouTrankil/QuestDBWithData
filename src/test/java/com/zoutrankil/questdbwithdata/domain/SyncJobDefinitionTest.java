package com.zoutrankil.questdbwithdata.domain;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncJobDefinitionTest {
    static SyncJobDefinition definition(int version) {
        return new SyncJobDefinition("data.stock_basic", version, "stock_basic_snapshot", 1, "stock_adapter",
                Set.of(Mode.INCREMENTAL, Mode.BACKFILL), Mode.INCREMENTAL,
                Map.of("codes", new Parameter(ParameterType.STRING_LIST, true, 20, 2, Set.of())),
                "tushare.shared", "by_code", "full_key_values",
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30)), Duration.ofMinutes(2),
                new Budget(31, 20, 10, 100, 4096), 2, List.of(), Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"), false, false);
    }
    private final LocalDate day = LocalDate.of(2026, 9, 29);

    @Test void freezesParametersAndDefinitionVersionWithoutMutableAliases() {
        var codes = new ArrayList<>(List.of("000001.SZ"));
        var params = new HashMap<String, Object>(); params.put("codes", codes);
        var request = definition(1).freeze(null, params, day, day, day);
        codes.add("600000.SH"); params.clear();
        assertEquals(List.of("000001.SZ"), request.parameters().get("codes"));
        assertEquals(Mode.INCREMENTAL, request.mode());
        assertEquals(1, request.definition().version());
        assertEquals(2, definition(2).version());
        assertEquals(1, request.definition().version());
        assertThrows(UnsupportedOperationException.class, () -> request.parameters().clear());
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) request.parameters().get("codes")).clear());
    }
    @Test void rejectsUnboundedReversedOrOversizedHistory() {
        var job = definition(1); var params = Map.of("codes", List.of("000001.SZ"));
        assertThrows(IllegalArgumentException.class, () -> job.freeze(null, params, null, null, day));
        assertThrows(IllegalArgumentException.class, () -> job.freeze(Mode.BACKFILL, params, day, null, day));
        assertThrows(IllegalArgumentException.class, () -> job.freeze(null, params, day, day.minusDays(1), day));
        assertThrows(IllegalArgumentException.class, () -> job.freeze(null, params, day.minusDays(31), day, day));
        assertNotNull(job.freeze(null, params, day.minusDays(30), day, day));
    }
    @Test void rejectsUnsupportedModesAndMalformedParameters() {
        var job = definition(1);
        assertThrows(IllegalArgumentException.class, () -> job.freeze(Mode.SNAPSHOT,
                Map.of("codes", List.of("000001.SZ")), day, day, day));
        for (Map<String, ?> params : List.<Map<String, ?>>of(Map.of(), Map.of("codes", "all"),
                Map.of("codes", List.of("a", "b", "c")), Map.of("codes", List.of("a", "a")),
                Map.of("codes", List.of("a"), "all_history", true))) {
            assertThrows(IllegalArgumentException.class, () -> job.freeze(null, params, day, day, day));
        }
    }
    @Test void finiteRetryAndMemoryBudgetsAreMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, Duration.ofSeconds(3), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new Budget(0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Parameter(ParameterType.BOOLEAN, false, 10, 1, Set.of("true")));
    }
}
