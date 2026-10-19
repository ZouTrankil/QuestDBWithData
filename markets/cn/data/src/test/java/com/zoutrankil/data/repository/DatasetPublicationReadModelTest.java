package com.zoutrankil.data.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.zoutrankil.data.repository.DatasetPublicationReadModel.State.*;
import static org.junit.jupiter.api.Assertions.*;

class DatasetPublicationReadModelTest {
    @TempDir Path temp;
    private static final String DATASET = "equity_style_monthly";

    @Test void missingLedgerIsClearWithoutCreatingAFile() throws Exception {
        Path missing = temp.resolve("absent.sqlite");
        assertEquals(CLEAR, DatasetPublicationReadModel.read(missing, DATASET));
        assertFalse(Files.exists(missing));
    }

    @Test void missingOptionalLockTableIsClearWithoutInitializingIt() throws Exception {
        var ledger = ledger("without-locks");
        Set<String> before = SqliteLedgerSchema.tableNames(ledger.path());
        assertFalse(before.contains("sync_interval_locks"));
        assertEquals(CLEAR, DatasetPublicationReadModel.read(ledger.path(), DATASET));
        assertEquals(before, SqliteLedgerSchema.tableNames(ledger.path()));
    }

    @Test void missingRequiredLedgerTablesStillFailInsteadOfBecomingClear() throws Exception {
        for (String missing : new String[]{"sync_entries", "sync_runs"}) {
            var ledger = ledger("missing-" + missing);
            sql(ledger.path(), "DROP TABLE " + missing);
            assertThrows(SQLException.class, () -> DatasetPublicationReadModel.read(ledger.path(), DATASET));
        }
        Path empty = Files.createFile(temp.resolve("empty.sqlite"));
        assertThrows(SQLException.class, () -> DatasetPublicationReadModel.read(empty, DATASET));
        assertEquals(0, Files.size(empty));
    }

    @Test void leaseCheckIsDatasetWideAndDoesNotMatchOtherDatasets() throws Exception {
        var ledger = ledger("leases");
        createLocks(ledger.path());
        insertLock(ledger.path(), "other", "macro_core_monthly", 0);
        assertEquals(CLEAR, DatasetPublicationReadModel.read(ledger.path(), DATASET));
        for (int inDoubt : new int[]{0, 1}) {
            insertLock(ledger.path(), "matching-" + inDoubt, DATASET, inDoubt);
            assertEquals(LEASE_HELD, DatasetPublicationReadModel.read(ledger.path(), DATASET));
            sql(ledger.path(), "DELETE FROM sync_interval_locks WHERE id='matching-" + inDoubt + "'");
        }
        assertEquals(CLEAR, DatasetPublicationReadModel.read(ledger.path(), "' OR 1=1 --"));
    }

    @Test void leaseTakesPrecedenceEvenWhenRunTablesAreMissing() throws Exception {
        var ledger = ledger("lease-priority");
        createLocks(ledger.path());
        insertLock(ledger.path(), "matching", DATASET, 1);
        sql(ledger.path(), "DROP TABLE sync_entries", "DROP TABLE sync_runs");
        assertEquals(LEASE_HELD, DatasetPublicationReadModel.read(ledger.path(), DATASET));
        assertThrows(SQLException.class, () -> DatasetPublicationReadModel.read(ledger.path(), "other"));
    }

    @Test void pendingEvidenceMatchesOnlyItsDatasetAndOriginalKindStateRules() throws Exception {
        var ledger = ledger("pending");
        ledger.createRun(new SyncRunLedger.Run("run", null, "data.fixture", 1, "2026-09-29", "target",
                "{\"definition\":{\"datasetId\":\"" + DATASET + "\"}}"));
        assertEquals(CLEAR, DatasetPublicationReadModel.read(ledger.path(), "macro_core_monthly"));
        for (String kind : new String[]{"RUN", "ATTEMPT", "SLICE"}) {
            for (String state : new String[]{"PENDING", "RUNNING", "FETCHED", "VALIDATED", "SUBMITTED",
                    "ACKNOWLEDGED", "IN_DOUBT", "VERIFIED", "VERIFIED_EMPTY", "FAILED", "CANCELLED"}) {
                sql(ledger.path(), "UPDATE sync_entries SET kind='" + kind + "',state='" + state + "' WHERE id='run'");
                boolean unresolved = Set.of("SUBMITTED", "ACKNOWLEDGED", "IN_DOUBT").contains(state)
                        || kind.equals("RUN") && Set.of("PENDING", "RUNNING").contains(state);
                assertEquals(unresolved ? PENDING_PUBLICATION : CLEAR,
                        DatasetPublicationReadModel.read(ledger.path(), DATASET), kind + "/" + state);
            }
        }
    }

    private SyncRunLedger ledger(String name) throws Exception {
        return new SyncRunLedger(temp.resolve(name + ".sqlite"));
    }

    private static void createLocks(Path path) throws Exception {
        sql(path, "CREATE TABLE sync_interval_locks (id TEXT PRIMARY KEY, run_id TEXT NOT NULL, "
                + "dataset_id TEXT NOT NULL, from_day INTEGER NOT NULL, to_day INTEGER NOT NULL, "
                + "in_doubt INTEGER NOT NULL, acquired_at TEXT NOT NULL)");
    }

    private static void insertLock(Path path, String id, String dataset, int inDoubt) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var query = connection.prepareStatement("INSERT INTO sync_interval_locks VALUES(?,'fixture-run',?,0,0,?,'fixture-time')")) {
            query.setString(1, id);
            query.setString(2, dataset);
            query.setInt(3, inDoubt);
            query.executeUpdate();
        }
    }

    private static void sql(Path path, String... statements) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }
}
