package com.zoutrankil.data.stock.application;

import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.stock.domain.StockSuspendState;
import com.zoutrankil.data.stock.mapper.StockSuspendLayoutMapper;
import com.zoutrankil.data.stock.port.StockSuspendTables;
import com.zoutrankil.data.stock.port.StockSuspendTarget;
import com.zoutrankil.data.stock.storage.StockSuspendStaging;
import com.zoutrankil.data.stock.storage.StockSuspendTargetTransitionStore;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** D011 date-window stage publication with durable rename recovery and physical-identity lineage. */
public final class StockSuspendPublication {
    public enum Layout {
        ORIGINAL,
        OLD_MOVED,
        PUBLISHED,
        CONFLICT
    }

    public record WindowProof(
            String logicalTargetId,
            String physicalTargetBefore,
            String physicalTargetAfter,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            boolean replacementPublished,
            int expectedRows,
            int actualRows,
            int matchedRows,
            int mismatchedRows,
            int duplicateKeys,
            int missingKeys,
            String readbackEvidence,
            String sourceFingerprint,
            String fullTargetFingerprint,
            boolean writerStopped,
            boolean passed) {}

    private record Scope(LocalDate from, LocalDate to) {}

    private final CnTradeDateLayout dateLayout;
    private final StockSuspendTables tables;
    private final Path ledgerPath;
    private final Path evidenceRoot;
    private final String table;
    private final String logicalTargetId;
    private final String runId;
    private final ReferencePublicationJournal journal;
    private final DatasetIntervalLock intervalLocks;
    private final StockSuspendTargetTransitionStore transitions;

    public StockSuspendPublication(
            StockSuspendTables tables,
            Path ledgerPath,
            Path evidenceRoot,
            String table,
            String logicalTargetId,
            String runId)
            throws Exception {
        this(
                tables,
                ledgerPath,
                evidenceRoot,
                table,
                logicalTargetId,
                runId,
                CnTradeDateLayout.LEGACY_V1);
    }

    public StockSuspendPublication(
            StockSuspendTables tables,
            Path ledgerPath,
            Path evidenceRoot,
            String table,
            String logicalTargetId,
            String runId,
            CnTradeDateLayout layout)
            throws Exception {
        this.dateLayout = Objects.requireNonNull(layout);
        int actual =
                tables instanceof StockSuspendTables.Versioned selected
                        ? selected.datasetVersion()
                        : 1;
        if (actual != layout.schemaVersion()) throw new IllegalArgumentException("停牌发布端口布局不同");
        this.tables = Objects.requireNonNull(tables);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
        DatasetDefinition.identifier(table);
        this.table = table;
        this.logicalTargetId = Objects.requireNonNull(logicalTargetId);
        this.runId = Objects.requireNonNull(runId);
        this.journal = new ReferencePublicationJournal(this.ledgerPath, "stk_suspend");
        this.intervalLocks = new DatasetIntervalLock(this.ledgerPath);
        this.transitions = new StockSuspendTargetTransitionStore(this.ledgerPath);
        transitions.initialize();
    }

