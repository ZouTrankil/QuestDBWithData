package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.port.StockDateWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Receipt-backed incremental checkpoint for one isolated target and frozen bootstrap anchor. */
public final class StockLimitCoverage {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_HISTORY = 10_000;
    private static final int MAX_DATES = 10_000;
    private StockLimitCoverage() {}

    public record Receipt(Path path, String fingerprint, LocalDate date) {
        public Receipt { Objects.requireNonNull(path); Objects.requireNonNull(fingerprint); Objects.requireNonNull(date); }
    }
    public record Coverage(LocalDate anchor, LocalDate through, Map<LocalDate,Receipt> receipts,
                          Instant latestVerifiedAt) {
        public Coverage {
            Objects.requireNonNull(anchor); Objects.requireNonNull(through); Objects.requireNonNull(latestVerifiedAt);
            if (through.isBefore(anchor)) throw new IllegalArgumentException("Invalid stk_limit verified interval");
            receipts = Map.copyOf(receipts);
        }
    }
    private record Interval(String runId, LocalDate anchor, LocalDate from, LocalDate to,
                            Instant verifiedAt, Map<LocalDate,Receipt> receipts) {}

    public static boolean hasHistorySchema(Path path) throws Exception {
        if (!Files.isRegularFile(path)) return false;
        var names = SqliteLedgerSchema.tableNames(path);
        var required = Set.of("ledger_meta", "sync_runs", "sync_entries", "sync_events");
        if (java.util.Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial sync-run ledger schema");
        return true;
    }

    public static Optional<Coverage> checkpoint(Path path, String targetId,
            StockLimitTradingDates calendars) throws Exception {
        Objects.requireNonNull(path); Objects.requireNonNull(targetId); Objects.requireNonNull(calendars);
        if (!Files.isRegularFile(path) || !hasHistorySchema(path)) return Optional.empty();
        var ledger = SyncRunLedger.openReadOnly(path); var json = JobDefinitionJson.mapper();
        JsonNode expectedDefinition = json.valueToTree(StockLimitSyncJobOwner.DEFINITION);
        var intervals = new ArrayList<Interval>(); String after = null; int seen = 0;
        while (true) {
            var summaries = ledger.history("data.stk_limit", after, PAGE_SIZE);
            for (var summary : summaries) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("stk_limit checkpoint history exceeds bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion() != 1) continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !"data.stk_limit".equals(run.jobId()) || run.jobVersion() != 1) continue;
                JsonNode frozen = json.readTree(run.frozenJson());
                if (!sameDefinition(expectedDefinition, frozen.path("definition"))) continue;
                if (!"INCREMENTAL".equals(frozen.path("mode").asText())) continue; // Backfill/reconcile never advance checkpoint.
                JsonNode params = frozen.path("parameters");
                if (!params.isObject() || !targetId.equals(params.path("targetId").asText()))
                    throw new IllegalStateException("Verified stk_limit run lacks its exact frozen target identity");
                JsonNode anchorNode = params.get("checkpointAnchor");
                if (anchorNode == null || !anchorNode.isTextual())
                    throw new IllegalStateException("Verified incremental stk_limit run lacks bootstrap anchor");
                LocalDate anchor = parseDate(anchorNode.asText());
                LocalDate from = parseDate(frozen.path("from").asText());
                LocalDate to = parseDate(frozen.path("to").asText());
                long span = ChronoUnit.DAYS.between(from, to) + 1;
                if (from.isAfter(to) || span > StockLimitSyncJobOwner.MAX_WINDOW_DAYS || from.isBefore(anchor)
                        || to.isBefore(anchor)) throw new IllegalStateException("Invalid verified stk_limit interval");
                var tradeDates = calendars.read(from, to);
                if (!StockLimitSyncAdapter.encodeTradeDates(tradeDates).equals(params.path("trade_dates").asText()))
                    throw new IllegalStateException("Verified stk_limit frozen date list differs from D001 calendar");
                Map<LocalDate,Receipt> receipts = receipts(ledger, summary.id(), tradeDates, summary.state());
                intervals.add(new Interval(summary.id(), anchor, from, to, Instant.parse(summary.updatedAt()), receipts));
            }
            if (summaries.size() < PAGE_SIZE) break;
            after = summaries.getLast().id(); // History paging is ID-ordered; time ordering is handled below.
        }
        return mergeLatestContinuous(intervals);
    }

    private static Map<LocalDate,Receipt> receipts(SyncRunLedger ledger, String runId,
            List<LocalDate> expectedDates, SyncRunState runState) throws Exception {
        var expected = new HashSet<>(expectedDates); var found = new HashMap<LocalDate,Receipt>();
        String after = null; int seen = 0;
        while (true) {
            var entries = ledger.entries(runId, after, 1000);
            for (var entry : entries) {
                if (++seen > MAX_DATES) throw new IllegalStateException("stk_limit run has too many date slices");
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("Verified stk_limit run contains an unverified slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
                    if (fetched != null) throw new IllegalStateException("Duplicate stk_limit FETCHED receipt event");
                    fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                }
                if (fetched == null) throw new IllegalStateException("Verified stk_limit slice lacks FETCHED source evidence");
                String cursor = fetched.path("cursor").asText("");
                if (!cursor.matches("[0-9]{8}")) throw new IllegalStateException("Invalid stk_limit receipt date cursor");
                LocalDate date = LocalDate.parse(cursor, DateTimeFormatter.BASIC_ISO_DATE);
                if (!expected.contains(date)) throw new IllegalStateException("stk_limit receipt lies outside frozen calendar dates");
                String path = fetched.path("responseEvidence").asText("");
                String hash = fetched.path("sourceFingerprint").asText("");
                if (path.isBlank() || !hash.matches("[0-9a-f]{64}"))
                    throw new IllegalStateException("Invalid stk_limit receipt path/fingerprint");
                var page = StockLimitSource.reopen(Path.of(path), hash, date);
                if (page.rows().isEmpty() != (entry.state() == SyncRunState.VERIFIED_EMPTY))
                    throw new IllegalStateException("stk_limit slice state differs from source row count");
                if (found.putIfAbsent(date, new Receipt(Path.of(path), hash, date)) != null)
                    throw new IllegalStateException("Duplicate receipt for a stk_limit trade date");
            }
            if (entries.size() < 1000) break;
            after = entries.getLast().id();
        }
        if (!found.keySet().equals(expected))
            throw new IllegalStateException("Verified stk_limit interval is missing one or more date receipts");
        boolean hasRows = found.values().stream().anyMatch(receipt -> {
            try { return !StockLimitSource.reopen(receipt.path(), receipt.fingerprint(), receipt.date()).rows().isEmpty(); }
            catch (Exception failure) { throw new IllegalStateException("Cannot reopen stk_limit receipt", failure); }
        });
        if (runState == SyncRunState.VERIFIED_EMPTY && hasRows)
            throw new IllegalStateException("VERIFIED_EMPTY stk_limit run contains source rows");
        if (runState == SyncRunState.VERIFIED && !hasRows)
            throw new IllegalStateException("VERIFIED stk_limit run has no source rows");
        return Map.copyOf(found);
    }

    private static Optional<Coverage> mergeLatestContinuous(List<Interval> intervals) {
        if (intervals.isEmpty()) return Optional.empty();
        var groups = new HashMap<LocalDate,List<Interval>>();
        intervals.forEach(i -> groups.computeIfAbsent(i.anchor(), ignored -> new ArrayList<>()).add(i));
        var candidates = new ArrayList<Coverage>();
        for (var group : groups.entrySet()) {
            LocalDate anchor = group.getKey();
            var ordered = group.getValue().stream().sorted(Comparator.comparing(Interval::from)
                    .thenComparing(Interval::to).thenComparing(Interval::verifiedAt).thenComparing(Interval::runId)).toList();
            LocalDate through = anchor.minusDays(1); boolean anchored = false;
            Instant latest = Instant.MIN; var selected = new HashMap<LocalDate,TimedReceipt>();
            for (var interval : ordered) {
                if (!anchored) {
                    if (!interval.from().equals(anchor)) continue;
                    anchored = true;
                }
                if (interval.from().isAfter(through.plusDays(1))) break;
                if (interval.to().isAfter(through)) through = interval.to();
                if (interval.verifiedAt().isAfter(latest)) latest = interval.verifiedAt();
                interval.receipts().forEach((date,receipt) -> {
                    TimedReceipt old = selected.get(date);
                    if (old == null || interval.verifiedAt().isAfter(old.verifiedAt)
                            || interval.verifiedAt().equals(old.verifiedAt) && interval.runId().compareTo(old.runId()) > 0)
                        selected.put(date, new TimedReceipt(interval.verifiedAt(), interval.runId(), receipt));
                });
            }
            if (anchored && !through.isBefore(anchor)) {
                var receipts = new HashMap<LocalDate,Receipt>(); selected.forEach((date, timed) -> receipts.put(date, timed.receipt));
                candidates.add(new Coverage(anchor, through, receipts, latest));
            }
        }
        // Prefer greatest covered-through date, then actual verified time; immutable run IDs are never treated as chronology.
        return candidates.stream().max(Comparator.comparing(Coverage::through).thenComparing(Coverage::latestVerifiedAt)
                .thenComparing(Coverage::anchor, Comparator.reverseOrder()));
    }

    private record TimedReceipt(Instant verifiedAt, String runId, Receipt receipt) {}

    /** Every existing physical date must be explained by the latest incremental receipts, and values must still match. */
    public static int validateExistingTarget(Path ledgerPath, Coverage coverage, String targetId,
            StockLimitTradingDates calendars, StockDateWriteSession<StockLimit, StockLimitKey> writer) throws Exception {
        var actualDates = writer.readExistingDates();
        if (actualDates.isEmpty()) {
            if (coverage != null) validateAllReceiptDays(coverage, calendars, writer, Set.of());
            return 0;
        }
        if (coverage == null) throw new IllegalStateException("stk_limit target has data without same-target verified incremental receipts");
        if (actualDates.getFirst().isBefore(coverage.anchor()) || actualDates.getLast().isAfter(coverage.through()))
            throw new IllegalStateException("stk_limit target dates exceed same-target incremental coverage");
        if (actualDates.size() > MAX_DATES) throw new IllegalStateException("stk_limit target date inventory exceeds reconciliation cap");
        validateAllReceiptDays(coverage, calendars, writer, new HashSet<>(actualDates));
        return actualDates.size();
    }

    /** Compare every receipt date in the contiguous chain so missing rows and out-of-ledger mutations fail closed. */
    private static void validateAllReceiptDays(Coverage coverage, StockLimitTradingDates calendars,
            StockDateWriteSession<StockLimit, StockLimitKey> writer, Set<LocalDate> actualDates) throws Exception {
        if (ChronoUnit.DAYS.between(coverage.anchor(), coverage.through()) + 1 > 10_000)
            throw new IllegalStateException("stk_limit checkpoint exceeds bounded 10000-day reconciliation interval");
        var dates = new ArrayList<LocalDate>();
        LocalDate chunkFrom = coverage.anchor();
        while (!chunkFrom.isAfter(coverage.through())) {
            LocalDate chunkTo = chunkFrom.plusDays(StockLimitTradingDates.MAX_WINDOW_DAYS - 1L);
            if (chunkTo.isAfter(coverage.through())) chunkTo = coverage.through();
            dates.addAll(calendars.read(chunkFrom, chunkTo));
            chunkFrom = chunkTo.plusDays(1);
        }
        if (dates.size() != coverage.receipts().size() || !coverage.receipts().keySet().equals(new HashSet<>(dates)))
            throw new IllegalStateException("stk_limit checkpoint receipt dates do not cover its full session interval");
        for (var date : dates) {
            var receipt = coverage.receipts().get(date);
            var expected = StockLimitSource.reopen(receipt.path(), receipt.fingerprint(), date).rows();
            if (expected.isEmpty()) {
                if (actualDates.contains(date) || !writer.readDate(date).isEmpty())
                    throw new IllegalStateException("QuestDB stk_limit has rows for a verified empty source date: " + date);
            } else {
                if (!actualDates.contains(date)) throw new IllegalStateException("QuestDB stk_limit is missing receipt-backed rows for " + date);
                if (!sameRows(expected, writer.readDate(date), writer.codec()))
                    throw new IllegalStateException("QuestDB stk_limit values differ from latest verified receipt on " + date);
            }
        }
        if (!dates.containsAll(actualDates)) throw new IllegalStateException("QuestDB stk_limit has a date without a verified source receipt");
    }

    static boolean sameRows(List<StockLimit> expected, List<StockLimit> actual, VerifiedBatchExecutor.Codec<StockLimit, StockLimitKey> codec) {
        if (expected.size() != actual.size()) return false;
        var left = new HashMap<StockLimitKey,byte[]>(); var right = new HashMap<StockLimitKey,byte[]>();
        for (var row : expected) if (left.putIfAbsent(row.key(), codec.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (right.putIfAbsent(row.key(), codec.canonicalBytes(row)) != null) return false;
        return left.keySet().equals(right.keySet())
                && left.keySet().stream().allMatch(key -> Arrays.equals(left.get(key), right.get(key)));
    }

    private static boolean sameDefinition(JsonNode expected, JsonNode actual) {
        try {
            var json = JobDefinitionJson.mapper();
            return json.treeToValue(expected, SyncJobDefinition.class)
                    .equals(json.treeToValue(actual, SyncJobDefinition.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return false;
        }
    }
    private static LocalDate parseDate(String date) {
        try { return LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid frozen stk_limit date", invalid); }
    }
}
