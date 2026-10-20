package com.zoutrankil.data.l2live.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class DailyParquetArchiveTest {
    @TempDir Path root;
    static final LocalDate DAY = LocalDate.parse("2026-10-14");

    String commit(RawJournal journal, int n) throws Exception {
        var event =
                ParquetPersistenceTest.append(
                        journal,
                        "trans/510500",
                        "510500.SH,B,93000001," + n + ",15460,1000,100,101");
        var batch = journal.prepareBatch(List.of(event), List.of(), Map.of("count", (long) n));
        new LocalLiveSink(journal).write(batch.events(), batch.states());
        journal.completeBatch(batch.id());
        return batch.id();
    }

    @Test
    void compactionPreservesOriginalBatchesAndLatestRecoveryWhileReducingFiles() throws Exception {
        String first, last;
        try (var journal = new RawJournal(root)) {
            first = commit(journal, 1);
            commit(journal, 2);
            last = commit(journal, 3);
            var original = journal.storedBatch(first);
            assertThrows(IllegalArgumentException.class, () -> journal.archiveDay(DAY, DAY));
            var result = journal.archiveDay(DAY, DAY.plusDays(1));
            assertEquals(2, result.get("batches"));
            assertEquals(8L, result.get("filesRemoved"));
            assertEquals(original, journal.storedBatch(first));
            assertEquals(3L, journal.committedAccumulatorState().get("count"));
            assertEquals(2, journal.parquetFiles().get("events").size());
            assertTrue(Files.isDirectory(root.resolve("parquet/batches").resolve(last)));
            assertFalse(Files.exists(root.resolve("parquet/batches").resolve(first)));
            assertEquals(0L, journal.archiveDay(DAY, DAY.plusDays(1)).get("filesRemoved"));
            assertEquals(3, journal.status().captures());
            assertEquals(0, journal.status().pending());
            assertEquals(true, journal.verifyStorage().get("verified"));
        }
        try (var journal = new RawJournal(root)) {
            assertEquals(1L, journal.storedBatch(first).accumulatorState().get("count"));
            assertEquals(3L, journal.committedAccumulatorState().get("count"));
            assertEquals(0, journal.migrateParquet());
        }
    }

    @Test
    void restartFinishesInterruptedCleanupOnlyAfterVerifyingCommittedArchive() throws Exception {
        String first;
        byte[] retained;
        try (var journal = new RawJournal(root)) {
            first = commit(journal, 1);
            commit(journal, 2);
            retained =
                    Files.readAllBytes(
                            root.resolve("parquet/batches").resolve(first).resolve("raw.parquet"));
            journal.archiveDay(DAY, DAY.plusDays(1));
        }
        Path old = Files.createDirectory(root.resolve("parquet/batches").resolve(first));
        Files.write(old.resolve("raw.parquet"), retained);
        try (var journal = new RawJournal(root)) {
            assertFalse(Files.exists(old));
            assertEquals(1L, journal.storedBatch(first).accumulatorState().get("count"));
        }
    }

    @Test
    void damagedArchiveFailsReadbackAndNeverAdvancesCurrentCheckpoint() throws Exception {
        String first;
        try (var journal = new RawJournal(root)) {
            first = commit(journal, 1);
            commit(journal, 2);
            journal.archiveDay(DAY, DAY.plusDays(1));
            Files.write(
                    root.resolve("parquet/archives/2026-10-14/events.parquet"),
                    new byte[] {1, 2, 3});
            assertThrows(IllegalStateException.class, () -> journal.storedBatch(first));
            assertThrows(IllegalStateException.class, journal::verifyStorage);
            assertEquals(2L, journal.committedAccumulatorState().get("count"));
            assertEquals(2, journal.status().completed());
        }
    }

    @Test
    void pendingAndPreparedRecordsBlockSealing() throws Exception {
        try (var journal = new RawJournal(root)) {
            commit(journal, 1);
            var event =
                    ParquetPersistenceTest.append(
                            journal, "trans/510500", "510500.SH,B,93000001,2,15460,1000,100,101");
            assertThrows(
                    IllegalStateException.class, () -> journal.archiveDay(DAY, DAY.plusDays(1)));
            journal.prepareBatch(List.of(event), List.of(), Map.of("count", 2L));
            assertThrows(
                    IllegalStateException.class, () -> journal.archiveDay(DAY, DAY.plusDays(1)));
            assertEquals(1, journal.parquetFiles().get("events").size());
            assertEquals(1, journal.status().pending());
        }
    }
}
