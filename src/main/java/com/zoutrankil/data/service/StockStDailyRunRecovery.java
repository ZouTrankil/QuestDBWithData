package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Finish an interrupted D012 rename and ledger acknowledgement from receipt-backed full target readback. */
public final class StockStDailyRunRecovery {
    public record Result(String runId, SyncRunState state, int sourceRows, int verifiedRows,
                         String publicationId, String evidence) {}
    private StockStDailyRunRecovery() {}

    public static Result finishInterrupted(JdbcTemplate jdbc, QuestDB questdb, Path ledgerPath, String table,
                                            String runId, boolean writerStopped) throws Exception {
        if (!writerStopped) throw new IllegalStateException("Stopped D012 writer proof required");
        ledgerPath = ledgerPath.toAbsolutePath().normalize(); StockStDailyJobService.requireIsolatedTableName(table);
        var ledger = new SyncRunLedger(ledgerPath); var root = ledger.get(runId); var run = ledger.getRun(runId);
        if (!Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT).contains(root.state())
                || !"data.stk_st_daily".equals(run.jobId()) || run.jobVersion() != 1)
            throw new IllegalStateException("Recoverable D012 source run required");
        var json = JobDefinitionJson.mapper();
        var request = StockStDailySyncAdapter.restoreFrozenRequest(run.frozenJson(), run.targetId());
        StockStDailySyncAdapter.validateFrozenRequest(request);
        String logicalTarget = request.parameters().get("targetId").toString();
        String physicalBefore = request.parameters().get("physicalTargetId").toString();
        if (!logicalTarget.equals(run.targetId())) throw new IllegalStateException("D012 recovery logical target differs from run ledger");

        var publication = new StockStDailyPublication(jdbc, ledgerPath);
        var beforeFinish = publication.forRun(runId);
        var intent = beforeFinish.intent();
        if (!intent.target().equals(table) || !intent.logicalTarget().equals(logicalTarget)
                || !intent.frozenPhysicalTarget().equals(physicalBefore)
                || !intent.runId().equals(runId))
            throw new IllegalStateException("D012 recovery intent differs from frozen run/target");
        var runEvidence = ledgerPath.getParent().resolve("sync-evidence").resolve(runId).toAbsolutePath().normalize();
        Path realEvidence = runEvidence.toRealPath();
        Path stageReceipt = Path.of(intent.stageReceipt()).toRealPath();
        if (!stageReceipt.startsWith(realEvidence) || Files.size(stageReceipt) > 32L * 1024 * 1024
                || !sha256(Files.readAllBytes(stageReceipt)).equals(intent.stageReceiptFingerprint()))
            throw new IllegalStateException("D012 verified stage receipt is absent, escaped, oversized or changed");
        JsonNode stageProof = json.readTree(stageReceipt.toFile());
        if (!stageProof.path("sourceComplete").asBoolean(false)
                || !intent.windowFrom().toString().equals(stageProof.path("windowFrom").asText())
                || !intent.windowTo().toString().equals(stageProof.path("windowTo").asText())
                || stageProof.path("sourceRows").asInt(-1) != intent.sourceRows()
                || !intent.sourceFingerprint().equals(stageProof.path("sourceFingerprint").asText()))
            throw new IllegalStateException("D012 stage receipt differs from durable publication intent");
        var originalBefore = json.treeToValue(stageProof.path("before"), StockStDailyStorage.Snapshot.class);
        var stageAfter = json.treeToValue(stageProof.path("after"), StockStDailyStorage.Snapshot.class);
        var outside = json.treeToValue(stageProof.path("preservedOutside"), StockStDailyStorage.Content.class);
        var sourceWindow = json.treeToValue(stageProof.path("authoritativeWindow"), StockStDailyStorage.Content.class);
        if (!samePhysical(originalBefore.identity(), intent.beforeIdentity())
                || !StockStDailyStorage.sameContent(originalBefore.content(), intent.before())
                || !StockStDailyStorage.sameContent(outside, intent.preservedOutside())
                || !StockStDailyStorage.sameContent(sourceWindow, intent.authoritativeWindow())
                || !samePhysical(stageAfter.identity(), intent.stageIdentity())
                || !StockStDailyStorage.sameContent(stageAfter.content(), intent.after()))
            throw new IllegalStateException("D012 stage proof content differs from frozen publication intent");

