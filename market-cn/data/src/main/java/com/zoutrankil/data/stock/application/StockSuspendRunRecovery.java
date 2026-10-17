package com.zoutrankil.data.stock.application;
import com.zoutrankil.data.stock.domain.StockSuspendState;
import com.zoutrankil.data.stock.port.StockSuspendTarget;


import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.mapper.StockSuspendMapper;
import com.zoutrankil.data.stock.storage.StockSuspendStaging;
import com.zoutrankil.data.repository.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Closes a published nonempty D011 run from immutable source receipts and a full-table readback. */
public final class StockSuspendRunRecovery {
    public record Result(String runId, SyncRunState state, int sourceRows, String completionEvidence,
                         String fullTargetFingerprint) {}

    private record Slice(SyncRunLedger.Entry entry, JsonNode fetched, LocalDate date,
                         Path pagePath, Path sourcePath, String sourceFingerprint,
                         List<StockSuspend> rows) {}

    private StockSuspendRunRecovery() {}

    /**
     * Finish only an interrupted, already-published nonempty replacement. Publication.finish repairs
     * the rename/journal first; this method then proves every daily source receipt against the full
     * replacement snapshot, closes active ledger entries and releases only this run's interval lease.
     */
    public static Result finishInterrupted(StockSuspendTarget target, Path ledgerPath,
                                            String runId, boolean writerStopped) throws Exception {
        if (!writerStopped) throw new IllegalStateException("Stopped stk_suspend writer proof required");
        Objects.requireNonNull(target); Objects.requireNonNull(ledgerPath); Objects.requireNonNull(runId);
        String table = target.tableName();
        requireIsolatedTableName(table);
        ledgerPath = ledgerPath.toAbsolutePath().normalize();
        var ledger = new SyncRunLedger(ledgerPath);
        var run = ledger.getRun(runId);
        var rootEntry = ledger.get(runId);
        if (!"data.stk_suspend".equals(run.jobId()) || run.jobVersion() != StockSuspendSyncJobOwner.DEFINITION.version()
                || !Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT).contains(rootEntry.state()))
            throw new IllegalStateException("Only an active or uncertain D011 run can be reconciled");

        JsonNode frozen = JobDefinitionJson.mapper().readTree(run.frozenJson());
        SyncJobDefinition frozenDefinition = JobDefinitionJson.mapper().treeToValue(
                frozen.path("definition"), SyncJobDefinition.class);
        if (!StockSuspendSyncJobOwner.DEFINITION.equals(frozenDefinition)
                || !run.targetId().equals(frozen.path("parameters").path("targetId").asText())) {
            throw new IllegalStateException("Frozen D011 definition or logical target differs from the run ledger");
        }
        String logicalTarget = frozen.path("parameters").path("targetId").asText();
        String physicalBefore = frozen.path("parameters").path("physicalTargetId").asText();
        if (!logicalTarget.matches("static-v2-[0-9a-f]{64}")
                || !physicalBefore.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalStateException("Frozen D011 logical/physical target identity is malformed");
        if (!frozen.path("from").isTextual() || !frozen.path("to").isTextual())
            throw new IllegalStateException("Frozen D011 recovery interval is absent");
        LocalDate from = LocalDate.parse(frozen.path("from").asText());
        LocalDate to = LocalDate.parse(frozen.path("to").asText());
        if (!frozen.path("logicalDate").isTextual())
            throw new IllegalStateException("Frozen D011 logical date is absent");
        LocalDate logicalDate = LocalDate.parse(frozen.path("logicalDate").asText());
        if (!run.logicalDate().equals(logicalDate.toString()))
            throw new IllegalStateException("D011 run logical date differs from its frozen request");
        long expectedDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (from.isAfter(to) || expectedDays < 1 || expectedDays > StockSuspendSyncJobOwner.MAX_WINDOW_DAYS
                || to.isAfter(logicalDate)
                || !StockSuspendSyncJobOwner.DEFINITION.supportedModes().stream()
                .anyMatch(mode -> mode.name().equals(frozen.path("mode").asText())))
            throw new IllegalStateException("Frozen D011 recovery interval is outside its bounded contract");

        var journal = new ReferencePublicationJournal(ledgerPath, "stk_suspend");
        var initial = journal.forRun(runId);
        var intent = initial.intent();
        if (!"stk_suspend".equals(intent.dataset()) || !runId.equals(intent.runId())
                || !table.equals(intent.target()) || !logicalTarget.equals(intent.initialTarget()))
            throw new IllegalStateException("D011 publication intent differs from the frozen run/target");
        if (!logicalTarget.equals(target.targetId()))
            throw new IllegalStateException("D011 frozen logical target differs from the configured PGWire endpoint/table");
        JsonNode scope = JobDefinitionJson.mapper().readTree(intent.scope());
        if (!logicalTarget.equals(scope.path("logicalTargetId").asText())
                || !physicalBefore.equals(scope.path("physicalTargetBefore").asText())
                || !from.toString().equals(scope.path("fromInclusive").asText())
                || !to.toString().equals(scope.path("toInclusive").asText()))
            throw new IllegalStateException("D011 publication scope differs from the frozen request");

        Path runEvidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId)
                .toAbsolutePath().normalize();
        Path realEvidence = runEvidence.toRealPath();
        Path completionPath = resolveForCreateUnder(runEvidence, realEvidence, requiredText(scope, "completionEvidence"),
                "complete-" + runId + ".json");
        Path stageReceipt = resolveUnder(runEvidence, realEvidence, requiredText(scope, "stageReceipt"),
                intent.stage()+"-verified.json", StockSuspendStaging.MAX_STAGE_EVIDENCE_BYTES);

