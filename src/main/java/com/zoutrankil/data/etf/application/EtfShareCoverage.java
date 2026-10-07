package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.etf.port.EtfWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Receipt-backed incremental checkpoint for one isolated target and frozen bootstrap anchor. */
public final class EtfShareCoverage {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_HISTORY = 10_000;
    private static final int MAX_DATES = 10_000;
    private EtfShareCoverage() {}

    public record Receipt(Path path, String fingerprint, LocalDate date) {
        public Receipt { Objects.requireNonNull(path); Objects.requireNonNull(fingerprint); Objects.requireNonNull(date); }
    }
    public record Coverage(LocalDate anchor, LocalDate through, Map<LocalDate,Receipt> receipts,
                          Instant latestVerifiedAt) {
        public Coverage {
            Objects.requireNonNull(anchor); Objects.requireNonNull(through); Objects.requireNonNull(latestVerifiedAt);
            if (through.isBefore(anchor)) throw new IllegalArgumentException("Invalid etf_share verified interval");
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
            EtfShareTradingDates calendars) throws Exception {
        Objects.requireNonNull(path); Objects.requireNonNull(targetId); Objects.requireNonNull(calendars);
        if (!Files.isRegularFile(path) || !hasHistorySchema(path)) return Optional.empty();
        var ledger = SyncRunLedger.openReadOnly(path); var json = JobDefinitionJson.mapper();
        SyncJobDefinition expectedDefinition = EtfShareSyncJobOwner.DEFINITION;
        var intervals = new ArrayList<Interval>();
        var latestValueReceipts = new HashMap<LocalDate,TimedReceipt>();
        String after = null; int seen = 0;
        while (true) {
            var summaries = ledger.history("data.etf_share", after, PAGE_SIZE);
            for (var summary : summaries) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("etf_share checkpoint history exceeds bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion() != expectedDefinition.version()) continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !expectedDefinition.jobId().equals(run.jobId())
                        || run.jobVersion() != expectedDefinition.version()) continue;
                JsonNode frozen = json.readTree(run.frozenJson());
                if (!sameDefinition(expectedDefinition, frozen.path("definition"))) continue;
                Mode mode;
                try { mode = Mode.valueOf(frozen.path("mode").asText()); }
                catch (RuntimeException invalid) { throw new IllegalStateException("Verified etf_share run has an invalid frozen mode", invalid); }
                if (!expectedDefinition.supportedModes().contains(mode))
                    throw new IllegalStateException("Verified etf_share run has an unsupported frozen mode");
                JsonNode params = frozen.path("parameters");
                if (!params.isObject() || !targetId.equals(params.path("targetId").asText()))
                    throw new IllegalStateException("Verified etf_share run lacks its exact frozen target identity");
                LocalDate from = parseDate(frozen.path("from").asText());
                LocalDate to = parseDate(frozen.path("to").asText());
                long span = ChronoUnit.DAYS.between(from, to) + 1;
                if (from.isAfter(to) || span > EtfShareSyncJobOwner.MAX_WINDOW_DAYS)
                    throw new IllegalStateException("Invalid verified etf_share interval");
                var tradeDates = calendars.read(from, to);
                if (!EtfShareSyncAdapter.encodeTradeDates(tradeDates).equals(params.path("trade_dates").asText()))
                    throw new IllegalStateException("Verified etf_share frozen date list differs from D001 calendar");
                Map<LocalDate,Receipt> receipts = receipts(ledger, summary.id(), tradeDates, summary.state());
                Instant verifiedAt = Instant.parse(summary.updatedAt());
                receipts.forEach((date, receipt) -> retainNewest(latestValueReceipts, date,
                        new TimedReceipt(verifiedAt, summary.id(), receipt)));
                if (mode != Mode.INCREMENTAL) {
                    if (params.has("checkpointAnchor") || params.has("checkpointBefore"))
                        throw new IllegalStateException("Verified etf_share BACKFILL/RECONCILE run carries checkpoint metadata");
                    continue; // Revisions can explain physical values, but only incrementals advance coverage.
                }
                JsonNode anchorNode = params.get("checkpointAnchor");
                if (anchorNode == null || !anchorNode.isTextual())
                    throw new IllegalStateException("Verified incremental etf_share run lacks bootstrap anchor");
                LocalDate anchor = parseDate(anchorNode.asText());
                if (from.isBefore(anchor) || to.isBefore(anchor))
                    throw new IllegalStateException("Invalid verified etf_share interval before bootstrap anchor");
                intervals.add(new Interval(summary.id(), anchor, from, to, verifiedAt, receipts));
            }
            if (summaries.size() < PAGE_SIZE) break;
            after = summaries.getLast().id(); // History paging is ID-ordered; time ordering is handled below.
        }
        return mergeLatestContinuous(intervals, latestValueReceipts);
    }

