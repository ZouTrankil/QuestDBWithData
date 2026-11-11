package com.zoutrankil.batch.l2;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class L2FeatureFilesTest {
    @TempDir Path directory;

    @Test
    void transientSharingConflictRetriesAndKeepsAtomicReplacement() throws Exception {
        Path source = directory.resolve("temp"), target = directory.resolve("target");
        Files.writeString(source, "new");
        Files.writeString(target, "old");
        var attempts = new AtomicInteger();
        L2FeatureFiles.replaceAtomically(source, target, (from, to) -> {
            if (attempts.incrementAndGet() <= 2) throw new AccessDeniedException(to.toString());
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        assertEquals(3, attempts.get());
        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(source));
    }

    @Test
    void persistentDenialIsBoundedAndPreservesBothFiles() throws Exception {
        Path source = directory.resolve("temp"), target = directory.resolve("target");
        Files.writeString(source, "new");
        Files.writeString(target, "old");
        var attempts = new AtomicInteger();
        assertThrows(AccessDeniedException.class, () -> L2FeatureFiles.replaceAtomically(source, target, (from, to) -> {
            attempts.incrementAndGet();
            throw new AccessDeniedException(to.toString());
        }));
        assertEquals(5, attempts.get());
        assertEquals("new", Files.readString(source));
        assertEquals("old", Files.readString(target));
    }

    @Test
    void doesNotRetryWhenTemporaryFileDisappearsOrErrorIsNotDenial() throws Exception {
        Path source = directory.resolve("temp"), target = directory.resolve("target");
        Files.writeString(source, "new");
        var attempts = new AtomicInteger();
        assertThrows(AccessDeniedException.class, () -> L2FeatureFiles.replaceAtomically(source, target, (from, to) -> {
            attempts.incrementAndGet();
            Files.delete(from);
            throw new AccessDeniedException(to.toString());
        }));
        assertEquals(1, attempts.get());
        Files.writeString(source, "new");
        attempts.set(0);
        assertThrows(IOException.class, () -> L2FeatureFiles.replaceAtomically(source, target, (from, to) -> {
            attempts.incrementAndGet();
            throw new IOException("other error");
        }));
        assertEquals(1, attempts.get());
    }

    @Test
    void interruptionStopsRetryAndRetainsInterruptFlag() throws Exception {
        Path source = directory.resolve("temp");
        Files.writeString(source, "new");
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, () -> L2FeatureFiles.replaceAtomically(source, directory.resolve("target"),
                    (from, to) -> {throw new AccessDeniedException(to.toString());}));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
