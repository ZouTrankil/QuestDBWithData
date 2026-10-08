package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.L2IntradayBarFeatures;
import com.zoutrankil.data.l2.mapper.L2IntradayBarFeaturesMapper;
import com.zoutrankil.data.l2.domain.L2IntradayBarFeaturesRows;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Read-only, bounded bridge to the materialized D087 Parquet and per-symbol D085 manifest receipts. */
public final class L2IntradayBarFeaturesParquetSource {
    public static final int PAGE_ROWS = 200;
    public static final long MAX_SOURCE_BYTES = 64L * 1024 * 1024;
    public static final long MAX_OUTPUT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_INSPECTION_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 8192;
    private static final int MAX_LINE_BYTES = 8 * 1024 * 1024;
    private static final String PARSER_VERSION = "l2-intraday-bar-features-parquet-v1";
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
                throw new IllegalArgumentException("D087 Parquet inspection is incomplete or outside its bounds");
            }
        }
    }

    private final Path datasetRoot;
    private final Path helper;
    private final String pythonExecutable;
    private final L2IntradayBarFeaturesMapper mapper = new L2IntradayBarFeaturesMapper();

    public L2IntradayBarFeaturesParquetSource(Path datasetRoot, Path helper, String pythonExecutable) {
        this.datasetRoot = Objects.requireNonNull(datasetRoot).toAbsolutePath().normalize();
        this.helper = Objects.requireNonNull(helper).toAbsolutePath().normalize();
        this.pythonExecutable = Objects.requireNonNull(pythonExecutable);
        if (pythonExecutable.isBlank() || pythonExecutable.length() > 4096)
            throw new IllegalArgumentException("Configured D087 Python executable path required");
    }

    public Path datasetRoot() { return datasetRoot; }

    public String sourceRootIdentity() throws Exception {
        return FileEvidenceStore.sha256(datasetRoot.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
    }

    public Inspection inspect(LocalDate from, LocalDate to, List<String> symbols,
                              int maxRows, int maxFiles) throws Exception {
        validateWindow(from, to, symbols);
        Path output = Files.createTempFile("d087-inspection-", ".json");
        Path error = Files.createTempFile("d087-inspection-", ".stderr");
        try {
            runProcess(command(from, to, symbols, maxRows, maxFiles, "--inspect", null),
                    output, error, MAX_INSPECTION_BYTES, Duration.ofMinutes(5), () -> false);
            if (Files.size(output) > MAX_INSPECTION_BYTES)
                throw new IOException("D087 source inspection exceeded its output bound");
            JsonNode node = JSON.readTree(Files.readString(output, StandardCharsets.UTF_8));
            Inspection inspection = decodeInspection(node, "inspection");
            if (!inspection.from().equals(from) || !inspection.to().equals(to)
                    || inspection.selectedRows() > maxRows || inspection.files() > maxFiles
                    || !inspection.rootIdentity().equals(sourceRootIdentity()))
                throw new IOException("D087 source inspection differs from its frozen request budgets");
            return inspection;
        } finally {
            deleteTemporaryFiles(output, error);
        }
    }

    public SyncJobRunner.SourceCompletion stream(Inspection expected, List<String> symbols,
            int maxRows, int maxFiles, SyncJobRunner.PageConsumer<L2IntradayBarFeatures> consumer,
            BooleanSupplier cancelled, Duration timeout) throws Exception {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(cancelled);
        validateWindow(expected.from(), expected.to(), symbols);
        Path output = Files.createTempFile("d087-source-", ".jsonl");
        Path error = Files.createTempFile("d087-source-", ".stderr");
        try {
            runProcess(command(expected.from(), expected.to(), symbols, maxRows, maxFiles,
                            "--stream", expected.sourceFingerprint()),
                    output, error, MAX_OUTPUT_BYTES, timeout, cancelled);
            return consumeOutput(output, expected, symbols, consumer, cancelled);
        } finally {
            deleteTemporaryFiles(output, error);
        }
    }

    private SyncJobRunner.SourceCompletion consumeOutput(Path output, Inspection expected,
            List<String> symbols, SyncJobRunner.PageConsumer<L2IntradayBarFeatures> consumer,
            BooleanSupplier cancelled) throws Exception {
        int pages = 0;
        int rows = 0;
        boolean headerSeen = false;
        boolean completionSeen = false;
        JsonNode completion = null;
        Set<String> selected = Set.copyOf(symbols);
        Set<String> cursors = new HashSet<>();
        Set<com.zoutrankil.data.domain.L2IntradayBarFeaturesKey> keys = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new CancellationException("D087 source stream cancelled");
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES)
                    throw new IOException("D087 page exceeded its JSONL line bound");
                JsonNode node = JSON.readTree(line);
                String kind = requiredText(node, "kind");
                if (!headerSeen) {
                    if (!"header".equals(kind)) throw new IOException("D087 stream did not begin with inspection evidence");
                    requireSameInspection(expected, decodeInspection(node, "header"));
                    headerSeen = true;
                    continue;
                }
                if ("completion".equals(kind)) {
                    if (completionSeen || !node.path("complete").asBoolean(false)
                            || !node.path("completeForSelectedSymbols").asBoolean(false)
                            || !expected.sourceFingerprint().equals(requiredText(node, "sourceFingerprint")))
                        throw new IOException("D087 source completion evidence is invalid");
                    completionSeen = true;
                    completion = node;
                    if (reader.readLine() != null) throw new IOException("Unexpected content after D087 completion");
                    break;
                }
                if (!"page".equals(kind) || !expected.sourceFingerprint().equals(requiredText(node, "sourceFingerprint")))
                    throw new IOException("Unexpected D087 source stream record");
                String cursor = requiredText(node, "cursor");
                if (!cursors.add(cursor)) throw new IOException("D087 source repeated a page cursor");
                JsonNode evidence = node.get("responseEvidence");
                if (evidence == null || !evidence.isObject() || evidence.size() > 16)
                    throw new IOException("D087 page lineage evidence is missing or oversized");
                LocalDate day = LocalDate.parse(requiredText(evidence, "date"), BASIC_DATE);
                if (!expected.dates().contains(day)) throw new IOException("D087 page date was not inspected");
                int batchId = requiredInt(evidence, "batchId");
                int offset = requiredInt(evidence, "sourceRowOffset");
                requiredInt(evidence, "page");
                if (!isSha256(requiredText(evidence, "manifestReceiptFingerprint"))
                        || !cursor.equals(day.format(BASIC_DATE) + ":" + batchId + ":" + offset))
                    throw new IOException("D087 page receipt identity is malformed");
                validatePartEvidence(evidence.path("featureParts"));
                JsonNode rowNodes = node.get("rows");
                if (rowNodes == null || !rowNodes.isArray() || rowNodes.isEmpty() || rowNodes.size() > PAGE_ROWS)
                    throw new IOException("D087 page row count is outside its bound");
                var pageRows = new ArrayList<L2IntradayBarFeatures>(rowNodes.size());
                for (JsonNode rowNode : rowNodes) {
                    L2IntradayBarFeatures row = mapper.fromParquet(rowNode, day);
                    if (!row.tradeDate().equals(day) || !selected.isEmpty() && !selected.contains(row.symbol()))
                        throw new IOException("D087 row differs from its inspected date/symbol filter");
                    if (!keys.add(row.key())) throw new IOException("D087 source repeated a complete business key");
                    pageRows.add(row);
                }
                if (cursor.length() > 96 || pages > expected.pages()) throw new IOException("D087 page cursor/count is invalid");
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
            throw new IOException("D087 pages do not cover the inspected source interval");
        return new SyncJobRunner.SourceCompletion(pages, rows, true, JSON.writeValueAsString(completion));
    }

    private static void validatePartEvidence(JsonNode parts) throws IOException {
        if (parts == null || !parts.isArray() || parts.isEmpty() || parts.size() > 1000)
            throw new IOException("D087 source part evidence is missing or oversized");
        for (JsonNode part : parts) {
            if (!part.isObject() || !isSha256(requiredText(part, "sha256")))
                throw new IOException("D087 source part hash is invalid");
            String path = requiredText(part, "path");
            if (path.length() > 4096 || path.contains("..") || !path.startsWith("l2_intraday_bar_features/"))
                throw new IOException("D087 source part path is outside its approved dataset");
        }
    }

    /** Recovery keys identify a single deterministic source page, not the whole multi-page scan. */
    private static String pageFingerprint(String sourceFingerprint, String cursor,
            List<L2IntradayBarFeatures> rows) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("d087-page-v1".getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(sourceFingerprint.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(cursor.getBytes(StandardCharsets.UTF_8));
        for (L2IntradayBarFeatures row : rows) {
            byte[] canonical = L2IntradayBarFeaturesRows.canonicalBytes(row);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(canonical.length).array());
            digest.update(canonical);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private List<String> command(LocalDate from, LocalDate to, List<String> symbols,
            int maxRows, int maxFiles, String mode, String fingerprint) throws Exception {
        if (!Files.isRegularFile(helper) || Files.isSymbolicLink(helper))
            throw new IOException("D087 Parquet reader helper is unavailable");
        Path python = Path.of(pythonExecutable).toAbsolutePath().normalize();
        if (!Files.isRegularFile(python)) throw new IOException("Configured D087 Python executable is unavailable");
        var command = new ArrayList<String>();
        command.add(python.toString());
        command.add(helper.toString());
        command.add("--dataset-root"); command.add(datasetRoot.toString());
        command.add("--from-date"); command.add(BASIC_DATE.format(from));
        command.add("--to-date"); command.add(BASIC_DATE.format(to));
        command.add("--page-rows"); command.add(Integer.toString(PAGE_ROWS));
        command.add("--max-files"); command.add(Integer.toString(maxFiles));
        command.add("--max-rows"); command.add(Integer.toString(maxRows));
        command.add("--max-bytes"); command.add(Long.toString(MAX_SOURCE_BYTES));
        command.add("--max-output-bytes"); command.add(Long.toString(MAX_OUTPUT_BYTES));
        symbols.forEach(symbol -> { command.add("--symbol"); command.add(symbol); });
        command.add(mode);
        if (fingerprint != null) { command.add("--expected-fingerprint"); command.add(fingerprint); }
        return List.copyOf(command);
    }

    private static void runProcess(List<String> command, Path output, Path error, long maxOutputBytes,
            Duration timeout, BooleanSupplier cancelled) throws Exception {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Positive D087 source timeout required");
        var builder = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("QUESTDB_")
                || key.startsWith("APP_QUESTDB_") || key.contains("TUSHARE"));
        Process process = builder.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            try (var stdin = process.getOutputStream()) { stdin.close(); }
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new CancellationException("D087 Parquet reader cancelled");
                if (System.nanoTime() >= deadline)
                    throw new java.util.concurrent.TimeoutException("D087 Parquet reader exceeded its bounded deadline");
                if (Files.size(output) > maxOutputBytes)
                    throw new IOException("D087 Parquet reader exceeded its output-byte budget");
            }
            if (process.exitValue() != 0) {
                String detail = Files.size(error) > MAX_ERROR_BYTES ? "source error output exceeded bound"
                        : Files.readString(error, StandardCharsets.UTF_8).lines().findFirst().orElse("source process failed");
                String errorType = detail.contains(":") ? detail.substring(0, detail.indexOf(':')) : detail;
                throw new IOException("D087 Parquet reader failed: " + errorType);
            }
            if (Files.size(output) > maxOutputBytes) throw new IOException("D087 output exceeded its byte budget");
        } finally {
            if (process.isAlive()) terminate(process);
        }
    }

    private static void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException stopWait) {
            interrupted = true;
            process.destroyForcibly();
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException interruptedAgain) { interrupted = true; }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void deleteTemporaryFiles(Path... files) {
        for (Path file : files) {
            try { Files.deleteIfExists(file); }
            catch (IOException locked) { file.toFile().deleteOnExit(); }
        }
    }

    private static Inspection decodeInspection(JsonNode node, String expectedKind) throws IOException {
        if (node == null || !node.isObject() || !expectedKind.equals(requiredText(node, "kind")))
            throw new IOException("D087 source inspection evidence is malformed");
        JsonNode datesNode = node.get("dates");
        if (datesNode == null || !datesNode.isArray() || datesNode.size() > 31)
            throw new IOException("D087 source dates are outside their finite bound");
        var dates = new ArrayList<LocalDate>();
        for (JsonNode item : datesNode) {
            if (!item.isTextual()) throw new IOException("D087 date evidence is not text");
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
        if (!expected.equals(actual)) throw new IOException("D087 source changed after planning");
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IOException("Missing D087 evidence field: " + field);
        return value.textValue();
    }

    private static int requiredInt(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw new IOException("Invalid D087 evidence count: " + field);
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IOException("Invalid D087 evidence count: " + field);
        return value.longValue();
    }

    private static boolean isSha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static void validateWindow(LocalDate from, LocalDate to, List<String> symbols) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 31)
            throw new IllegalArgumentException("D087 date range must be nonempty and at most 31 calendar days");
        if (symbols == null || symbols.isEmpty() || symbols.size() > 100 || symbols.stream().distinct().count() != symbols.size()
                || symbols.stream().anyMatch(value -> value == null || !value.matches("[0-9]{6}\\.(SH|SZ|BJ)")))
            throw new IllegalArgumentException("D087 requires 1..100 unique canonical stock symbols");
    }
}
