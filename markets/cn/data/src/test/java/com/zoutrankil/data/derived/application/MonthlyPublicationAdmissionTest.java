package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MonthlyPublicationAdmissionTest {
    @TempDir Path temp;
    private record Admission(String dataset, String code, Executable check) {}

    @Test void bothServicesPreserveAbsentLedgerOptionalTableAndRequiredTableBehavior() throws Exception {
        Path path = temp.resolve("ledger.sqlite");
        for (var admission : admissions(path)) assertDoesNotThrow(admission.check());
        assertFalse(Files.exists(path));
        new SyncRunLedger(path);
        for (var admission : admissions(path)) assertDoesNotThrow(admission.check());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE sync_entries");
        }
        for (var admission : admissions(path)) assertThrows(SQLException.class, admission.check());
    }

    @Test void bothServicesPreserveDatasetScopeLeasePrecedenceAndFailureMessages() throws Exception {
        for (int index = 0; index < 2; index++) {
            Path path = temp.resolve("dataset-" + index + ".sqlite");
            var checks = admissions(path);
            var matching = checks.get(index);
            var other = checks.get(1 - index);
            var ledger = new SyncRunLedger(path);
            ledger.createRun(new SyncRunLedger.Run("run", null, "data.fixture", 1, "2026-09-29", "target",
                    "{\"definition\":{\"datasetId\":\"" + matching.dataset() + "\"}}"));
            assertEquals(matching.code() + " pending publication requires explicit reconciliation",
                    assertThrows(IllegalStateException.class, matching.check()).getMessage());
            assertDoesNotThrow(other.check());
            var locks = new DatasetIntervalLock(path);
            assertNotNull(locks.acquire("run", DatasetIntervalLock.Scope.allDates(matching.dataset())));
            assertEquals(matching.code() + " dataset has an active or uncertain publication lease",
                    assertThrows(IllegalStateException.class, matching.check()).getMessage());
            assertDoesNotThrow(other.check());
        }
    }

    private static List<Admission> admissions(Path path) {
        var equity = new EquityStyleMonthlyJobService(path, mock(EquityStyleMonthlySource.class),
                () -> { throw new AssertionError("Admission must not access the QuestDB writer"); });
        var macro = new MacroCoreMonthlyJobService(path, mock(MacroCoreMonthlySource.class),
                () -> { throw new AssertionError("Admission must not access the QuestDB writer"); });
        return List.of(new Admission(equity.datasetId(), "D103", equity::requireNoPendingPublication),
                new Admission(macro.datasetId(), "D104", macro::requireNoPendingPublication));
    }
}
