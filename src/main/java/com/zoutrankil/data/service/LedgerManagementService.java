package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.LedgerReadModels.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/** Management operations against an existing ledger; construction never opens or creates one. */
@Service
public final class LedgerManagementService {
    public static final String DEFAULT_LEDGER_PATH = "var/sync-ledger.sqlite3";
    public record Cancellation(String runId, boolean cancellationRequested, SyncRunState state) {}
    public record RunDetails(Run run, List<Entry> entries) {
        public RunDetails { entries = List.copyOf(entries); }
    }

    public record StatusDetails(Entry status, List<Entry> entries) {
        public StatusDetails { entries = List.copyOf(entries); }
    }

    public StatusDetails status(String runId) throws IOException, SQLException {
        var ledger = SyncRunLedger.openReadOnly(configuredLedgerPath);
        return new StatusDetails(LedgerReadModels.entry(ledger.get(runId)),
                ledger.entries(runId, null, 100).stream().map(LedgerReadModels::entry).toList());
    }

    private final Path configuredLedgerPath;

    public LedgerManagementService(@Value("${app.sync.ledger-path:" + DEFAULT_LEDGER_PATH + "}") String ledgerPath) {
        configuredLedgerPath = Path.of(ledgerPath).toAbsolutePath().normalize();
    }

    Path resolvePath(String override) {
        return override == null ? configuredLedgerPath : Path.of(override).toAbsolutePath().normalize();
    }

    public Cancellation cancel(String override, String runId) throws IOException, SQLException {
        Path path = resolvePath(override);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Ledger does not exist");
        var ledger = new SyncRunLedger(path);
        boolean accepted = ledger.requestCancellation(runId);
        return new Cancellation(runId, accepted, ledger.get(runId).state());
    }

    public List<RunSummary> history(String override, String jobId, String afterId, int limit)
            throws IOException, SQLException {
        return SyncRunLedger.openReadOnly(resolvePath(override)).history(jobId, afterId, limit).stream()
                .map(row -> new RunSummary(row.id(), row.parentRunId(), row.jobId(), row.jobVersion(),
                        row.logicalDate(), row.targetId(), row.state(), row.revision(), row.updatedAt())).toList();
    }

    public RunDetails run(String override, String runId, String afterId, int limit) throws IOException, SQLException {
        var ledger = SyncRunLedger.openReadOnly(resolvePath(override));
        var row = ledger.getRun(runId);
        var run = new Run(row.id(), row.parentRunId(), row.jobId(), row.jobVersion(), row.logicalDate(),
                row.targetId(), row.frozenJson());
        var entries = ledger.entries(runId, afterId, limit).stream().map(LedgerReadModels::entry).toList();
        return new RunDetails(run, entries);
    }
}
