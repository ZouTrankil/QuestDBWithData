package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.IndexMonthly;
import com.zoutrankil.questdbwithdata.domain.IndexMonthlyKey;
import com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity;
import com.zoutrankil.questdbwithdata.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.questdbwithdata.repository.ReferencePublicationJournal.State;

/** Serialized two-rename D022 publication of an exact non-DEDUP full-table stage. */
public final class IndexMonthlyPublication {
    public enum Layout { ORIGINAL, OLD_MOVED, PUBLISHED, CONFLICT }
    public record Result(ReferencePublicationJournal.Entry entry, Layout layout,
                         IndexMonthlyStorage.Snapshot target, IndexMonthlyStorage.Snapshot backup) {}
    public static final class Uncertain extends Exception {
        private final String runId;
        public Uncertain(String runId, Exception cause) { super("D022 table replacement needs reconciliation: " + runId, cause); this.runId=runId; }
        public String runId() { return runId; }
    }
    /** Holds the process-wide dataset publication slot from before target cloning through rename verification. */
    public static final class Operation implements AutoCloseable {
        private final IndexMonthlyPublication owner; private final FileLockHolder holder; private boolean closed;
        private Operation(IndexMonthlyPublication owner,FileLockHolder holder){this.owner=owner;this.holder=holder;}
        @Override public synchronized void close()throws Exception{if(!closed){closed=true;holder.close();}}
    }
    private final JdbcTemplate jdbc;
    private final Path ledgerPath;
    private final ReferencePublicationJournal journal;

    public IndexMonthlyPublication(JdbcTemplate jdbc, Path ledgerPath) throws Exception {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(120);
        this.ledgerPath = Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();
        new SyncRunLedger(this.ledgerPath);
        journal = new ReferencePublicationJournal(this.ledgerPath, "index_monthly");
    }

    public Result publish(String runId, String logicalTarget, String frozenPhysicalTarget, String target,
                          IndexMonthlyStaging.Prepared prepared, IndexMonthlyStaging.Verified stage,
                          BooleanSupplier cancelled) throws Exception {
        try(Operation operation=beginOperation()) {
            return publish(operation,runId,logicalTarget,frozenPhysicalTarget,target,prepared,stage,cancelled);
        }
    }
    public Operation beginOperation() throws Exception {
        FileLockHolder lock=acquireLock();
        try { requireNoPendingPublication(); return new Operation(this,lock); }
        catch(Exception failure){lock.close();throw failure;}
    }
    /** Recovery lock may exclude exactly its own stage-only intent while still blocking every other orphan. */
    public Operation beginStageRecoveryOperation(String runId) throws Exception {
        return beginRecoveryOperation(Objects.requireNonNull(runId));
    }
    public Result publish(Operation operation,String runId,String logicalTarget,String frozenPhysicalTarget,String target,
                          IndexMonthlyStaging.Prepared prepared,IndexMonthlyStaging.Verified stage,
                          BooleanSupplier cancelled) throws Exception {
        DatasetDefinition.identifier(target); Objects.requireNonNull(cancelled);
        if(operation==null||operation.owner!=this||operation.closed)throw new IllegalArgumentException("Active D022 dataset publication lock required");
        {
            requireNoPendingPublication(runId); check(cancelled); verifyRunBinding(runId, logicalTarget, frozenPhysicalTarget, target);
            var before = new IndexMonthlyStorage(jdbc, target).snapshot();
            var staged = new IndexMonthlyStorage(jdbc, stage.table()).snapshot();
            if (!same(before, prepared.before())
                    || !IndexMonthlyStorage.physicalTargetId(jdbc, target, before.identity()).equals(frozenPhysicalTarget)
                    || !same(staged, stage.snapshot())
                    || !IndexMonthlyStorage.physicalTargetId(jdbc, stage.table(), staged.identity()).equals(stage.physicalTarget()))
                throw new IllegalStateException("D022 target or stage changed after source-window verification");
            verifySourceEvidence(runId, stage, prepared);
            String backup = IndexMonthlyJobService.ISOLATED_TABLE_PREFIX + "backup_" + UUID.randomUUID().toString().replace("-", "");
            String receiptFingerprint=sha256(Files.readAllBytes(Path.of(stage.receipt())));
            String scope = JobDefinitionJson.mapper().writeValueAsString(Map.of("code", prepared.code(),
                    "fromInclusive", prepared.from(), "toInclusive", prepared.to(), "stageDirectory", stage.snapshot().identity().directory(),
                    "sourceFingerprint", stage.sourceFingerprint(), "sourceReceipt", stage.sourceReceipt(),
                    "stageReceipt", stage.receipt(), "stageReceiptFingerprint", receiptFingerprint));
            var intent = new ReferencePublicationJournal.Intent("index-monthly-publication-" + UUID.randomUUID(),
                    "index_monthly", runId, target, backup, stage.table(), frozenPhysicalTarget,
                    before.identity().id(), before.identity().directory(), staged.identity().id(), before.fingerprint(),
                    staged.fingerprint(), scope);
            var entry = journal.create(intent);
            try {
                check(cancelled); rename(target, backup); entry = journal.advance(entry, State.OLD_MOVED);
                check(cancelled); rename(stage.table(), target); entry = journal.advance(entry, State.PUBLISHED);
                var actual = verifyPublished(entry); entry = journal.advance(entry, State.VERIFIED);
                return new Result(entry, Layout.PUBLISHED, actual.target(), actual.backup());
            } catch (Exception failure) {
                try { var current = journal.forRun(runId); if (current.state() != State.IN_DOUBT && current.state() != State.VERIFIED) journal.advance(current, State.IN_DOUBT); }
                catch (Exception journalFailure) { failure.addSuppressed(journalFailure); }
                throw new Uncertain(runId, failure);
            }
        }
    }