        var stageProof = readStageProof(stageReceipt, intent, table, from, to);
        var publication = new StockSuspendPublication(target.newPublicationTables(), ledgerPath, runEvidence, table, logicalTarget, runId);
        StockSuspendPublication.Layout layout = publication.inspect(runId);
        if (layout == StockSuspendPublication.Layout.CONFLICT)
            throw new IllegalStateException("D011 target/backup/stage layout conflicts with the publication journal");

        // Validate every source receipt and the complete intended stage before finish() can rename
        // any table. The original target is still named target in ORIGINAL layout; after the first
        // rename its exact frozen generation is available under the journaled backup name.
        var slices = readSlices(ledger, runId, runEvidence, realEvidence, from, to);
        var byDate = new TreeMap<LocalDate, Slice>();
        var sourceRows = new ArrayList<StockSuspend>();
        int totalRows = 0;
        for (Slice slice : slices) {
            if (byDate.putIfAbsent(slice.date(), slice) != null)
                throw new IllegalStateException("Duplicate D011 source date receipt");
            totalRows = Math.addExact(totalRows, slice.rows().size());
            sourceRows.addAll(slice.rows());
        }
        if (byDate.size() != expectedDays || !byDate.firstKey().equals(from) || !byDate.lastKey().equals(to))
            throw new IllegalStateException("D011 receipts do not cover every frozen calendar date");
        String attemptId = uniqueAttempt(ledger, runId);
        var attemptState = ledger.get(attemptId).state();
        Set<SyncRunState> recoverableAttempts = totalRows == 0
                ? Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT, SyncRunState.VERIFIED_EMPTY)
                : Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT, SyncRunState.VERIFIED);
        if (!recoverableAttempts.contains(attemptState))
            throw new IllegalStateException("D011 attempt state cannot be reconciled from its published snapshot");
        String sourceFingerprint = combineFingerprints(byDate.values().stream().map(Slice::sourceFingerprint).toList());
        if (!sourceFingerprint.equals(requiredText(scope, "sourceFingerprint"))
                || stageProof.path("sourceRows").asInt(-1) != totalRows)
            throw new IllegalStateException("D011 raw receipts differ from the publication source fingerprint/row count");

        String beforeTable = layout == StockSuspendPublication.Layout.ORIGINAL ? intent.target() : intent.backup();
        var before = target.openTable(beforeTable).snapshot();
        if (before.identity().id() != intent.originalId()
                || !before.identity().directory().equals(intent.originalDirectory())
                || !before.fingerprint().equals(intent.beforeFingerprint())
                || !target.physicalTargetId(table, before.identity()).equals(physicalBefore))
            throw new IllegalStateException("D011 publication backup no longer proves the frozen physical target");
        var prepared = target.prepare(before, before,
                StockSuspendState.sortedUnique(sourceRows), from, to);
        if (!stageProof.path("actual").isObject()
                || stageProof.path("preservedOutsideRows").asInt(-1)
                != StockSuspendState.outside(before.rows(), from, to.plusDays(1)).size())
            throw new IllegalStateException("D011 stage receipt differs from source plus preserved outside rows");
        StockSuspendState.Snapshot staged = JobDefinitionJson.mapper().treeToValue(
                stageProof.path("actual"), StockSuspendState.Snapshot.class);
        if (staged.identity().id() != intent.replacementId() || !staged.rows().equals(prepared.expected())
                || !staged.fingerprint().equals(intent.afterFingerprint()))
            throw new IllegalStateException("D011 verified stage differs from receipt-backed source and outside rows");

        // All immutable receipts and the verified stage are now proven before any recovery rename.
        StockSuspendPublication.finish(ledgerPath, target, runId, true);
        var after = target.openTable(table).snapshot();
        if (after.identity().id() != intent.replacementId() || !after.rows().equals(prepared.expected())
                || !after.fingerprint().equals(intent.afterFingerprint()) || staged.bytes() != after.bytes())
            throw new IllegalStateException("Published D011 target differs from receipt-backed full snapshot");
        String physicalAfter = target.physicalTargetId(table, after.identity());
        if (!StockSuspendPublication.authorizesResume(ledgerPath, runId, logicalTarget, physicalBefore, physicalAfter))
            throw new IllegalStateException("D011 physical target generation transition lacks verified journal lineage");

        var currentRoot = ledger.get(runId);
        if (totalRows == 0) {
            if (currentRoot.state() != SyncRunState.VERIFIED_EMPTY)
                throw new IllegalStateException("D011 all-empty publication did not produce its strict VERIFIED_EMPTY proof");
            return new Result(runId, currentRoot.state(), 0, completionPath.toString(), after.fingerprint());
        }
        if (!Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT).contains(currentRoot.state()))
            throw new IllegalStateException("D011 published run became terminal before nonempty recovery");

        String readbackEvidence = "questdb-full-snapshot:" + after.fingerprint();
        var verification = Map.of("passed", true, "writerStopped", true,
                "expectedRows", totalRows, "actualRows", totalRows, "matchedRows", totalRows,
                "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0,
                "readbackEvidence", readbackEvidence, "sourceFingerprint", sourceFingerprint);
        var publicationEvidence = Map.of("logicalTargetId", logicalTarget,
                "physicalTargetBefore", physicalBefore, "physicalTargetAfter", physicalAfter,
                "fromInclusive", from.toString(), "toInclusive", to.toString(),
                "replacementPublished", true, "fullTargetFingerprint", after.fingerprint(),
                "verification", verification);
        var sourceReceiptPaths = byDate.values().stream().map(slice -> slice.sourcePath().toString()).toList();
        var complete = Map.ofEntries(Map.entry("endpoint", "suspend_d"),
                Map.entry("mode", frozen.path("mode").asText()), Map.entry("fromInclusive", from.toString()),
                Map.entry("toInclusive", to.toString()), Map.entry("completedDateSlices", Math.toIntExact(expectedDays)),
                Map.entry("sourceRows", totalRows), Map.entry("returnedRows", totalRows),
                Map.entry("submittedRows", totalRows), Map.entry("responseEvidence", String.join(";", sourceReceiptPaths)),
                Map.entry("sliceReceipts", sourceReceiptPaths), Map.entry("sourceComplete", true),
                Map.entry("complete", true), Map.entry("publication", publicationEvidence));
        writeOrValidateCompletion(completionPath, complete, from, to, totalRows, logicalTarget,
                physicalBefore, physicalAfter, sourceFingerprint, after.fingerprint());
        for (Slice slice : slices) attachPublication(slice.pagePath(), completionPath, publicationEvidence);

        var rootProof = new LinkedHashMap<String, Object>();
        rootProof.put("sourceComplete", true); rootProof.put("returnedRows", totalRows);
        rootProof.put("submittedRows", totalRows); rootProof.put("responseEvidence", completionPath.toString());
        rootProof.put("verification", verification); rootProof.put("checkpoint", after.fingerprint());
        rootProof.put("fullTargetFingerprint", after.fingerprint()); rootProof.put("publication", publicationEvidence);
        rootProof.put("completionEvidence", completionPath.toString());
        String proofJson = JobDefinitionJson.mapper().writeValueAsString(rootProof);

        for (Slice slice : slices) {
            SyncRunState desired = slice.rows().isEmpty() ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
            finishSlice(ledger, slice, desired, completionPath.toString(), readbackEvidence, true);
        }
        finishActiveEntry(ledger, attemptId, SyncRunState.VERIFIED, proofJson);
        finishActiveEntry(ledger, runId, SyncRunState.VERIFIED, proofJson);

        var locks = new DatasetIntervalLock(ledgerPath);
        var lease = locks.findOwned(runId, new DatasetIntervalLock.Scope("stk_suspend", from, to));
        if (lease != null) {
            if (lease.inDoubt()) locks.releaseAfterReconciliation(lease, true, true);
            else locks.releaseVerified(lease);
        }
        return new Result(runId, SyncRunState.VERIFIED, totalRows, completionPath.toString(), after.fingerprint());
    }

    private static List<Slice> readSlices(SyncRunLedger ledger, String runId, Path runEvidence,
                                          Path realEvidence, LocalDate from, LocalDate to) throws Exception {
        var entries = ledgerEntries(ledger, runId);
        var attempts = entries.stream().filter(entry -> entry.kind() == SyncRunLedger.Kind.ATTEMPT
                && runId.equals(entry.parentId())).toList();
        if (attempts.size() != 1) throw new IllegalStateException("D011 recovery requires one owned attempt");
        var result = new ArrayList<Slice>();
        var seen = new HashSet<LocalDate>();
        var mapper = new StockSuspendMapper();
        for (var entry : entries) {
            if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
            if (!attempts.getFirst().id().equals(entry.parentId()))
                throw new IllegalStateException("D011 slice is not owned by its run attempt");
            var fetchedEvents = ledger.events(entry.id(), -1, 100).stream()
                    .filter(event -> event.state() == SyncRunState.FETCHED).toList();
            if (fetchedEvents.size() != 1) throw new IllegalStateException("D011 slice lacks one immutable FETCHED receipt");
            JsonNode fetched = JobDefinitionJson.mapper().readTree(fetchedEvents.getFirst().payloadJson());
            if (!fetched.path("sourceFingerprint").isTextual() || !fetched.path("responseEvidence").isTextual())
                throw new IllegalStateException("D011 FETCHED event lacks a source receipt reference");
            Path page = resolveUnder(runEvidence, realEvidence, fetched.path("responseEvidence").asText(), null);
            if (!page.getFileName().toString().matches("page-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json"))
                throw new IllegalStateException("D011 FETCHED event does not reference a daily page receipt");
            JsonNode pageJson = JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(page, StockSuspendSource.MAX_EVIDENCE_BYTES, () -> new IllegalStateException("D011 recovery evidence escapes its run directory or exceeds its byte bound")));
            if (!"stk_suspend_daily_source".equals(pageJson.path("evidenceType").asText())
                    || !"suspend_d".equals(pageJson.path("endpoint").asText())
                    || !pageJson.path("sourceComplete").asBoolean(false)
                    || !fetched.path("sourceFingerprint").asText().equals(pageJson.path("sourceFingerprint").asText())
                    || !pageJson.path("sourceReceipt").isTextual())
                throw new IllegalStateException("D011 daily page receipt differs from its FETCHED event");
            LocalDate date = LocalDate.parse(pageJson.path("tradeDate").asText());
            if (date.isBefore(from) || date.isAfter(to) || !seen.add(date)
                    || !page.getFileName().toString().equals("page-" + date + ".json"))
                throw new IllegalStateException("D011 page receipt date is duplicate or outside frozen interval");
            Path raw = resolveUnder(runEvidence.resolve("source"), realEvidence.resolve("source"),
                    pageJson.path("sourceReceipt").asText(), null);
            byte[] bytes = FileEvidenceStore.readBounded(raw, StockSuspendSource.MAX_EVIDENCE_BYTES, () -> new IllegalStateException("D011 recovery evidence escapes its run directory or exceeds its byte bound"));
            String fingerprint = sha256(bytes);
            if (!fingerprint.equals(fetched.path("sourceFingerprint").asText())
                    || fetched.path("returnedRows").asInt(-1) < 0)
                throw new IllegalStateException("D011 raw source receipt digest/row count differs from FETCHED event");
            JsonNode receipt = JobDefinitionJson.mapper().readTree(bytes);
            List<String> declaredFields = new ArrayList<>();
            if (receipt.path("fields").isArray()) receipt.path("fields").forEach(node -> declaredFields.add(node.asText()));
            if (!"suspend_d".equals(receipt.path("endpoint").asText())
                    || !receipt.path("sourceComplete").asBoolean(false)
                    || !StockSuspendMapper.SOURCE_FIELDS.equals(declaredFields)
                    || receipt.path("sourceRowCap").asInt(-1) != StockSuspendSource.SOURCE_ROW_CAP
                    || receipt.path("pages").asInt(-1) != 1
                    || !receipt.path("rows").isArray() || !receipt.path("normalizedRows").isArray()
                    || receipt.path("rows").size() != receipt.path("normalizedRows").size()
                    || receipt.path("rows").size() != receipt.path("returnedRows").asInt(-1)
                    || receipt.path("returnedRows").asInt(-1) != fetched.path("returnedRows").asInt(-2)
                    || receipt.path("returnedRows").asInt(-1) >= StockSuspendSource.SOURCE_ROW_CAP
                    || !date.toString().equals(receipt.path("tradeDate").asText())
                    || !date.format(DateTimeFormatter.BASIC_ISO_DATE).equals(receipt.path("parameters").path("trade_date").asText())
                    || !"S".equals(receipt.path("parameters").path("suspend_type").asText()))
                throw new IllegalStateException("D011 raw receipt is incomplete, capped or outside its daily source contract");
            var rows = new ArrayList<StockSuspend>();
            var keys = new HashSet<StockSuspendKey>();
            String previousCode = null;
            for (int index = 0; index < receipt.path("rows").size(); index++) {
                JsonNode rawRow = receipt.path("rows").get(index);
                var source = new LinkedHashMap<String, JsonNode>();
                rawRow.fields().forEachRemaining(field -> source.put(field.getKey(), field.getValue()));
                if (!source.keySet().containsAll(StockSuspendMapper.SOURCE_FIELDS))
                    throw new IllegalStateException("D011 raw row omits a declared source field");
                var dto = mapper.fromSource(source);
                var normalized = mapper.fromSource(dto);
                if (!date.equals(normalized.tradeDate()) || !keys.add(normalized.key())
                        || previousCode != null && previousCode.compareTo(normalized.tsCode()) >= 0)
                    throw new IllegalStateException("D011 raw rows contain duplicate, unsorted or out-of-scope keys");
                previousCode = normalized.tsCode();
                JsonNode norm = receipt.path("normalizedRows").get(index);
                if (!normalized.tsCode().equals(norm.path("ts_code").asText())
                        || !date.toString().equals(norm.path("trade_date").asText())
                        || norm.path("is_suspended").asLong(-1) != 1L
                        || dto.suspendTiming() == null && !norm.path("suspend_timing").isNull()
                        || dto.suspendTiming() != null && !dto.suspendTiming().equals(norm.path("suspend_timing").asText(null)))
                    throw new IllegalStateException("D011 normalized receipt differs from its source row mapping");
                rows.add(normalized);
            }
            SyncRunState desired = rows.isEmpty() ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
            if (Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(entry.state())
                    && entry.state() != desired)
                throw new IllegalStateException("D011 terminal slice state disagrees with its authoritative source receipt");
            if (entry.state().terminal() && !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(entry.state()))
                throw new IllegalStateException("D011 terminal slice cannot be reconciled from a publication");
            Set<SyncRunState> recoverable = desired == SyncRunState.VERIFIED
                    ? Set.of(SyncRunState.RUNNING, SyncRunState.FETCHED, SyncRunState.VALIDATED,
                            SyncRunState.SUBMITTED, SyncRunState.ACKNOWLEDGED, SyncRunState.IN_DOUBT, SyncRunState.VERIFIED)
                    : Set.of(SyncRunState.RUNNING, SyncRunState.FETCHED, SyncRunState.IN_DOUBT, SyncRunState.VERIFIED_EMPTY);
            if (!recoverable.contains(entry.state()))
                throw new IllegalStateException("D011 slice state is not recoverable from publication and source receipt");
            result.add(new Slice(entry, fetched, date, page, raw, fingerprint, List.copyOf(rows)));
        }
        return List.copyOf(result);
    }

    private static JsonNode readStageProof(Path stageReceipt, ReferencePublicationJournal.Intent intent,
                                           String table, LocalDate from, LocalDate to) throws Exception {
        if (!intent.stage().matches("java_stk_suspend_stage_[0-9a-f]{32}"))
            throw new IllegalStateException("D011 publication stage name is malformed");
        JsonNode proof = JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(stageReceipt,
                StockSuspendStaging.MAX_STAGE_EVIDENCE_BYTES,
                () -> new IllegalStateException("D011 recovery evidence escapes its run directory or exceeds its byte bound")));
        if (!table.equals(proof.path("target").asText()) || !intent.stage().equals(proof.path("stage").asText())
                || !from.toString().equals(proof.path("windowFrom").asText())
                || !to.toString().equals(proof.path("windowTo").asText())
                || proof.path("sourceRows").asInt(-1) < 0 || !proof.path("actual").isObject())
            throw new IllegalStateException("D011 verified stage receipt differs from publication intent");
        return proof;
    }

    private static Path resolveUnder(Path expectedRoot, Path realRoot, String rawPath, String expectedName) throws Exception {
        return resolveUnder(expectedRoot, realRoot, rawPath, expectedName, StockSuspendSource.MAX_EVIDENCE_BYTES);
    }

    private static Path resolveUnder(Path expectedRoot, Path realRoot, String rawPath, String expectedName,
                                     int maxBytes) throws Exception {
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        if (!candidate.startsWith(expectedRoot.toAbsolutePath().normalize()) || !Files.isRegularFile(candidate))
            throw new IllegalStateException("D011 recovery evidence is absent or outside its run directory");
        Path real = candidate.toRealPath();
        if (!real.startsWith(realRoot) || Files.size(real) > maxBytes)
            throw new IllegalStateException("D011 recovery evidence escapes its run directory or exceeds its byte bound");
        if (expectedName != null && !expectedName.equals(real.getFileName().toString()))
            throw new IllegalStateException("D011 recovery evidence path has an unexpected stable name");
        return real;
    }

    private static Path resolveForCreateUnder(Path expectedRoot, Path realRoot, String rawPath,
                                              String expectedName) throws Exception {
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        if (!candidate.startsWith(expectedRoot.toAbsolutePath().normalize())
                || !expectedName.equals(candidate.getFileName().toString()))
            throw new IllegalStateException("D011 completion path is outside its run evidence directory");
        Path parent = candidate.getParent().toRealPath();
        if (!parent.startsWith(realRoot)) throw new IllegalStateException("D011 completion parent escapes its run directory");
        if (Files.exists(candidate)) {
            Path real = candidate.toRealPath();
            if (!real.startsWith(realRoot) || Files.size(real) > StockSuspendSource.MAX_EVIDENCE_BYTES)
                throw new IllegalStateException("D011 existing completion evidence escapes its run directory or exceeds its byte bound");
            return real;
        }
        return candidate;
    }

    private static void writeOrValidateCompletion(Path path, Map<String, ?> complete,
                                                  LocalDate from, LocalDate to, int rows,
                                                  String logicalTarget, String physicalBefore,
                                                  String physicalAfter, String sourceFingerprint,
                                                  String targetFingerprint) throws Exception {
        var json = JobDefinitionJson.mapper();
        if (Files.exists(path)) {
            JsonNode old = json.readTree(FileEvidenceStore.readBounded(path, StockSuspendSource.MAX_EVIDENCE_BYTES, () -> new IllegalStateException("D011 recovery evidence escapes its run directory or exceeds its byte bound")));
            JsonNode publication = old.path("publication");
            if (!"suspend_d".equals(old.path("endpoint").asText()) || !old.path("complete").asBoolean(false)
                    || !old.path("sourceComplete").asBoolean(false)
                    || !from.toString().equals(old.path("fromInclusive").asText())
                    || !to.toString().equals(old.path("toInclusive").asText())
                    || old.path("sourceRows").asInt(-1) != rows
                    || old.path("returnedRows").asInt(-1) != rows
                    || old.path("submittedRows").asInt(-1) != rows
                    || old.path("completedDateSlices").asInt(-1) != ChronoUnit.DAYS.between(from, to) + 1
                    || !logicalTarget.equals(publication.path("logicalTargetId").asText())
                    || !physicalBefore.equals(publication.path("physicalTargetBefore").asText())
                    || !physicalAfter.equals(publication.path("physicalTargetAfter").asText())
                    || !from.toString().equals(publication.path("fromInclusive").asText())
                    || !to.toString().equals(publication.path("toInclusive").asText())
                    || !targetFingerprint.equals(publication.path("fullTargetFingerprint").asText())
                    || !publication.path("replacementPublished").asBoolean(false)
                    || !publication.path("verification").path("passed").asBoolean(false)
                    || !publication.path("verification").path("writerStopped").asBoolean(false)
                    || publication.path("verification").path("expectedRows").asInt(-1) != rows
                    || publication.path("verification").path("actualRows").asInt(-1) != rows
                    || publication.path("verification").path("matchedRows").asInt(-1) != rows
                    || publication.path("verification").path("mismatchedRows").asInt(-1) != 0
                    || publication.path("verification").path("duplicateKeys").asInt(-1) != 0
                    || publication.path("verification").path("missingKeys").asInt(-1) != 0
                    || !sourceFingerprint.equals(publication.path("verification").path("sourceFingerprint").asText())
                    || publication.path("verification").path("readbackEvidence").asText().isBlank())
                throw new IllegalStateException("Existing D011 completion receipt conflicts with the recovered publication");
            return;
        }
        Files.createDirectories(path.getParent());
        FileEvidenceStore.writeNew(path,json.writeValueAsBytes(complete));
    }

    private static void attachPublication(Path pagePath, Path completionPath,
                                          Map<String, ?> publication) throws Exception {
        ObjectNode page = (ObjectNode) JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(pagePath, StockSuspendSource.MAX_EVIDENCE_BYTES, () -> new IllegalStateException("D011 recovery evidence escapes its run directory or exceeds its byte bound")));
        if (page.has("completionEvidence") && !completionPath.toString().equals(page.path("completionEvidence").asText()))
            throw new IllegalStateException("D011 page receipt already references another completion");
        page.put("completionEvidence", completionPath.toString());
        page.set("publication", JobDefinitionJson.mapper().valueToTree(publication));
        JobDefinitionJson.mapper().writeValue(pagePath.toFile(), page);
    }

    private static void finishSlice(SyncRunLedger ledger, Slice slice, SyncRunState desired,
                                    String completionEvidence, String readbackEvidence,
                                    boolean writerStopped) throws Exception {
        SyncRunLedger.Entry current = ledger.get(slice.entry().id());
        if (current.state() == desired) return;
        if (current.state().terminal()) throw new IllegalStateException("Terminal D011 slice cannot be reconciled");
        if (desired == SyncRunState.VERIFIED_EMPTY && !slice.rows().isEmpty()
                || desired == SyncRunState.VERIFIED && slice.rows().isEmpty())
            throw new IllegalArgumentException("D011 slice state disagrees with receipt row count");
        while (true) {
            SyncRunState state = current.state();
            if (state == desired) return;
            if (desired == SyncRunState.VERIFIED && state == SyncRunState.FETCHED) {
                var source = new LinkedHashMap<String, Object>();
                source.put("returnedRows", slice.rows().size());
                source.put("sourceFingerprint", slice.sourceFingerprint());
                source.put("responseEvidence", slice.pagePath().toString());
                source.put("cursor", slice.date().toString());
                ledger.transition(current.id(), current.revision(), SyncRunState.VALIDATED,
                        JobDefinitionJson.mapper().writeValueAsString(source));
            } else if (desired == SyncRunState.VERIFIED && state == SyncRunState.SUBMITTED) {
                ledger.transition(current.id(), current.revision(), SyncRunState.ACKNOWLEDGED,
                        JobDefinitionJson.mapper().writeValueAsString(
                                sliceProof(slice, desired, completionEvidence, readbackEvidence, writerStopped)));
            } else if (state == SyncRunState.IN_DOUBT
                    || desired == SyncRunState.VERIFIED && (state == SyncRunState.RUNNING
                    || state == SyncRunState.VALIDATED || state == SyncRunState.ACKNOWLEDGED)
                    || desired == SyncRunState.VERIFIED_EMPTY && (state == SyncRunState.RUNNING
                    || state == SyncRunState.FETCHED)) {
                ledger.transition(current.id(), current.revision(), desired,
                        JobDefinitionJson.mapper().writeValueAsString(
                                sliceProof(slice, desired, completionEvidence, readbackEvidence, writerStopped)));
            } else {
                throw new IllegalStateException("D011 slice state cannot be reconciled from publication: " + state);
            }
            current = ledger.get(slice.entry().id());
        }
    }

    private static Map<String, Object> sliceProof(Slice slice, SyncRunState desired,
                                                   String completionEvidence, String readbackEvidence,
                                                   boolean writerStopped) {
        int rows = slice.rows().size();
        var proof = new LinkedHashMap<String, Object>();
        proof.put("sourceComplete", true); proof.put("returnedRows", rows); proof.put("submittedRows", rows);
        proof.put("responseEvidence", slice.pagePath().toString()); proof.put("completionEvidence", completionEvidence);
        proof.put("verification", Map.of("passed", true, "writerStopped", writerStopped,
                "expectedRows", rows, "actualRows", rows, "matchedRows", rows,
                "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0,
                "readbackEvidence", readbackEvidence, "sourceFingerprint", slice.sourceFingerprint()));
        if (desired == SyncRunState.VERIFIED_EMPTY && rows != 0)
            throw new IllegalArgumentException("Nonempty D011 receipt cannot be verified empty");
        return proof;
    }

    private static void finishActiveEntry(SyncRunLedger ledger, String id, SyncRunState desired,
                                          String proof) throws Exception {
        var current = ledger.get(id);
        if (current.state() == desired) return;
        if (!Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT).contains(current.state()))
            throw new IllegalStateException("D011 attempt/run is not reconcilable from publication");
        ledger.transition(id, current.revision(), desired, proof);
    }

    private static String uniqueAttempt(SyncRunLedger ledger, String runId) throws Exception {
        var matches = ledgerEntries(ledger, runId).stream().filter(entry -> entry.kind() == SyncRunLedger.Kind.ATTEMPT
                && runId.equals(entry.parentId())).toList();
        if (matches.size() != 1) throw new IllegalStateException("D011 recovery requires exactly one attempt");
        return matches.getFirst().id();
    }

    private static List<SyncRunLedger.Entry> ledgerEntries(SyncRunLedger ledger, String runId) throws Exception {
        var result = new ArrayList<SyncRunLedger.Entry>();
        String cursor = null;
        while (true) {
            var page = ledger.entries(runId, cursor, 1000);
            result.addAll(page);
            if (page.size() < 1000) return List.copyOf(result);
            cursor = page.getLast().id();
        }
    }

    private static String combineFingerprints(List<String> values) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String value : values) {
            digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return FileEvidenceStore.sha256(bytes);
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank())
            throw new IllegalStateException("D011 recovery evidence lacks " + field);
        return value.asText();
    }

    private static void requireIsolatedTableName(String table) {
        StockSuspendJobService.requireAdmittedTableName(table);
    }
}
