package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;
import com.zoutrankil.data.domain.SyncJobDefinition;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.StockStDaily;
import com.zoutrankil.data.domain.StockStDailyKey;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.stock.port.StockDateWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Receipt-backed D012 checkpoint for one exact isolated target and 20100101 anchor. */
public final class StockStDailyCoverage {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_HISTORY = 10_000;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_DAYS = 10_000;
    private StockStDailyCoverage() {}

    public record Receipt(Path path, String fingerprint, LocalDate date) {
        public Receipt { Objects.requireNonNull(path); Objects.requireNonNull(fingerprint); Objects.requireNonNull(date); }
    }
    public record Coverage(LocalDate anchor, LocalDate through, Map<LocalDate,Receipt> receipts,
                           Instant latestVerifiedAt) {
        public Coverage {
            Objects.requireNonNull(anchor); Objects.requireNonNull(through); Objects.requireNonNull(latestVerifiedAt);
            if (through.isBefore(anchor)) throw new IllegalArgumentException("Invalid D012 coverage interval");
            receipts = Map.copyOf(receipts);
        }
    }
    private record Interval(String runId, LocalDate anchor, LocalDate from, LocalDate to,
                            Instant verifiedAt, Map<LocalDate,Receipt> receipts) {}
    private record TimedReceipt(Instant verifiedAt, String runId, Receipt receipt) {}

