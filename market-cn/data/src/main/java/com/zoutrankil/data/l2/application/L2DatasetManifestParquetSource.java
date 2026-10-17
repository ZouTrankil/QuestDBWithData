package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.L2DatasetManifest;
import com.zoutrankil.data.l2.mapper.L2DatasetManifestMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded, read-only native Java decoder for the materialized D085 manifest. */
public final class L2DatasetManifestParquetSource {
    public static final int PAGE_ROWS = 200;
    public static final long MAX_SOURCE_BYTES = 256L * 1024 * 1024;
    public static final long MAX_OUTPUT_BYTES = 256L * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024 * 1024;
    private static final String PARSER_VERSION = "l2-dataset-manifest-parquet-v1";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Inspection(LocalDate from, LocalDate to, List<LocalDate> dates,
                             int sourceRows, int selectedRows, int files, int pages,
                             long sourceBytes, String sourceFingerprint,
                             String schemaFingerprint, String parserVersion,
                             boolean completeForDiscoveredFiles, boolean externalCompletionManifest) {
        public Inspection {
            dates = List.copyOf(dates);
            if (sourceRows < 0 || selectedRows < 0 || selectedRows > sourceRows || files < 0 || pages < 0
                    || sourceBytes < 0 || !isSha256(sourceFingerprint) || !isSha256(schemaFingerprint)
                    || !PARSER_VERSION.equals(parserVersion) || !completeForDiscoveredFiles
                    || externalCompletionManifest) {
                throw new IllegalArgumentException("Incomplete or invalid D085 Parquet inspection");
            }
        }
    }

    private final Path datasetRoot;
    private final L2DatasetManifestMapper mapper = new L2DatasetManifestMapper();

    public L2DatasetManifestParquetSource(Path datasetRoot) {
        this.datasetRoot = Objects.requireNonNull(datasetRoot).toAbsolutePath().normalize();
    }

