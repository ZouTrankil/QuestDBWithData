package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only verifier for the complete daily JSONL emitted by l2DailyFeatureBatch. */
public final class L2NativeDailyFeaturesJsonlSource {
    public static final String READER_VERSION = "java-native-batch-jsonl-v1";
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ROWS = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Day(
            LocalDate date,
            Path manifestPath,
            Path aggregatePath,
            String manifestSha256,
            String aggregateSha256,
            String sourceFingerprint,
            String schemaFingerprint,
            String rootIdentity,
            int sourceRows,
            List<L2DailyFeatures> rows) {
        public Day {
            rows = List.copyOf(rows);
        }

        public void assertUnchanged() throws Exception {
            if (!manifestSha256.equals(hashFile(manifestPath))
                    || !aggregateSha256.equals(hashFile(aggregatePath)))
                throw new IOException("Native L2 JSONL source changed during formal ingestion");
        }
    }

    private L2NativeDailyFeaturesJsonlSource() {}

    public static Day inspect(Path outputRoot, LocalDate day) throws Exception {
        Path root = outputRoot.toRealPath();
        Path dayRoot = root.resolve(day.format(DateTimeFormatter.BASIC_ISO_DATE));
        Path manifestPath = dayRoot.resolve("manifest.json");
        Path aggregatePath = dayRoot.resolve("l2_daily_features.jsonl");
        requireRegularFileInside(root, manifestPath);
        requireRegularFileInside(root, aggregatePath);
        if (Files.size(manifestPath) > MAX_MANIFEST_BYTES
                || Files.size(aggregatePath) > L2DailyFeaturesParquetSource.MAX_OUTPUT_BYTES)
            throw new IOException("Native L2 daily source exceeds its bounded file size");

        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        String manifestSha256 = sha256(manifestBytes);
        JsonNode manifest = JSON.readTree(manifestBytes);
        validateManifest(manifest, day);
        String aggregateSha256 = hashFile(aggregatePath);
        if (!aggregateSha256.equals(requiredText(manifest, "aggregateSha256")))
            throw new IOException(
                    "Native L2 aggregate SHA-256 differs from its completion manifest");

        Map<String, String> successfulSymbols = new HashMap<>();
        Set<String> outcomeSymbols = new HashSet<>();
        int emptySymbols = 0;
        JsonNode symbolOutcomes = manifest.get("symbols");
        if (symbolOutcomes == null
                || !symbolOutcomes.isArray()
                || symbolOutcomes.size() != manifest.path("discoveredCanonicalSymbols").intValue())
            throw new IOException(
                    "Native L2 manifest symbol outcomes differ from its discovered universe");
        for (JsonNode outcome : symbolOutcomes) {
            String symbol = requiredText(outcome, "symbol");
            String status = requiredText(outcome, "status");
            if (!outcomeSymbols.add(symbol))
                throw new IOException("Duplicate native L2 symbol outcome");
            if ("SUCCESS".equals(status)) {
                String resultSha = requiredText(outcome, "resultSha256");
                if (!isSha256(resultSha)
                        || successfulSymbols.putIfAbsent(symbol, resultSha) != null)
                    throw new IOException(
                            "Native L2 manifest has an invalid or duplicate successful symbol");
            } else if ("EMPTY".equals(status)) {
                emptySymbols++;
            } else {
                throw new IOException(
                        "Native L2 manifest contains a nonterminal or failed symbol outcome");
            }
        }
        if (successfulSymbols.size() != manifest.path("counts").path("success").intValue()
                || emptySymbols != manifest.path("counts").path("empty").intValue())
            throw new IOException("Native L2 manifest status counts do not match symbol outcomes");

        var mapper = new L2DailyFeaturesMapper();
        var rows = new ArrayList<L2DailyFeatures>(successfulSymbols.size());
        Set<String> rowSymbols = new HashSet<>();
        try (BufferedReader reader =
                Files.newBufferedReader(aggregatePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES)
                    throw new IOException("Native L2 aggregate contains an empty or oversized row");
                JsonNode row = JSON.readTree(line);
                if (row == null
                        || !row.isObject()
                        || row.size() != L2DailyFeatureField.values().length)
                    throw new IOException(
                            "Native L2 aggregate row differs from the frozen 110-field schema");
                JsonNode ts = row.get("ts");
                JsonNode symbolNode = row.get("symbol");
                if (ts == null
                        || !ts.isTextual()
                        || !day.format(DateTimeFormatter.BASIC_ISO_DATE).equals(ts.textValue())
                        || symbolNode == null
                        || !symbolNode.isTextual())
                    throw new IOException(
                            "Native L2 aggregate row has an invalid date or symbol key");
                String symbol = symbolNode.textValue();
                String expectedRowHash = successfulSymbols.get(symbol);
                if (expectedRowHash == null
                        || !rowSymbols.add(symbol)
                        || !expectedRowHash.equals(
                                sha256((line + "\n").getBytes(StandardCharsets.UTF_8))))
                    throw new IOException(
                            "Native L2 aggregate row differs from its successful manifest receipt");
                var features = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
                for (var field : L2DailyFeatureField.values()) {
                    JsonNode value = row.get(field.fieldName());
                    if (value == null)
                        throw new IOException(
                                "Native L2 aggregate is missing field " + field.fieldName());
                    if (!field.identity()) features.put(field, field.decode(value));
                }
                rows.add(new L2DailyFeatures(day, symbol, features));
                if (rows.size() > MAX_ROWS)
                    throw new IOException("Native L2 date exceeds the formal row bound");
            }
        }
        if (rows.isEmpty()
                || rows.size() != successfulSymbols.size()
                || !rowSymbols.equals(successfulSymbols.keySet()))
            throw new IOException(
                    "Native L2 aggregate does not contain every successful symbol exactly once");

