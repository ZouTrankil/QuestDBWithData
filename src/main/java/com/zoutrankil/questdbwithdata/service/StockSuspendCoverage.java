package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.mapper.StockSuspendMapper;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** A checkpoint is derived only from contiguous, receipt-backed, fully verified incremental dates. */
public final class StockSuspendCoverage {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_HISTORY = 10_000;
    private StockSuspendCoverage() {}

    public record Coverage(LocalDate anchor, LocalDate through, List<String> runIds) {
        public Coverage {
            Objects.requireNonNull(anchor); Objects.requireNonNull(through); runIds = List.copyOf(runIds);
            if (through.isBefore(anchor) || runIds.isEmpty()) throw new IllegalArgumentException("Verified stock suspension coverage required");
        }
    }
    private record Interval(String runId, LocalDate anchor, LocalDate from, LocalDate to) {}

    public static Optional<Coverage> checkpoint(Path ledgerPath, String targetId) throws Exception {
        Objects.requireNonNull(ledgerPath); Objects.requireNonNull(targetId);
        if (!Files.isRegularFile(ledgerPath) || !hasHistorySchema(ledgerPath)) return Optional.empty();
        SyncRunLedger ledger = SyncRunLedger.openReadOnly(ledgerPath);
        ObjectMapper json = JobDefinitionJson.mapper();
        SyncJobDefinition expectedDefinition = StockSuspendSyncJobOwner.DEFINITION;
        var intervals = new ArrayList<Interval>();
        String after = null; int seen = 0;
        while (true) {
            var summaries = ledger.history("data.stk_suspend", after, PAGE_SIZE);
            for (var summary : summaries) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("stk_suspend checkpoint history exceeds bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion() != 1) continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !"data.stk_suspend".equals(run.jobId()) || run.jobVersion() != 1) continue;
                JsonNode frozen = json.readTree(run.frozenJson());
                SyncJobDefinition frozenDefinition;
                try { frozenDefinition=json.treeToValue(frozen.path("definition"),SyncJobDefinition.class); }
                catch(Exception invalid) { throw new IllegalStateException("Verified stk_suspend run has an invalid frozen definition",invalid); }
                if (!expectedDefinition.equals(frozenDefinition) || !"INCREMENTAL".equals(frozen.path("mode").asText())) continue;
                JsonNode parameters = frozen.path("parameters");
                JsonNode frozenTarget = parameters.path("targetId");
                JsonNode anchorNode = parameters.path("checkpointAnchor");
                if (!frozenTarget.isTextual() || !targetId.equals(frozenTarget.asText()) || !anchorNode.isTextual()) continue;
                JsonNode fromNode = frozen.path("from"), toNode = frozen.path("to");
                if (!fromNode.isTextual() || !toNode.isTextual())
                    throw new IllegalStateException("Verified stk_suspend run lacks its frozen bounded range");
                LocalDate anchor = LocalDate.parse(anchorNode.asText());
                LocalDate from = LocalDate.parse(fromNode.asText()), to = LocalDate.parse(toNode.asText());
                long days = ChronoUnit.DAYS.between(from, to) + 1;
                if (from.isAfter(to) || days < 1 || days > StockSuspendSyncJobOwner.MAX_WINDOW_DAYS || to.isBefore(anchor))
                    throw new IllegalStateException("Verified stk_suspend run has invalid interval");
                verifyDateReceipts(ledger, ledgerPath, summary.id(), from, to, summary.state());
                intervals.add(new Interval(summary.id(), anchor, from, to));
            }
            if (summaries.size() < PAGE_SIZE) break;
            after = summaries.getLast().id();
        }
        return merge(intervals);
    }