    public static boolean hasHistorySchema(Path path) throws Exception {
        if (!Files.isRegularFile(path)) return false;
        var names = SqliteLedgerSchema.tableNames(path);
        var required = Set.of("ledger_meta", "sync_runs", "sync_entries", "sync_events");
        if (java.util.Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial D012 sync ledger schema");
        return true;
    }

    public static Optional<Coverage> checkpoint(Path ledgerPath, String targetId,
            StockStTradingDates tradingDates) throws Exception {
        Objects.requireNonNull(ledgerPath); Objects.requireNonNull(targetId); Objects.requireNonNull(tradingDates);
        if (!Files.isRegularFile(ledgerPath) || !hasHistorySchema(ledgerPath)) return Optional.empty();
        var ledger = SyncRunLedger.openReadOnly(ledgerPath); var json = JobDefinitionJson.mapper();
        JsonNode expectedDefinition = json.valueToTree(StockStDailySyncJobOwner.DEFINITION);
        var intervals = new ArrayList<Interval>(); String after = null; int seen = 0;
        while (true) {
            var page = ledger.history("data.stk_st_daily", after, PAGE_SIZE);
            for (var summary : page) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("D012 checkpoint history exceeds bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion() != 1) continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !"data.stk_st_daily".equals(run.jobId()) || run.jobVersion() != 1) continue;
                JsonNode frozen = json.readTree(run.frozenJson());
                if (!sameDefinition(expectedDefinition, frozen.path("definition"))) continue;
                if (!"INCREMENTAL".equals(frozen.path("mode").asText())) continue;
                JsonNode params = frozen.path("parameters");
                if (!params.isObject() || !targetId.equals(params.path("targetId").asText()))
                    throw new IllegalStateException("Verified D012 run lacks frozen target identity");
                if (!params.path("checkpointAnchor").isTextual())
                    throw new IllegalStateException("Verified D012 incremental run lacks bootstrap anchor");
                LocalDate anchor = parseDate(params.path("checkpointAnchor").asText());
                LocalDate from = parseDate(frozen.path("from").asText()), to = parseDate(frozen.path("to").asText());
                long span = ChronoUnit.DAYS.between(from, to) + 1;
                if (from.isAfter(to) || from.isBefore(anchor) || to.isBefore(anchor)
                        || span > StockStDailySyncJobOwner.MAX_WINDOW_DAYS)
                    throw new IllegalStateException("Verified D012 interval is outside its bounded anchor");
                var expectedDates = tradingDates.read(from, to);
                if (!StockStDailySyncAdapter.encodeTradeDates(expectedDates).equals(params.path("trade_dates").asText()))
                    throw new IllegalStateException("Verified D012 frozen sessions differ from the D001 calendar");
                var receipts = readReceipts(ledgerPath, ledger, summary.id(), from, to, expectedDates, summary.state());
                intervals.add(new Interval(summary.id(), anchor, from, to, Instant.parse(summary.updatedAt()), receipts));
            }
            if (page.size() < PAGE_SIZE) break;
            after = page.getLast().id();
        }
        return mergeLatestContinuous(intervals);
    }

    private static Map<LocalDate,Receipt> readReceipts(Path ledgerPath, SyncRunLedger ledger, String runId,
            LocalDate from, LocalDate to, List<LocalDate> expectedDates, SyncRunState runState) throws Exception {
        var expected = new HashSet<>(expectedDates); var found = new HashMap<LocalDate,Receipt>();
        Path evidenceBase = ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").normalize();
        Path runEvidence = evidenceBase.resolve(runId).normalize();
        if (!runEvidence.startsWith(evidenceBase)) throw new IllegalStateException("D012 run id escapes source evidence root");
        Path sourceEvidence = runEvidence.resolve("source");
        if (!expected.isEmpty()) {
            Path realBase = evidenceBase.toRealPath();
            Path realSource = sourceEvidence.toRealPath();
            if (!realSource.startsWith(realBase)) throw new IllegalStateException("D012 source evidence root escaped ledger evidence directory");
            sourceEvidence = realSource;
        }
        String after = null; int seen = 0; boolean hasRows = false;
        while (true) {
            var entries = ledger.entries(runId, after, 1000);
            for (var entry : entries) {
                if (++seen > MAX_ENTRIES) throw new IllegalStateException("D012 run exceeds bounded slice inventory");
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("Verified D012 run contains a nonverified slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
                    if (fetched != null) throw new IllegalStateException("Duplicate D012 FETCHED event");
                    fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                }
                if (fetched == null) throw new IllegalStateException("D012 verified slice has no raw-source receipt");
                String cursor = fetched.path("cursor").asText("");
                if (!cursor.matches("[0-9]{8}")) throw new IllegalStateException("Invalid D012 trade-date receipt cursor");
                LocalDate date = LocalDate.parse(cursor, DateTimeFormatter.BASIC_ISO_DATE);
                if (!expected.contains(date)) throw new IllegalStateException("D012 receipt date is outside frozen SSE sessions");
                String path = fetched.path("responseEvidence").asText("");
                String fingerprint = fetched.path("sourceFingerprint").asText("");
                if (path.isBlank() || !fingerprint.matches("[0-9a-f]{64}"))
                    throw new IllegalStateException("Invalid D012 receipt path/fingerprint");
                Path candidate = Path.of(path).toRealPath();
                if (!expected.isEmpty() && !candidate.startsWith(sourceEvidence))
                    throw new IllegalStateException("D012 receipt path is outside this run's ledger evidence root");
                var reopened = StockStDailySource.reopen(candidate, fingerprint, date);
                var json = JobDefinitionJson.mapper(); JsonNode receiptBody = json.readTree(FileEvidenceStore.readBounded(
                        reopened.path(), StockStDailySource.MAX_RESPONSE_BYTES, () -> new IllegalArgumentException(
                                "Bounded stk_st_daily daily receipt and SHA-256 required")));
                if (!from.toString().equals(receiptBody.path("from").asText())
                        || !to.toString().equals(receiptBody.path("to").asText()))
                    throw new IllegalStateException("D012 date receipt does not match frozen run window");
                if (reopened.rows().isEmpty() != (entry.state() == SyncRunState.VERIFIED_EMPTY)
                        || fetched.path("returnedRows").asInt(-1) != reopened.rows().size())
                    throw new IllegalStateException("D012 slice state/row count differs from raw receipt");
                hasRows |= !reopened.rows().isEmpty();
                if (found.putIfAbsent(date, new Receipt(reopened.path(), fingerprint, date)) != null)
                    throw new IllegalStateException("Duplicate D012 receipt for one SSE date");
            }
            if (entries.size() < 1000) break;
            after = entries.getLast().id();
        }
        if (!found.keySet().equals(expected)) throw new IllegalStateException("D012 run omits one or more session receipts");
        if (runState == SyncRunState.VERIFIED_EMPTY && hasRows || runState == SyncRunState.VERIFIED && !hasRows)
            throw new IllegalStateException("D012 run state differs from its source row receipts");
        return Map.copyOf(found);
    }

    private static Optional<Coverage> mergeLatestContinuous(List<Interval> intervals) {
        if (intervals.isEmpty()) return Optional.empty();
        var groups = new HashMap<LocalDate,List<Interval>>();
        intervals.forEach(interval -> groups.computeIfAbsent(interval.anchor(), ignored -> new ArrayList<>()).add(interval));
        var candidates = new ArrayList<Coverage>();
        for (var group : groups.entrySet()) {
            LocalDate anchor = group.getKey(), through = anchor.minusDays(1); boolean anchored = false;
            Instant latest = Instant.MIN; var selected = new HashMap<LocalDate,TimedReceipt>();
            var ordered = group.getValue().stream().sorted(Comparator.comparing(Interval::from)
                    .thenComparing(Interval::to).thenComparing(Interval::verifiedAt).thenComparing(Interval::runId)).toList();
            for (var interval : ordered) {
                if (!anchored) { if (!interval.from().equals(anchor)) continue; anchored = true; }
                if (interval.from().isAfter(through.plusDays(1))) break;
                if (interval.to().isAfter(through)) through = interval.to();
                if (interval.verifiedAt().isAfter(latest)) latest = interval.verifiedAt();
                interval.receipts().forEach((date,receipt) -> {
                    TimedReceipt old = selected.get(date);
                    if (old == null || interval.verifiedAt().isAfter(old.verifiedAt())
                            || interval.verifiedAt().equals(old.verifiedAt()) && interval.runId().compareTo(old.runId()) > 0)
                        selected.put(date, new TimedReceipt(interval.verifiedAt(), interval.runId(), receipt));
                });
            }
            if (anchored && !through.isBefore(anchor)) {
                var receipts = new HashMap<LocalDate,Receipt>(); selected.forEach((date,value) -> receipts.put(date,value.receipt()));
                candidates.add(new Coverage(anchor, through, receipts, latest));
            }
        }
        return candidates.stream().max(Comparator.comparing(Coverage::through).thenComparing(Coverage::latestVerifiedAt)
                .thenComparing(Coverage::anchor, Comparator.reverseOrder()));
    }

    /** Explain every existing physical status date and positive key using the newest verified receipt. */
    public static int validateExistingTarget(Coverage coverage, StockStTradingDates tradingDates,
            StockDateWriteSession<StockStDaily, StockStDailyKey> writer) throws Exception {
        var actualDates = writer.readExistingDates();
        if (actualDates.isEmpty()) {
            if (coverage != null) verifyReceiptRows(coverage, tradingDates, writer, Set.of());
            return 0;
        }
        if (coverage == null)
            throw new IllegalStateException("Nonempty stk_st_daily target has no same-target verified incremental receipts");
        if (actualDates.getFirst().isBefore(coverage.anchor()) || actualDates.getLast().isAfter(coverage.through()))
            throw new IllegalStateException("stk_st_daily physical dates exceed verified coverage");
        verifyReceiptRows(coverage, tradingDates, writer, new HashSet<>(actualDates));
        return actualDates.size();
    }

    private static void verifyReceiptRows(Coverage coverage, StockStTradingDates tradingDates,
            StockDateWriteSession<StockStDaily, StockStDailyKey> writer, Set<LocalDate> actualDates) throws Exception {
        if (ChronoUnit.DAYS.between(coverage.anchor(), coverage.through()) + 1 > MAX_DAYS)
            throw new IllegalStateException("D012 coverage exceeds bounded 10000-calendar-day reconciliation");
        var sessions = readLongRange(tradingDates, coverage.anchor(), coverage.through());
        if (!coverage.receipts().keySet().equals(new HashSet<>(sessions)))
            throw new IllegalStateException("D012 receipts do not cover every SSE session in the checkpoint chain");
        var expectedPhysicalDates = new HashSet<LocalDate>();
        for (LocalDate date : sessions) {
            Receipt receipt = coverage.receipts().get(date);
            List<StockStDaily> expected = StockStDailySource.reopen(receipt.path(), receipt.fingerprint(), date).rows();
            List<StockStDaily> actual = writer.readDate(date);
            if (!sameRows(expected, actual, writer.codec()))
                throw new IllegalStateException("QuestDB stk_st_daily values differ from latest raw-source receipt on " + date);
            if (!actual.isEmpty()) expectedPhysicalDates.add(date);
        }
        if (!expectedPhysicalDates.equals(actualDates))
            throw new IllegalStateException("QuestDB stk_st_daily contains dates without exact receipt-backed rows");
    }

    private static List<LocalDate> readLongRange(StockStTradingDates tradingDates, LocalDate from, LocalDate to) {
        var all = new ArrayList<LocalDate>(); LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LocalDate end = cursor.plusDays(StockStDailySyncJobOwner.MAX_WINDOW_DAYS - 1L);
            if (end.isAfter(to)) end = to;
            all.addAll(tradingDates.read(cursor, end));
            if (all.size() > MAX_ENTRIES) throw new IllegalStateException("D012 coverage exceeds bounded session inventory");
            cursor = end.plusDays(1);
        }
        return List.copyOf(all);
    }

    static boolean sameRows(List<StockStDaily> expected, List<StockStDaily> actual, VerifiedBatchExecutor.Codec<StockStDaily, StockStDailyKey> codec) {
        if (expected.size() != actual.size()) return false;
        var left = new HashMap<StockStDailyKey,byte[]>(); var right = new HashMap<StockStDailyKey,byte[]>();
        for (var row : expected) if (left.putIfAbsent(row.key(), codec.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (right.putIfAbsent(row.key(), codec.canonicalBytes(row)) != null) return false;
        return left.keySet().equals(right.keySet())
                && left.keySet().stream().allMatch(key -> Arrays.equals(left.get(key),right.get(key)));
    }

    private static boolean sameDefinition(JsonNode expected, JsonNode actual) {
        if (!expected.isObject() || !actual.isObject()) return false;
        try {
            var json = JobDefinitionJson.mapper();
            return json.treeToValue(expected, SyncJobDefinition.class)
                    .equals(json.treeToValue(actual, SyncJobDefinition.class));
        } catch (Exception malformed) { return false; }
    }
    private static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid D012 frozen date", invalid); }
    }
}