        List<LocalDate> dates = StockStDailySyncAdapter.decodeTradeDates(request);
        if (dates.size() != stageProof.path("sourceReceipts").size())
            throw new IllegalStateException("D012 stage proof omits session receipts");
        Path sourceRoot = realEvidence.resolve("source").toRealPath();
        var byDate = new HashMap<LocalDate,JsonNode>(); var expectedRows = new ArrayList<StockStDaily>();
        int returnedRows = 0;
        for (JsonNode ref : stageProof.path("sourceReceipts")) {
            LocalDate date = parseDate(ref.path("tradeDate").asText());
            if (!dates.contains(date) || byDate.putIfAbsent(date, ref) != null)
                throw new IllegalStateException("Duplicate or out-of-range D012 source receipt");
            Path candidate = Path.of(ref.path("path").asText()).toRealPath();
            if (!candidate.startsWith(sourceRoot)) throw new IllegalStateException("D012 source receipt escaped run source root");
            var reopened = StockStDailySource.reopen(candidate, ref.path("fingerprint").asText(), date);
            if (reopened.rows().size() != ref.path("rows").asInt(-1))
                throw new IllegalStateException("D012 source receipt row count differs from stage proof");
            expectedRows.addAll(reopened.rows()); returnedRows = Math.addExact(returnedRows, reopened.rows().size());
        }
        if (!byDate.keySet().equals(new HashSet<>(dates)) || returnedRows != intent.sourceRows())
            throw new IllegalStateException("D012 source receipts do not completely cover frozen SSE dates");
        String fingerprint = fingerprint(dates.stream().map(date -> byDate.get(date).path("fingerprint").asText()).toList());
        var expectedWindow = StockStDailyStaging.fingerprintWindow(expectedRows, request.from(), request.to());
        if (!fingerprint.equals(intent.sourceFingerprint())
                || !StockStDailyStorage.sameContent(expectedWindow, intent.authoritativeWindow()))
            throw new IllegalStateException("D012 raw receipts do not reproduce the authoritative stage window");
        verifyLedgerSlices(ledger, runId, dates, byDate);

        var publicationResult = publication.finish(runId, true);
        var targetStorage = new StockStDailyStorage(jdbc, table);
        var actual = targetStorage.snapshot();
        var actualWindow = targetStorage.window(request.from(), request.to());
        var actualOutside = targetStorage.outside(request.from(), request.to());
        if (!StockStDailyStorage.sameContent(actual.content(), intent.after())
                || !StockStDailyStorage.sameContent(actualWindow, expectedWindow)
                || !StockStDailyStorage.sameContent(actualOutside, intent.preservedOutside())
                || actual.content().rows() != actualWindow.rows() + actualOutside.rows()
                || !StockStDailyStorage.sameContent(publicationResult.backup().content(), intent.before()))
            throw new IllegalStateException("D012 post-publication whole-table/window readback differs from raw receipts");

