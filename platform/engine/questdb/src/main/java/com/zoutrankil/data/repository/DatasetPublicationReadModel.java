package com.zoutrankil.data.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Read-only dataset-wide publication exclusions; it neither initializes nor repairs a ledger. */
public final class DatasetPublicationReadModel {
    public enum State { CLEAR, LEASE_HELD, PENDING_PUBLICATION }

    private DatasetPublicationReadModel() {}

    public static State read(Path ledgerPath, String datasetId) throws SQLException {
        if (!Files.isRegularFile(ledgerPath)) return State.CLEAR;
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + ledgerPath.toUri().toASCIIString() + "?mode=ro");
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.setQueryTimeout(5);
            if (SqliteLedgerSchema.tableExists(connection, "sync_interval_locks")) {
                try (var query = connection.prepareStatement(
                        "SELECT id FROM sync_interval_locks WHERE dataset_id=? LIMIT 1")) {
                    query.setString(1, datasetId);
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) return State.LEASE_HELD;
                    }
                }
            }
            try (var query = connection.prepareStatement(
                    "SELECT e.id FROM sync_entries e JOIN sync_runs r ON r.id=e.run_id "
                            + "WHERE json_extract(r.frozen_json,'$.definition.datasetId')=? "
                            + "AND (e.state IN ('SUBMITTED','ACKNOWLEDGED','IN_DOUBT') "
                            + "OR (e.kind='RUN' AND e.state IN ('PENDING','RUNNING'))) LIMIT 1")) {
                query.setString(1, datasetId);
                try (var rows = query.executeQuery()) {
                    return rows.next() ? State.PENDING_PUBLICATION : State.CLEAR;
                }
            }
        }
    }
}
