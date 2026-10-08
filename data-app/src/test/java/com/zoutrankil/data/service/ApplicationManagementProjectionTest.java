package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SyncScheduleStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationManagementProjectionTest {
    @TempDir Path temp;

    @Test void ledgerReadModelsKeepEveryFieldAndExactSerializedBytes() throws Exception {
        var path = temp.resolve("projection.sqlite");
        var ledger = new SyncRunLedger(path);
        ledger.createRun(new SyncRunLedger.Run("run", null, "test.job", 2,
                "2026-10-07", "target", "{\"unicode\":\"中文\"}"));
        ledger.createChild("attempt", SyncRunLedger.Kind.ATTEMPT, "run", "run");
        ledger.createChild("slice", SyncRunLedger.Kind.SLICE, "run", "attempt");
        ledger.transition("run", 0, SyncRunState.RUNNING, "{\"nullable\":null}");
        var service = new LedgerManagementService(path.toString());
        var actual = service.run(null, "run", null, 100);
        var json = new ObjectMapper();
        assertEquals(json.writeValueAsString(ledger.getRun("run")), json.writeValueAsString(actual.run()));
        assertEquals(json.writeValueAsString(ledger.entries("run", null, 100)), json.writeValueAsString(actual.entries()));
        assertEquals(json.writeValueAsString(ledger.history(null, null, 100)),
                json.writeValueAsString(service.history(null, null, null, 100)));
        assertThrows(UnsupportedOperationException.class, () -> actual.entries().clear());
        assertEquals(Arrays.stream(SyncRunLedger.Kind.values()).map(Enum::name).toList(),
                Arrays.stream(LedgerReadModels.Kind.values()).map(Enum::name).toList());
    }

    @Test void scheduleHistoryRetainsAllStateNamesNullsAndTimestampEncoding() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var instant = Instant.parse("2026-10-07T00:00:00.123456Z");
        assertEquals(Arrays.stream(SyncScheduleStore.State.values()).map(Enum::name).toList(),
                Arrays.stream(StockBasicScheduleService.State.values()).map(Enum::name).toList());
        for (var state : SyncScheduleStore.State.values()) {
            var prior = new SyncScheduleStore.History("schedule", instant, state, null, "detail");
            var actual = new StockBasicScheduleService.History("schedule", instant,
                    StockBasicScheduleService.State.valueOf(state.name()), null, "detail");
            assertEquals(json.writerWithDefaultPrettyPrinter().writeValueAsString(prior),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(actual));
        }
        var priorStatus = new SyncScheduleManager.Status(null, null, List.of());
        var actualStatus = new StockBasicScheduleService.Status(null, null, List.of());
        assertEquals(json.writeValueAsString(priorStatus), json.writeValueAsString(actualStatus));
    }

    @Test void publicManagementModelsDoNotExposeRepositoryTypes() {
        for (var type : List.of(LedgerReadModels.Run.class, LedgerReadModels.Entry.class,
                LedgerReadModels.RunSummary.class, LedgerManagementService.RunDetails.class,
                StockBasicScheduleService.History.class, StockBasicScheduleService.Status.class)) {
            for (var component : type.getRecordComponents())
                assertFalse(component.getGenericType().getTypeName().contains(".repository."),
                        type.getSimpleName() + "." + component.getName());
        }
    }
}