        String attemptId = uniqueAttempt(ledger, runId);
        var verification = Map.of("passed", true, "expectedRows", returnedRows, "actualRows", returnedRows,
                "matchedRows", returnedRows, "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0,
                "readbackEvidence", stageReceipt.toString(), "sourceFingerprint", fingerprint, "writerStopped", true);
        var rootProof = new LinkedHashMap<String,Object>();
        rootProof.put("sourceComplete", true); rootProof.put("returnedRows", returnedRows);
        rootProof.put("submittedRows", returnedRows); rootProof.put("responseEvidence", stageReceipt.toString());
        rootProof.put("verification", verification); rootProof.put("checkpoint", actual.content().fingerprint());
        rootProof.put("targetAfter", actual); rootProof.put("preservedOutside", actualOutside);
        rootProof.put("authoritativeWindow", actualWindow); rootProof.put("publicationId", intent.id());
        var jsonText = json.writeValueAsString(rootProof);
        for (var entry : ledgerEntries(ledger, runId)) {
            if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
            JsonNode fetched = fetchEvent(ledger, entry.id());
            LocalDate date = parseDate(fetched.path("cursor").asText());
            int rows = fetched.path("returnedRows").asInt(-1);
            if (!dates.contains(date) || rows != byDate.get(date).path("rows").asInt(-2))
                throw new IllegalStateException("D012 slice ledger disagrees with raw receipt during recovery");
            SyncRunState desired = rows == 0 ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
            var current = ledger.get(entry.id());
            if (current.state() != desired) {
                if (current.state() != SyncRunState.RUNNING && current.state() != SyncRunState.IN_DOUBT)
                    throw new IllegalStateException("D012 slice state cannot be reconciled from publication");
                var sliceProof = sliceProof(fetched, desired, intent.id(), stageReceipt.toString(), true);
                ledger.transition(entry.id(), current.revision(), desired, json.writeValueAsString(sliceProof));
            }
        }
        var attempt = ledger.get(attemptId);
        SyncRunState desired = returnedRows == 0 ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
        if (attempt.state() != desired) ledger.transition(attemptId, attempt.revision(), desired, jsonText);
        var currentRoot = ledger.get(runId);
        if (currentRoot.state() != desired) ledger.transition(runId, currentRoot.revision(), desired, jsonText);
        var locks = new DatasetIntervalLock(ledgerPath);
        var lease = locks.findOwned(runId, new DatasetIntervalLock.Scope("stk_st_daily", request.from(), request.to()));
        if (lease != null) {
            if (lease.inDoubt()) locks.releaseAfterReconciliation(lease, true, true);
            else locks.releaseVerified(lease);
        }
        return new Result(runId, desired, returnedRows, returnedRows, intent.id(), stageReceipt.toString());
    }

    private static List<SyncRunLedger.Entry> ledgerEntries(SyncRunLedger ledger, String runId) throws Exception {
        var all = new ArrayList<SyncRunLedger.Entry>(); String cursor = null;
        while (true) {
            var page = ledger.entries(runId, cursor, 1000); all.addAll(page);
            if (page.size() < 1000) return List.copyOf(all);
            cursor = page.getLast().id();
        }
    }
    private static String uniqueAttempt(SyncRunLedger ledger, String runId) throws Exception {
        var attempts = ledgerEntries(ledger, runId).stream().filter(e -> e.kind() == SyncRunLedger.Kind.ATTEMPT
                && runId.equals(e.parentId()) && runId.equals(e.runId())).toList();
        if (attempts.size() != 1) throw new IllegalStateException("D012 run does not have one owned attempt");
        return attempts.getFirst().id();
    }
    private static void verifyLedgerSlices(SyncRunLedger ledger, String runId, List<LocalDate> dates,
                                           Map<LocalDate,JsonNode> receipts) throws Exception {
        var found = new HashSet<LocalDate>();
        for (var entry : ledgerEntries(ledger, runId)) {
            if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
            JsonNode fetched = fetchEvent(ledger, entry.id()); LocalDate date = parseDate(fetched.path("cursor").asText());
            JsonNode source = receipts.get(date);
            if (source == null || !found.add(date) || fetched.path("returnedRows").asInt(-1) != source.path("rows").asInt(-2)
                    || !fetched.path("sourceFingerprint").asText().equals(source.path("fingerprint").asText())
                    || !Path.of(fetched.path("responseEvidence").asText()).toRealPath()
                    .equals(Path.of(source.path("path").asText()).toRealPath()))
                throw new IllegalStateException("D012 source receipt differs from its immutable FETCHED ledger event");
            if (!Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT, SyncRunState.VERIFIED,
                    SyncRunState.VERIFIED_EMPTY).contains(entry.state()))
                throw new IllegalStateException("D012 slice state is not publication-recoverable");
        }
        if (!found.equals(new HashSet<>(dates))) throw new IllegalStateException("D012 ledger omits a trade-date slice");
    }
    private static JsonNode fetchEvent(SyncRunLedger ledger, String id) throws Exception {
        JsonNode fetched = null;
        for (var event : ledger.events(id, -1, 100)) if (event.state() == SyncRunState.FETCHED) {
            if (fetched != null) throw new IllegalStateException("D012 slice has duplicate FETCHED events");
            fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
        }
        if (fetched == null) throw new IllegalStateException("D012 slice lacks FETCHED source receipt");
        return fetched;
    }
    private static Map<String,Object> sliceProof(JsonNode fetched, SyncRunState state, String publicationId,
            String evidence, boolean writerStopped) {
        int count = fetched.path("returnedRows").asInt(-1);
        var proof = new LinkedHashMap<String,Object>(); proof.put("sourceComplete", true);
        proof.put("returnedRows", count); proof.put("submittedRows", count);
        proof.put("responseEvidence", fetched.path("responseEvidence").asText()); proof.put("publicationId", publicationId);
        proof.put("verification", Map.of("passed", true, "expectedRows", count, "actualRows", count,
                "matchedRows", count, "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0,
                "readbackEvidence", evidence, "sourceFingerprint", fetched.path("sourceFingerprint").asText(),
                "writerStopped", writerStopped));
        if (state == SyncRunState.VERIFIED_EMPTY && count != 0)
            throw new IllegalArgumentException("Nonempty D012 receipt cannot be verified empty");
        return proof;
    }
    private static LocalDate parseDate(String text) {
        try { return text.matches("[0-9]{8}") ? LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE) : LocalDate.parse(text); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid frozen D012 date receipt", invalid); }
    }
    private static boolean samePhysical(StockStDailyStorage.Identity a, StockStDailyStorage.Identity b) {
        return a.id() == b.id() && a.directory().equals(b.directory());
    }
    private static String fingerprint(List<String> parts) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String part : parts) { digest.update(part.getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte) 0); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
