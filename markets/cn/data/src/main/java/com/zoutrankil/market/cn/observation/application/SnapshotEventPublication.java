package com.zoutrankil.market.cn.observation.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.ReferencePublicationJournal.State;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.market.cn.observation.client.SnapshotEventSource;
import com.zoutrankil.market.cn.observation.mapper.SnapshotEventDefinition;
import com.zoutrankil.market.cn.observation.port.SnapshotEventTables;
import com.zoutrankil.market.cn.observation.port.SnapshotEventTables.Snapshot;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 耐久 stage/backup 发布；恢复只处理已经完整核验的原 stage，从不重发无去重表。 */
public final class SnapshotEventPublication {
    public record Prepared(
            String protocol,
            String dataset,
            String runId,
            String stage,
            String backup,
            Snapshot before,
            SnapshotEventSource.Frozen source,
            List<Map<String, Object>> expected) {}

    public record Scope(String prepared, String sha256, String stageDirectory) {}

    private final String dataset;
    private final Path ledgerPath;
    private final SnapshotEventTables tables;
    private final ReferencePublicationJournal journal;

    public SnapshotEventPublication(String dataset, Path ledger, SnapshotEventTables tables)
            throws Exception {
        SnapshotEventDefinition.dateField(dataset);
        this.dataset = dataset;
        this.ledgerPath = ledger.toAbsolutePath().normalize();
        this.tables = tables;
        this.journal = new ReferencePublicationJournal(ledgerPath, dataset);
    }

    public Path folder(String run) {
        if (!run.matches("snapshot-event-[a-z_]+-[a-f0-9-]{36}"))
            throw new IllegalArgumentException("快照原运行编号无效");
        return ledgerPath.getParent().resolve("sync-evidence").resolve(run);
    }

    public static List<Map<String, Object>> expected(
            String dataset, Snapshot before, SnapshotEventSource.Frozen source) {
        if (!dataset.equals(source.dataset())) throw new IllegalArgumentException("来源数据集不符");
        var result = new ArrayList<Map<String, Object>>();
        for (var row : before.rows())
            if (!SnapshotEventDefinition.date(dataset, row).equals(source.date())) result.add(row);
        // 非空旧范围的空响应须独立撤销证据，不能仅凭空响应清表。
        if (source.rows().isEmpty() && result.size() != before.rows().size())
            throw new IllegalStateException("空来源不能撤销已有范围");
        result.addAll(source.rows());
        if (result.size() > SnapshotEventDefinition.MAX_TARGET_ROWS)
            throw new IllegalArgumentException("替换结果超出快照预算");
        return List.copyOf(result);
    }

    public void requireNoPending() throws Exception {
        journal.visitSummaries(
                1,
                true,
                ReferencePublicationJournal.ConnectionPolicy.FOREIGN_KEYS_WITH_BUSY_TIMEOUT,
                (summary, index) -> {
                    throw new IllegalStateException("存在未决原发布：" + summary.runId());
                });
    }