    /** Recovery mutates tables only after the caller proves all writers have stopped. */
    public Result finish(String runId, boolean writerStopped) throws Exception {
        if (!writerStopped) throw new IllegalStateException("D022 stopped-writer proof required before recovery");
        try (var operation = beginRecoveryOperation(runId)) {
            var entry = journal.forRun(runId); Layout layout = inspect(entry);
            verifySourceEvidence(entry);
            if (layout == Layout.CONFLICT) throw new IllegalStateException("D022 publication layout differs from its durable identities/fingerprints");
            if (entry.state() == State.VERIFIED) {
                if (layout != Layout.PUBLISHED) throw new IllegalStateException("Verified D022 publication drifted");
                var result = verifyPublished(entry); return new Result(entry, layout, result.target(), result.backup());
            }
            if (entry.state() != State.IN_DOUBT) entry = journal.advance(entry, State.IN_DOUBT);
            entry = journal.advance(entry, State.RESUMING);
            try {
                if (layout == Layout.ORIGINAL) rename(entry.intent().target(), entry.intent().backup());
                if (layout != Layout.PUBLISHED) rename(entry.intent().stage(), entry.intent().target());
                entry = journal.advance(entry, State.PUBLISHED); var actual = verifyPublished(entry);
                entry = journal.advance(entry, State.VERIFIED);
                return new Result(entry, Layout.PUBLISHED, actual.target(), actual.backup());
            } catch (Exception failure) {
                try { var current = journal.forRun(runId); if (current.state() != State.IN_DOUBT && current.state() != State.VERIFIED) journal.advance(current, State.IN_DOUBT); }
                catch (Exception journalFailure) { failure.addSuppressed(journalFailure); }
                throw new Uncertain(runId, failure);
            }
        }
    }
    private Operation beginRecoveryOperation(String exceptRun) throws Exception {
        FileLockHolder lock=acquireLock();
        try { requireNoPendingPublication(exceptRun); return new Operation(this,lock); }
        catch(Exception failure){lock.close();throw failure;}
    }

