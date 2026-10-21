package com.zoutrankil.market.btc.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class BinanceFrozenRestDayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.parse("2026-10-20");
    private static final Instant OBSERVED = Instant.parse("2026-10-21T01:00:00Z");
    private static final long FIRST = Instant.parse("2026-10-20T00:00:00Z").toEpochMilli();
    @TempDir Path root;

    private ArrayNode candle(long first, int minutes, String volume, String quoteVolume) {
        return JSON.valueToTree(
                List.of(
                        first,
                        "100",
                        "102",
                        "99",
                        "101",
                        volume,
                        first + minutes * 60000L - 1,
                        quoteVolume,
                        10,
                        "0.5",
                        "1",
                        "0"));
    }

    private ArrayNode hours() {
        var rows = JSON.createArrayNode();
        for (int h = 0; h < 24; h++)
            rows.add(candle(FIRST + h * 3600000L, 60, "" + (h + 1), "" + (2 * (h + 1))));
        return rows;
    }

    private String url(String instrument, String interval) {
        return (instrument.equals("SPOT")
                        ? "https://data-api.binance.vision/api/v3/klines"
                        : "https://fapi.binance.com/fapi/v1/klines")
                + "?symbol=BTCUSDT&interval="
                + interval
                + "&startTime="
                + FIRST
                + "&endTime="
                + (FIRST + 86400000L - 1)
                + "&limit=100";
    }

    private ObjectNode proof(String instrument, String volume) throws Exception {
        Path nativePath = root.resolve("raw/binance/native.response");
        Files.createDirectories(nativePath.getParent());
        byte[] nativeBytes =
                JSON.writeValueAsBytes(
                        JSON.createArrayNode().add(candle(FIRST, 1440, volume, "600")));
        Files.write(nativePath, nativeBytes);
        return JSON.valueToTree(
                Map.of(
                        "sourceFormat", BinanceFrozenRestDay.FORMAT,
                        "requestedFrom", DAY.toString(),
                        "requestedThrough", DAY.toString(),
                        "sourceUrl", url(instrument, "1h"),
                        "nativeDaily",
                                Map.of(
                                        "raw", nativePath.toString(),
                                        "sha256", VerifiedMarketDataStore.sha(nativeBytes),
                                        "observedAt", OBSERVED.toString(),
                                        "sourceUrl", url(instrument, "1d"))));
    }

    private List<Map<String, Object>> read(ArrayNode source, ObjectNode proof, String instrument)
            throws Exception {
        byte[] bytes = JSON.writeValueAsBytes(source);
        return BinanceFrozenRestDay.read(
                root,
                bytes,
                VerifiedMarketDataStore.sha(bytes),
                OBSERVED,
                "BTCUSDT",
                instrument,
                DAY,
                DAY,
                JSON.writeValueAsString(proof));
    }

    @Test
    void acceptsMillisecondSpotAndPerpetualHoursOnlyAfterNativeDailyMatches() throws Exception {
        for (String instrument : List.of("SPOT", "USDT_PERPETUAL")) {
            var source = hours();
            var rows = read(source, proof(instrument, "300"), instrument);
            assertEquals(24, rows.size());
            assertEquals(13, rows.getFirst().size());
            assertEquals("2026-10-20T23:00:00Z", rows.getLast().get("ts"));
            assertEquals("BINANCE", rows.getFirst().get("exchange"));
            assertEquals(instrument, rows.getFirst().get("instrument"));
            assertEquals(24.0, rows.getLast().get("volume"));
            assertEquals(48.0, rows.getLast().get("quote_volume"));
            assertEquals(
                    VerifiedMarketDataStore.sha(JSON.writeValueAsBytes(source)),
                    rows.getFirst().get("source_sha256"));
        }
    }

    @Test
    void rejectsMissingAndDuplicateHours() throws Exception {
        var proof = proof("SPOT", "300");
        var missing = hours();
        missing.remove(6);
        assertThrows(IllegalArgumentException.class, () -> read(missing, proof, "SPOT"));
        var duplicate = hours();
        duplicate.set(6, duplicate.get(5).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> read(duplicate, proof, "SPOT"));
    }

    @Test
    void rejectsArchiveMicrosecondsInRestEvenForPost2025Spot() throws Exception {
        var source = hours();
        source.set(0, candle(FIRST * 1000, 60, "1", "2"));
        var proof = proof("SPOT", "300");
        assertThrows(IllegalArgumentException.class, () -> read(source, proof, "SPOT"));
    }

    @Test
    void rejectsAlteredNativeBytesAndIndependentlyWrongNativeVolume() throws Exception {
        var proof = proof("SPOT", "300");
        Files.writeString(root.resolve("raw/binance/native.response"), "[]");
        assertThrows(IllegalArgumentException.class, () -> read(hours(), proof, "SPOT"));
        var wrongVolume = proof("SPOT", "301");
        assertThrows(IllegalArgumentException.class, () -> read(hours(), wrongVolume, "SPOT"));
    }

    @Test
    void rejectsMixedProductEndpointAndWrongRequestFrequency() throws Exception {
        var mixed = proof("SPOT", "300");
        assertThrows(IllegalArgumentException.class, () -> read(hours(), mixed, "USDT_PERPETUAL"));
        var wrong = proof("SPOT", "300");
        wrong.put("sourceUrl", url("SPOT", "1d"));
        assertThrows(IllegalArgumentException.class, () -> read(hours(), wrong, "SPOT"));
    }

    @Test
    void rejectsUnknownOriginAndNativePathOutsideRetainedSourceRoot() throws Exception {
        var external = proof("SPOT", "300");
        external.put(
                "sourceUrl", url("SPOT", "1h").replace("data-api.binance.vision", "example.org"));
        assertThrows(IllegalArgumentException.class, () -> read(hours(), external, "SPOT"));
        var wrongPath = proof("SPOT", "300");
        Path outside = root.resolve("other.response");
        Files.copy(root.resolve("raw/binance/native.response"), outside);
        ((ObjectNode) wrongPath.get("nativeDaily")).put("raw", outside.toString());
        assertThrows(IllegalArgumentException.class, () -> read(hours(), wrongPath, "SPOT"));
    }

    @Test
    void requiresExplicitRestFormatAndRetainsLegacyArchiveProofRoute() throws Exception {
        assertFalse(BinanceFrozenRestDay.supports("{\"officialArchiveSha256\":\"legacy\"}"));
        assertTrue(BinanceFrozenRestDay.supports(JSON.writeValueAsString(proof("SPOT", "300"))));
        var unknown = proof("SPOT", "300");
        unknown.put("sourceFormat", "unknown");
        assertThrows(IllegalArgumentException.class, () -> read(hours(), unknown, "SPOT"));
    }
}
