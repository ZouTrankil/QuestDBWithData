package com.zoutrankil.data.l2live.storage;

import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteState;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Normalized events and minute observations remain in the durable local SQLite batch payload. */
public final class LocalLiveSink {
    private final RawJournal journal;

    public LocalLiveSink(RawJournal journal) { this.journal = Objects.requireNonNull(journal); }

    public synchronized void write(List<L2CleanEvent> events, List<L2MinuteState> states) {
        var batch = journal.preparedBatch().orElseThrow(() ->
                new IllegalStateException("Freeze the local normalization batch before committing"));
        if (!Arrays.equals(LiveStorageJson.bytes(events), LiveStorageJson.bytes(batch.events()))
                || !Arrays.equals(LiveStorageJson.bytes(states), LiveStorageJson.bytes(batch.states())))
            throw new IllegalStateException("Local values differ from the durable frozen batch");
        // preparedBatch reads SQLite and checks its payload SHA before exposing values.
        String payload = LiveStorageJson.sha256(LiveStorageJson.bytes(List.of(events, states)));
        String local = LiveStorageJson.sha256(LiveStorageJson.bytes(
                List.of("local-sqlite-v1", journal.databasePath().toString())));
        journal.reserveOutbox(batch.id(), payload, local, local);
        journal.markWriteVerified(batch.id());
    }
}