        String secondManifestSha = hashFile(manifestPath);
        String secondAggregateSha = hashFile(aggregatePath);
        if (!manifestSha256.equals(secondManifestSha)
                || !aggregateSha256.equals(secondAggregateSha))
            throw new IOException("Native L2 source changed during formal source inspection");

        String rootIdentity = sha256(root.toString().getBytes(StandardCharsets.UTF_8));
        String schemaFingerprint = schemaFingerprint();
        String sourceFingerprint =
                sha256(
                        (rootIdentity + "\n" + manifestSha256 + "\n" + aggregateSha256)
                                .getBytes(StandardCharsets.UTF_8));
        return new Day(
                day,
                manifestPath,
                aggregatePath,
                manifestSha256,
                aggregateSha256,
                sourceFingerprint,
                schemaFingerprint,
                rootIdentity,
                manifest.path("discoveredCanonicalSymbols").intValue(),
                rows);
    }

    private static void validateManifest(JsonNode manifest, LocalDate day) throws IOException {
        if (manifest == null
                || !manifest.isObject()
                || !day.format(DateTimeFormatter.BASIC_ISO_DATE)
                        .equals(requiredText(manifest, "date"))
                || !"COMPLETE".equals(requiredText(manifest, "status"))
                || !manifest.path("published").asBoolean(false)
                || !isSha256(requiredText(manifest, "aggregateSha256"))
                || manifest.path("counts").path("failed").asInt(-1) != 0
                || manifest.path("counts").path("success").asInt(0) < 1
                || manifest.path("discoveredCanonicalSymbols").asInt(-1) < 1)
            throw new IOException("Native L2 day is not COMPLETE and published");
        JsonNode missing = manifest.path("selection").path("requestedNotPresent");
        if (!missing.isArray() || missing.size() != 0)
            throw new IOException(
                    "Native L2 manifest has requested symbols missing from the archive");
        int discovered = manifest.path("discoveredCanonicalSymbols").asInt(-1);
        int statusTotal =
                manifest.path("counts").path("success").asInt(-1)
                        + manifest.path("counts").path("empty").asInt(-1)
                        + manifest.path("counts").path("failed").asInt(-1);
        if (discovered != statusTotal)
            throw new IOException(
                    "Native L2 discovered universe differs from terminal symbol counts");
    }

    private static void requireRegularFileInside(Path root, Path file) throws IOException {
        if (!file.normalize().startsWith(root)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native L2 source file is missing or outside its output root");
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IOException("Native L2 manifest field is missing: " + field);
        return value.textValue();
    }

    private static String schemaFingerprint() throws Exception {
        var value = new StringBuilder();
        for (var field : L2DailyFeatureField.values())
            value.append(field.fieldName())
                    .append(':')
                    .append(field.storageType())
                    .append(':')
                    .append(field.nullable())
                    .append('\n');
        return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String hashFile(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0)
                if (count > 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }
}