    /** Compatibility constructor; native reads do not launch the supplied legacy helper. */
    @Deprecated
    public L2DatasetManifestParquetSource(Path datasetRoot, Path ignoredHelper, String ignoredInterpreter) {
        this(datasetRoot);
    }
    public Path datasetRoot() { return datasetRoot; }
    public String sourceRootIdentity() throws Exception {
        return FileEvidenceStore.sha256(datasetRoot.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
    }

    public Inspection inspect(LocalDate from, LocalDate to, List<String> symbols,
                              int maxRows, int maxFiles) throws Exception {
        validateWindow(from, to, symbols);
        var control = NativeL2DailyParquetReader.Control.of(() -> false, Duration.ofMinutes(5));
        return decodeInspection(NativeL2ManifestParquetReader.scan(datasetRoot, from, to, symbols, maxRows, maxFiles, control).inspection(), "inspection");
    }
    public SyncJobRunner.SourceCompletion stream(Inspection expected, List<String> symbols,
            int maxRows, int maxFiles, SyncJobRunner.PageConsumer<L2DatasetManifest> consumer,
            BooleanSupplier cancelled, Duration timeout) throws Exception {
        Objects.requireNonNull(expected); Objects.requireNonNull(consumer); Objects.requireNonNull(cancelled);
        validateWindow(expected.from(), expected.to(), symbols);
        var control = NativeL2DailyParquetReader.Control.of(cancelled, timeout);
        Path output = Files.createTempFile("d085-native-source-", ".jsonl");
        try {
            var scan = NativeL2ManifestParquetReader.scan(datasetRoot, expected.from(), expected.to(), symbols, maxRows, maxFiles, control);
            requireSameInspection(expected, decodeInspection(scan.inspection(), "inspection"));
            NativeL2ManifestParquetReader.writeStream(scan, output, control);
            // Complete source stability proof before the first downstream consumer can write.
            requireSameInspection(expected, decodeInspection(NativeL2ManifestParquetReader.scan(datasetRoot, expected.from(), expected.to(), symbols, maxRows, maxFiles, control).inspection(), "inspection"));
            return consumeOutput(output, expected, symbols, consumer, () -> NativeL2SourceSupport.check(control));
        } catch (NativeL2SourceSupport.Deadline failure) {
            throw (Exception) failure.getCause();
        } finally {
            deleteTemporaryFiles(output);
        }
    }
    private static void deleteTemporaryFiles(Path... files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException locked) {
                // A native reader can briefly retain a file handle on Windows.
                // Preserve the primary cancellation or source error, then let the JVM retry at exit.
                file.toFile().deleteOnExit();
            }
        }
    }

    private SyncJobRunner.SourceCompletion consumeOutput(Path output, Inspection expected,
            List<String> symbols, SyncJobRunner.PageConsumer<L2DatasetManifest> consumer,
            BooleanSupplier cancelled) throws Exception {
        int pages = 0;
        int rows = 0;
        boolean headerSeen = false;
        boolean completionSeen = false;
        JsonNode completion = null;
        Set<String> selected = Set.copyOf(symbols);
        try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new CancellationException("D085 source stream cancelled");
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES)
                    throw new IOException("D085 source page exceeded its bounded JSONL line size");
                JsonNode node = JSON.readTree(line);
                String kind = requiredText(node, "kind");
                if (!headerSeen) {
                    if (!kind.equals("header")) throw new IOException("D085 source stream did not begin with evidence");
                    Inspection header = decodeInspection(node, "header");
                    requireSameInspection(expected, header);
                    headerSeen = true;
                    continue;
                }
                if (kind.equals("completion")) {
                    if (completionSeen || node.path("complete").asBoolean(false) != true
                            || !expected.sourceFingerprint().equals(requiredText(node, "sourceFingerprint")))
                        throw new IOException("D085 source completion evidence is invalid");
                    completionSeen = true;
                    completion = node;
                    if (reader.readLine() != null) throw new IOException("Unexpected content after D085 completion evidence");
                    break;
                }
                if (!kind.equals("page")) throw new IOException("Unexpected D085 source stream record");
                String fingerprint = requiredText(node, "sourceFingerprint");
                if (!isSha256(fingerprint)) throw new IOException("Invalid D085 page fingerprint");
                String cursor = requiredText(node, "cursor");
                JsonNode evidence = node.get("responseEvidence");
                if (evidence == null || !evidence.isObject() || evidence.size() > 32)
                    throw new IOException("D085 page evidence is missing or oversized");
                JsonNode sourceDay = evidence.get("date");
                if (sourceDay == null || !sourceDay.isTextual()) throw new IOException("D085 page date evidence is missing");
                LocalDate day = LocalDate.parse(sourceDay.textValue(), BASIC_DATE);
                if (day.isBefore(expected.from()) || day.isAfter(expected.to()) || !expected.dates().contains(day))
                    throw new IOException("D085 page is outside the inspected trade-date range");
                JsonNode sourceHash = evidence.get("sourceSha256");
                if (sourceHash == null || !sourceHash.isTextual() || !isSha256(sourceHash.textValue()))
                    throw new IOException("D085 page file hash evidence is invalid");
                JsonNode rowNodes = node.get("rows");
                if (rowNodes == null || !rowNodes.isArray() || rowNodes.isEmpty() || rowNodes.size() > PAGE_ROWS)
                    throw new IOException("D085 source page row count is outside its bound");
                var pageRows = new ArrayList<L2DatasetManifest>(rowNodes.size());
                for (JsonNode rowNode : rowNodes) {
                    L2DatasetManifest row = mapper.fromParquet(rowNode);
                    if (!row.tradeDate().equals(day) || !selected.isEmpty() && !selected.contains(row.symbol()))
                        throw new IOException("D085 Parquet row differs from the inspected date/symbol filter");
                    pageRows.add(row);
                }
                consumer.accept(new SyncJobRunner.Page<>(pageRows, fingerprint,
                        JSON.writeValueAsString(evidence), cursor));
                pages++;
                rows = Math.addExact(rows, pageRows.size());
            }
        }
        if (!headerSeen || !completionSeen || completion == null
                || pages != expected.pages() || rows != expected.selectedRows()
                || completion.path("files").asInt(-1) != expected.files()
                || completion.path("sourceRows").asInt(-1) != expected.sourceRows()
                || completion.path("returnedRows").asInt(-1) != rows
                || completion.path("pages").asInt(-1) != pages) {
            throw new IOException("D085 emitted pages do not cover the inspected source interval");
        }
        return new SyncJobRunner.SourceCompletion(pages, rows, true,
                JSON.writeValueAsString(completion));
    }

    private static Inspection decodeInspection(JsonNode node, String expectedKind) throws IOException {
        if (node == null || !node.isObject() || !expectedKind.equals(requiredText(node, "kind")))
            throw new IOException("D085 source inspection evidence is malformed");
        var dates = new ArrayList<LocalDate>();
        JsonNode datesNode = node.get("dates");
        if (datesNode == null || !datesNode.isArray() || datesNode.size() > 366)
            throw new IOException("D085 source dates are missing or outside the finite window");
        for (JsonNode item : datesNode) {
            if (!item.isTextual()) throw new IOException("D085 source date is not text");
            dates.add(LocalDate.parse(item.textValue(), BASIC_DATE));
        }
        JsonNode quality = node.get("quality");
        if (quality == null || !quality.isObject()) throw new IOException("D085 source quality evidence is missing");
        return new Inspection(
                LocalDate.parse(requiredText(node, "from"), BASIC_DATE),
                LocalDate.parse(requiredText(node, "to"), BASIC_DATE),
                dates,
                requiredInt(node, "sourceRows"), requiredInt(node, "selectedRows"),
                requiredInt(node, "files"), requiredInt(node, "pages"),
                requiredLong(node, "sourceBytes"), requiredText(node, "sourceFingerprint"),
                requiredText(node, "schemaFingerprint"), requiredText(node, "parserVersion"),
                quality.path("completeForDiscoveredFiles").asBoolean(false),
                quality.path("externalCompletionManifest").asBoolean(false));
    }

    private static void requireSameInspection(Inspection expected, Inspection actual) throws IOException {
        if (!expected.equals(actual)) throw new IOException("D085 source changed between plan and run");
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IOException("Missing D085 evidence field: " + field);
        return value.textValue();
    }

    private static int requiredInt(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt() || !value.isIntegralNumber() || value.intValue() < 0)
            throw new IOException("Invalid D085 evidence count: " + field);
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || !value.isIntegralNumber() || value.longValue() < 0)
            throw new IOException("Invalid D085 evidence count: " + field);
        return value.longValue();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void validateWindow(LocalDate from, LocalDate to, List<String> symbols) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 31)
            throw new IllegalArgumentException("D085 date range must be finite and at most 31 calendar days");
        if (symbols == null || symbols.size() > 1000 || symbols.stream().distinct().count() != symbols.size()
                || symbols.stream().anyMatch(value -> value == null || !value.matches("[0-9]{6}\\.(SH|SZ|BJ)")))
            throw new IllegalArgumentException("D085 symbol filter must be a bounded unique canonical list");
    }
}
