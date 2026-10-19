package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Manifest-certified D086 scan. No interpreter, subprocess or mutable database is involved. */
final class NativeL2DailyParquetReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Map<String, String> SCHEMA = dailySchema();
    private static final long MAX_DECODED_PART_ROWS = 1_000_000;
    private static final String VERSION = "l2-daily-features-parquet-v1";

    record Day(LocalDate date, List<ObjectNode> rows, List<Path> manifests, List<Path> features) {}
    record Scan(L2DailyFeaturesParquetSource.Inspection inspection, List<Day> days, List<ObjectNode> files) {}
    record Control(BooleanSupplier cancelled, long deadline) implements NativeL2ParquetReader.Check {
        static Control of(BooleanSupplier cancelled, Duration timeout) {
            if (timeout == null || timeout.isZero() || timeout.isNegative())
                throw new IllegalArgumentException("Positive L2 source timeout required");
            long nanos = timeout.toNanos();
            return new Control(cancelled, Math.addExact(System.nanoTime(), nanos));
        }
        @Override public void run() throws Exception {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new CancellationException("L2 native Parquet read cancelled");
            if (System.nanoTime() >= deadline) throw new TimeoutException("L2 native Parquet read exceeded its deadline");
        }
    }

    static Scan scan(Path requestedRoot, LocalDate from, LocalDate to, List<String> symbols,
            int maxRows, int maxFiles, Control control) throws Exception {
        control.run();
        if (maxRows < 1 || maxFiles < 1) throw new IllegalArgumentException("Positive L2 source budgets required");
        if (!Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(requestedRoot))
            throw new IOException("L2 dataset root is missing or linked");
        Path root = requestedRoot.toRealPath();
        var files = new TreeMap<String, ObjectNode>();
        var days = new ArrayList<Day>();
        var dayParts = new LinkedHashMap<LocalDate, List<Path>>();
        int sourceRows = 0, selectedRows = 0, pages = 0;
        long outputBytes = 0;
        Set<String> selected = Set.copyOf(symbols);
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            control.run();
            Path manifestDirectory = partition(root, "l2_dataset_manifest", date);
            if (!Files.exists(manifestDirectory, LinkOption.NOFOLLOW_LINKS)) continue;
            var manifests = parts(root, manifestDirectory);
            dayParts.put(date, manifests);
            Path featureDirectory = partition(root, "l2_daily_features", date);
            var featureCandidates = parts(root, featureDirectory);
            var seen = new TreeSet<String>();
            var expectedByBatch = new TreeMap<Integer, Set<String>>();
            for (Path manifest : manifests) {
                addFile(root, manifest, files, maxFiles, control);
                var manifestSchema = NativeL2ParquetReader.schema(manifest);
                for (var required : Map.of("trade_date", "string", "symbol", "string", "daily_feature_ok", "bool",
                        "batch_id", "int64", "output_paths", "string").entrySet())
                    if (!required.getValue().equals(manifestSchema.get(required.getKey())))
                        throw new IOException("L2 manifest lacks the typed per-symbol receipt fields");
                LocalDate day = date;
                sourceRows = Math.addExact(sourceRows, Math.toIntExact(NativeL2ParquetReader.read(manifest, null,
                        MAX_DECODED_PART_ROWS, control, row -> {
                    String symbol = text(row, "symbol");
                    if (!selected.isEmpty() && !selected.contains(symbol)) return;
                    if (!symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)") || !seen.add(symbol))
                        throw new IOException("L2 manifest has invalid or duplicate selected symbols");
                    if (!text(row, "trade_date").replace("-", "").equals(BASIC.format(day)))
                        throw new IOException("L2 manifest trade date differs from its partition");
                    if (!row.path("daily_feature_ok").isBoolean() || !row.path("daily_feature_ok").booleanValue())
                        throw new IOException("L2 manifest does not certify the selected daily feature");
                    var batch = row.get("batch_id");
                    if (batch == null || !batch.isIntegralNumber() || !batch.canConvertToInt()
                            || batch.intValue() < 0 || batch.intValue() > 99999)
                        throw new IOException("L2 manifest batch identifier is invalid");
                    int batchId = batch.intValue();
                    Path expected = featureDirectory.resolve("part-%05d.parquet".formatted(batchId));
                    var outputs = JSON.readTree(text(row, "output_paths"));
                    if (outputs == null || !outputs.isObject() || !outputs.path("daily_features").isTextual())
                        throw new IOException("L2 manifest has no daily-feature output receipt");
                    Path receipt = Path.of(outputs.path("daily_features").textValue()).toAbsolutePath().normalize();
                    // 比较真实文件身份，系统目录别名不能让有效的批次凭证失效。
                    if (Files.isSymbolicLink(receipt) || !Files.isRegularFile(receipt,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            || !receipt.toRealPath().equals(expected.toRealPath()) || !featureCandidates.contains(expected))
                        throw new IOException("L2 output receipt differs from the approved date/batch source");
                    expectedByBatch.computeIfAbsent(batchId, ignored -> new TreeSet<>()).add(symbol);
                })));
            }
            if (seen.isEmpty() || !selected.isEmpty() && !seen.equals(selected))
                throw new IOException("L2 manifest does not cover all selected symbols");
            var rows = new ArrayList<ObjectNode>();
            var featureFiles = new ArrayList<Path>();
            for (var batch : expectedByBatch.entrySet()) {
                Path file = featureDirectory.resolve("part-%05d.parquet".formatted(batch.getKey()));
                addFile(root, file, files, maxFiles, control);
                featureFiles.add(file);
                var actualSymbols = new TreeSet<String>();
                LocalDate day = date;
                int remaining = maxRows - selectedRows;
                NativeL2ParquetReader.read(file, SCHEMA, MAX_DECODED_PART_ROWS, control, row -> {
                    if (!batch.getValue().contains(text(row, "symbol"))) return;
                    if (!actualSymbols.add(text(row, "symbol")))
                        throw new IOException("L2 feature part repeats a selected business key");
                    new L2DailyFeaturesMapper().fromParquet(row, day);
                    if (rows.size() >= remaining) throw new IOException("L2 selected rows exceed the frozen budget");
                    rows.add(row);
                });
                if (!actualSymbols.equals(batch.getValue()))
                    throw new IOException("L2 manifest and daily-feature rows disagree for their batch");
            }
            rows.sort(Comparator.comparing((ObjectNode row) -> row.path("ts").textValue())
                    .thenComparing(row -> row.path("symbol").textValue()));
            for (var row : rows) {
                outputBytes = Math.addExact(outputBytes, JSON.writeValueAsBytes(row).length);
                if (outputBytes > L2DailyFeaturesParquetSource.MAX_OUTPUT_BYTES)
                    throw new IOException("L2 decoded source exceeds its output-byte budget");
            }
            selectedRows = Math.addExact(selectedRows, rows.size());
            pages = Math.addExact(pages, (rows.size() + L2DailyFeaturesParquetSource.PAGE_ROWS - 1)
                    / L2DailyFeaturesParquetSource.PAGE_ROWS);
            days.add(new Day(date, List.copyOf(rows), manifests, List.copyOf(featureFiles)));
        }
        if (days.isEmpty()) throw new IOException("L2 requested interval contains no certified manifest partitions");
        var manifest = List.copyOf(files.values());
        var inspection = new L2DailyFeaturesParquetSource.Inspection(from, to, days.stream().map(Day::date).toList(),
                sourceRows, selectedRows, files.size(), pages, manifest.stream().mapToLong(row -> row.path("bytes").longValue()).sum(),
                sha(JSON.writeValueAsBytes(manifest)), schemaFingerprint(), VERSION,
                sha(root.toString().getBytes(StandardCharsets.UTF_8)), true);
        // Hashes and discovered manifest partitions must still describe the same immutable scan.
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Path directory = partition(root, "l2_dataset_manifest", date);
            var actual = Files.exists(directory, LinkOption.NOFOLLOW_LINKS) ? parts(root, directory) : List.<Path>of();
            if (!actual.equals(dayParts.getOrDefault(date, List.of())))
                throw new IOException("L2 manifest partitions changed during native inspection");
        }
        for (var file : manifest) {
            control.run();
            Path path = root.resolve(file.path("path").textValue());
            if (Files.size(path) != file.path("bytes").longValue()
                    || !file.path("sha256").textValue().equals(fileHash(path, control)))
                throw new IOException("L2 Parquet source changed during native inspection");
        }
        return new Scan(inspection, List.copyOf(days), manifest);
    }

    static void writeStream(Scan scan, Path output, Control control) throws Exception {
        var inspection = scan.inspection();
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            long[] bytes = {0};
            var header = JSON.createObjectNode().put("kind", "header").put("from", BASIC.format(inspection.from()))
                    .put("to", BASIC.format(inspection.to())).put("sourceRows", inspection.sourceRows())
                    .put("selectedRows", inspection.selectedRows()).put("files", inspection.files()).put("pages", inspection.pages())
                    .put("sourceBytes", inspection.sourceBytes()).put("sourceFingerprint", inspection.sourceFingerprint())
                    .put("schemaFingerprint", inspection.schemaFingerprint()).put("parserVersion", VERSION)
                    .put("rootIdentity", inspection.rootIdentity()).put("completeForSelectedSymbols", true);
            var dates = header.putArray("dates"); inspection.dates().forEach(date -> dates.add(BASIC.format(date)));
            emit(writer, header, bytes, control);
            int pageNumber = 0, rowOffset = 0;
            for (var day : scan.days()) {
                for (int offset = 0; offset < day.rows().size(); offset += L2DailyFeaturesParquetSource.PAGE_ROWS) {
                    int end = Math.min(day.rows().size(), offset + L2DailyFeaturesParquetSource.PAGE_ROWS);
                    var page = JSON.createObjectNode().put("kind", "page").put("sourceFingerprint", inspection.sourceFingerprint())
                            .put("cursor", BASIC.format(day.date()) + ":" + offset);
                    var evidence = page.putObject("responseEvidence").put("date", BASIC.format(day.date()))
                            .put("rowOffset", rowOffset).put("manifestPartCount", day.manifests().size())
                            .put("page", ++pageNumber).put("sourceRowOffset", offset).put("reader", "java-parquet-1.17.1");
                    var parts = evidence.putArray("featureParts");
                    for (var file : scan.files()) if (file.path("path").textValue().startsWith("l2_daily_features/")
                            && day.features().stream().anyMatch(path -> path.endsWith(Path.of(file.path("path").textValue()))))
                        parts.addObject().put("path", file.path("path").textValue()).put("sha256", file.path("sha256").textValue());
                    var rows = page.putArray("rows"); day.rows().subList(offset, end).forEach(rows::add);
                    emit(writer, page, bytes, control);
                    rowOffset += end - offset;
                }
            }
            emit(writer, JSON.createObjectNode().put("kind", "completion").put("sourceFingerprint", inspection.sourceFingerprint())
                    .put("files", inspection.files()).put("sourceRows", inspection.sourceRows()).put("returnedRows", inspection.selectedRows())
                    .put("pages", inspection.pages()).put("complete", true).put("completeForSelectedSymbols", true), bytes, control);
        }
    }

    private static void emit(BufferedWriter writer, ObjectNode node, long[] bytes, Control control) throws Exception {
        control.run();
        String line = JSON.writeValueAsString(node);
        bytes[0] = Math.addExact(bytes[0], line.getBytes(StandardCharsets.UTF_8).length + 1L);
        if (bytes[0] > L2DailyFeaturesParquetSource.MAX_OUTPUT_BYTES) throw new IOException("L2 stream exceeds its output-byte budget");
        writer.write(line); writer.newLine();
    }

    private static Path partition(Path root, String dataset, LocalDate day) {
        return root.resolve(dataset).resolve("year=%04d".formatted(day.getYear()))
                .resolve("month=%02d".formatted(day.getMonthValue())).resolve("trade_date=" + BASIC.format(day));
    }

    private static List<Path> parts(Path root, Path directory) throws IOException {
        for (Path ancestor = directory; ancestor != null && ancestor.startsWith(root); ancestor = ancestor.getParent())
            if (Files.isSymbolicLink(ancestor)) throw new IOException("L2 source partition contains a linked path");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("L2 source partition is missing");
        try (var entries = Files.list(directory)) {
            var result = entries.filter(path -> path.getFileName().toString().startsWith("part-")
                            && path.getFileName().toString().endsWith(".parquet")).sorted().toList();
            if (result.isEmpty() || result.stream().anyMatch(path -> !path.getFileName().toString().matches("part-[0-9]{5}\\.parquet")
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)))
                throw new IOException("L2 source has missing or unsafe batch Parquet parts");
            return result;
        }
    }

    private static void addFile(Path root, Path file, Map<String, ObjectNode> files, int maxFiles, Control control) throws Exception {
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (files.containsKey(relative)) return;
        long size = Files.size(file);
        long bytes = files.values().stream().mapToLong(row -> row.path("bytes").longValue()).sum();
        if (files.size() >= maxFiles || size > L2DailyFeaturesParquetSource.MAX_SOURCE_BYTES - bytes)
            throw new IOException("L2 source files or bytes exceed the frozen budget");
        files.put(relative, JSON.createObjectNode().put("bytes", size).put("path", relative).put("sha256", fileHash(file, control)));
    }

    private static String fileHash(Path file, Control control) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) { control.run(); digest.update(buffer, 0, count); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String text(ObjectNode row, String field) throws IOException {
        var value = row.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new IOException("L2 receipt text is missing: " + field);
        return value.textValue();
    }

    private static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static String schemaFingerprint() throws Exception {
        return sha(JSON.writeValueAsBytes(SCHEMA.entrySet().stream().map(entry -> List.of(entry.getKey(), entry.getValue())).toList()));
    }
    private static Map<String, String> dailySchema() {
        var result = new LinkedHashMap<String, String>();
        for (var field : L2DailyFeatureField.values()) result.put(field.fieldName(), switch (field.storageType()) {
            case TIMESTAMP -> "timestamp[ns]"; case SYMBOL, STRING -> "string"; case BOOLEAN -> "bool";
            case LONG -> "int64"; case DOUBLE -> "double"; default -> throw new IllegalStateException("Unsupported L2 daily field");
        });
        return java.util.Collections.unmodifiableMap(result);
    }
}
