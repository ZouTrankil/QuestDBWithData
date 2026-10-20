package com.zoutrankil.data.l2live.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.nio.file.ExtendedOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class StatusFilePublicationTest {
    @TempDir Path root;

    @Test
    void transientWindowsReaderDoesNotTerminateStatusPublication() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path status = root.resolve("status.json");
        Files.writeString(status, "{\"phase\":\"previous\"}");
        var method = AliyunL2LiveMain.class.getDeclaredMethod("status", Path.class, Object.class);
        method.setAccessible(true);
        method.invoke(null, root, Map.of("phase", "previous"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var held =
                    Files.newByteChannel(
                            status,
                            Set.of(StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE));
            try {
                var entered = new CountDownLatch(1);
                var published =
                        executor.submit(
                                () -> {
                                    entered.countDown();
                                    method.invoke(null, root, Map.of("phase", "running"));
                                    return true;
                                });
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                Thread.sleep(200);
                assertFalse(
                        published.isDone(),
                        "A short-lived Windows reader must not fail the collector");
                held.close();
                assertTrue(published.get(3, TimeUnit.SECONDS));
                assertTrue(Files.readString(status).contains("running"));
            } finally {
                held.close();
            }
        }
    }

    @Test
    void transientWindowsReaderOfPendingFileDoesNotTerminateStatusPublication() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var method = AliyunL2LiveMain.class.getDeclaredMethod("status", Path.class, Object.class);
        method.setAccessible(true);
        method.invoke(null, root, Map.of("phase", "previous"));
        Path pending = root.resolve("status.json.tmp");
        Files.writeString(pending, "pending");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var held =
                    Files.newByteChannel(
                            pending,
                            Set.of(
                                    StandardOpenOption.READ,
                                    ExtendedOpenOption.NOSHARE_WRITE,
                                    ExtendedOpenOption.NOSHARE_DELETE));
            try {
                var entered = new CountDownLatch(1);
                var published =
                        executor.submit(
                                () -> {
                                    entered.countDown();
                                    method.invoke(null, root, Map.of("phase", "running"));
                                    return true;
                                });
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                Thread.sleep(200);
                if (published.isDone()) published.get();
                assertFalse(published.isDone(), "A pending-file reader must not terminate capture");
                held.close();
                assertTrue(published.get(3, TimeUnit.SECONDS));
                assertTrue(Files.readString(root.resolve("status.json")).contains("running"));
            } finally {
                held.close();
            }
        }
    }

    @Test
    void persistentWindowsSharingFailureRemainsBoundedAndVisible() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var method = AliyunL2LiveMain.class.getDeclaredMethod("status", Path.class, Object.class);
        method.setAccessible(true);
        method.invoke(null, root, Map.of("phase", "previous"));
        Path pending = root.resolve("status.json.tmp");
        Files.writeString(pending, "pending");
        try (var held =
                        Files.newByteChannel(
                                pending,
                                Set.of(
                                        StandardOpenOption.READ,
                                        ExtendedOpenOption.NOSHARE_WRITE,
                                        ExtendedOpenOption.NOSHARE_DELETE));
                var executor = Executors.newSingleThreadExecutor()) {
            var published =
                    executor.submit(
                            () -> {
                                method.invoke(null, root, Map.of("phase", "running"));
                                return true;
                            });
            var failure =
                    assertThrows(
                            ExecutionException.class, () -> published.get(5, TimeUnit.SECONDS));
            assertInstanceOf(FileSystemException.class, failure.getCause().getCause());
            assertTrue(Files.readString(root.resolve("status.json")).contains("previous"));
        }
    }
}
