package com.zoutrankil.data.l2live.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AliyunL2LiveMainTest {
    @TempDir Path root;

    @Test void missingCredentialsDoesNotInstantiateCaptureStoreOrConnectVendor() throws Exception {
        assertEquals(2, AliyunL2LiveMain.execute(new String[]{"run", "--root", root.toString(), "--symbols", "563360"}, Map.of()));
        assertFalse(Files.exists(root.resolve("raw-journal.sqlite3")));
        assertEquals("SUBSCRIPTION_CREDENTIALS_MISSING", new ObjectMapper().readTree(root.resolve("status.json").toFile()).get("failureCode").asText());
    }

    @Test void pendingCredentialServiceCanBeStoppedBeforeAnyDatabaseOrBrokerActivity() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> AliyunL2LiveMain.execute(
                    new String[]{"run", "--root", root.toString(), "--symbols", "563360", "--wait-credentials", "true"}, Map.of()));
            Instant deadline = Instant.now().plusSeconds(3);
            while (!Files.exists(root.resolve("status.json")) && Instant.now().isBefore(deadline)) Thread.sleep(10);
            assertTrue(Files.isRegularFile(root.resolve("status.json")));
            assertEquals("awaiting_credentials", new ObjectMapper().readTree(root.resolve("status.json").toFile()).get("phase").asText());
            Files.writeString(root.resolve("stop.request"), "stop");
            assertEquals(0, result.get(3, TimeUnit.SECONDS));
            assertFalse(Files.exists(root.resolve("raw-journal.sqlite3")));
            assertEquals("stopped", new ObjectMapper().readTree(root.resolve("status.json").toFile()).get("phase").asText());
        }
    }
}