    private static Map<LocalDate,Receipt> receipts(SyncRunLedger ledger, String runId,
            List<LocalDate> expectedDates, SyncRunState runState) throws Exception {
        var expected = new HashSet<>(expectedDates); var found = new HashMap<LocalDate,Receipt>();
        String after = null; int seen = 0;
        while (true) {
            var entries = ledger.entries(runId, after, 1000);
            for (var entry : entries) {
                if (++seen > MAX_DATES) throw new IllegalStateException("etf_share run has too many date slices");
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("Verified etf_share run contains an unverified slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
                    if (fetched != null) throw new IllegalStateException("Duplicate etf_share FETCHED receipt event");
                    fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                }
                if (fetched == null) throw new IllegalStateException("Verified etf_share slice lacks FETCHED source evidence");
                String cursor = fetched.path("cursor").asText("");
                if (!cursor.matches("[0-9]{8}")) throw new IllegalStateException("Invalid etf_share receipt date cursor");
                LocalDate date = LocalDate.parse(cursor, DateTimeFormatter.BASIC_ISO_DATE);
                if (!expected.contains(date)) throw new IllegalStateException("etf_share receipt lies outside frozen calendar dates");
                String path = fetched.path("responseEvidence").asText("");
                String hash = fetched.path("sourceFingerprint").asText("");
                if (path.isBlank() || !hash.matches("[0-9a-f]{64}"))
                    throw new IllegalStateException("Invalid etf_share receipt path/fingerprint");
                var page = EtfShareSource.reopen(Path.of(path), hash, date);
                if (page.rows().isEmpty() != (entry.state() == SyncRunState.VERIFIED_EMPTY))
                    throw new IllegalStateException("etf_share slice state differs from source row count");
                if (found.putIfAbsent(date, new Receipt(Path.of(path), hash, date)) != null)
                    throw new IllegalStateException("Duplicate receipt for a etf_share trade date");
            }
            if (entries.size() < 1000) break;
            after = entries.getLast().id();
        }
        if (!found.keySet().equals(expected))
            throw new IllegalStateException("Verified etf_share interval is missing one or more date receipts");
        boolean hasRows = found.values().stream().anyMatch(receipt -> {
            try { return !EtfShareSource.reopen(receipt.path(), receipt.fingerprint(), receipt.date()).rows().isEmpty(); }
            catch (Exception failure) { throw new IllegalStateException("Cannot reopen etf_share receipt", failure); }
        });
        if (runState == SyncRunState.VERIFIED_EMPTY && hasRows)
            throw new IllegalStateException("VERIFIED_EMPTY etf_share run contains source rows");
        if (runState == SyncRunState.VERIFIED && !hasRows)
            throw new IllegalStateException("VERIFIED etf_share run has no source rows");
        return Map.copyOf(found);
    }

