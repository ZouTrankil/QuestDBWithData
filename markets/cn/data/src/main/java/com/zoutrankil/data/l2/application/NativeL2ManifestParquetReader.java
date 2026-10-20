package com.zoutrankil.data.l2.application;

import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.BASIC;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.JSON;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.addFile;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.batch;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.digest;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.emit;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.partition;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.parts;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.relative;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.root;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.sha;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.text;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.verifyFiles;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.l2.mapper.L2DatasetManifestMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** D085's 15-field immutable manifest protocol, decoded and validated entirely in Java. */
final class NativeL2ManifestParquetReader {
    private static final String VERSION = "l2-dataset-manifest-parquet-v1";
    private static final Map<String, String> SCHEMA = schema();
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    record Page(
            LocalDate day,
            int batch,
            String path,
            String hash,
            long bytes,
            int number,
            int sourceRows,
            List<ObjectNode> rows) {}

    record Scan(ObjectNode inspection, List<Page> pages) {}

    private NativeL2ManifestParquetReader() {}

    static Scan scan(
            Path requestedRoot,
            LocalDate from,
            LocalDate to,
            List<String> symbols,
            int maxRows,
            int maxFiles,
            NativeL2DailyParquetReader.Control control)
            throws Exception {
        control.run();
        if (maxRows < 1 || maxFiles < 1)
            throw new IllegalArgumentException("Positive L2 source budgets required");
        Path root = root(requestedRoot);
        Path product = root.resolve("l2_dataset_manifest");
        if (!Files.isDirectory(product, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(product))
            throw new IOException("L2 manifest product directory is missing or linked");
        var files = new TreeMap<String, ObjectNode>();
        var discovered = new LinkedHashMap<LocalDate, List<Path>>();
        var pages = new ArrayList<Page>();
        var keys = new TreeSet<String>();
        Set<String> selected = Set.copyOf(symbols);
        int[] sourceRows = {0}, selectedRows = {0};
        long[] decodedBytes = {0};
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            control.run();
            Path directory = partition(root, "l2_dataset_manifest", day);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            var parts = parts(root, directory, true);
            discovered.put(day, parts);
            for (Path file : parts) {
                addFile(root, file, files, maxFiles, MAX_BYTES, control);
                String partName = file.getFileName().toString();
                int id = Integer.parseInt(partName.substring(5, partName.length() - 8));
                String relative = relative(root, file);
                var evidence = files.get(relative);
                var rawPage = new ArrayList<ObjectNode>();
                int[] pageNumber = {0};
                LocalDate date = day;
                NativeL2ParquetReader.read(
                        file,
                        SCHEMA,
                        maxRows,
                        control,
                        row -> {
                            if (++sourceRows[0] > maxRows)
                                throw new IOException(
                                        "L2 manifest exceeds its frozen source-row budget");
                            if (!text(row, "trade_date").equals(BASIC.format(date))
                                    || batch(row) != id)
                                throw new IOException(
                                        "L2 manifest date/batch differs from its partition");
                            String symbol = text(row, "symbol");
                            if (!symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)")
                                    || !keys.add(date + ":" + symbol + ":" + id))
                                throw new IOException(
                                        "L2 manifest has an invalid or duplicate business key");
                            for (String name :
                                    List.of(
                                            "raw_row_counts",
                                            "output_paths",
                                            "cost_config",
                                            "horizons_min",
                                            "errors")) {
                                var node = row.get(name);
                                if (node != null
                                        && !node.isNull()
                                        && JSON.readTree(node.textValue()) == null)
                                    throw new IOException(
                                            "L2 manifest JSON field is empty: " + name);
                            }
                            new L2DatasetManifestMapper().fromParquet(row);
                            decodedBytes[0] =
                                    Math.addExact(
                                            decodedBytes[0], JSON.writeValueAsBytes(row).length);
                            if (decodedBytes[0] > MAX_BYTES)
                                throw new IOException(
                                        "L2 manifest decoded rows exceed their byte budget");
                            rawPage.add(row);
                            if (rawPage.size() == 200) {
                                addPage(
                                        pages,
                                        rawPage,
                                        selected,
                                        selectedRows,
                                        date,
                                        id,
                                        relative,
                                        evidence,
                                        pageNumber);
                                rawPage.clear();
                            }
                        });
                if (!rawPage.isEmpty())
                    addPage(
                            pages,
                            rawPage,
                            selected,
                            selectedRows,
                            date,
                            id,
                            relative,
                            evidence,
                            pageNumber);
            }
        }
        var inventory = List.copyOf(files.values());
        String schemaHash =
                digest(
                        SCHEMA.entrySet().stream()
                                .map(entry -> entry.getKey() + ":" + entry.getValue())
                                .toList());
        var fingerprintParts = new ArrayList<String>();
        fingerprintParts.add(VERSION);
        fingerprintParts.add(sha(root.toString().getBytes(StandardCharsets.UTF_8)));
        fingerprintParts.add(BASIC.format(from));
        fingerprintParts.add(BASIC.format(to));
        fingerprintParts.add(schemaHash);
        fingerprintParts.add(
                JSON.writeValueAsString(symbols.stream().distinct().sorted().toList()));
        for (var file : inventory)
            fingerprintParts.add(
                    text(file, "path")
                            + "\0"
                            + file.path("bytes").longValue()
                            + "\0"
                            + text(file, "sha256"));
        var inspection =
                JSON.createObjectNode()
                        .put("kind", "inspection")
                        .put("from", BASIC.format(from))
                        .put("to", BASIC.format(to))
                        .put("parserVersion", VERSION)
                        .put("schemaFingerprint", schemaHash)
                        .put("sourceFingerprint", digest(fingerprintParts))
                        .put("sourceRows", sourceRows[0])
                        .put("selectedRows", selectedRows[0])
                        .put("files", files.size())
                        .put("pages", pages.size())
                        .put(
                                "sourceBytes",
                                inventory.stream()
                                        .mapToLong(file -> file.path("bytes").longValue())
                                        .sum());
        var dates = inspection.putArray("dates");
        discovered.keySet().forEach(day -> dates.add(BASIC.format(day)));
        inspection
                .putObject("quality")
                .put("schemaStable", true)
                .put("businessKeysUnique", true)
                .put("partIdsContiguous", true)
                .put("dateAndBatchMatchPartition", true)
                .put("jsonFieldsValid", true)
                .put("completeForDiscoveredFiles", true)
                .put("externalCompletionManifest", false);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Path directory = partition(root, "l2_dataset_manifest", day);
            var current =
                    Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                            ? parts(root, directory, true)
                            : List.<Path>of();
            if (!current.equals(discovered.getOrDefault(day, List.of())))
                throw new IOException("L2 manifest inventory changed during inspection");
        }
        verifyFiles(root, inventory, control);
        return new Scan(inspection, List.copyOf(pages));
    }

    private static void addPage(
            List<Page> pages,
            List<ObjectNode> raw,
            Set<String> selected,
            int[] count,
            LocalDate day,
            int batch,
            String path,
            ObjectNode file,
            int[] number) {
        var returned =
                raw.stream()
                        .filter(
                                row ->
                                        selected.isEmpty()
                                                || selected.contains(
                                                        row.path("symbol").textValue()))
                        .toList();
        if (!returned.isEmpty()) {
            pages.add(
                    new Page(
                            day,
                            batch,
                            path,
                            file.path("sha256").textValue(),
                            file.path("bytes").longValue(),
                            number[0]++,
                            raw.size(),
                            List.copyOf(returned)));
            count[0] = Math.addExact(count[0], returned.size());
        }
    }

    static void writeStream(Scan scan, Path output, NativeL2DailyParquetReader.Control control)
            throws Exception {
        var inspection = scan.inspection();
        String source = text(inspection, "sourceFingerprint"),
                schema = text(inspection, "schemaFingerprint");
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            long[] bytes = {0};
            emit(writer, inspection.deepCopy().put("kind", "header"), bytes, MAX_BYTES, control);
            for (var value : scan.pages()) {
                String fingerprint =
                        digest(
                                List.of(
                                        VERSION,
                                        schema,
                                        source,
                                        value.path(),
                                        value.hash(),
                                        String.valueOf(value.number())));
                var page =
                        JSON.createObjectNode()
                                .put("kind", "page")
                                .put("sourceFingerprint", fingerprint)
                                .put(
                                        "cursor",
                                        BASIC.format(value.day())
                                                + "/"
                                                + value.path()
                                                + "#"
                                                + value.number());
                page.putObject("responseEvidence")
                        .put("date", BASIC.format(value.day()))
                        .put("sourcePath", value.path())
                        .put("sourceSha256", value.hash())
                        .put("sourceBytes", value.bytes())
                        .put("batchId", value.batch())
                        .put("page", value.number())
                        .put("sourceRowsInPage", value.sourceRows())
                        .put("returnedRows", value.rows().size())
                        .put("parserVersion", VERSION)
                        .put("schemaFingerprint", schema)
                        .put("sourceFingerprint", source)
                        .put("completeForDiscoveredFiles", true)
                        .put("reader", "java-parquet-1.17.1");
                var rows = page.putArray("rows");
                value.rows().forEach(rows::add);
                emit(writer, page, bytes, MAX_BYTES, control);
            }
            emit(
                    writer,
                    JSON.createObjectNode()
                            .put("kind", "completion")
                            .put("sourceFingerprint", source)
                            .put("sourceRows", inspection.path("sourceRows").intValue())
                            .put("returnedRows", inspection.path("selectedRows").intValue())
                            .put("files", inspection.path("files").intValue())
                            .put("pages", scan.pages().size())
                            .put("complete", true)
                            .put("completeForDiscoveredFiles", true)
                            .put("externalCompletionManifest", false),
                    bytes,
                    MAX_BYTES,
                    control);
        }
    }

    private static Map<String, String> schema() {
        var result = new LinkedHashMap<String, String>();
        for (String name :
                List.of(
                        "trade_date",
                        "symbol",
                        "market",
                        "board",
                        "source_root",
                        "output_root",
                        "feature_version",
                        "daily_feature_ok",
                        "t0_ok",
                        "raw_row_counts",
                        "output_paths",
                        "cost_config",
                        "horizons_min",
                        "errors",
                        "batch_id"))
            result.put(
                    name,
                    name.equals("batch_id")
                            ? "int64"
                            : Set.of("daily_feature_ok", "t0_ok").contains(name)
                                    ? "bool"
                                    : "string");
        return Collections.unmodifiableMap(result);
    }
}
