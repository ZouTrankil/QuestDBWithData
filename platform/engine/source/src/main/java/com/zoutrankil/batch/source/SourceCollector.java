package com.zoutrankil.batch.source;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.domain.SourcePeriods;
import com.zoutrankil.batch.support.Json;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.service.PageExecutor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Java 负责采集、归一化和不可变来源归档，不调用 Python。 */
public final class SourceCollector {
    @FunctionalInterface
    public interface ContractFetcher {
        PageExecutor.Page fetch(PageContract providerContract, Map<String, Object> params)
                throws Exception;
    }

    public record Request(
            String dataset,
            LocalDate logicalDate,
            Set<String> expectedCodes,
            String universeVersion,
            String tsCode,
            LocalDate rangeStart,
            LocalDate rangeEnd) {
        public Request(
                String dataset,
                LocalDate logicalDate,
                Set<String> expectedCodes,
                String universeVersion,
                String tsCode) {
            this(
                    dataset,
                    logicalDate,
                    expectedCodes,
                    universeVersion,
                    tsCode,
                    logicalDate,
                    logicalDate);
        }

        public Request {
            var contract = SourceContract.load(dataset);
            expectedCodes =
                    SourceStrategyRegistry.require(dataset)
                            .request()
                            .validate(
                                    contract,
                                    logicalDate,
                                    expectedCodes,
                                    universeVersion,
                                    tsCode,
                                    rangeStart,
                                    rangeEnd);
        }
    }

    public record Frozen(
            int schemaVersion,
            String dataset,
            String definitionVersion,
            LocalDate logicalDate,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            Set<String> expectedCodes,
            String universeVersion,
            String sourceDocumentation,
            List<Map<String, Object>> rows,
            int sourcePages,
            boolean completeCoverage) {
        public Frozen {
            if ((schemaVersion != 1 && schemaVersion != 2) || sourcePages < 1)
                throw new IllegalArgumentException(
                        "Unknown frozen-source schema or missing probe evidence");
            // 第 1 版归档只表示一个逻辑日期，仍可作为有效的恢复输入。
            if (rangeStart == null) rangeStart = logicalDate;
            if (rangeEnd == null) rangeEnd = logicalDate;
            new Request(
                    dataset,
                    logicalDate,
                    expectedCodes,
                    universeVersion,
                    null,
                    rangeStart,
                    rangeEnd);
            expectedCodes = Collections.unmodifiableSet(new TreeSet<>(expectedCodes));
            rows =
                    rows.stream()
                            .map(r -> Collections.unmodifiableMap(new LinkedHashMap<>(r)))
                            .toList();
        }
    }

    public record Collected(
            String fingerprint,
            String artifact,
            int rows,
            int pages,
            boolean completeCoverage,
            String scopeIdentity,
            BusinessState state) {}

    private final Path root;

    public SourceCollector(Path archiveRoot) {
        root = archiveRoot.toAbsolutePath().normalize().resolve("sources");
    }

    public Collected collect(Request request, PageExecutor.Fetcher source) throws Exception {
        return collect(request, (providerContract, params) -> source.fetch(params));
    }

