package com.zoutrankil.data.l2live.storage;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalLiveSinkTest {
    @TempDir Path root;

    @Test void frozenNormalizationSurvivesRestartAndCommitsWithoutRemoteStorage() throws Exception {
        String batchId;
        try (var journal = new RawJournal(root)) {
            var event = RawJournalTest.event(journal);
            batchId = journal.prepareBatch(List.of(event), List.of(), Map.of("count", 1L)).id();
        }
        try (var journal = new RawJournal(root)) {
            var batch = journal.preparedBatch().orElseThrow();
            var sink = new LocalLiveSink(journal);
            sink.write(batch.events(), batch.states());
            // Retry after local verification but before completion is idempotent.
            sink.write(batch.events(), batch.states());
            journal.completeBatch(batchId);
            assertEquals(1, journal.status().completed());
        }
        try (var journal = new RawJournal(root);
             var c = DriverManager.getConnection("jdbc:sqlite:" + journal.databasePath());
             var s = c.createStatement();
             var r = s.executeQuery("SELECT payload FROM prepared_batch WHERE active=0")) {
            assertEquals(1L, journal.committedAccumulatorState().get("count"));
            assertTrue(r.next());
            assertEquals(0, r.getBytes(1).length);
            var retained = journal.storedBatch(batchId);
            assertEquals(1, retained.events().size());
            assertEquals("600000.SH", retained.events().getFirst().symbol());
            assertFalse(r.next());
        }
    }

    @Test void mismatchedValuesNeverAdvanceTheRawCheckpoint() {
        try (var journal = new RawJournal(root)) {
            var batch = journal.prepareBatch(List.of(RawJournalTest.event(journal)), List.of(), Map.of());
            assertThrows(IllegalStateException.class, () -> new LocalLiveSink(journal).write(List.of(), List.of()));
            assertThrows(IllegalStateException.class, () -> journal.completeBatch(batch.id()));
            assertEquals(1, journal.status().pending());
        }
    }
}
