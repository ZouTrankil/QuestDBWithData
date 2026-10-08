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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded, read-only bridge to the source project's installed PyArrow runtime. */
public final class L2DatasetManifestParquetSource {
    public static final int PAGE_ROWS = 200;
    public static final long MAX_SOURCE_BYTES = 256L * 1024 * 1024;
    public static final long MAX_OUTPUT_BYTES = 256L * 1024 * 1024;
    private static final int MAX_INSPECTION_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 8192;
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
    private final Path helper;
    private final String pythonExecutable;
    private final L2DatasetManifestMapper mapper = new L2DatasetManifestMapper();

    public L2DatasetManifestParquetSource(Path datasetRoot, Path helper, String pythonExecutable) {
        this.datasetRoot = Objects.requireNonNull(datasetRoot).toAbsolutePath().normalize();
        this.helper = Objects.requireNonNull(helper).toAbsolutePath().normalize();
        this.pythonExecutable = Objects.requireNonNull(pythonExecutable);
        if (pythonExecutable.isBlank() || pythonExecutable.length() > 4096)
            throw new IllegalArgumentException("Configured Python executable path required");
    }

    public Path datasetRoot() { return datasetRoot; }
    public String sourceRootIdentity() throws Exception {
        return FileEvidenceStore.sha256(datasetRoot.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
    }

    public Inspection inspect(LocalDate from, LocalDate to, List<String> symbols,
                              int maxRows, int maxFiles) throws Exception {
        validateWindow(from, to, symbols);
        Path output = Files.createTempFile("d085-inspection-", ".json");
        Path error = Files.createTempFile("d085-inspection-", ".stderr");
        try {
            runProcess(command(from, to, symbols, maxRows, maxFiles, "--inspect", null),
                    output, error, MAX_INSPECTION_BYTES, Duration.ofMinutes(5), () -> false);
            if (Files.size(output) > MAX_INSPECTION_BYTES)
                throw new IOException("D085 source inspection exceeded its output bound");
            JsonNode node = JSON.readTree(Files.readString(output, StandardCharsets.UTF_8));
            Inspection inspection = decodeInspection(node, "inspection");
            if (!inspection.from().equals(from) || !inspection.to().equals(to)
                    || inspection.selectedRows() > maxRows || inspection.files() > maxFiles) {
                throw new IOException("D085 source inspection differs from its frozen request budgets");
            }
            return inspection;
        } finally {
            deleteTemporaryFiles(output, error);
        }
    }

    public SyncJobRunner.SourceCompletion stream(Inspection expected, List<String> symbols,
                                                  int maxRows, int maxFiles,
                                                  SyncJobRunner.PageConsumer<L2DatasetManifest> consumer,
                                                  BooleanSupplier cancelled, Duration timeout) throws Exception {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(cancelled);
        validateWindow(expected.from(), expected.to(), symbols);
        Path output = Files.createTempFile("d085-source-", ".jsonl");
        Path error = Files.createTempFile("d085-source-", ".stderr");
        try {
            runProcess(command(expected.from(), expected.to(), symbols, maxRows, maxFiles,
                            "--stream", expected.sourceFingerprint()),
                    output, error, MAX_OUTPUT_BYTES, timeout, cancelled);
            return consumeOutput(output, expected, symbols, consumer, cancelled);
        } finally {
            deleteTemporaryFiles(output, error);
        }
    }

    private static void deleteTemporaryFiles(Path... files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException locked) {
                // A forcibly terminated child can briefly retain a redirected file handle on Windows.
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

    private List<String> command(LocalDate from, LocalDate to, List<String> symbols,
                                 int maxRows, int maxFiles, String mode, String fingerprint) throws Exception {
        if (!Files.isRegularFile(helper) || Files.isSymbolicLink(helper))
            throw new IOException("D085 Python reader helper is unavailable");
        Path python = Path.of(pythonExecutable).toAbsolutePath().normalize();
        if (!Files.isRegularFile(python)) throw new IOException("Configured D085 Python executable is unavailable");
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
            throw new IllegalArgumentException("Positive D085 source timeout required");
        var processBuilder = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile());
        var environment = processBuilder.environment();
        environment.keySet().removeIf(key -> key.startsWith("QUESTDB_") || key.startsWith("APP_QUESTDB_")
                || key.contains("TUSHARE"));
        Process process = processBuilder.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            try (var stdin = process.getOutputStream()) { stdin.close(); }
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new CancellationException("D085 Parquet reader cancelled");
                if (System.nanoTime() >= deadline)
                    throw new java.util.concurrent.TimeoutException("D085 Parquet reader exceeded its bounded deadline");
                if (Files.size(output) > maxOutputBytes)
                    throw new IOException("D085 Parquet reader exceeded its output-byte budget");
            }
            if (process.exitValue() != 0) {
                String detail = Files.size(error) > MAX_ERROR_BYTES ? "source error output exceeded bound"
                        : Files.readString(error, StandardCharsets.UTF_8).lines().findFirst().orElse("source process failed");
                String errorType = detail.contains(":") ? detail.substring(0, detail.indexOf(':')) : detail;
                throw new IOException("D085 Parquet reader failed: " + errorType);
            }
            if (Files.size(output) > maxOutputBytes) throw new IOException("D085 source output exceeded its byte budget");
        } finally {
            // Windows keeps redirected files locked until the child process has actually exited.
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
