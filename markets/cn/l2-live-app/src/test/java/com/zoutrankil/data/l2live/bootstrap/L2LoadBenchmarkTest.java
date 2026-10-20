package com.zoutrankil.data.l2live.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.l2live.storage.RawJournal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class L2LoadBenchmarkTest {
    @TempDir Path root;

    @Test
    void loadUsesAllTopicsAndRealPersistenceThenChecksRestart() throws Exception {
        try (var journal = new RawJournal(root)) {
            var report = L2LoadBenchmark.run(journal, 48, 10000, 200, 0);
            assertEquals(48, report.get("topics"));
            assertEquals(16, report.get("symbols"));
            assertEquals(48L, report.get("verified"));
            assertEquals(false, report.get("productionAcceptance"));
        }
        try (var journal = new RawJournal(root)) {
            assertEquals(48, journal.status().completed());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> L2LoadBenchmark.run(journal, 48, 1000, 200, 0));
        }
    }
}