    public record Lock(FileChannel channel, FileLock lock) implements AutoCloseable {
        public void close() throws Exception {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    public Lock lock() throws Exception {
        var channel =
                FileChannel.open(
                        ledgerPath.resolveSibling(
                                ledgerPath.getFileName()
                                        + "."
                                        + dataset
                                        + "-snapshot-publication.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) throw new IllegalStateException("原快照 writer 仍持有发布锁");
            return new Lock(channel, lock);
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    public Prepared prepare(String run, Snapshot before, SnapshotEventSource.Frozen source)
            throws Exception {
        var rows = expected(dataset, before, source);
        Path folder = folder(run);
        Files.createDirectories(folder);
        var prepared =
                new Prepared(
                        "cn.snapshot.events.v2",
                        dataset,
                        run,
                        "java_"
                                + dataset
                                + "_stage_"
                                + UUID.randomUUID().toString().replace("-", ""),
                        "java_"
                                + dataset
                                + "_backup_"
                                + UUID.randomUUID().toString().replace("-", ""),
                        before,
                        source,
                        rows);
        byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(prepared);
        if (bytes.length > 3L * SnapshotEventDefinition.MAX_BYTES)
            throw new IllegalArgumentException("发布原件超过预算");
        FileEvidenceStore.writeNewDurable(folder.resolve("prepared.json"), bytes);
        return prepared;
    }

    private void lease(String run, boolean uncertain) {
        var lease =
                new DatasetIntervalLock(ledgerPath)
                        .findOwned(run, DatasetIntervalLock.Scope.allDates(dataset));
        if (lease == null) throw new IllegalStateException("原全表锁不存在");
        journal.requireLease(lease, uncertain);
    }

    public void publish(Prepared prepared, Snapshot stage, BooleanSupplier cancelled)
            throws Exception {
        tables.requireNoNativeDependencies();
        validatePrepared(prepared);
        lease(prepared.runId(), false);
        if (cancelled.getAsBoolean()) throw new CancellationException();
        if (!prepared.before().equals(tables.snapshot(dataset))
                || !stage.equals(tables.snapshot(prepared.stage()))
                || !stage.fingerprint()
                        .equals(SnapshotEventDefinition.fingerprint(prepared.expected())))
            throw new IllegalStateException("来源抓取或stage核验后目标已改变");
        Path path = folder(prepared.runId()).resolve("prepared.json");
        var scope =
                new Scope(
                        path.toString(),
                        FileEvidenceStore.sha256(Files.readAllBytes(path)),
                        stage.identity().directory());
        var entry =
                journal.create(
                        new ReferencePublicationJournal.Intent(
                                "snapshot-publication-" + UUID.randomUUID(),
                                dataset,
                                prepared.runId(),
                                dataset,
                                prepared.backup(),
                                prepared.stage(),
                                prepared.before().identity().targetId(),
                                prepared.before().identity().id(),
                                prepared.before().identity().directory(),
                                stage.identity().id(),
                                prepared.before().fingerprint(),
                                stage.fingerprint(),
                                JobDefinitionJson.mapper().writeValueAsString(scope)));
        try {
            finishRenames(entry, prepared, false, cancelled);
        } catch (Exception failure) {
            var current = journal.forRun(prepared.runId());
            if (current.state() != State.VERIFIED && current.state() != State.IN_DOUBT)
                journal.advance(current, State.IN_DOUBT);
            throw failure;
        }
    }

    private boolean matches(Snapshot actual, long id, String directory, String fingerprint) {
        return actual != null
                && actual.identity().id() == id
                && actual.identity().directory().equals(directory)
                && actual.fingerprint().equals(fingerprint);
    }

    private Snapshot finishRenames(
            ReferencePublicationJournal.Entry entry,
            Prepared prepared,
            boolean recovering,
            BooleanSupplier cancelled)
            throws Exception {
        var intent = entry.intent();
        var scope = JobDefinitionJson.mapper().readValue(intent.scope(), Scope.class);
        var target = tables.snapshotIfPresent(dataset);
        var backup = tables.snapshotIfPresent(intent.backup());
        var stage = tables.snapshotIfPresent(intent.stage());
        boolean original =
                matches(
                                target,
                                intent.originalId(),
                                intent.originalDirectory(),
                                intent.beforeFingerprint())
                        && backup == null
                        && matches(
                                stage,
                                intent.replacementId(),
                                scope.stageDirectory(),
                                intent.afterFingerprint());
        boolean oldMoved =
                target == null
                        && matches(
                                backup,
                                intent.originalId(),
                                intent.originalDirectory(),
                                intent.beforeFingerprint())
                        && matches(
                                stage,
                                intent.replacementId(),
                                scope.stageDirectory(),
                                intent.afterFingerprint());
        boolean published =
                matches(
                                target,
                                intent.replacementId(),
                                scope.stageDirectory(),
                                intent.afterFingerprint())
                        && matches(
                                backup,
                                intent.originalId(),
                                intent.originalDirectory(),
                                intent.beforeFingerprint())
                        && stage == null;
        if (entry.state() == State.VERIFIED && !published)
            throw new IllegalStateException("已核验发布不再对应原目标，不能重新切换旧对象");
        if (!original && !oldMoved && !published)
            throw new IllegalStateException("原表、stage、backup 与原意图不符，保留锁");
        if (!recovering && entry.state() != State.PREPARED)
            throw new IllegalStateException("发布状态已经改变");
        if (recovering && entry.state() == State.IN_DOUBT)
            entry = journal.advance(entry, State.RESUMING);
        if (original) {
            lease(intent.runId(), recovering);
            if (cancelled.getAsBoolean()) throw new CancellationException();
            tables.rename(dataset, intent.backup());
            if (!recovering) entry = journal.advance(entry, State.OLD_MOVED);
            oldMoved = true;
        }
        if (oldMoved) {
            // 第二次重命名前重新核对：只续接原已核验stage，不能认领新的同名表。
            if (tables.snapshotIfPresent(dataset) != null
                    || !matches(
                            tables.snapshot(intent.backup()),
                            intent.originalId(),
                            intent.originalDirectory(),
                            intent.beforeFingerprint())
                    || !matches(
                            tables.snapshot(intent.stage()),
                            intent.replacementId(),
                            scope.stageDirectory(),
                            intent.afterFingerprint()))
                throw new IllegalStateException("原重命名尾部前物理对象或内容改变");
            lease(intent.runId(), recovering);
            if (cancelled.getAsBoolean()) throw new CancellationException();
            tables.rename(intent.stage(), dataset);
        }
        var after = tables.snapshot(dataset);
        var old = tables.snapshot(intent.backup());
        if (!matches(
                        after,
                        intent.replacementId(),
                        scope.stageDirectory(),
                        intent.afterFingerprint())
                || !matches(
                        old,
                        intent.originalId(),
                        intent.originalDirectory(),
                        intent.beforeFingerprint())
                || tables.snapshotIfPresent(intent.stage()) != null)
            throw new IllegalStateException("发布后全字段、重复计数或物理归属不符");
        if (entry.state() != State.PUBLISHED && entry.state() != State.VERIFIED) {
            if (entry.state() != State.RESUMING && entry.state() != State.OLD_MOVED)
                throw new IllegalStateException("恢复阶段与已发布布局不符");
            entry = journal.advance(entry, State.PUBLISHED);
        }
        if (entry.state() == State.PUBLISHED) journal.advance(entry, State.VERIFIED);
        return after;
    }

    private void validatePrepared(Prepared prepared) throws Exception {
        if (!prepared.protocol().equals("cn.snapshot.events.v2")
                || !prepared.dataset().equals(dataset)
                || !prepared.stage().matches("java_" + dataset + "_stage_[a-f0-9]{32}")
                || !prepared.backup().matches("java_" + dataset + "_backup_[a-f0-9]{32}"))
            throw new IllegalArgumentException("原发布协议或对象归属无效");
        Path folder = folder(prepared.runId()).toRealPath();
        Path raw = Path.of(prepared.source().rawPath()).toRealPath();
        if (!raw.equals(folder.resolve("source-raw.json")))
            throw new IllegalStateException("原来源路径越出原运行");
        var replay = SnapshotEventSource.replay(prepared.source());
        if (!SnapshotEventDefinition.fingerprint(replay.rows())
                        .equals(SnapshotEventDefinition.fingerprint(prepared.source().rows()))
                || !SnapshotEventDefinition.fingerprint(
                                expected(dataset, prepared.before(), replay))
                        .equals(SnapshotEventDefinition.fingerprint(prepared.expected()))
                || !SnapshotEventDefinition.fingerprint(prepared.before().rows())
                        .equals(prepared.before().fingerprint()))
            throw new IllegalStateException("原来源或保留范围与原件不符");
        var run = SyncRunLedger.openReadOnly(ledgerPath).getRun(prepared.runId());
        var request = JobDefinitionJson.mapper().readTree(run.frozenJson());
        if (!run.targetId().equals(prepared.before().identity().targetId())
                || !request.path("from").asText().equals(prepared.source().date().toString())
                || !request.path("to").equals(request.path("from"))
                || !request.path("definition").path("datasetId").asText().equals(dataset)
                || request.path("definition").path("datasetVersion").asInt() != 2)
            throw new IllegalStateException("原冻结运行与发布范围不符");
    }

    public Snapshot reconcile(String run, boolean writerStopped) throws Exception {
        if (!writerStopped) throw new IllegalArgumentException("恢复需要明确原writer已停止");
        try (var lock = lock()) {
            tables.requireNoNativeDependencies();
            lease(run, true);
            var entry = journal.forRun(run);
            var scope = JobDefinitionJson.mapper().readValue(entry.intent().scope(), Scope.class);
            Path expectedPath = folder(run).resolve("prepared.json").toAbsolutePath().normalize();
            if (!Path.of(scope.prepared()).toAbsolutePath().normalize().equals(expectedPath)
                    || !expectedPath.toRealPath().startsWith(folder(run).toRealPath()))
                throw new IllegalStateException("原发布原件越界");
            byte[] bytes =
                    FileEvidenceStore.readBounded(
                            expectedPath,
                            3 * SnapshotEventDefinition.MAX_BYTES,
                            () -> new IllegalStateException("原发布原件超出预算"));
            if (!FileEvidenceStore.sha256(bytes).equals(scope.sha256()))
                throw new IllegalStateException("原发布原件摘要不符");
            var prepared = JobDefinitionJson.mapper().readValue(bytes, Prepared.class);
            validatePrepared(prepared);
            if (!entry.intent().runId().equals(run)
                    || !entry.intent().stage().equals(prepared.stage())
                    || !entry.intent().backup().equals(prepared.backup())
                    || !entry.intent()
                            .afterFingerprint()
                            .equals(SnapshotEventDefinition.fingerprint(prepared.expected())))
                throw new IllegalStateException("原发布日志与stage原件不符");
            // 所有通用页必须先已核验stage；恢复不补造未完成页的发送证明。
            var ledger = SyncRunLedger.openExisting(ledgerPath);
            var entries = ledger.entries(run, null, 1000);
            boolean empty = prepared.source().rows().isEmpty();
            if (entries.size() >= 1000
                    || entries.stream().noneMatch(e -> e.kind() == SyncRunLedger.Kind.SLICE)
                    || entries.stream()
                            .anyMatch(
                                    e ->
                                            e.kind() == SyncRunLedger.Kind.SLICE
                                                    && !(empty
                                                            ? Set.of(
                                                                            SyncRunState.FETCHED,
                                                                            SyncRunState
                                                                                    .VERIFIED_EMPTY)
                                                                    .contains(e.state())
                                                            : e.state() == SyncRunState.VERIFIED)))
                throw new IllegalStateException("原stage核验页不完整，不能续接发布");
            if (entry.state() != State.VERIFIED && entry.state() != State.IN_DOUBT)
                entry = journal.advance(entry, State.IN_DOUBT);
            var after = finishRenames(entry, prepared, true, () -> false);
            Path proof = folder(run).resolve("reconciled.json");
            int sourceRows = prepared.source().rows().size();
            var verification =
                    Map.ofEntries(
                            Map.entry("passed", true),
                            Map.entry("writerStopped", true),
                            Map.entry("expectedRows", sourceRows),
                            Map.entry("actualRows", sourceRows),
                            Map.entry("matchedRows", sourceRows),
                            Map.entry("mismatchedRows", 0),
                            Map.entry("missingKeys", 0),
                            Map.entry("duplicateKeys", 0),
                            Map.entry("readbackEvidence", proof.toString()),
                            Map.entry("sourceFingerprint", prepared.source().rawSha256()));
            var evidence =
                    Map.of(
                            "verification",
                            verification,
                            "sourceComplete",
                            true,
                            "returnedRows",
                            sourceRows,
                            "submittedRows",
                            sourceRows,
                            "responseEvidence",
                            prepared.source().rawPath(),
                            "fullTargetFingerprint",
                            after.fingerprint());
            FileEvidenceStore.replaceDurable(
                    proof, JobDefinitionJson.mapper().writeValueAsBytes(evidence));
            String json = JobDefinitionJson.mapper().writeValueAsString(evidence);
            SyncRunState state =
                    prepared.source().rows().isEmpty()
                            ? SyncRunState.VERIFIED_EMPTY
                            : SyncRunState.VERIFIED;
            if (empty)
                for (var slice : entries)
                    if (slice.kind() == SyncRunLedger.Kind.SLICE
                            && slice.state() == SyncRunState.FETCHED)
                        ledger.transition(
                                slice.id(), slice.revision(), SyncRunState.VERIFIED_EMPTY, json);
            for (var attempt : entries)
                if (attempt.kind() == SyncRunLedger.Kind.ATTEMPT
                        && !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                .contains(attempt.state()))
                    ledger.transition(attempt.id(), attempt.revision(), state, json);
            var current = ledger.get(run);
            if (current.state() != state) ledger.transition(run, current.revision(), state, json);
            var locks = new DatasetIntervalLock(ledgerPath);
            var lease = locks.findOwned(run, DatasetIntervalLock.Scope.allDates(dataset));
            if (lease != null) locks.releaseAfterReconciliation(lease, true, true);
            return after;
        }
    }
}