    public Optional<ReferencePublicationJournal.Entry> findForRun(String runId) throws Exception { return journal.findForRun(runId); }
    public void requireNoPendingPublication() throws Exception { requireNoPendingPublication(null); }
    private void requireNoPendingPublication(String exceptRun) throws Exception {
        try (var db = open(); var query = db.prepareStatement("SELECT run_id FROM reference_publications WHERE dataset='index_monthly' AND state<>'VERIFIED' LIMIT 32")) {
            try (var rows = query.executeQuery()) { while (rows.next()) {
                String run = rows.getString(1); if (!Objects.equals(run, exceptRun)) throw new IllegalStateException("Unresolved D022 publication requires finish: " + run);
            } }
        }
        requireNoPendingStageOnly(exceptRun);
    }
    private void requireNoPendingStageOnly(String exceptRun) throws Exception {
        Path evidenceRoot=ledgerPath.getParent().resolve("sync-evidence");
        if(!Files.exists(evidenceRoot,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(evidenceRoot)||!Files.isDirectory(evidenceRoot,LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("D022 sync-evidence root is not a regular directory");
        int scanned=0;
        try(DirectoryStream<Path> runs=Files.newDirectoryStream(evidenceRoot)) {
            for(Path runEvidence:runs) {
                if(++scanned>50_000)throw new IllegalStateException("D022 stage-intent scan exceeds 50000 run directories");
                if(Files.isSymbolicLink(runEvidence)||!Files.isDirectory(runEvidence,LinkOption.NOFOLLOW_LINKS))continue;
                if(!IndexMonthlyStaging.hasStageIntent(runEvidence))continue;
                String runId=runEvidence.getFileName().toString();
                if(Objects.equals(runId,exceptRun))continue;
                var existing=journal.findForRun(runId);
                if(existing.isPresent()&&existing.get().state()==State.VERIFIED)continue;
                throw new IllegalStateException("Unresolved D022 stage-only intent requires finishInterrupted: "+runId);
            }
        }
    }

    private Result verifyPublished(ReferencePublicationJournal.Entry entry) throws Exception {
        if (inspect(entry) != Layout.PUBLISHED) throw new IllegalStateException("D022 published target differs from stage intent");
        return new Result(entry, Layout.PUBLISHED, new IndexMonthlyStorage(jdbc, entry.intent().target()).snapshot(),
                new IndexMonthlyStorage(jdbc, entry.intent().backup()).snapshot());
    }
    private void verifySourceEvidence(String runId, IndexMonthlyStaging.Verified stage,
                                      IndexMonthlyStaging.Prepared prepared) throws Exception {
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);
        JsonNode frozen=frozenRequest(runId,run);
        if(!SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId()).equals(prepared.requestFingerprint())
                ||!run.targetId().equals(prepared.logicalTargetId())
                ||!requestScopeMatches(frozen,prepared.code(),prepared.from(),prepared.to(),prepared.observedAt(),
                prepared.logicalTargetId(),prepared.physicalTargetBefore())
                ||!runId.equals(prepared.runId())||!prepared.logicalTargetId().equals(runTarget(frozen))
                ||!prepared.logicalTargetId().equals(IndexMonthlyTargetIdentity.logical(jdbc,prepared.target())))
            throw new IllegalStateException("D022 stage source receipt differs from the complete frozen run/target request");
        requireEvidenceFiles(runId,stage.receipt(),stage.sourceReceipt(),stage.sourceFingerprint(),sha256(Files.readAllBytes(Path.of(stage.receipt()))));
        JsonNode stageProof=JobDefinitionJson.mapper().readTree(Path.of(stage.receipt()).toFile());
        requireStageProof(stageProof,prepared,stage);
        var page=IndexMonthlySource.reopen(Path.of(stage.sourceReceipt()),stage.sourceFingerprint(),prepared.code(),prepared.from(),prepared.to(),
                prepared.observedAt());
        if(!sameRows(page.rows(),stage.window().rows()))throw new IllegalStateException("D022 stage window differs from reopened raw source evidence");
    }
    private void verifySourceEvidence(ReferencePublicationJournal.Entry entry) throws Exception {
        JsonNode scope=JobDefinitionJson.mapper().readTree(entry.intent().scope());
        String receipt=required(scope,"stageReceipt"),sourceReceipt=required(scope,"sourceReceipt"),fingerprint=required(scope,"sourceFingerprint");
        requireEvidenceFiles(entry.intent().runId(),receipt,sourceReceipt,fingerprint,required(scope,"stageReceiptFingerprint"));
        JsonNode proof=JobDefinitionJson.mapper().readTree(Path.of(receipt).toFile());
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(entry.intent().runId());
        JsonNode frozen=frozenRequest(entry.intent().runId(),run);
        if(!proof.path("sourceComplete").asBoolean(false)||proof.path("dedup").asBoolean(true)
                ||!"index_monthly".equals(proof.path("dataset").asText())
                ||!fingerprint.equals(proof.path("sourceFingerprint").asText())
                ||proof.path("sourceRows").asInt(-1)!=proof.path("authoritativeWindow").path("rows").asInt(-2)
                ||!SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId()).equals(proof.path("requestFingerprint").asText())
                ||!requestScopeMatches(frozen,proof.path("code").asText(),java.time.LocalDate.parse(proof.path("windowFrom").asText()),
                java.time.LocalDate.parse(proof.path("windowTo").asText()),java.time.Instant.parse(proof.path("observedAt").asText()),
                proof.path("logicalTargetId").asText(),proof.path("physicalTargetBefore").asText())
                ||!IndexMonthlyTargetIdentity.logical(jdbc,entry.intent().target()).equals(runTarget(frozen))
                ||!entry.intent().runId().equals(proof.path("runId").asText())
                ||!entry.intent().target().equals(proof.path("target").asText())
                ||!entry.intent().stage().equals(proof.path("stage").asText())
                ||!entry.intent().initialTarget().equals(proof.path("physicalTargetBefore").asText())
                ||!required(scope,"sourceReceipt").equals(proof.path("sourceReceipt").asText()))
            throw new IllegalStateException("D022 stage receipt differs from frozen source scope");
        var page=IndexMonthlySource.reopen(Path.of(sourceReceipt),fingerprint,
                proof.path("code").asText(),java.time.LocalDate.parse(proof.path("windowFrom").asText()),
                java.time.LocalDate.parse(proof.path("windowTo").asText()),java.time.Instant.parse(proof.path("observedAt").asText()));
        String table=inspect(entry)==Layout.PUBLISHED?entry.intent().target():entry.intent().stage();
        var actual=new IndexMonthlyStorage(jdbc,table).window(proof.path("code").asText(),
                java.time.LocalDate.parse(proof.path("windowFrom").asText()),java.time.LocalDate.parse(proof.path("windowTo").asText())).rows();
        if(!sameRows(page.rows(),actual))throw new IllegalStateException("D022 recovered stage window differs from raw source receipt");
    }
    private JsonNode frozenRequest(String runId,SyncRunLedger.Run run)throws Exception {
        if(!runId.equals(run.id())||!IndexMonthlySyncJobOwner.DEFINITION.jobId().equals(run.jobId())
                ||run.jobVersion()!=IndexMonthlySyncJobOwner.DEFINITION.version())
            throw new IllegalStateException("D022 publication belongs to another run definition");
        return JobDefinitionJson.mapper().readTree(run.frozenJson());
    }
    private static String runTarget(JsonNode frozen) {
        return frozen.path("parameters").path("targetId").asText();
    }
    private static boolean requestScopeMatches(JsonNode frozen,String code,java.time.LocalDate from,java.time.LocalDate to,
                                               java.time.Instant observedAt,String logicalTarget,String physicalTarget) {
        var parameters=frozen.path("parameters");
        return frozen.path("from").asText().equals(from.toString())&&frozen.path("to").asText().equals(to.toString())
                &&parameters.path("tsCode").asText().equals(code)
                &&parameters.path("observedAt").asText().equals(observedAt.toString())
                &&parameters.path("targetId").asText().equals(logicalTarget)
                &&parameters.path("physicalTargetId").asText().equals(physicalTarget);
    }
    private static void requireStageProof(JsonNode proof,IndexMonthlyStaging.Prepared prepared,
                                          IndexMonthlyStaging.Verified stage) {
        if(!proof.path("sourceComplete").asBoolean(false)||proof.path("dedup").asBoolean(true)
                ||!prepared.runId().equals(proof.path("runId").asText())
                ||!prepared.target().equals(proof.path("target").asText())
                ||!prepared.stage().equals(proof.path("stage").asText())
                ||!prepared.logicalTargetId().equals(proof.path("logicalTargetId").asText())
                ||!prepared.requestFingerprint().equals(proof.path("requestFingerprint").asText())
                ||!prepared.physicalTargetBefore().equals(proof.path("physicalTargetBefore").asText())
                ||!prepared.stagePhysicalTarget().equals(proof.path("stagePhysicalTarget").asText())
                ||!prepared.code().equals(proof.path("code").asText())
                ||!prepared.from().toString().equals(proof.path("windowFrom").asText())
                ||!prepared.to().toString().equals(proof.path("windowTo").asText())
                ||!prepared.observedAt().toString().equals(proof.path("observedAt").asText())
                ||!prepared.sourceReceipt().equals(proof.path("sourceReceipt").asText())
                ||!prepared.sourceFingerprint().equals(proof.path("sourceFingerprint").asText())
                ||stage.sourceRows()!=prepared.sourceRows()||proof.path("sourceRows").asInt(-1)!=prepared.sourceRows())
            throw new IllegalStateException("D022 verified stage receipt differs from the frozen stage binding");
    }
    private void requireEvidenceFiles(String runId,String stageReceipt,String sourceReceipt,String sourceFingerprint,String expectedStageHash)throws Exception {
        Path runRoot=ledgerPath.getParent().resolve("sync-evidence").resolve(runId).toRealPath();
        Path stageCandidate=Path.of(stageReceipt).toAbsolutePath().normalize();
        Path sourceCandidate=Path.of(sourceReceipt).toAbsolutePath().normalize();
        if(Files.isSymbolicLink(stageCandidate)||Files.isSymbolicLink(sourceCandidate))
            throw new IllegalStateException("D022 source/stage evidence may not be symlinked");
        Path stage=stageCandidate.toRealPath();
        Path source=sourceCandidate.toRealPath();
        if(!stage.startsWith(runRoot)||!source.startsWith(runRoot)||Files.size(stage)>16L*1024*1024
                ||Files.size(source)>IndexMonthlySource.MAX_EVIDENCE_BYTES||!sha256(Files.readAllBytes(source)).equals(sourceFingerprint)
                ||!sha256(Files.readAllBytes(stage)).equals(expectedStageHash))
            throw new IllegalStateException("D022 source/stage evidence is absent, oversized, altered, or outside the run root");
    }
    private static boolean sameRows(List<IndexMonthly> expected,List<IndexMonthly> actual) {
        if(expected.size()!=actual.size())return false;var left=new HashMap<IndexMonthlyKey,byte[]>();var right=new HashMap<IndexMonthlyKey,byte[]>();
        for(var row:expected)if(left.putIfAbsent(row.key(),IndexMonthlyWritePort.CODEC.canonicalBytes(row))!=null)return false;
        for(var row:actual)if(right.putIfAbsent(row.key(),IndexMonthlyWritePort.CODEC.canonicalBytes(row))!=null)return false;
        return left.keySet().equals(right.keySet())&&left.keySet().stream().allMatch(k->Arrays.equals(left.get(k),right.get(k)));
    }
    private Layout inspect(ReferencePublicationJournal.Entry entry) throws Exception {
        var i = entry.intent(); var target = optional(i.target()); var backup = optional(i.backup()); var stage = optional(i.stage());
        if (matches(target, i.originalId(), i.originalDirectory(), i.beforeFingerprint()) && backup == null
                && matches(stage, i.replacementId(), stageDirectory(i.scope()), i.afterFingerprint())) return Layout.ORIGINAL;
        if (target == null && matches(backup, i.originalId(), i.originalDirectory(), i.beforeFingerprint())
                && matches(stage, i.replacementId(), stageDirectory(i.scope()), i.afterFingerprint())) return Layout.OLD_MOVED;
        if (matches(target, i.replacementId(), stageDirectory(i.scope()), i.afterFingerprint())
                && matches(backup, i.originalId(), i.originalDirectory(), i.beforeFingerprint()) && stage == null) return Layout.PUBLISHED;
        return Layout.CONFLICT;
    }
    private IndexMonthlyStorage.Snapshot optional(String table) throws Exception {
        if (jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table).isEmpty()) return null;
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("D022 renamed WAL unresolved; retain publication intent");
            Thread.sleep(50);
        }
        return new IndexMonthlyStorage(jdbc, table).snapshot();
    }
    private void verifyRunBinding(String runId, String logical, String physical, String table) throws Exception {
        var run = SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);
        var frozen = JobDefinitionJson.mapper().readTree(run.frozenJson());
        if (!"data.index_monthly".equals(run.jobId()) || run.jobVersion() != 1 || !logical.equals(run.targetId())
                || !logical.equals(frozen.path("parameters").path("targetId").asText())
                || !physical.equals(frozen.path("parameters").path("physicalTargetId").asText())
                || !table.startsWith(IndexMonthlyJobService.ISOLATED_TABLE_PREFIX))
            throw new IllegalStateException("D022 publication is not bound to the frozen isolated target generation");
    }
    private FileLockHolder acquireLock() throws Exception {
        Path path = ledgerPath.resolveSibling(ledgerPath.getFileName() + ".index-monthly-publication.lock");
        Files.createDirectories(path.getParent()); FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock; try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { lock = null; }
            if (lock == null) throw new IllegalStateException("Another D022 table publication is active");
            return new FileLockHolder(channel, lock);
        } catch (Exception failure) { channel.close(); throw failure; }
    }
    private record FileLockHolder(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws Exception { try { lock.release(); } finally { channel.close(); } }
    }
    private static boolean same(IndexMonthlyStorage.Snapshot a, IndexMonthlyStorage.Snapshot b) {
        return a != null && b != null && a.identity().id() == b.identity().id()
                && a.identity().directory().equals(b.identity().directory()) && IndexMonthlyStorage.sameContent(a,b);
    }
    private static boolean matches(IndexMonthlyStorage.Snapshot s, long id, String directory, String fingerprint) {
        return s != null && s.identity().id() == id && s.identity().directory().equals(directory) && s.fingerprint().equals(fingerprint);
    }
    private static String stageDirectory(String scope) throws Exception {
        JsonNode value = JobDefinitionJson.mapper().readTree(scope).path("stageDirectory");
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("D022 publication stage directory proof absent");
        return value.asText();
    }
    private static String required(JsonNode node,String field) { JsonNode value=node.path(field);if(!value.isTextual()||value.asText().isBlank())throw new IllegalStateException("D022 publication scope lacks "+field);return value.asText(); }
    private static String sha256(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void check(BooleanSupplier cancelled) { if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("D022 publication cancelled"); }
    private void rename(String from, String to) { DatasetDefinition.identifier(from); DatasetDefinition.identifier(to); jdbc.execute("RENAME TABLE \""+from+"\" TO \""+to+"\""); }
    private Connection open() throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite:" + ledgerPath);
        try (var statement = db.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); statement.execute("PRAGMA busy_timeout=5000"); }
        return db;
    }
}