    private static Optional<Coverage> mergeLatestContinuous(List<Interval> intervals,
            Map<LocalDate,TimedReceipt> latestValueReceipts) {
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
                var receipts = new HashMap<LocalDate,Receipt>();
                selected.forEach((date, timed) -> {
                    TimedReceipt latestValue = latestValueReceipts.get(date);
                    receipts.put(date, latestValue == null ? timed.receipt : latestValue.receipt());
                });
                candidates.add(new Coverage(anchor, through, receipts, latest));
            }
        }
        // Prefer greatest covered-through date, then actual verified time; immutable run IDs are never treated as chronology.
        return candidates.stream().max(Comparator.comparing(Coverage::through).thenComparing(Coverage::latestVerifiedAt)
                .thenComparing(Coverage::anchor, Comparator.reverseOrder()));
    }

    private record TimedReceipt(Instant verifiedAt, String runId, Receipt receipt) {}

    private static void retainNewest(Map<LocalDate,TimedReceipt> selected, LocalDate date, TimedReceipt candidate) {
        TimedReceipt old = selected.get(date);
        if (old == null || candidate.verifiedAt().isAfter(old.verifiedAt())
                || candidate.verifiedAt().equals(old.verifiedAt()) && candidate.runId().compareTo(old.runId()) > 0)
            selected.put(date, candidate);
    }

    /** Every existing physical date must be explained by same-target incremental coverage; values use the latest verified receipt per date. */
    public static int validateExistingTarget(Path ledgerPath, Coverage coverage, String targetId,
            EtfShareTradingDates calendars, EtfWriteSession<EtfShare, EtfShareKey> writer) throws Exception {
        var actualDates = writer.readExistingDates();
        if (actualDates.isEmpty()) {
            if (coverage != null) validateAllReceiptDays(coverage, calendars, writer, Set.of());
            return 0;
        }
        if (coverage == null) throw new IllegalStateException("etf_share target has data without same-target verified incremental receipts");
        if (actualDates.getFirst().isBefore(coverage.anchor()) || actualDates.getLast().isAfter(coverage.through()))
            throw new IllegalStateException("etf_share target dates exceed same-target incremental coverage");
        if (actualDates.size() > MAX_DATES) throw new IllegalStateException("etf_share target date inventory exceeds reconciliation cap");
        validateAllReceiptDays(coverage, calendars, writer, new HashSet<>(actualDates));
        return actualDates.size();
    }

    /** Compare every incremental checkpoint date to its newest verified same-target receipt across supported modes. */
    private static void validateAllReceiptDays(Coverage coverage, EtfShareTradingDates calendars,
            EtfWriteSession<EtfShare, EtfShareKey> writer, Set<LocalDate> actualDates) throws Exception {
        if (ChronoUnit.DAYS.between(coverage.anchor(), coverage.through()) + 1 > 10_000)
            throw new IllegalStateException("etf_share checkpoint exceeds bounded 10000-day reconciliation interval");
        var dates = new ArrayList<LocalDate>();
        LocalDate chunkFrom = coverage.anchor();
        while (!chunkFrom.isAfter(coverage.through())) {
            LocalDate chunkTo = chunkFrom.plusDays(EtfShareTradingDates.MAX_WINDOW_DAYS - 1L);
            if (chunkTo.isAfter(coverage.through())) chunkTo = coverage.through();
            dates.addAll(calendars.read(chunkFrom, chunkTo));
            chunkFrom = chunkTo.plusDays(1);
        }
        if (dates.size() != coverage.receipts().size() || !coverage.receipts().keySet().equals(new HashSet<>(dates)))
            throw new IllegalStateException("etf_share checkpoint receipt dates do not cover its full session interval");
        for (var date : dates) {
            var receipt = coverage.receipts().get(date);
            var expected = EtfShareSource.reopen(receipt.path(), receipt.fingerprint(), date).rows();
            if (expected.isEmpty()) {
                if (actualDates.contains(date) || !writer.readDate(date).isEmpty())
                    throw new IllegalStateException("QuestDB etf_share has rows for a verified empty source date: " + date);
            } else {
                if (!actualDates.contains(date)) throw new IllegalStateException("QuestDB etf_share is missing receipt-backed rows for " + date);
                if (!sameRows(expected, writer.readDate(date), writer.codec()))
                    throw new IllegalStateException("QuestDB etf_share values differ from latest verified receipt on " + date);
            }
        }
        if (!dates.containsAll(actualDates)) throw new IllegalStateException("QuestDB etf_share has a date without a verified source receipt");
    }

    static boolean sameRows(List<EtfShare> expected, List<EtfShare> actual,
                                    VerifiedBatchExecutor.Codec<EtfShare, EtfShareKey> codec) {
        if (expected.size() != actual.size()) return false;
        var left = new HashMap<EtfShareKey,byte[]>(); var right = new HashMap<EtfShareKey,byte[]>();
        for (var row : expected) if (left.putIfAbsent(row.key(), codec.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (right.putIfAbsent(row.key(), codec.canonicalBytes(row)) != null) return false;
        return left.keySet().equals(right.keySet())
                && left.keySet().stream().allMatch(key -> Arrays.equals(left.get(key), right.get(key)));
    }

    private static boolean sameDefinition(SyncJobDefinition expected, JsonNode actual) throws Exception {
        if (actual == null || !actual.isObject()) return false;
        return expected.equals(JobDefinitionJson.mapper().treeToValue(actual, SyncJobDefinition.class));
    }
    private static LocalDate parseDate(String date) {
        try { return LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid frozen etf_share date", invalid); }
    }
}
