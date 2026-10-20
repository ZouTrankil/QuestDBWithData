package com.zoutrankil.batch.source;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.CompletionEvidence;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.job.StageExecutor;
import com.zoutrankil.batch.storage.SqliteLedger;
import com.zoutrankil.batch.support.Json;
import com.zoutrankil.batch.writer.DurableWriter;
import com.zoutrankil.batch.writer.QuestDbTablePort;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 每个写入批次都经实际落库核验后，才发布该运行实例的类型化、不可变来源快照。 */
public final class SourceProductRunner {
    private final SqliteLedger ledger;
    private final SourceCollector collector;
    private final Path archive;
    private final URI endpoint;
    private final String authorization;

    public SourceProductRunner(
            SqliteLedger ledger,
            SourceCollector collector,
            Path archive,
            URI endpoint,
            String authorization) {
        this.ledger = ledger;
        this.collector = collector;
        this.archive = archive.toAbsolutePath().normalize();
        this.endpoint = endpoint;
        this.authorization = authorization;
    }

    public StageExecutor.Result execute(RunRequest request, String dataset) throws Exception {
        var contract = SourceContract.load(dataset);
        var frozen = collector.read(request.inputFingerprint());
        var layout = contract.writeLayout(request.definitionVersion());
        var writeContract = layout.target();
        if (!request.job().equals("source_" + dataset)
                || !frozen.dataset().equals(dataset)
                || !frozen.definitionVersion().equals(contract.version())
                || !frozen.logicalDate().equals(request.logicalDate())
                || !frozen.rangeStart().equals(request.rangeStart())
                || !frozen.rangeEnd().equals(request.rangeEnd())
                || !request.logicalDate().equals(request.rangeEnd())
                || !SourceContract.MONTHLY_AGGREGATES.contains(dataset)
                        && !SourceContract.QUARTERLY_AGGREGATES.contains(dataset)
                        && !request.rangeStart().equals(request.rangeEnd()))
            throw new IllegalArgumentException("Frozen source and business identity do not match");
        if (frozen.rows().isEmpty() && !contract.emptyAllowed())
            return new StageExecutor.Result(
                    BusinessState.WAITING_SOURCE, null, "expected-nonempty-source-window");
        if (frozen.rows().size() > contract.maxRows())
            throw new IllegalArgumentException("Frozen row budget exceeded");
        var keys = new HashSet<String>();
        for (var row : frozen.rows()) {
            LocalDate rowDate =
                    SourceContract.MONTHLY_AGGREGATES.contains(dataset)
                                    || SourceContract.QUARTERLY_AGGREGATES.contains(dataset)
                            ? LocalDate.parse(
                                    Objects.toString(row.get(contract.logicalDateColumn())))
                            : request.logicalDate();
            if (rowDate.isBefore(request.rangeStart()) || rowDate.isAfter(request.rangeEnd()))
                throw new IllegalArgumentException("Frozen observation is outside requested range");
            contract.validateNormalized(row, rowDate, frozen.expectedCodes());
            if (!row.keySet()
                    .equals(
                            new HashSet<>(
                                    contract.businessColumns().stream()
                                            .map(SourceContract.Column::target)
                                            .toList())))
                throw new IllegalArgumentException("Frozen schema mismatch");
            if (!keys.add(contract.key(row)))
                throw new IllegalArgumentException("Duplicate frozen business key");
            // fut_basic 是静态交易所快照，旧 QuestDB 时间戳使用固定的纪元值
            // 作为去重占位；真正的观测日期由 request.logicalDate 记录。
            if (!SourceContract.MONTHLY_AGGREGATES.contains(dataset)
                    && !SourceContract.QUARTERLY_AGGREGATES.contains(dataset)
                    && !Set.of("fut_basic", "etf_basic", "ths_index").contains(dataset)) {
                String value = Objects.toString(row.get(contract.logicalDateColumn()), "");
                String iso = request.logicalDate().toString(),
                        basic = request.logicalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
                if (!value.equals(iso) && !value.equals(basic))
                    throw new IllegalArgumentException("Frozen logical date mismatch");
            }
        }
        if (!frozen.completeCoverage()
                || !contract.covers(frozen.rows(), frozen.expectedCodes())
                || SourceContract.MONTHLY_AGGREGATES.contains(dataset)
                        && !SourceCollector.coversMonths(
                                frozen.rows(), request.rangeStart(), request.rangeEnd())
                || SourceContract.QUARTERLY_AGGREGATES.contains(dataset)
                        && !SourceCollector.coversQuarters(
                                frozen.rows(), request.rangeStart(), request.rangeEnd()))
            return new StageExecutor.Result(
                    BusinessState.PARTIAL, null, "expected-entity-coverage-incomplete");
        var qualityFailures = SourceQuality.failures(dataset, frozen.rows());
        if (!qualityFailures.isEmpty())
            return new StageExecutor.Result(
                    BusinessState.BLOCKED, null, "quality:" + String.join(",", qualityFailures));
        if (endpoint == null)
            return new StageExecutor.Result(
                    BusinessState.BLOCKED, null, "source-writer-not-configured");
        Instant createdAt = ledger.businessCreatedAt(request.instanceId());
        var physicalRows =
                frozen.rows().stream().map(row -> layout.physicalRow(row, createdAt)).toList();
        String target =
                "jdb_test_"
                        + ledger.environmentNamespace()
                        + "_"
                        + dataset
                        + "_"
                        + request.instanceId();
        Path directory = archive.resolve("writes").resolve(request.instanceId());
        Files.createDirectories(directory);
        var writer = new DurableWriter(ledger);
        long written = 0;
        var batches = new ArrayList<String>();
        for (int offset = 0;
                offset < Math.max(1, physicalRows.size());
                offset += writeContract.writeBatchSize()) {
            var rows =
                    physicalRows.subList(
                            offset,
                            Math.min(offset + writeContract.writeBatchSize(), physicalRows.size()));
            byte[] bytes = QuestDbTablePort.encode(writeContract, target, rows);
            String digest =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String batch = RunRequest.hash(request.instanceId(), Integer.toString(offset), digest);
            Path file = directory.resolve(batch + ".ilp");
            if (!Files.exists(file)) {
                Path temporary = Files.createTempFile(directory, "write-", ".partial");
                try {
                    try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                        var buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) channel.write(buffer);
                        channel.force(true);
                    }
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            var intent =
                    new DurableWriter.Intent(
                            batch,
                            request.instanceId(),
                            target,
                            "java-source:" + dataset,
                            digest,
                            file.toString(),
                            rows.size());
            var delivery =
                    writer.execute(
                            intent,
                            new QuestDbTablePort(
                                    endpoint, authorization, writeContract, target, rows));
            batches.add(batch);
            if (delivery != DurableWriter.Delivery.VERIFIED) {
                BusinessState state =
                        switch (delivery) {
                            case ACKNOWLEDGED -> BusinessState.VERIFYING;
                            case UNKNOWN -> BusinessState.IN_DOUBT;
                            default -> BusinessState.BLOCKED;
                        };
                return new StageExecutor.Result(state, null, "batch:" + batch + ":" + delivery);
            }
            written += rows.size();
        }
        // 各批次成功后仍复查完整快照，防止漏批、额外行或尚未应用的 WAL 被当成完整结果。
        var finalProbe =
                new QuestDbTablePort(
                        endpoint,
                        authorization,
                        writeContract,
                        target,
                        physicalRows.subList(
                                0, Math.min(writeContract.writeBatchSize(), physicalRows.size())));
        if (!finalProbe.completeSnapshot(frozen.rows().size()))
            return new StageExecutor.Result(
                    BusinessState.BLOCKED, null, "snapshot-row-count-or-WAL-state-mismatch");
        Path report = directory.resolve("certificate.json");
        Files.writeString(
                report,
                Json.write(
                        Map.of(
                                "schemaVersion",
                                1,
                                "target",
                                target,
                                "sourceArtifact",
                                collector.path(request.inputFingerprint()).toString(),
                                "sourceFingerprint",
                                request.inputFingerprint(),
                                "definitionVersion",
                                writeContract.version(),
                                "expectedCodes",
                                frozen.expectedCodes(),
                                "universeVersion",
                                frozen.universeVersion(),
                                "batches",
                                batches,
                                "verifiedRows",
                                written,
                                "floatingTolerance",
                                0.0)),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        var evidence =
                new CompletionEvidence(
                        1,
                        "java-source:" + dataset,
                        request.instanceId(),
                        "Source:" + dataset,
                        RunRequest.hash(batches.toArray(String[]::new)),
                        request.logicalDate(),
                        request.rangeStart(),
                        request.rangeEnd(),
                        request.rangeStart(),
                        request.rangeEnd(),
                        writeContract.version(),
                        request.inputFingerprint(),
                        request.inputFingerprint(),
                        frozen.rows().size(),
                        written,
                        true,
                        true,
                        true,
                        true,
                        true,
                        contract.emptyAllowed(),
                        Instant.now(),
                        frozen.rows().isEmpty()
                                ? BusinessState.VERIFIED_EMPTY
                                : BusinessState.VERIFIED,
                        report.toString(),
                        null);
        return new StageExecutor.Result(evidence.state(), evidence, null);
    }
}
