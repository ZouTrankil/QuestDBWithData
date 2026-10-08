package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.SyncRunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SyncRunHistoryTest {
    @TempDir Path temp;
    @Test void readonlyHistoryIsBoundedFilteredAndKeepsActualState() throws Exception {
        Path path = temp.resolve("ledger.sqlite");
        var writer = new SyncRunLedger(path);
        writer.createRun(new SyncRunLedger.Run("run-a", null, "job.one", 1, "2026-09-29", "target", "{}"));
        writer.createRun(new SyncRunLedger.Run("run-b", "run-a", "job.two", 2, "2026-09-29", "target", "{}"));
        writer.createRun(new SyncRunLedger.Run("run-c", null, "job.one", 1, "2026-09-30", "target", "{}"));
        var reader = SyncRunLedger.openReadOnly(path);
        var first = reader.history(null, null, 1);
        assertEquals(List.of("run-a"), first.stream().map(SyncRunLedger.RunSummary::id).toList());
        assertEquals(writer.get("run-a").state(), first.getFirst().state());
        assertNotEquals(SyncRunState.VERIFIED, first.getFirst().state());
        var next = reader.history(null, first.getFirst().id(), 1);
        assertEquals("run-b", next.getFirst().id());
        assertEquals("run-a", next.getFirst().parentRunId());
        assertEquals(List.of("run-a", "run-c"), reader.history("job.one", null, 10).stream()
                .map(SyncRunLedger.RunSummary::id).toList());
        assertTrue(reader.history("job.one", "run-c", 10).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> reader.history(null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> reader.history(null, null, 1001));
        assertThrows(Exception.class, () -> reader.createRun(new SyncRunLedger.Run(
                "run-d", null, "job.one", 1, "2026-09-29", "target", "{}")));
        assertEquals(3, writer.history(null, null, 10).size());
    }
    @Test void missingLedgerIsNotCreatedByHistoryInspection() {
        Path missing = temp.resolve("missing.sqlite");
        assertThrows(Exception.class, () -> SyncRunLedger.openReadOnly(missing));
        assertFalse(Files.exists(missing));
    }
}
