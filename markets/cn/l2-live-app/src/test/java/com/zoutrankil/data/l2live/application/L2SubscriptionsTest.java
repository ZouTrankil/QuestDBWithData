package com.zoutrankil.data.l2live.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

class L2SubscriptionsTest {
    @TempDir Path temporary;

    private Path config(String json) throws IOException {
        Path path = temporary.resolve("subscriptions.json");
        Files.writeString(path, json);
        return path;
    }

    @Test
    void allowListIsOnlyAFilterAndDoesNotCreateSubscriptions() throws Exception {
        assertEquals(
                List.of(),
                L2Subscriptions.load(
                        config(
                                "{\"allow_symbols\":[\"563360.SH\",\"159242.SZ\"],\"force_symbols\":[]}"),
                        LocalDate.of(2026, 10, 14)));
    }

    @Test
    void explicitForceUsesExactOffsetExpiryBlocksAndHighestPriority() throws Exception {
        Path path =
                config(
                        """
                {"allow_symbols":["563360.SH","159242.SZ","588010.SH","589330.SH"],
                 "block_symbols":["588010.SH"],"force_symbols":[
                  {"symbol":"563360.SH","priority":10,"expires_at":"2026-10-14T10:00:00+08:00"},
                  {"symbol":"563360.SZ","priority":300,"expires_at":"2026-10-14T03:00:00+00:00"},
                  {"symbol":"159242.SZ","priority":100},
                  {"symbol":"588010.SH","priority":500},
                  {"symbol":"589330.SH","priority":700,"expires_at":"2026-10-04T19:05:00+08:00"},
                  {"symbol":"512770.SH","priority":900}]}
                """);
        assertEquals(
                List.of("563360.SH", "159242.SZ"),
                L2Subscriptions.load(path, Instant.parse("2026-10-14T02:00:00Z")));
        assertEquals(
                List.of("159242.SZ"),
                L2Subscriptions.load(path, Instant.parse("2026-10-14T03:00:00Z")));
    }

    @Test
    void explicitCapacitySelectsSixteenAndEachSymbolHasAllThreeChannels() throws Exception {
        var force = new ArrayList<String>();
        for (int i = 1; i <= 20; i++)
            force.add("{\"symbol\":\"" + (510000 + i) + "\",\"priority\":" + i + "}");
        var selected =
                L2Subscriptions.load(
                        config("{\"force_symbols\":[" + String.join(",", force) + "]}"),
                        Instant.parse("2026-10-14T02:00:00Z"));
        assertEquals(16, selected.size());
        assertEquals("510020.SH", selected.getFirst());
        assertEquals("510005.SH", selected.getLast());
        assertEquals(48, L2Subscriptions.topics(selected).size());
    }

    @Test
    void invalidOrUnsupportedPolicyFailsClosedRatherThanCreatingBroaderSubscriptions()
            throws Exception {
        for (String json :
                List.of(
                        "{",
                        "[]",
                        "{\"force_symbols\":{}}",
                        "{\"force_symbols\":[\"830001.BJ\"]}",
                        "{\"force_symbols\":[\"123001.SZ\"]}",
                        "{\"force_symbols\":[{\"symbol\":\"563360\",\"expires_at\":\"2026-10-14T10:00:00\"}]}",
                        "{\"force_symbols\":[{\"symbol\":\"563360\",\"priority\":\"high\"}]}",
                        "{\"allow_symbols\":true}")) {
            Path path = config(json);
            var error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    L2Subscriptions.load(
                                            path, Instant.parse("2026-10-14T02:00:00Z")));
            assertNull(error.getCause());
        }
        assertThrows(
                IOException.class,
                () ->
                        L2Subscriptions.load(
                                temporary.resolve("missing.json"), LocalDate.of(2026, 10, 14)));
    }
}