    public Collected collect(Request request, ContractFetcher source) throws Exception {
        var contract = SourceContract.load(request.dataset());
        var normalized = new ArrayList<Map<String, Object>>();
        var strategy = SourceStrategyRegistry.require(contract.dataset());
        var session = strategy.request().openSession(contract, request, strategy.rows());
        final int[] bytes = {0}, rawBytes = {0}, rawRows = {0};
        var rawArtifacts = new ArrayList<String>();
        final int[] completedPages = {0}, completedRows = {0};
        PageExecutor.Fetcher retainedSource =
                pageParams -> {
                    String providerCode = (String) pageParams.get("ts_code");
                    var page = source.fetch(contract.providerContract(providerCode), pageParams);
                    if (page == null) return null;
                    session.validateResponse(pageParams, page);
                    if (page.rows().size() > contract.pageSize())
                        throw new IllegalArgumentException("Raw page row bound exceeded");
                    rawRows[0] = Math.addExact(rawRows[0], page.rows().size());
                    byte[] raw = Json.MAPPER.writeValueAsBytes(page);
                    rawBytes[0] = Math.addExact(rawBytes[0], raw.length);
                    if (rawBytes[0] > contract.maxBytes())
                        throw new IllegalArgumentException("Raw source byte budget exceeded");
                    String digest =
                            HexFormat.of()
                                    .formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
                    Path artifact = root.resolve("raw").resolve(digest + ".json");
                    retain(artifact, raw);
                    rawArtifacts.add(artifact.toString());
                    var prepared = session.prepareRows(providerCode, page.rows());
                    return new PageExecutor.Page(
                            prepared, page.nextCursor(), page.explicitEnd(), page.sourceVersion());
                };
        for (var queryParams : strategy.request().queries(contract, request)) {
            var completed =
                    new PageExecutor()
                            .execute(
                                    contract.pageContract(),
                                    queryParams,
                                    retainedSource,
                                    (page, receipt) ->
                                            session.acceptPage(
                                                    page,
                                                    value -> {
                                                        bytes[0] =
                                                                Math.addExact(
                                                                        bytes[0],
                                                                        Json.write(value)
                                                                                .getBytes(
                                                                                        StandardCharsets
                                                                                                .UTF_8)
                                                                                .length);
                                                        if (bytes[0] > contract.maxBytes())
                                                            throw new IllegalArgumentException(
                                                                    "Frozen source byte budget exceeded");
                                                        normalized.add(value);
                                                    }),
                                    session::validateRow,
                                    () -> Thread.currentThread().isInterrupted());
            completedPages[0] = Math.addExact(completedPages[0], completed.pages());
            completedRows[0] = Math.addExact(completedRows[0], completed.rows());
        }
        session.finish(normalized);
        if (normalized.size() > 1) {
            record KeyedRow(String key, Map<String, Object> row) {}
            var keyedRows = new ArrayList<KeyedRow>(normalized.size());
            for (var row : normalized) keyedRows.add(new KeyedRow(contract.key(row), row));
            keyedRows.sort(Comparator.comparing(KeyedRow::key));
            for (int i = 0; i < keyedRows.size(); i++) normalized.set(i, keyedRows.get(i).row());
        }
        boolean coverage =
                strategy.coverage()
                        .coversRange(
                                contract,
                                normalized,
                                request.expectedCodes(),
                                request.rangeStart(),
                                request.rangeEnd());
        var frozen =
                new Frozen(
                        2,
                        contract.dataset(),
                        contract.version(),
                        request.logicalDate(),
                        request.rangeStart(),
                        request.rangeEnd(),
                        request.expectedCodes(),
                        request.universeVersion(),
                        contract.sourceDocumentation(),
                        normalized,
                        completedPages[0],
                        coverage);
        // 观测时间记录在探测回执中，不参与内容身份；相同输入重复处理时身份保持稳定。
        byte[] content = Json.MAPPER.writeValueAsBytes(frozen);
        if (content.length > contract.maxBytes())
            throw new IllegalArgumentException("Frozen manifest byte budget exceeded");
        String fingerprint =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Path target = root.resolve(fingerprint + ".json");
        retain(target, content);
        Files.writeString(
                root.resolve(fingerprint + ".probe-" + UUID.randomUUID() + ".json"),
                Json.write(
                        Map.of(
                                "observedAt",
                                Instant.now(),
                                "rows",
                                normalized.size(),
                                "sourceRows",
                                rawRows[0],
                                "filteredFooterRows",
                                completedRows[0] - normalized.size(),
                                "collapsedOrFilteredEventRows",
                                rawRows[0] - completedRows[0],
                                "rawArtifacts",
                                rawArtifacts,
                                "pages",
                                completedPages[0],
                                "sourceProbeComplete",
                                true)),
                StandardOpenOption.CREATE_NEW);
        return new Collected(
                fingerprint,
                target.toString(),
                normalized.size(),
                completedPages[0],
                coverage,
                scopeIdentity(frozen),
                normalized.isEmpty() && !contract.emptyAllowed()
                        ? BusinessState.WAITING_SOURCE
                        : !coverage
                                ? BusinessState.PARTIAL
                                : SourceQuality.failures(contract.dataset(), normalized).isEmpty()
                                        ? BusinessState.VERIFYING
                                        : BusinessState.BLOCKED);
    }

    public static String scopeIdentity(Frozen frozen) {
        return RunRequest.hash(
                frozen.dataset(),
                frozen.universeVersion(),
                String.join("\n", frozen.expectedCodes()));
    }

    static boolean coversMonths(List<Map<String, Object>> rows, LocalDate start, LocalDate end) {
        return SourcePeriods.coversMonths(rows, start, end);
    }

    static boolean coversQuarters(List<Map<String, Object>> rows, LocalDate start, LocalDate end) {
        return SourcePeriods.coversQuarters(rows, start, end);
    }

    static String quarter(LocalDate date) {
        return SourcePeriods.quarter(date);
    }

    static boolean isQuarterEnd(LocalDate date) {
        return SourcePeriods.isQuarterEnd(date);
    }

    static long quartersBetween(LocalDate start, LocalDate end) {
        return SourcePeriods.quartersBetween(start, end);
    }

    private static void retain(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            if (!Arrays.equals(Files.readAllBytes(target), content))
                throw new IllegalArgumentException("Retained content changed");
            return;
        }
        Path temp = Files.createTempFile(target.getParent(), "collect-", ".partial");
        try {
            try (var file = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public boolean sameScope(RunRequest previous, RunRequest next) {
        try {
            Frozen left = read(previous.inputFingerprint()), right = read(next.inputFingerprint());
            return left.dataset().equals(right.dataset())
                    && left.logicalDate().equals(right.logicalDate())
                    && left.rangeStart().equals(right.rangeStart())
                    && left.rangeEnd().equals(right.rangeEnd())
                    && left.definitionVersion().equals(right.definitionVersion())
                    && left.expectedCodes().equals(right.expectedCodes())
                    && left.universeVersion().equals(right.universeVersion());
        } catch (Exception invalid) {
            return false;
        }
    }

    public Frozen read(String fingerprint) throws Exception {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Source SHA-256 required");
        Path path = root.resolve(fingerprint + ".json");
        if (Files.size(path) > 64L * 1024 * 1024)
            throw new IllegalArgumentException("Frozen source exceeds memory budget");
        byte[] content = Files.readAllBytes(path);
        if (!HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(content))
                .equals(fingerprint))
            throw new IllegalArgumentException("Frozen source content changed");
        return Json.MAPPER.readValue(content, Frozen.class);
    }

    public Path path(String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Source SHA-256 required");
        return root.resolve(fingerprint + ".json");
    }
}