    private static Optional<Coverage> merge(List<Interval> intervals) {
        if (intervals.isEmpty()) return Optional.empty();
        var byAnchor = new TreeMap<LocalDate,List<Interval>>();
        intervals.forEach(item -> byAnchor.computeIfAbsent(item.anchor(), ignored -> new ArrayList<>()).add(item));
        var candidates = new ArrayList<Coverage>();
        for (var entry : byAnchor.entrySet()) {
            LocalDate anchor = entry.getKey(), through = anchor.minusDays(1);
            var runs = new ArrayList<String>();
            var ordered = entry.getValue().stream().sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to)).toList();
            for (var interval : ordered) {
                if (interval.to().isBefore(anchor)) continue;
                if (interval.from().isAfter(through.plusDays(1))) break;
                if (interval.to().isAfter(through)) { through = interval.to(); runs.add(interval.runId()); }
            }
            if (!through.isBefore(anchor)) candidates.add(new Coverage(anchor, through, runs));
        }
        return candidates.stream().max(Comparator.comparing(Coverage::through)
                .thenComparing(Coverage::anchor, Comparator.reverseOrder()));
    }

    private static void verifyDateReceipts(SyncRunLedger ledger, Path ledgerPath, String runId,
                                           LocalDate from, LocalDate to, SyncRunState runState) throws Exception {
        Path ledgerRoot = ledgerPath.toAbsolutePath().normalize().getParent();
        Path evidenceRoot = ledgerRoot.resolve("sync-evidence").normalize();
        var dates = new HashSet<LocalDate>(); int slices = 0;
        Path completionPath=null;int receiptRows=0;
        String after = null;
        while (true) {
            var entries = ledger.entries(runId, after, 1000);
            for (var entry : entries) {
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                slices++;
                if (entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("Verified stk_suspend run contains an unverified date slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) {
                    if (event.state() == SyncRunState.FETCHED) {
                        if (fetched != null) throw new IllegalStateException("Duplicate stk_suspend fetch receipt event");
                        fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                    }
                }
                if (fetched == null || !fetched.path("responseEvidence").isTextual()
                        || !fetched.path("sourceFingerprint").isTextual())
                    throw new IllegalStateException("Verified stk_suspend slice has no source receipt reference");
                Path pageEvidencePath=Path.of(fetched.path("responseEvidence").asText()).toAbsolutePath().normalize();
                if(!pageEvidencePath.startsWith(evidenceRoot)||!Files.isRegularFile(pageEvidencePath))
                    throw new IllegalStateException("stk_suspend daily page evidence is absent or outside evidence root");
                Path realRoot=evidenceRoot.toRealPath(),realPageEvidence=pageEvidencePath.toRealPath();
                if(!realPageEvidence.startsWith(realRoot)||Files.size(realPageEvidence)>StockSuspendSource.MAX_EVIDENCE_BYTES)
                    throw new IllegalStateException("stk_suspend daily page evidence is oversized or escapes evidence root");
                JsonNode pageEvidence=JobDefinitionJson.mapper().readTree(Files.readAllBytes(realPageEvidence));
                if(!"stk_suspend_daily_source".equals(pageEvidence.path("evidenceType").asText())
                        ||!pageEvidence.path("sourceComplete").asBoolean(false)
                        ||!fetched.path("sourceFingerprint").asText().equals(pageEvidence.path("sourceFingerprint").asText())
                        ||!pageEvidence.path("sourceReceipt").isTextual()
                        ||!pageEvidence.path("completionEvidence").isTextual()
                        ||!pageEvidence.path("publication").isObject())
                    throw new IllegalStateException("stk_suspend page evidence lacks complete source/publication linkage");
                Path candidateCompletion=Path.of(pageEvidence.path("completionEvidence").asText()).toAbsolutePath().normalize();
                if(!candidateCompletion.startsWith(evidenceRoot)||!Files.isRegularFile(candidateCompletion)
                        ||completionPath!=null&&!completionPath.equals(candidateCompletion))
                    throw new IllegalStateException("stk_suspend complete-window evidence is absent, outside root or inconsistent");
                completionPath=candidateCompletion;Path realCompletion=candidateCompletion.toRealPath();
                if(!realCompletion.startsWith(realRoot)||Files.size(realCompletion)>StockSuspendSource.MAX_EVIDENCE_BYTES)
                    throw new IllegalStateException("stk_suspend complete-window evidence is oversized or escapes evidence root");
                Path receiptPath=Path.of(pageEvidence.path("sourceReceipt").asText()).toAbsolutePath().normalize();
                if(!receiptPath.startsWith(evidenceRoot)||!Files.isRegularFile(receiptPath))
                    throw new IllegalStateException("stk_suspend raw source receipt is absent or outside evidence root");
                Path realReceipt=receiptPath.toRealPath();
                if(!realReceipt.startsWith(realRoot)||Files.size(realReceipt)>StockSuspendSource.MAX_EVIDENCE_BYTES)
                    throw new IllegalStateException("stk_suspend raw source receipt is oversized or escapes evidence root");
                byte[] bytes = Files.readAllBytes(realReceipt);
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!digest.equals(fetched.path("sourceFingerprint").asText()))
                    throw new IllegalStateException("stk_suspend source receipt fingerprint differs from the ledger");
                JsonNode receipt = JobDefinitionJson.mapper().readTree(bytes);
                if (!"suspend_d".equals(receipt.path("endpoint").asText())
                        || !receipt.path("sourceComplete").asBoolean(false)
                        || receipt.path("sourceRowCap").asInt() != StockSuspendSource.SOURCE_ROW_CAP
                        || !receipt.path("rows").isArray() || !receipt.path("normalizedRows").isArray())
                    throw new IllegalStateException("stk_suspend source receipt is incomplete");
                LocalDate date = LocalDate.parse(receipt.path("tradeDate").asText());
                if (date.isBefore(from) || date.isAfter(to) || !dates.add(date))
                    throw new IllegalStateException("stk_suspend receipt date is duplicate or outside frozen interval");
                if(!date.toString().equals(pageEvidence.path("tradeDate").asText()))
                    throw new IllegalStateException("stk_suspend page and raw source receipt dates differ");
                JsonNode parameters = receipt.path("parameters");
                if (!date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).equals(parameters.path("trade_date").asText())
                        || !"S".equals(parameters.path("suspend_type").asText()))
                    throw new IllegalStateException("stk_suspend receipt query differs from its daily date slice");
                int returned = receipt.path("returnedRows").asInt(-1);
                JsonNode raw = receipt.path("rows"), normalized = receipt.path("normalizedRows");
                if (returned < 0 || returned != raw.size() || raw.size() != normalized.size() || returned >= StockSuspendSource.SOURCE_ROW_CAP)
                    throw new IllegalStateException("stk_suspend receipt row count is invalid or at the source cap");
                var keys = new HashSet<String>();
                for (int i = 0; i < raw.size(); i++) {
                    JsonNode sourceRow = raw.get(i), targetRow = normalized.get(i);
                    String code = sourceRow.path("ts_code").asText();
                    if (!code.matches("[0-9]{6}\\.(?:SH|SZ|BJ)") || !date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                            .equals(sourceRow.path("trade_date").asText()) || !"S".equals(sourceRow.path("suspend_type").asText())
                            || !keys.add(code) || !code.equals(targetRow.path("ts_code").asText())
                            || !date.toString().equals(targetRow.path("trade_date").asText())
                            || targetRow.path("is_suspended").asLong(-1) != 1L)
                        throw new IllegalStateException("stk_suspend receipt contains inconsistent source/normalized values");
                }
                if (entry.state() == SyncRunState.VERIFIED_EMPTY && returned != 0)
                    throw new IllegalStateException("Empty slice has a nonempty suspend_d receipt");
                if (entry.state() == SyncRunState.VERIFIED && returned == 0)
                    throw new IllegalStateException("Nonempty verified slice has an empty suspend_d receipt");
                receiptRows=Math.addExact(receiptRows,returned);
                JsonNode completeProof=JobDefinitionJson.mapper().readTree(completionPath.toFile());
                if(!pageEvidence.path("publication").equals(completeProof.path("publication")))
                    throw new IllegalStateException("stk_suspend page and completion publication proofs differ");
            }
            if (entries.size() < 1000) break;
            after = entries.getLast().id();
        }
        long expectedDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (slices != expectedDays || dates.size() != expectedDays)
            throw new IllegalStateException("Verified stk_suspend checkpoint lacks one receipt for every requested calendar day");
        verifyWindowProof(ledger,runId,completionPath,from,to,receiptRows,runState);
        if (runState == SyncRunState.VERIFIED_EMPTY && ledger.entries(runId, null, 1000).stream()
                .anyMatch(e -> e.kind() == SyncRunLedger.Kind.SLICE && e.state() == SyncRunState.VERIFIED))
            throw new IllegalStateException("Empty run contains nonempty verified date slices");
    }

    private static void verifyWindowProof(SyncRunLedger ledger,String runId,Path completionPath,
                                          LocalDate from,LocalDate to,int rows,SyncRunState state)throws Exception {
        if(completionPath==null||!Files.isRegularFile(completionPath))
            throw new IllegalStateException("stk_suspend complete-window receipt is missing");
        JsonNode complete=JobDefinitionJson.mapper().readTree(completionPath.toFile());
        JsonNode publication=complete.path("publication"),verification=publication.path("verification");
        JsonNode frozen=JobDefinitionJson.mapper().readTree(ledger.getRun(runId).frozenJson());
        String logical=frozen.path("parameters").path("targetId").asText();
        if(!"suspend_d".equals(complete.path("endpoint").asText())||!complete.path("complete").asBoolean(false)
                ||!complete.path("sourceComplete").asBoolean(false)
                ||complete.path("completedDateSlices").asInt(-1)!=ChronoUnit.DAYS.between(from,to)+1
                ||complete.path("sourceRows").asInt(-1)!=rows||complete.path("returnedRows").asInt(-1)!=rows
                ||complete.path("submittedRows").asInt(-1)!=rows||!complete.path("responseEvidence").isTextual()
                ||complete.path("responseEvidence").asText().isBlank()
                ||!from.toString().equals(complete.path("fromInclusive").asText())
                ||!to.toString().equals(complete.path("toInclusive").asText())
                ||!logical.equals(publication.path("logicalTargetId").asText())
                ||!from.toString().equals(publication.path("fromInclusive").asText())
                ||!to.toString().equals(publication.path("toInclusive").asText())
                ||!publication.path("physicalTargetBefore").asText().matches("static-v2-[0-9a-f]{64}")
                ||!publication.path("physicalTargetAfter").asText().matches("static-v2-[0-9a-f]{64}")
                ||publication.path("fullTargetFingerprint").asText().isBlank()
                ||!verification.path("passed").asBoolean(false)||!verification.path("writerStopped").asBoolean(false)
                ||verification.path("expectedRows").asInt(-1)!=rows||verification.path("actualRows").asInt(-1)!=rows
                ||verification.path("matchedRows").asInt(-1)!=rows||verification.path("mismatchedRows").asInt(-1)!=0
                ||verification.path("duplicateKeys").asInt(-1)!=0||verification.path("missingKeys").asInt(-1)!=0
                ||!verification.path("readbackEvidence").isTextual()||verification.path("readbackEvidence").asText().isBlank()
                ||!verification.path("sourceFingerprint").isTextual()||verification.path("sourceFingerprint").asText().isBlank()
                ||publication.path("replacementPublished").asBoolean(false)
                        ==publication.path("physicalTargetBefore").asText().equals(publication.path("physicalTargetAfter").asText()))
            throw new IllegalStateException("stk_suspend full-window replacement evidence is incomplete or inconsistent");
        if(state==SyncRunState.VERIFIED_EMPTY) {
            JsonNode runProof=JobDefinitionJson.mapper().readTree(ledger.get(runId).payloadJson());
            Path referenced=Path.of(runProof.path("responseEvidence").asText()).toAbsolutePath().normalize();
            if(!runProof.path("sourceComplete").asBoolean(false)||runProof.path("returnedRows").asInt(-1)!=0
                    ||runProof.path("submittedRows").asInt(-1)!=0||!referenced.equals(completionPath.toAbsolutePath().normalize())
                    ||rows!=0||verification.path("expectedRows").asInt(-1)!=0||verification.path("actualRows").asInt(-1)!=0
                    ||verification.path("matchedRows").asInt(-1)!=0)
                throw new IllegalStateException("VERIFIED_EMPTY stk_suspend proof must bind an authoritative empty replacement window");
        }
    }

    private static boolean hasHistorySchema(Path path) throws Exception {
        var names = new HashSet<String>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        var required = Set.of("ledger_meta", "sync_runs", "sync_entries");
        if (Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial stk_suspend sync-run ledger schema");
        return true;
    }
}
