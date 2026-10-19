package com.zoutrankil.data.index.application;

import com.zoutrankil.data.index.port.ThsMemberTarget;

import com.zoutrankil.data.index.domain.ThsMemberState;


import com.zoutrankil.data.index.mapper.ThsMemberMapper;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.util.*;

/** Stopped-writer completion from a frozen source receipt and exact target/stage identities. */
public final class ThsMemberRunRecovery {
    private ThsMemberRunRecovery() {}

    public static ThsMemberJobService.Result finish(ThsMemberTarget targetAccess, Path ledgerPath, String table,
                                                     String runId, boolean writerStopped) throws Exception {
        if (!writerStopped) throw new IllegalStateException("Stopped THS member writer proof required");
        DatasetDefinition.identifier(table);
        ledgerPath = ledgerPath.toAbsolutePath().normalize();
        var ledger = new SyncRunLedger(ledgerPath);
        var run = ledger.getRun(runId);
        if (!Set.of("data.ths_member", "write.ths_member").contains(run.jobId()) || run.jobVersion() != 1
                || !Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT).contains(ledger.get(runId).state()))
            throw new IllegalStateException("Expected unfinished THS member owner run");
        var locks = new DatasetIntervalLock(ledgerPath);
        var lease = locks.findOwned(runId, DatasetIntervalLock.Scope.allDates("ths_member"));
        if (lease == null) throw new IllegalStateException("Retained THS member lease required");
        Path folder = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        Path preparedPath = folder.resolve("prepared.json");
        if (Files.size(preparedPath) > 32L * 1024 * 1024)
            throw new IllegalStateException("THS member preparation exceeds bound");
        var json = JobDefinitionJson.mapper();
        var frozen = json.readTree(FileEvidenceStore.readBounded(preparedPath, 32 * 1024 * 1024, () -> new IllegalStateException("THS member preparation exceeds bound")));
        if (!runId.equals(frozen.path("runId").asText())
                || !run.targetId().equals(frozen.path("targetId").asText())
                || !run.frozenJson().equals(frozen.path("request").asText()))
            throw new IllegalStateException("Frozen THS member preparation differs from run authority");
        var request = json.readTree(run.frozenJson());
        boolean preparedWrite = run.jobId().equals("write.ths_member");
        if (preparedWrite) {
            if (!request.path("definition").path("datasetId").asText().equals("ths_member")
                    || !request.path("parameters").path("payloadFingerprint").asText().matches("[0-9a-f]{64}"))
                throw new IllegalStateException("Prepared THS member definition changed");
        } else if (!ThsMemberJobService.definition().equals(
                json.treeToValue(request.path("definition"), SyncJobDefinition.class)))
            throw new IllegalStateException("THS member job definition changed");
        String board = request.path("parameters").path("board_code").asText();
        if ((!preparedWrite && request.path("parameters").size() != 1) || !ThsIndex.validCode(board))
            throw new IllegalStateException("Frozen THS board scope differs");
        var savedSource = json.readValue(json.writeValueAsBytes(frozen.path("source")),
                new com.fasterxml.jackson.core.type.TypeReference<SyncJobRunner.Page<ThsMember>>() {});
        Path sourcePath = Path.of(savedSource.responseEvidence()).toAbsolutePath().normalize();
        Path allowed = preparedWrite ? ledgerPath.getParent().resolve("write-evidence").toAbsolutePath().normalize()
                : folder.toAbsolutePath().normalize();
        if (!(preparedWrite ? sourcePath.startsWith(allowed) : sourcePath.getParent().equals(allowed))
                || Files.size(sourcePath) > 16L * 1024 * 1024)
            throw new IllegalStateException("THS source receipt path or size differs");
        SyncJobRunner.Page<ThsMember> source;
        if (preparedWrite) {
            byte[] bytes = FileEvidenceStore.readBounded(sourcePath, 16 * 1024 * 1024, () -> new IllegalStateException("THS source receipt path or size differs"));
            String hash = FileEvidenceStore.sha256(bytes);
            var proof = json.readTree(bytes);
            var mapper = new com.zoutrankil.data.index.mapper.ThsMemberMapper();
            if (!hash.equals(savedSource.sourceFingerprint())
                    || !proof.path("sourceKind").asText().equals("prepared-write-request")
                    || !proof.path("targetId").asText().equals(run.targetId())
                    || !proof.path("board").asText().equals(board)
                    || !proof.path("fingerprint").asText().equals(request.path("parameters").path("payloadFingerprint").asText())
                    || !json.valueToTree(savedSource.rows().stream().map(mapper::values).toList()).equals(proof.path("rows")))
                throw new IllegalStateException("Prepared THS member receipt differs from frozen request");
            source = new SyncJobRunner.Page<>(savedSource.rows(), hash, sourcePath.toString(), null);
        } else source = ThsMemberSource.reopen(sourcePath, savedSource.sourceFingerprint(), board);
        if (!source.rows().equals(savedSource.rows()))
            throw new IllegalStateException("THS source rows differ from frozen receipt");
        var prepared = json.treeToValue(frozen.path("prepared"), ThsMemberState.Prepared.class);
        if (!prepared.target().equals(table) || !prepared.board().equals(board)
                || !prepared.source().equals(source.rows())
                || !run.targetId().equals(targetAccess.identify( table,
                        prepared.before().identity().id(), prepared.before().identity().directory())))
            throw new IllegalStateException("THS member prepared target or source differs");
        if (source.rows().isEmpty() && !prepared.before().boardRows().isEmpty())
            throw new IllegalStateException("Empty provider response cannot delete existing board");
        var staging = targetAccess.newStaging();
        var journal = new ReferencePublicationJournal(ledgerPath, "ths_member");
        var existing = journal.findForRun(runId);
        String publicationId = null;
        if (staging.requiresWrite(prepared)) {
            var publisher = new ThsMemberBoardPublication(targetAccess.publicationTables(), ledgerPath);
            ThsMemberBoardPublication.Result publication;
            if (existing.isPresent()) {
                var intent = existing.get().intent();
                if (!intent.target().equals(table) || !intent.scope().equals(board)
                        || !intent.initialTarget().equals(run.targetId())
                        || intent.originalId() != prepared.before().identity().id()
                        || !intent.beforeFingerprint().equals(prepared.before().contentFingerprint()))
                    throw new IllegalStateException("THS member publication differs from frozen input");
                if (publisher.inspect(runId) == ThsMemberBoardPublication.Layout.CONFLICT)
                    throw new IllegalStateException("Conflicting THS member physical layout");
                publication = publisher.finish(lease, true);
            } else {
                var current = targetAccess.open(table).snapshot(board);
                if (!current.equals(prepared.before()))
                    throw new IllegalStateException("THS member target changed before recovery staging");
                var stage = staging.write(prepared, folder, () -> false);
                journal.requireLease(lease, true);
                journal.create(new ReferencePublicationJournal.Intent(
                        "ths-member-publication-" + UUID.randomUUID(), "ths_member", runId, table,
                        "java_ths_member_backup_" + UUID.randomUUID().toString().replace("-", ""),
                        stage.stage(), run.targetId(), current.identity().id(), current.identity().directory(),
                        stage.snapshot().identity().id(), current.contentFingerprint(),
                        stage.snapshot().contentFingerprint(), board));
                publication = publisher.finish(lease, true);
            }
            publicationId = publication.publication().intent().id();
        } else {
            if (existing.isPresent()) throw new IllegalStateException("No-write THS run has publication intent");
            if (!targetAccess.open(table).snapshot(board).equals(prepared.before()))
                throw new IllegalStateException("No-write THS target drifted");
        }
        var actual = targetAccess.open(table).snapshot(board);
        var expected = source.rows().stream().map(new com.zoutrankil.data.index.mapper.ThsMemberMapper()::toStorage)
                .sorted(Comparator.comparing(com.zoutrankil.data.domain.table.ThsMemberRow::conCode)).toList();
        if (actual.otherRows() != prepared.before().otherRows()
                || !actual.otherFingerprint().equals(prepared.before().otherFingerprint())
                || staging.requiresWrite(prepared) && !actual.boardRows().equals(expected)
                || !staging.requiresWrite(prepared) && actual.boardRows().size() != expected.size())
            throw new IllegalStateException("Recovered THS member full-key values differ");
        Path receipt = folder.resolve("completion.json");
        var proof = new LinkedHashMap<String, Object>();
        proof.put("runId", runId); proof.put("board", board); proof.put("source", savedSource);
        proof.put("before", prepared.before()); proof.put("actual", actual);
        proof.put("publicationId", publicationId); proof.put("sourceRows", source.rows().size());
        proof.put("verifiedRows", source.rows().size()); proof.put("copiedOtherRows", actual.otherRows());
        var expectedJson = json.readTree(json.writeValueAsBytes(proof));
        if (Files.exists(receipt)) {
            if (!expectedJson.equals(json.readTree(receipt.toFile()))) {
                Files.write(folder.resolve("completion-recomputed.json"), json.writeValueAsBytes(proof));
                throw new IllegalStateException("Existing THS member completion receipt differs");
            }
        } else FileEvidenceStore.writeNew(receipt,json.writeValueAsBytes(proof));
        int sourceRows = source.rows().size();
        var verification = Map.of("passed", true, "expectedRows", sourceRows, "actualRows", sourceRows,
                "matchedRows", sourceRows, "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0,
                "readbackEvidence", receipt.toString(), "sourceFingerprint", source.sourceFingerprint(),
                "writerStopped", true);
        Map<String, Object> payload = Map.of("verification", verification, "evidence", receipt.toString(),
                "checkpoint", actual.contentFingerprint());
        if (sourceRows == 0)
            payload = Map.of("verification", verification, "evidence", receipt.toString(),
                    "checkpoint", actual.contentFingerprint(), "sourceComplete", true, "returnedRows", 0,
                    "submittedRows", 0, "responseEvidence", source.responseEvidence());
        String attempt = runId + "-attempt", slice = runId + "-board";
        var attemptEntry = ledger.get(attempt); var sliceEntry = ledger.get(slice);
        if (attemptEntry.kind() != SyncRunLedger.Kind.ATTEMPT || !attemptEntry.parentId().equals(runId)
                || sliceEntry.kind() != SyncRunLedger.Kind.SLICE || !sliceEntry.parentId().equals(attempt))
            throw new IllegalStateException("THS member run/attempt/slice ownership differs");
        var state = sourceRows == 0 ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
        for (String id : List.of(slice, attempt, runId)) {
            var entry = ledger.get(id);
            if (entry.state() != state)
                ledger.transition(id, entry.revision(), state, json.writeValueAsString(payload));
        }
        var currentLease = locks.findOwned(runId, DatasetIntervalLock.Scope.allDates("ths_member"));
        if (currentLease == null) throw new IllegalStateException("THS member recovery lease disappeared");
        if (currentLease.inDoubt()) locks.releaseAfterReconciliation(currentLease, true, true);
        else locks.releaseVerified(currentLease);
        return new ThsMemberJobService.Result(runId, state, board, sourceRows,
                prepared.before().boardRows().size(), actual.boardRows().size(), actual.otherRows(),
                publicationId, receipt.toString(), null);
    }
}
