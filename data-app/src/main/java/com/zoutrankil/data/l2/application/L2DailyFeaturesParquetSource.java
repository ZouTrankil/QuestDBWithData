package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.L2DailyFeatures;
import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.l2.domain.L2DailyFeaturesRows;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Read-only native Java reader for D086 Parquet and per-symbol D085 manifest receipts. */
public final class L2DailyFeaturesParquetSource {
    public static final int PAGE_ROWS = 200;
    public static final long MAX_SOURCE_BYTES = 256L * 1024 * 1024;
    public static final long MAX_OUTPUT_BYTES = 256L * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024 * 1024;
    private static final String PARSER_VERSION = "l2-daily-features-parquet-v1";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Inspection(LocalDate from, LocalDate to, List<LocalDate> dates,
                             int sourceRows, int selectedRows, int files, int pages,
                             long sourceBytes, String sourceFingerprint,
                             String schemaFingerprint, String parserVersion,
                             String rootIdentity, boolean completeForSelectedSymbols) {
        public Inspection {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            dates = List.copyOf(dates);
            if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 31
                    || sourceRows < 0 || selectedRows < 1 || files < 1 || pages < 1 || sourceBytes < 0
                    || !isSha256(sourceFingerprint) || !isSha256(schemaFingerprint) || !isSha256(rootIdentity)
                    || !PARSER_VERSION.equals(parserVersion) || !completeForSelectedSymbols
                    || dates.isEmpty() || dates.stream().distinct().count() != dates.size()
                    || dates.stream().anyMatch(day -> day.isBefore(from) || day.isAfter(to))) {
                throw new IllegalArgumentException("D086 Parquet inspection is incomplete or outside its bounds");
            }
        }
    }

    private final Path datasetRoot;
    private final L2DailyFeaturesMapper mapper = new L2DailyFeaturesMapper();

    public L2DailyFeaturesParquetSource(Path datasetRoot) {
        this.datasetRoot = Objects.requireNonNull(datasetRoot).toAbsolutePath().normalize();
    }

    /** Source compatibility only; the retired interpreter/helper arguments are never used. */
    @Deprecated
    public L2DailyFeaturesParquetSource(Path datasetRoot, Path ignoredHelper, String ignoredInterpreter) {
        this(datasetRoot);
    }

    public Path datasetRoot() { return datasetRoot; }

    public String sourceRootIdentity() throws Exception {
        return FileEvidenceStore.sha256(datasetRoot.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
    }

    public Inspection inspect(LocalDate from, LocalDate to, List<String> symbols,
                              int maxRows, int maxFiles) throws Exception {
        return inspect(from, to, symbols, maxRows, maxFiles, () -> false, Duration.ofMinutes(5));
    }

    public Inspection inspect(LocalDate from, LocalDate to, List<String> symbols,
            int maxRows, int maxFiles, BooleanSupplier cancelled, Duration timeout) throws Exception {
        validateWindow(from, to, symbols);
        return NativeL2DailyParquetReader.scan(datasetRoot, from, to, symbols, maxRows, maxFiles,
                NativeL2DailyParquetReader.Control.of(cancelled, timeout)).inspection();
    }

    public SyncJobRunner.SourceCompletion stream(Inspection expected, List<String> symbols,
            int maxRows, int maxFiles, SyncJobRunner.PageConsumer<L2DailyFeatures> consumer,
            BooleanSupplier cancelled, Duration timeout) throws Exception {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(cancelled);
        validateWindow(expected.from(), expected.to(), symbols);
        Path output = Files.createTempFile("d086-source-", ".jsonl");
        try {
            var control = NativeL2DailyParquetReader.Control.of(cancelled, timeout);
            var scan = NativeL2DailyParquetReader.scan(datasetRoot, expected.from(), expected.to(), symbols,
                    maxRows, maxFiles, control);
            requireSameInspection(expected, scan.inspection());
            NativeL2DailyParquetReader.writeStream(scan, output, control);
            // Certify the same source again immediately before delivering any writable page.
            requireSameInspection(expected, NativeL2DailyParquetReader.scan(datasetRoot, expected.from(), expected.to(),
                    symbols, maxRows, maxFiles, control).inspection());
            return consumeOutput(output, expected, symbols, consumer, () -> {
                try { control.run(); return false; }
                catch (RuntimeException failure) { throw failure; }
                catch (Exception failure) { throw new NativeReadDeadline(failure); }
            });
        } catch (NativeReadDeadline failure) {
            throw (Exception) failure.getCause();
        } finally {
            deleteTemporaryFiles(output);
        }
    }

    private static final class NativeReadDeadline extends RuntimeException {
        NativeReadDeadline(Exception cause) { super(cause); }
    }

    private SyncJobRunner.SourceCompletion consumeOutput(Path output, Inspection expected,
            List<String> symbols, SyncJobRunner.PageConsumer<L2DailyFeatures> consumer,
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
                    throw new CancellationException("D086 source stream cancelled");
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES)
                    throw new IOException("D086 page exceeded its JSONL line bound");
                JsonNode node = JSON.readTree(line);
                String kind = requiredText(node, "kind");
                if (!headerSeen) {
                    if (!"header".equals(kind)) throw new IOException("D086 stream did not begin with inspection evidence");
                    requireSameInspection(expected, decodeInspection(node, "header"));
                    headerSeen = true;
                    continue;
                }
                if ("completion".equals(kind)) {
                    if (completionSeen || !node.path("complete").asBoolean(false)
                            || !node.path("completeForSelectedSymbols").asBoolean(false)
                            || !expected.sourceFingerprint().equals(requiredText(node, "sourceFingerprint")))
                        throw new IOException("D086 source completion evidence is invalid");
                    completionSeen = true;
                    completion = node;
                    if (reader.readLine() != null) throw new IOException("Unexpected content after D086 completion");
                    break;
                }
                if (!"page".equals(kind) || !expected.sourceFingerprint().equals(requiredText(node, "sourceFingerprint")))
                    throw new IOException("Unexpected D086 source stream record");
                String cursor = requiredText(node, "cursor");
                JsonNode evidence = node.get("responseEvidence");
                if (evidence == null || !evidence.isObject() || evidence.size() > 16)
                    throw new IOException("D086 page lineage evidence is missing or oversized");
                LocalDate day = LocalDate.parse(requiredText(evidence, "date"), BASIC_DATE);
                if (!expected.dates().contains(day)) throw new IOException("D086 page date was not inspected");
                validatePartEvidence(evidence.path("featureParts"));
                JsonNode rowNodes = node.get("rows");
                if (rowNodes == null || !rowNodes.isArray() || rowNodes.isEmpty() || rowNodes.size() > PAGE_ROWS)
                    throw new IOException("D086 page row count is outside its bound");
                var pageRows = new ArrayList<L2DailyFeatures>(rowNodes.size());
                for (JsonNode rowNode : rowNodes) {
                    L2DailyFeatures row = mapper.fromParquet(rowNode, day);
                    if (!row.tradeDate().equals(day) || !selected.isEmpty() && !selected.contains(row.symbol()))
                        throw new IOException("D086 row differs from its inspected date/symbol filter");
                    pageRows.add(row);
                }
                if (cursor.length() > 96 || pages > expected.pages()) throw new IOException("D086 page cursor/count is invalid");
                consumer.accept(new SyncJobRunner.Page<>(pageRows, pageFingerprint(expected.sourceFingerprint(), cursor, pageRows),
                        JSON.writeValueAsString(evidence), cursor));
                pages++;
                rows = Math.addExact(rows, pageRows.size());
            }
        }
        if (!headerSeen || !completionSeen || completion == null || pages != expected.pages()
                || rows != expected.selectedRows() || completion.path("files").asInt(-1) != expected.files()
                || completion.path("sourceRows").asInt(-1) != expected.sourceRows()
                || completion.path("returnedRows").asInt(-1) != rows || completion.path("pages").asInt(-1) != pages)
            throw new IOException("D086 pages do not cover the inspected source interval");
        return new SyncJobRunner.SourceCompletion(pages, rows, true, JSON.writeValueAsString(completion));
    }

    private static void validatePartEvidence(JsonNode parts) throws IOException {
        if (parts == null || !parts.isArray() || parts.isEmpty() || parts.size() > 1000)
            throw new IOException("D086 source part evidence is missing or oversized");
        for (JsonNode part : parts) {
            if (!part.isObject() || !isSha256(requiredText(part, "sha256")))
                throw new IOException("D086 source part hash is invalid");
            String path = requiredText(part, "path");
            if (path.length() > 4096 || path.contains("..") || !path.startsWith("l2_daily_features/"))
                throw new IOException("D086 source part path is outside its approved dataset");
        }
    }

    /** Recovery keys identify a single deterministic source page, not the whole multi-page scan. */
    private static String pageFingerprint(String sourceFingerprint, String cursor,
            List<L2DailyFeatures> rows) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("d086-page-v1".getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(sourceFingerprint.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(cursor.getBytes(StandardCharsets.UTF_8));
        for (L2DailyFeatures row : rows) {
            byte[] canonical = L2DailyFeaturesRows.canonicalBytes(row);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(canonical.length).array());
            digest.update(canonical);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteTemporaryFiles(Path... files) {
        for (Path file : files) {
            try { Files.deleteIfExists(file); }
            catch (IOException locked) { file.toFile().deleteOnExit(); }
        }
    }

    private static Inspection decodeInspection(JsonNode node, String expectedKind) throws IOException {
        if (node == null || !node.isObject() || !expectedKind.equals(requiredText(node, "kind")))
            throw new IOException("D086 source inspection evidence is malformed");
        JsonNode datesNode = node.get("dates");
        if (datesNode == null || !datesNode.isArray() || datesNode.size() > 31)
            throw new IOException("D086 source dates are outside their finite bound");
        var dates = new ArrayList<LocalDate>();
        for (JsonNode item : datesNode) {
            if (!item.isTextual()) throw new IOException("D086 date evidence is not text");
            dates.add(LocalDate.parse(item.textValue(), BASIC_DATE));
        }
        return new Inspection(LocalDate.parse(requiredText(node, "from"), BASIC_DATE),
                LocalDate.parse(requiredText(node, "to"), BASIC_DATE), dates,
                requiredInt(node, "sourceRows"), requiredInt(node, "selectedRows"),
                requiredInt(node, "files"), requiredInt(node, "pages"), requiredLong(node, "sourceBytes"),
                requiredText(node, "sourceFingerprint"), requiredText(node, "schemaFingerprint"),
                requiredText(node, "parserVersion"), requiredText(node, "rootIdentity"),
                node.path("completeForSelectedSymbols").asBoolean(false));
    }

    private static void requireSameInspection(Inspection expected, Inspection actual) throws IOException {
        if (!expected.equals(actual)) throw new IOException("D086 source changed after planning");
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IOException("Missing D086 evidence field: " + field);
        return value.textValue();
    }

    private static int requiredInt(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw new IOException("Invalid D086 evidence count: " + field);
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IOException("Invalid D086 evidence count: " + field);
        return value.longValue();
    }

    private static boolean isSha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static void validateWindow(LocalDate from, LocalDate to, List<String> symbols) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 31)
            throw new IllegalArgumentException("D086 date range must be nonempty and at most 31 calendar days");
        if (symbols == null || symbols.size() > 1000 || symbols.stream().distinct().count() != symbols.size()
                || symbols.stream().anyMatch(value -> value == null || !value.matches("[0-9]{6}\\.(SH|SZ|BJ)")))
            throw new IllegalArgumentException("D086 symbol filter must be bounded and canonical");
    }
}