    /** OS lock releases on process death; the durable journal blocks subsequent runs until recovery. */
    public Lock acquire() throws Exception {
        Path path =
                ledgerPath.resolveSibling(
                        ledgerPath.getFileName() + ".stk_suspend-publication.lock");
        Files.createDirectories(path.getParent());
        FileChannel channel =
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                lock = null;
            }
            if (lock == null)
                throw new IllegalStateException(
                        "Another stk_suspend full-table replacement is active");
            requireNoUnresolved();
            verifyCurrentTarget(logicalTargetId, currentPhysicalTargetId());
            return new Lock(channel, lock);
        } catch (Exception failure) {
            channel.close();
            throw failure;
        }
    }

    public record Lock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    public WindowProof publishWindow(
            StockSuspendState.Snapshot before,
            StockSuspendState.Verified stage,
            List<StockSuspend> source,
            LocalDate from,
            LocalDate to,
            String expectedPhysicalTarget,
            String sourceFingerprint,
            String completionEvidencePath,
            BooleanSupplier cancelled)
            throws Exception {
        check(cancelled);
        requireRunBinding(runId, from, to, expectedPhysicalTarget);
        if (!logicalTargetId.equals(tables.logicalTargetId(table)))
            throw new IllegalStateException(
                    "stk_suspend logical target changed during the frozen run");
        String currentPhysical = currentPhysicalTargetId();
        if (!expectedPhysicalTarget.equals(currentPhysical)
                || !tables.physicalTargetId(table, before.identity())
                        .equals(expectedPhysicalTarget))
            throw new IllegalStateException(
                    "stk_suspend physical target changed before scoped replacement");
        var storage = tables.openTable(table);
        var current = storage.snapshot();
        if (!before.equals(current))
            throw new IllegalStateException(
                    "stk_suspend logical target changed while source pages were staged");
        var prepared = tables.prepare(before, current, source, from, to);
        check(cancelled);
        requireRunLease(from, to);
        var currentAgain = storage.snapshot();
        if (!before.equals(currentAgain))
            throw new IllegalStateException(
                    "stk_suspend target changed while source pages were staged");
        Path stageReceipt = Path.of(stage.receipt()).toAbsolutePath().normalize();
        if (!stage.table().matches("java_stk_suspend_stage_[0-9a-f]{32}")
                || !stageReceipt.getFileName().toString().equals(stage.table() + "-verified.json")
                || !stageReceipt.startsWith(evidenceRoot)
                || !Files.isRegularFile(stageReceipt)
                || !stageReceipt.toRealPath().startsWith(evidenceRoot.toRealPath())
                || Files.size(stageReceipt) > StockSuspendStaging.MAX_STAGE_EVIDENCE_BYTES)
            throw new IllegalStateException(
                    "stk_suspend verified stage receipt is absent, oversized or outside the run evidence directory");
        byte[] frozenStageBytes = null;
        if (dateLayout == CnTradeDateLayout.TRADE_DATE_V2) {
            frozenStageBytes =
                    FileEvidenceStore.readBounded(
                            stageReceipt,
                            StockSuspendStaging.MAX_STAGE_EVIDENCE_BYTES,
                            () -> new IllegalStateException("停牌 stage 凭证过大"));
            var proof = JobDefinitionJson.mapper().readTree(frozenStageBytes);
            StockSuspendLayoutMapper.require(proof, dateLayout);
            if (!table.equals(proof.path("target").asText())
                    || !stage.table().equals(proof.path("stage").asText())
                    || !from.toString().equals(proof.path("windowFrom").asText())
                    || !to.toString().equals(proof.path("windowTo").asText())
                    || !proof.path("sourceRows").isIntegralNumber()
                    || !proof.path("sourceRows").canConvertToInt()
                    || proof.path("sourceRows").intValue() != source.size()
                    || !proof.path("actual")
                            .equals(
                                    JobDefinitionJson.mapper()
                                            .readTree(
                                                    JobDefinitionJson.mapper()
                                                            .writeValueAsBytes(stage.snapshot()))))
                throw new IllegalStateException("停牌 stage 凭证与原冻结发布不同");
        }
        var stageAgain = tables.openTable(stage.table()).snapshot();
        if (!stage.snapshot().equals(stageAgain) || !stageAgain.rows().equals(prepared.expected()))
            throw new IllegalStateException(
                    "stk_suspend complete stage differs from preserved outside rows plus authoritative source window");
        var oldIdentity = current.identity();
        var newIdentity = stage.snapshot().identity();
        String backup = "java_stk_suspend_backup_" + UUID.randomUUID().toString().replace("-", "");
        Path completion = Path.of(completionEvidencePath).toAbsolutePath().normalize();
        if (!completion.startsWith(evidenceRoot)
                || !completion.getFileName().toString().equals("complete-" + runId + ".json"))
            throw new IllegalArgumentException(
                    "Frozen stk_suspend completion evidence path is outside its run directory");
        String scopeJson =
                JobDefinitionJson.mapper()
                        .writeValueAsString(
                                Map.of(
                                        "logicalTargetId",
                                        logicalTargetId,
                                        "physicalTargetBefore",
                                        expectedPhysicalTarget,
                                        "fromInclusive",
                                        from.toString(),
                                        "toInclusive",
                                        to.toString(),
                                        "stageDirectory",
                                        newIdentity.directory(),
                                        "stageReceipt",
                                        stage.receipt(),
                                        "sourceFingerprint",
                                        sourceFingerprint,
                                        "completionEvidence",
                                        completion.toString()));
        if (dateLayout == CnTradeDateLayout.TRADE_DATE_V2) {
            var scoped = (ObjectNode) JobDefinitionJson.mapper().readTree(scopeJson);
            scoped.put("protocolVersion", 2);
            scoped.put("datasetSchemaVersion", 2);
            scoped.put("rowEncoding", "trade_date-v1");
            scoped.put("stageReceiptFingerprint", FileEvidenceStore.sha256(frozenStageBytes));
            scopeJson = JobDefinitionJson.mapper().writeValueAsString(scoped);
        }
        var entry =
                journal.create(
                        new ReferencePublicationJournal.Intent(
                                "stk-suspend-publication-" + UUID.randomUUID(),
                                "stk_suspend",
                                runId,
                                table,
                                backup,
                                stage.table(),
                                logicalTargetId,
                                oldIdentity.id(),
                                oldIdentity.directory(),
                                newIdentity.id(),
                                current.fingerprint(),
                                stage.snapshot().fingerprint(),
                                scopeJson));
        try {
            requireRunLease(from, to);
            check(cancelled);
            rename(table, backup);
            entry = journal.advance(entry, State.OLD_MOVED);
            requireRunLease(from, to);
            check(cancelled);
            rename(stage.table(), table);
            entry = journal.advance(entry, State.PUBLISHED);
            var after = completePublished(entry, expectedPhysicalTarget);
            var scoped =
                    after.rows().stream()
                            .filter(
                                    row ->
                                            !row.tradeDate().isBefore(from)
                                                    && !row.tradeDate().isAfter(to))
                            .toList();
            String readback = stage.receipt();
            if (!scoped.equals(prepared.source()))
                throw new IllegalStateException(
                        "Published stk_suspend date window differs from complete source snapshot");
            return new WindowProof(
                    logicalTargetId,
                    expectedPhysicalTarget,
                    tables.physicalTargetId(table, after.identity()),
                    from,
                    to,
                    true,
                    source.size(),
                    scoped.size(),
                    source.size(),
                    0,
                    0,
                    0,
                    readback,
                    sourceFingerprint,
                    after.fingerprint(),
                    true,
                    true);
        } catch (Exception failure) {
            markInDoubt(runId, failure);
            throw new IllegalStateException(
                    "stk_suspend window publication is uncertain; reconcile its journal before another run",
                    failure);
        }
    }

    public Layout inspect(String publicationRun) throws Exception {
        var entry = journal.forRun(publicationRun);
        var intent = entry.intent();
        var frozenScope = parseScope(intent.scope());
        requireRunBinding(
                publicationRun,
                frozenScope.from(),
                frozenScope.to(),
                parsePhysicalBefore(intent.scope()));
        requireStageProof(intent);
        if (!intent.target().equals(table)
                || !intent.dataset().equals("stk_suspend")
                || !SyncRunLedger.openReadOnly(ledgerPath)
                        .getRun(publicationRun)
                        .targetId()
                        .equals(intent.initialTarget()))
            throw new IllegalStateException(
                    "stk_suspend publication endpoint/logical target differs from its run");
        StockSuspendState.Snapshot target = optional(intent.target()),
                backup = optional(intent.backup()),
                stage = optional(intent.stage());
        if (matches(
                        target,
                        intent.originalId(),
                        intent.originalDirectory(),
                        intent.beforeFingerprint())
                && backup == null
                && matches(stage, intent.replacementId(), null, intent.afterFingerprint()))
            return Layout.ORIGINAL;
        if (target == null
                && matches(
                        backup,
                        intent.originalId(),
                        intent.originalDirectory(),
                        intent.beforeFingerprint())
                && matches(stage, intent.replacementId(), null, intent.afterFingerprint()))
            return Layout.OLD_MOVED;
        if (matches(target, intent.replacementId(), null, intent.afterFingerprint())
                && matches(
                        backup,
                        intent.originalId(),
                        intent.originalDirectory(),
                        intent.beforeFingerprint())
                && stage == null) return Layout.PUBLISHED;
        return Layout.CONFLICT;
    }

    /** Explicit operator-facing recovery entry; caller must prove the old writer stopped. */
    public void finish(String publicationRun, boolean writerStopped) throws Exception {
        if (!writerStopped)
            throw new IllegalStateException("Stopped stk_suspend writer proof required");
        if (dateLayout == CnTradeDateLayout.TRADE_DATE_V2)
            throw new IllegalStateException("停牌 v2 恢复须先完整核验来源，请使用 finishInterrupted");
        finishAfterSourceValidation(publicationRun, writerStopped);
    }

    /** v2 仅由完整来源恢复流程进入；先证明原件和全窗，再允许补 rename。 */
    void finishAfterSourceValidation(String publicationRun, boolean writerStopped)
            throws Exception {
        if (!writerStopped)
            throw new IllegalStateException("Stopped stk_suspend writer proof required");
        try (var lock = acquireRecoveryLock()) {
            var publication = journal.forRun(publicationRun);
            var intent = publication.intent();
            if (!intent.target().equals(table)
                    || !intent.initialTarget().matches("static-v2-[0-9a-f]{64}"))
                throw new IllegalStateException(
                        "stk_suspend publication intent does not bind this logical target");
            Scope scope = parseScope(intent.scope());
            requireRunBinding(
                    publicationRun, scope.from(), scope.to(), parsePhysicalBefore(intent.scope()));
            requireStageProof(intent);
            var run = SyncRunLedger.openReadOnly(ledgerPath).getRun(publicationRun);
            if (!run.jobId().equals("data.stk_suspend")
                    || !run.targetId().equals(intent.initialTarget()))
                throw new IllegalStateException(
                        "stk_suspend publication intent differs from its run");
            Layout layout = inspect(publicationRun);
            if (layout == Layout.CONFLICT)
                throw new IllegalStateException(
                        "Conflicting stk_suspend target/backup/stage layout; no automatic rename");
            var entry = publication;
            if (entry.state() == State.VERIFIED) {
                if (layout != Layout.PUBLISHED)
                    throw new IllegalStateException("Verified stk_suspend publication drifted");
                var after = completeTransition(entry, intent.scope());
                reconcileEmptyRun(publicationRun, intent, scope, after, writerStopped);
                return;
            }
            if (entry.state() != State.IN_DOUBT) entry = journal.advance(entry, State.IN_DOUBT);
            entry = journal.advance(entry, State.RESUMING);
            if (layout == Layout.ORIGINAL) {
                rename(intent.target(), intent.backup());
                rename(intent.stage(), intent.target());
            } else if (layout == Layout.OLD_MOVED) rename(intent.stage(), intent.target());
            entry = journal.advance(entry, State.PUBLISHED);
            var after = completePublished(entry, parsePhysicalBefore(intent.scope()));
            reconcileEmptyRun(publicationRun, intent, scope, after, writerStopped);
        }
    }

    public static void verifyCurrentTarget(
            Path ledgerPath,
            StockSuspendTarget target,
            String logicalTargetId,
            String currentPhysicalId)
            throws Exception {
        if (!Files.isRegularFile(ledgerPath)) return;
        var publication =
                new StockSuspendPublication(
                        target.newPublicationTables(),
                        ledgerPath,
                        ledgerPath
                                .toAbsolutePath()
                                .normalize()
                                .getParent()
                                .resolve("sync-evidence"),
                        target.tableName(),
                        logicalTargetId,
                        "identity-check",
                        CnTradeDateLayout.fromVersion(StockSuspendTarget.versionOf(target)));
        publication.verifyCurrentTarget(logicalTargetId, currentPhysicalId);
    }

    /** A resume may cross physical generations only through its own VERIFIED journal transition. */
    public static boolean authorizesResume(
            Path ledgerPath,
            String runId,
            String logicalTargetId,
            String frozenPhysicalId,
            String currentPhysicalId)
            throws Exception {
        if (!Files.isRegularFile(ledgerPath)) return false;
        var found =
                new StockSuspendTargetTransitionStore(ledgerPath).forRun(runId, logicalTargetId);
        if (found.isEmpty()) return false;
        var entry = found.get();
        boolean matches =
                frozenPhysicalId.equals(entry.intent().previousPhysicalId())
                        && currentPhysicalId.equals(entry.intent().nextPhysicalId())
                        && "VERIFIED".equals(entry.state());
        if (!matches) return false;
        var journal = new ReferencePublicationJournal(ledgerPath, "stk_suspend");
        return journal.forRun(runId).state() == State.VERIFIED;
    }

    public static void finish(
            Path ledgerPath, StockSuspendTarget target, String runId, boolean writerStopped)
            throws Exception {
        var run = SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);
        if (run.jobVersion() == 2) {
            StockSuspendRunRecovery.finishInterrupted(target, ledgerPath, runId, writerStopped);
            return;
        }
        var intent =
                new ReferencePublicationJournal(ledgerPath, "stk_suspend").forRun(runId).intent();
        var publisher =
                new StockSuspendPublication(
                        target.newPublicationTables(),
                        ledgerPath,
                        ledgerPath
                                .toAbsolutePath()
                                .normalize()
                                .getParent()
                                .resolve("sync-evidence")
                                .resolve(runId),
                        target.tableName(),
                        intent.initialTarget(),
                        runId);
        publisher.finish(runId, writerStopped);
    }

    /** Shared group writers call this before touching the logical table to avoid bypassing an unfinished swap. */
    public static void requireNoPendingPublication(Path ledgerPath) throws Exception {
        var pending = new StockSuspendTargetTransitionStore(ledgerPath).pendingIfPresent();
        if (pending.publicationRunId() != null)
            throw new IllegalStateException(
                    "Unresolved stk_suspend publication for run " + pending.publicationRunId());
        if (pending.transitionRunId() != null)
            throw new IllegalStateException(
                    "Unverified stk_suspend target transition for run "
                            + pending.transitionRunId());
    }

    public static boolean recoverIfPresent(
            Path ledgerPath, StockSuspendTarget target, String runId, boolean writerStopped)
            throws Exception {
        if (!Files.isRegularFile(ledgerPath)) return false;
        var journal = new ReferencePublicationJournal(ledgerPath, "stk_suspend");
        if (journal.findForRun(runId).isEmpty()) return false;
        finish(ledgerPath, target, runId, writerStopped);
        return true;
    }

    private StockSuspendState.Snapshot completePublished(
            ReferencePublicationJournal.Entry entry, String physicalBefore) throws Exception {
        if (inspect(entry.intent().runId()) != Layout.PUBLISHED)
            throw new IllegalStateException(
                    "stk_suspend published target differs from journaled replacement");
        var actual = tables.openTable(table).snapshot();
        if (actual.identity().id() != entry.intent().replacementId()
                || !actual.fingerprint().equals(entry.intent().afterFingerprint()))
            throw new IllegalStateException(
                    "stk_suspend renamed target identity or full snapshot differs from stage");
        String actualPhysical = tables.physicalTargetId(table, actual.identity());
        completeTransition(entry, entry.intent().scope(), physicalBefore, actual, actualPhysical);
        return actual;
    }

    private StockSuspendState.Snapshot completeTransition(
            ReferencePublicationJournal.Entry entry, String scopeJson) throws Exception {
        String before = parsePhysicalBefore(scopeJson);
        var actual = tables.openTable(table).snapshot();
        completeTransition(
                entry,
                scopeJson,
                before,
                actual,
                tables.physicalTargetId(table, actual.identity()));
        return actual;
    }

    /**
     * A process can stop after an all-empty replacement becomes authoritative but before
     * the runner records completion. Reconcile only a receipt-backed all-empty window.
     */
    private void reconcileEmptyRun(
            String publicationRun,
            ReferencePublicationJournal.Intent intent,
            Scope scope,
            StockSuspendState.Snapshot after,
            boolean writerStopped)
            throws Exception {
        if (!writerStopped)
            throw new IllegalStateException("Stopped stk_suspend writer proof required");
        var scoped =
                after.rows().stream()
                        .filter(
                                row ->
                                        !row.tradeDate().isBefore(scope.from())
                                                && !row.tradeDate().isAfter(scope.to()))
                        .toList();
        if (!scoped.isEmpty()) return;

        Path completionPath =
                Path.of(requiredScopeText(intent.scope(), "completionEvidence"))
                        .toAbsolutePath()
                        .normalize();
        if (!completionPath.startsWith(evidenceRoot)
                || !completionPath
                        .getFileName()
                        .toString()
                        .equals("complete-" + publicationRun + ".json"))
            throw new IllegalStateException(
                    "stk_suspend recovery completion path is outside its run evidence directory");
        var ledger = new SyncRunLedger(ledgerPath);
        var run = ledger.getRun(publicationRun);
        var runEntry = ledger.get(publicationRun);
        if (!run.jobId().equals("data.stk_suspend") || !run.targetId().equals(logicalTargetId))
            throw new IllegalStateException("stk_suspend recovery run identity changed");
        boolean alreadyComplete = runEntry.state().terminal();
        if (alreadyComplete && dateLayout == CnTradeDateLayout.LEGACY_V1) return;
        if (!Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT).contains(runEntry.state())
                && !(dateLayout == CnTradeDateLayout.TRADE_DATE_V2
                        && runEntry.state() == SyncRunState.VERIFIED_EMPTY))
            throw new IllegalStateException(
                    "stk_suspend recovery run is not in a reconcilable state");

        var allEntries = new ArrayList<SyncRunLedger.Entry>();
        String cursor = null;
        while (true) {
            var page = ledger.entries(publicationRun, cursor, 1000);
            allEntries.addAll(page);
            if (page.size() < 1000) break;
            cursor = page.getLast().id();
        }
        var attempts =
                allEntries.stream()
                        .filter(item -> item.kind() == SyncRunLedger.Kind.ATTEMPT)
                        .toList();
        if (attempts.size() != 1)
            throw new IllegalStateException(
                    "stk_suspend empty publication recovery requires one attempt");
        var attempt = attempts.getFirst();
        var slices =
                allEntries.stream()
                        .filter(item -> item.kind() == SyncRunLedger.Kind.SLICE)
                        .toList();
        long expectedDays = ChronoUnit.DAYS.between(scope.from(), scope.to()) + 1;
        if (slices.size() != expectedDays)
            throw new IllegalStateException(
                    "stk_suspend recovery lacks one source slice per calendar day");

        var receipts = new ArrayList<String>();
        var pagePaths = new ArrayList<Path>();
        var perDateFingerprints = new TreeMap<LocalDate, String>();
        for (var slice : slices) {
            if (!attempt.id().equals(slice.parentId())
                    || !Set.of(
                                    SyncRunState.FETCHED,
                                    SyncRunState.IN_DOUBT,
                                    SyncRunState.VERIFIED_EMPTY)
                            .contains(slice.state()))
                throw new IllegalStateException(
                        "stk_suspend recovery slice is not an empty fetched or reconciled slice");
            var fetched =
                    ledger.events(slice.id(), -1, 100).stream()
                            .filter(event -> event.state() == SyncRunState.FETCHED)
                            .toList();
            if (fetched.size() != 1)
                throw new IllegalStateException(
                        "stk_suspend recovery slice lacks one immutable fetch event");
            JsonNode fetch = JobDefinitionJson.mapper().readTree(fetched.getFirst().payloadJson());
            if (fetch.path("returnedRows").asInt(-1) != 0
                    || !fetch.path("sourceFingerprint").isTextual()
                    || !fetch.path("responseEvidence").isTextual())
                throw new IllegalStateException(
                        "stk_suspend recovery found a nonempty or incomplete source slice");
            Path pagePath =
                    Path.of(fetch.path("responseEvidence").asText()).toAbsolutePath().normalize();
            if (!pagePath.startsWith(evidenceRoot) || !Files.isRegularFile(pagePath))
                throw new IllegalStateException(
                        "stk_suspend recovery page receipt is absent or outside its run directory");
            Path realRoot = evidenceRoot.toRealPath(), realPage = pagePath.toRealPath();
            if (!realPage.startsWith(realRoot)
                    || Files.size(realPage) > StockSuspendSource.MAX_EVIDENCE_BYTES)
                throw new IllegalStateException(
                        "stk_suspend recovery page receipt is oversized or escapes its run directory");
            JsonNode page =
                    JobDefinitionJson.mapper()
                            .readTree(
                                    FileEvidenceStore.readBounded(
                                            realPage,
                                            StockSuspendSource.MAX_EVIDENCE_BYTES,
                                            () ->
                                                    new IllegalStateException(
                                                            "D011 recovery evidence escapes its run directory or exceeds its byte bound")));
            if (!"stk_suspend_daily_source".equals(page.path("evidenceType").asText())
                    || !page.path("sourceComplete").asBoolean(false)
                    || !fetch.path("sourceFingerprint")
                            .asText()
                            .equals(page.path("sourceFingerprint").asText())
                    || !page.path("sourceReceipt").isTextual())
                throw new IllegalStateException("stk_suspend recovery page receipt is incomplete");
            Path receiptPath =
                    Path.of(page.path("sourceReceipt").asText()).toAbsolutePath().normalize();
            if (!receiptPath.startsWith(evidenceRoot) || !Files.isRegularFile(receiptPath))
                throw new IllegalStateException(
                        "stk_suspend recovery raw source evidence is absent or outside its run directory");
            Path realReceipt = receiptPath.toRealPath();
            if (!realReceipt.startsWith(realRoot)
                    || Files.size(realReceipt) > StockSuspendSource.MAX_EVIDENCE_BYTES)
                throw new IllegalStateException(
                        "stk_suspend recovery raw source evidence is oversized or escapes its run directory");
            byte[] receiptBytes =
                    FileEvidenceStore.readBounded(
                            realReceipt,
                            StockSuspendSource.MAX_EVIDENCE_BYTES,
                            () ->
                                    new IllegalStateException(
                                            "D011 recovery evidence escapes its run directory or exceeds its byte bound"));
            String fingerprint = FileEvidenceStore.sha256(receiptBytes);
            if (!fingerprint.equals(fetch.path("sourceFingerprint").asText()))
                throw new IllegalStateException(
                        "stk_suspend recovery source fingerprint differs from immutable receipt");
            JsonNode receipt = JobDefinitionJson.mapper().readTree(receiptBytes);
            if (!"suspend_d".equals(receipt.path("endpoint").asText())
                    || !receipt.path("sourceComplete").asBoolean(false)
                    || receipt.path("sourceRowCap").asInt() != StockSuspendSource.SOURCE_ROW_CAP
                    || !receipt.path("rows").isArray()
                    || !receipt.path("normalizedRows").isArray()
                    || receipt.path("returnedRows").asInt(-1) != receipt.path("rows").size()
                    || receipt.path("rows").size() != receipt.path("normalizedRows").size()
                    || receipt.path("returnedRows").asInt(-1)
                            != fetch.path("returnedRows").asInt(-2)
                    || receipt.path("returnedRows").asInt(-1) != 0)
                throw new IllegalStateException(
                        "stk_suspend recovery source is not an authoritative empty daily response");
            LocalDate date = LocalDate.parse(receipt.path("tradeDate").asText());
            if (date.isBefore(scope.from())
                    || date.isAfter(scope.to())
                    || perDateFingerprints.putIfAbsent(date, fingerprint) != null
                    || !date.toString().equals(page.path("tradeDate").asText())
                    || !date.format(DateTimeFormatter.BASIC_ISO_DATE)
                            .equals(receipt.path("parameters").path("trade_date").asText())
                    || !"S".equals(receipt.path("parameters").path("suspend_type").asText()))
                throw new IllegalStateException(
                        "stk_suspend recovery receipt date or route differs from its frozen window");
            receipts.add(receiptPath.toString());
            pagePaths.add(pagePath);
        }
        if (perDateFingerprints.size() != expectedDays)
            throw new IllegalStateException(
                    "stk_suspend recovery has a missing authoritative date");
        String recomputedSource =
                combineFingerprints(new ArrayList<>(perDateFingerprints.values()));
        if (!recomputedSource.equals(requiredScopeText(intent.scope(), "sourceFingerprint")))
            throw new IllegalStateException(
                    "stk_suspend recovery source fingerprint differs from the publication intent");

        String physicalAfter = tables.physicalTargetId(table, after.identity());
        String physicalBefore = parsePhysicalBefore(intent.scope());
        String readbackEvidence = "questdb-full-snapshot:" + after.fingerprint();
        var verification =
                Map.of(
                        "passed",
                        true,
                        "writerStopped",
                        true,
                        "expectedRows",
                        0,
                        "actualRows",
                        0,
                        "matchedRows",
                        0,
                        "mismatchedRows",
                        0,
                        "duplicateKeys",
                        0,
                        "missingKeys",
                        0,
                        "readbackEvidence",
                        readbackEvidence,
                        "sourceFingerprint",
                        recomputedSource);
        var publicationEvidence =
                Map.of(
                        "logicalTargetId",
                        logicalTargetId,
                        "physicalTargetBefore",
                        physicalBefore,
                        "physicalTargetAfter",
                        physicalAfter,
                        "fromInclusive",
                        scope.from().toString(),
                        "toInclusive",
                        scope.to().toString(),
                        "replacementPublished",
                        true,
                        "fullTargetFingerprint",
                        after.fingerprint(),
                        "verification",
                        verification);
        String responseEvidence = String.join(";", receipts);
        var complete =
                Map.ofEntries(
                        Map.entry("endpoint", "suspend_d"),
                        Map.entry("mode", "RECOVERED_EMPTY_PUBLICATION"),
                        Map.entry("fromInclusive", scope.from().toString()),
                        Map.entry("toInclusive", scope.to().toString()),
                        Map.entry("completedDateSlices", Math.toIntExact(expectedDays)),
                        Map.entry("sourceRows", 0),
                        Map.entry("returnedRows", 0),
                        Map.entry("submittedRows", 0),
                        Map.entry("responseEvidence", responseEvidence),
                        Map.entry("sliceReceipts", receipts),
                        Map.entry("sourceComplete", true),
                        Map.entry("complete", true),
                        Map.entry("publication", publicationEvidence));
        Files.createDirectories(evidenceRoot);
        if (dateLayout == CnTradeDateLayout.TRADE_DATE_V2) {
            StockSuspendRunRecovery.writeOrValidateCompletion(
                    completionPath,
                    complete,
                    scope.from(),
                    scope.to(),
                    0,
                    logicalTargetId,
                    physicalBefore,
                    physicalAfter,
                    recomputedSource,
                    after.fingerprint(),
                    dateLayout);
            if (alreadyComplete) return;
        } else JobDefinitionJson.mapper().writeValue(completionPath.toFile(), complete);
        for (Path pagePath : pagePaths) {
            var page =
                    (ObjectNode)
                            JobDefinitionJson.mapper()
                                    .readTree(
                                            FileEvidenceStore.readBounded(
                                                    pagePath,
                                                    StockSuspendSource.MAX_EVIDENCE_BYTES,
                                                    () ->
                                                            new IllegalStateException(
                                                                    "D011 recovery evidence escapes its run directory or exceeds its byte bound")));
            page.put("completionEvidence", completionPath.toString());
            page.set("publication", JobDefinitionJson.mapper().valueToTree(publicationEvidence));
            JobDefinitionJson.mapper().writeValue(pagePath.toFile(), page);
        }

        String runProof =
                emptyRecoveryProof(completionPath.toString(), readbackEvidence, recomputedSource);
        for (var slice : slices) {
            var current = ledger.get(slice.id());
            if (current.state() == SyncRunState.VERIFIED_EMPTY) continue;
            ledger.transition(
                    slice.id(), current.revision(), SyncRunState.VERIFIED_EMPTY, runProof);
        }
        var currentAttempt = ledger.get(attempt.id());
        if (currentAttempt.state() != SyncRunState.VERIFIED_EMPTY) {
            if (!Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT)
                    .contains(currentAttempt.state()))
                throw new IllegalStateException("stk_suspend recovery attempt is not reconcilable");
            ledger.transition(
                    attempt.id(), currentAttempt.revision(), SyncRunState.VERIFIED_EMPTY, runProof);
        }
        var currentRun = ledger.get(publicationRun);
        if (currentRun.state() != SyncRunState.VERIFIED_EMPTY) {
            if (!Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT).contains(currentRun.state()))
                throw new IllegalStateException(
                        "stk_suspend recovery run changed before completion");
            ledger.transition(
                    publicationRun, currentRun.revision(), SyncRunState.VERIFIED_EMPTY, runProof);
        }
        var lease =
                intervalLocks.findOwned(
                        publicationRun,
                        new DatasetIntervalLock.Scope("stk_suspend", scope.from(), scope.to()));
        if (lease != null) {
            if (!lease.inDoubt()) {
                intervalLocks.retainInDoubt(lease);
                lease = intervalLocks.findOwned(publicationRun, lease.scope());
            }
            intervalLocks.releaseAfterReconciliation(lease, writerStopped, true);
        }
    }

    private static String emptyRecoveryProof(
            String responseEvidence, String readbackEvidence, String sourceFingerprint)
            throws Exception {
        return JobDefinitionJson.mapper()
                .writeValueAsString(
                        Map.of(
                                "sourceComplete",
                                true,
                                "returnedRows",
                                0,
                                "submittedRows",
                                0,
                                "responseEvidence",
                                responseEvidence,
                                "verification",
                                Map.of(
                                        "writerStopped",
                                        true,
                                        "passed",
                                        true,
                                        "expectedRows",
                                        0,
                                        "actualRows",
                                        0,
                                        "matchedRows",
                                        0,
                                        "mismatchedRows",
                                        0,
                                        "duplicateKeys",
                                        0,
                                        "missingKeys",
                                        0,
                                        "readbackEvidence",
                                        readbackEvidence,
                                        "sourceFingerprint",
                                        sourceFingerprint)));
    }

    private static String combineFingerprints(List<String> values) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String value : values) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String requiredScopeText(String scope, String field) throws Exception {
        JsonNode value = JobDefinitionJson.mapper().readTree(scope).path(field);
        if (!value.isTextual() || value.asText().isBlank())
            throw new IllegalStateException("stk_suspend recovery scope lacks " + field);
        return value.asText();
    }

    private void completeTransition(
            ReferencePublicationJournal.Entry entry,
            String scopeJson,
            String physicalBefore,
            StockSuspendState.Snapshot actual,
            String physicalAfter)
            throws Exception {
        var intent = entry.intent();
        Scope scope = parseScope(scopeJson);
        if (!logicalTargetId.equals(intent.initialTarget())
                || actual.identity().id() != intent.replacementId()
                || !actual.fingerprint().equals(intent.afterFingerprint())
                || physicalBefore.equals(physicalAfter))
            throw new IllegalStateException(
                    "stk_suspend physical identity transition proof is incomplete");
        writeTransition(
                intent, scope, physicalBefore, physicalAfter, actual.identity().directory());
        var latest = journal.forRun(intent.runId());
        if (latest.state() != State.VERIFIED) journal.advance(latest, State.VERIFIED);
        markTransitionVerified(intent.runId());
    }

    private void writeTransition(
            ReferencePublicationJournal.Intent intent,
            Scope scope,
            String before,
            String after,
            String afterDirectory)
            throws Exception {
        var found = transitions.forPublication(intent.id());
        if (found.isPresent()) {
            var saved = found.get().intent();
            if (!intent.initialTarget().equals(saved.logicalTargetId())
                    || !before.equals(saved.previousPhysicalId())
                    || !after.equals(saved.nextPhysicalId()))
                throw new IllegalStateException("stk_suspend transition changed during recovery");
            return;
        }
        transitions.insertPending(
                new StockSuspendTargetTransitionStore.Intent(
                        intent.id(),
                        intent.runId(),
                        intent.initialTarget(),
                        before,
                        after,
                        intent.originalDirectory(),
                        afterDirectory,
                        scope.from().toString(),
                        scope.to().toString(),
                        intent.beforeFingerprint(),
                        intent.afterFingerprint()));
    }

    private void markTransitionVerified(String runId) throws Exception {
        transitions.markVerified(runId);
    }

    private void verifyCurrentTarget(String logical, String physical) throws Exception {
        requireNoUnresolved();
        if (!logical.equals(tables.logicalTargetId(table)))
            throw new IllegalStateException("stk_suspend logical endpoint/table identity changed");
        String prior = null;
        for (var transition : transitions.lineage(logical)) {
            var intent = transition.intent();
            if (!"VERIFIED".equals(transition.state())
                    || prior != null && !prior.equals(intent.previousPhysicalId()))
                throw new IllegalStateException(
                        "stk_suspend physical identity lineage is incomplete or discontinuous");
            var lineagePublication = journal.forRun(intent.runId());
            StockSuspendLayoutMapper.require(
                    JobDefinitionJson.mapper().readTree(lineagePublication.intent().scope()),
                    dateLayout);
            if (lineagePublication.state() != State.VERIFIED)
                throw new IllegalStateException(
                        "stk_suspend transition lacks a VERIFIED publication journal");
            prior = intent.nextPhysicalId();
        }
        if (prior != null) {
            if (!prior.equals(physical))
                throw new IllegalStateException(
                        "stk_suspend physical target changed outside its verified publication chain");
        } else {
            String lastFrozenPhysical =
                    lastVerifiedPhysical(ledgerPath, logical, dateLayout.schemaVersion());
            if (lastFrozenPhysical != null && !lastFrozenPhysical.equals(physical))
                throw new IllegalStateException(
                        "stk_suspend physical target changed without a journaled replacement transition");
        }
    }

    private static String lastVerifiedPhysical(Path ledgerPath, String logical, int version)
            throws Exception {
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        String after = null;
        long seen = 0;
        String latest = null;
        String latestAt = null;
        while (true) {
            var page = ledger.history("data.stk_suspend", after, 100);
            for (var run : page) {
                if (++seen > 10_000)
                    throw new IllegalStateException(
                            "stk_suspend physical identity history exceeds bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                .contains(run.state())
                        || !logical.equals(run.targetId())
                        || run.jobVersion() != version) continue;
                var frozen =
                        JobDefinitionJson.mapper().readTree(ledger.getRun(run.id()).frozenJson());
                var id = frozen.path("parameters").path("physicalTargetId");
                if (!id.isTextual() || !id.asText().matches("static-v2-[0-9a-f]{64}"))
                    throw new IllegalStateException(
                            "Verified stk_suspend run lacks its frozen physical identity");
                if (latestAt == null || run.updatedAt().compareTo(latestAt) > 0) {
                    latestAt = run.updatedAt();
                    latest = id.asText();
                }
            }
            if (page.size() < 100) break;
            after = page.getLast().id();
        }
        return latest;
    }

    private void requireNoUnresolved() throws Exception {
        String run = transitions.firstUnverifiedPublication();
        if (run != null)
            throw new IllegalStateException("Unresolved stk_suspend publication for run " + run);
    }

    private void requireRunLease(LocalDate from, LocalDate to) {
        var lease =
                intervalLocks.findOwned(
                        runId, new DatasetIntervalLock.Scope("stk_suspend", from, to));
        if (lease == null || lease.inDoubt())
            throw new IllegalStateException(
                    "Current stk_suspend date-window lease is absent or uncertain");
    }

    private String currentPhysicalTargetId() {
        var identity = tables.openTable(table).preflight();
        return tables.physicalTargetId(table, identity);
    }

    private StockSuspendState.Snapshot optional(String tableName) throws Exception {
        return tables.snapshotIfPresent(tableName);
    }

    private static boolean matches(
            StockSuspendState.Snapshot snapshot, long id, String directory, String fingerprint) {
        return snapshot != null
                && snapshot.identity().id() == id
                && (directory == null || snapshot.identity().directory().equals(directory))
                && snapshot.fingerprint().equals(fingerprint);
    }

    private void rename(String from, String to) {
        tables.rename(from, to);
    }

    private void markInDoubt(String run, Exception failure) {
        try {
            var current = journal.forRun(run);
            if (current.state() != State.IN_DOUBT && current.state() != State.VERIFIED)
                journal.advance(current, State.IN_DOUBT);
        } catch (Exception journalFailure) {
            failure.addSuppressed(journalFailure);
        }
    }

    private Scope parseScope(String text) throws Exception {
        JsonNode json = JobDefinitionJson.mapper().readTree(text);
        StockSuspendLayoutMapper.require(json, dateLayout);
        if (!json.path("fromInclusive").isTextual() || !json.path("toInclusive").isTextual())
            throw new IllegalStateException("stk_suspend journal scope absent");
        LocalDate from = LocalDate.parse(json.path("fromInclusive").asText()),
                to = LocalDate.parse(json.path("toInclusive").asText());
        if (from.isAfter(to)) throw new IllegalStateException("stk_suspend journal scope reversed");
        return new Scope(from, to);
    }

    private void requireRunBinding(String id, LocalDate from, LocalDate to, String physical)
            throws Exception {
        if (dateLayout == CnTradeDateLayout.LEGACY_V1) return;
        var run = SyncRunLedger.openReadOnly(ledgerPath).getRun(id);
        var frozen = JobDefinitionJson.mapper().readTree(run.frozenJson());
        if (!"data.stk_suspend".equals(run.jobId())
                || run.jobVersion() != 2
                || !logicalTargetId.equals(run.targetId())
                || !logicalTargetId.equals(frozen.path("parameters").path("targetId").asText())
                || !physical.equals(frozen.path("parameters").path("physicalTargetId").asText())
                || !from.toString().equals(frozen.path("from").asText())
                || !to.toString().equals(frozen.path("to").asText())
                || !StockSuspendSyncJobOwner.definition(2)
                        .equals(
                                JobDefinitionJson.mapper()
                                        .treeToValue(
                                                frozen.path("definition"),
                                                SyncJobDefinition.class)))
            throw new IllegalStateException("停牌发布布局或窗口与原冻结定义不同");
    }

    private void requireStageProof(ReferencePublicationJournal.Intent intent) throws Exception {
        if (dateLayout == CnTradeDateLayout.LEGACY_V1) return;
        var scope = JobDefinitionJson.mapper().readTree(intent.scope());
        Path receipt =
                Path.of(requiredScopeText(intent.scope(), "stageReceipt"))
                        .toAbsolutePath()
                        .normalize();
        if (!receipt.startsWith(evidenceRoot)
                || !Files.isRegularFile(receipt)
                || !receipt.toRealPath().startsWith(evidenceRoot.toRealPath())
                || !receipt.getFileName().toString().equals(intent.stage() + "-verified.json"))
            throw new IllegalStateException("停牌 stage 凭证越界或缺失");
        byte[] bytes =
                FileEvidenceStore.readBounded(
                        receipt,
                        StockSuspendStaging.MAX_STAGE_EVIDENCE_BYTES,
                        () -> new IllegalStateException("停牌 stage 凭证过大"));
        if (!FileEvidenceStore.sha256(bytes).equals(scope.path("stageReceiptFingerprint").asText()))
            throw new IllegalStateException("停牌原 stage 凭证摘要改变");
        StockSuspendLayoutMapper.require(JobDefinitionJson.mapper().readTree(bytes), dateLayout);
    }

    private static String parsePhysicalBefore(String text) throws Exception {
        JsonNode value = JobDefinitionJson.mapper().readTree(text).path("physicalTargetBefore");
        if (!value.isTextual() || !value.asText().matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalStateException("stk_suspend physical-before journal proof absent");
        return value.asText();
    }

    private FileLockHolder acquireRecoveryLock() throws Exception {
        Path path =
                ledgerPath.resolveSibling(
                        ledgerPath.getFileName() + ".stk_suspend-publication.lock");
        Files.createDirectories(path.getParent());
        FileChannel channel =
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                lock = null;
            }
            if (lock == null)
                throw new IllegalStateException("stk_suspend publication is currently active");
            return new FileLockHolder(channel, lock);
        } catch (Exception failure) {
            channel.close();
            throw failure;
        }
    }

    private record FileLockHolder(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("stk_suspend publication cancelled");
    }
}
