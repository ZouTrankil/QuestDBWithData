package com.zoutrankil.data.l2.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class L2NativeDailyFeaturesJsonlSourceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);
    @TempDir Path root;

    @Test
    void rowVersionsMustMatchManifestEvenWhenHashesMatch() throws Exception {
        String row;
        try (var in = getClass().getResourceAsStream("/l2-native-row.json")) {
            row = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        writeDay(row, "dfcf_csv_v1.2", "v1.3");
        assertEquals(1, L2NativeDailyFeaturesJsonlSource.inspect(root, DAY).sourceRows());
        writeDay(row, "another_source_v1", "v1.3");
        assertThrows(IOException.class, () -> L2NativeDailyFeaturesJsonlSource.inspect(root, DAY));
        writeDay(row, "dfcf_csv_v1.2", "v2");
        assertThrows(IOException.class, () -> L2NativeDailyFeaturesJsonlSource.inspect(root, DAY));
    }

    @Test
    void rejectsOutOfRangeGmmRatiosEvenWhenHashesMatch() throws Exception {
        String row;
        try (var in = getClass().getResourceAsStream("/l2-native-row.json")) {
            row = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(row);
        value.put("gmm_main_force_ratio", 10.000280805906515);
        value.put("gmm_hft_ratio", -9.000280805906515);
        writeDay(JSON.writeValueAsString(value), "dfcf_csv_v1.2", "v1.3");
        IOException error = assertThrows(IOException.class, () -> L2NativeDailyFeaturesJsonlSource.inspect(root, DAY));
        assertTrue(error.getMessage().contains("gmm_main_force_ratio"));
    }

    private void writeDay(String row, String parserVersion, String featureVersion) throws Exception {
        Path day = root.resolve("20260924");
        Files.createDirectories(day);
        String line = row + "\n";
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(day.resolve("l2_daily_features.jsonl"), line, StandardCharsets.UTF_8);
        var manifest = Map.of(
                "date", "20260924", "status", "COMPLETE", "published", true,
                "parserVersion", parserVersion, "featureVersion", featureVersion,
                "aggregateSha256", hash, "discoveredCanonicalSymbols", 1,
                "counts", Map.of("success", 1, "empty", 0, "failed", 0),
                "selection", Map.of("requestedNotPresent", java.util.List.of()),
                "symbols", java.util.List.of(Map.of("symbol", "159915.SZ", "status", "SUCCESS", "resultSha256", hash)));
        Files.writeString(day.resolve("manifest.json"), JSON.writeValueAsString(manifest), StandardCharsets.UTF_8);
    }
}
