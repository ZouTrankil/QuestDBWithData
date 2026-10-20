package com.zoutrankil.data.factor.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.zoutrankil.data.factor.application.FactorIncrementalPlanner;
import com.zoutrankil.data.factor.application.FactorPoolEvidence;
import com.zoutrankil.data.repository.FileEvidenceStore;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Durable section receipts plus content-addressed result files. Does not certify source completeness. */
public final class FactorPoolCoverageStore {
    private final Path root;

    public FactorPoolCoverageStore(Path root) throws Exception {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root.resolve("objects"));
        try (var connection = open();
                var sql = connection.createStatement()) {
            sql.execute(
                    "CREATE TABLE IF NOT EXISTS pool_coverage (signal_date TEXT NOT NULL,factor_id TEXT NOT NULL,"
                            + "version TEXT NOT NULL,pool_id TEXT NOT NULL,input_sha TEXT NOT NULL,file_sha TEXT NOT NULL,"
                            + "PRIMARY KEY(signal_date,factor_id,version,pool_id))");
        }
    }

    public record Receipt(String fileSha256, int sections) {}

    public Receipt record(Path artifact) throws Exception {
        var evidence = FactorPoolEvidence.read(artifact);
        Path object = object(evidence.sha256());
        if (!Files.exists(object)) {
            try {
                FileEvidenceStore.writeNewDurable(object, evidence.bytes());
            } catch (FileAlreadyExistsException raced) {
                /* Validate the concurrent writer's object below. */
            }
        }
        if (!FactorPoolEvidence.read(object).sha256().equals(evidence.sha256()))
            throw new IllegalStateException("Stored pool object digest mismatch");
        try (var connection = open()) {
            connection.setAutoCommit(false);
            try (var insert =
                    connection.prepareStatement(
                            "INSERT INTO pool_coverage VALUES(?,?,?,?,?,?) "
                                    + "ON CONFLICT(signal_date,factor_id,version,pool_id) DO UPDATE SET input_sha=excluded.input_sha,file_sha=excluded.file_sha")) {
                for (var request : evidence.sections()) {
                    var section = request.section();
                    insert.setString(1, section.date().toString());
                    insert.setString(2, section.factorId());
                    insert.setString(3, section.version());
                    insert.setString(4, section.poolId());
                    insert.setString(5, evidence.inputFingerprint());
                    insert.setString(6, evidence.sha256());
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
        return new Receipt(evidence.sha256(), evidence.sections().size());
    }

    public List<FactorIncrementalPlanner.Coverage> read(
            List<FactorIncrementalPlanner.Requested> requested) throws Exception {
        var result = new ArrayList<FactorIncrementalPlanner.Coverage>();
        var files = new HashMap<String, FactorPoolEvidence.Snapshot>();
        var invalid = new HashSet<String>();
        try (var connection = open();
                var select =
                        connection.prepareStatement(
                                "SELECT input_sha,file_sha FROM pool_coverage WHERE signal_date=? AND factor_id=? AND version=? AND pool_id=?")) {
            for (var request : requested) {
                var section = request.section();
                select.setString(1, section.date().toString());
                select.setString(2, section.factorId());
                select.setString(3, section.version());
                select.setString(4, section.poolId());
                try (var rows = select.executeQuery()) {
                    if (!rows.next()) continue;
                    String input = rows.getString(1), sha = rows.getString(2);
                    if (!files.containsKey(sha) && !invalid.contains(sha)) {
                        try {
                            var file = FactorPoolEvidence.read(object(sha));
                            if (file.sha256().equals(sha)) files.put(sha, file);
                            else invalid.add(sha);
                        } catch (NoSuchFileException
                                | IllegalArgumentException
                                | JsonProcessingException damaged) {
                            invalid.add(sha);
                        }
                    }
                    var evidence = files.get(sha);
                    boolean physical =
                            evidence != null
                                    && evidence.inputFingerprint().equals(input)
                                    && evidence.sections().stream()
                                            .anyMatch(s -> s.section().equals(section));
                    result.add(
                            new FactorIncrementalPlanner.Coverage(section, input, physical, false));
                }
            }
        }
        return List.copyOf(result);
    }

    private Connection open() throws SQLException {
        var connection =
                DriverManager.getConnection("jdbc:sqlite:" + root.resolve("coverage.sqlite3"));
        try (var sql = connection.createStatement()) {
            sql.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    private Path object(String sha) {
        if (!sha.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid object digest");
        return root.resolve("objects").resolve(sha + ".json");
    }
}
