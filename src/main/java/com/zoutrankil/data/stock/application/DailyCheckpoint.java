package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.port.DailyWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Verified-ledger coverage plus actual date scans; never derives a checkpoint from MAX(trade_date). */
public final class DailyCheckpoint {
    private DailyCheckpoint() {}
    private static final int MAX_HISTORY = 10000;
    private static final int MAX_TARGET_INTERVAL_DAYS = 10000;

    public record VerifiedRun(String runId, LocalDate from, LocalDate through, Instant verifiedAt,
                              SyncJobDefinition.Mode mode) {
        public VerifiedRun {
            Objects.requireNonNull(runId); Objects.requireNonNull(from); Objects.requireNonNull(through);
            Objects.requireNonNull(verifiedAt); Objects.requireNonNull(mode);
            if (from.isAfter(through)) throw new IllegalArgumentException("Invalid verified daily interval");
        }
    }

    public record Coverage(List<VerifiedRun> verifiedRuns, LocalDate from, LocalDate through) {
        public Coverage { verifiedRuns = List.copyOf(verifiedRuns); }
    }

    /** Schedule-only ledgers can be empty; partially initialized run ledgers are not treated as empty. */
    public static boolean hasHistorySchema(Path ledgerPath) throws Exception {
        if (!java.nio.file.Files.isRegularFile(ledgerPath)) return false;
        var names = SqliteLedgerSchema.tableNames(ledgerPath);
        var required = Set.of("ledger_meta", "sync_runs", "sync_entries", "sync_events");
        if (java.util.Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial sync-run ledger schema");
        return true;
    }

    /** Returns the greatest contiguous verified interval beginning at the explicit bootstrap date. */
    public static Coverage load(SyncRunLedger ledger, String targetId, LocalDate bootstrapFrom) throws Exception {
        var intervals = new ArrayList<Interval>();
        String after = null;
        int seen = 0;
        var json = JobDefinitionJson.mapper();
        while (true) {
            var page = ledger.history("data.daily", after, 100);
            for (var summary : page) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("Daily checkpoint history exceeds bounded scan; archive required");
                if ((summary.state() != SyncRunState.VERIFIED && summary.state() != SyncRunState.VERIFIED_EMPTY)
                        || !summary.targetId().equals(targetId) || summary.jobVersion() != 1) continue;
                var run = ledger.getRun(summary.id());
                var frozen = json.readTree(run.frozenJson());
                if (!frozen.at("/definition/datasetId").asText().equals("daily")
                        || frozen.at("/definition/datasetVersion").asInt() != DailyDataset.DEFINITION.schemaVersion())
                    throw new IllegalStateException("Verified daily run snapshot has a different definition");
                var fromNode = frozen.get("from");
                var toNode = frozen.get("to");
                if (fromNode == null || !fromNode.isTextual() || toNode == null || !toNode.isTextual())
                    throw new IllegalStateException("Verified daily run lacks bounded date coverage");
                LocalDate from = LocalDate.parse(fromNode.textValue());
                LocalDate through = LocalDate.parse(toNode.textValue());
                long intervalDays = Duration.between(from.atStartOfDay(), through.plusDays(1).atStartOfDay()).toDays();
                if (from.isAfter(through) || intervalDays > DailyTradingSessions.MAX_WINDOW_DAYS)
                    throw new IllegalStateException("Verified daily run has an invalid or unbounded date interval");
                var modeNode = frozen.path("mode");
                if (!modeNode.isTextual()) throw new IllegalStateException("Verified daily run lacks its frozen sync mode");
                SyncJobDefinition.Mode mode;
                try { mode = SyncJobDefinition.Mode.valueOf(modeNode.asText()); }
                catch (IllegalArgumentException invalid) { throw new IllegalStateException("Verified daily run has an unknown sync mode", invalid); }
                intervals.add(new Interval(summary.id(), from, through, Instant.parse(summary.updatedAt()), mode));
            }
            if (page.size() < 100) break;
            after = page.getLast().id();
        }
        intervals.sort(Comparator.comparing(Interval::from).thenComparing(Interval::through));
        var allVerifiedRuns = intervals.stream()
                .filter(interval -> !interval.through().isBefore(bootstrapFrom))
                .map(interval -> new VerifiedRun(interval.runId(), interval.from(), interval.through(),
                        interval.verifiedAt(), interval.mode()))
                .toList();
        LocalDate coverageThrough = contiguousIncrementalThrough(bootstrapFrom, allVerifiedRuns);
        if (coverageThrough == null) return null;
        var coverageRuns = intervals.stream()
                .filter(interval -> !interval.from().isAfter(coverageThrough) && !interval.through().isBefore(bootstrapFrom))
                .map(interval -> new VerifiedRun(interval.runId(), interval.from(), interval.through(),
                        interval.verifiedAt(), interval.mode()))
                .toList();
        return new Coverage(coverageRuns, bootstrapFrom, coverageThrough);
    }

    /** Every persisted target date must be inside this target's contiguous verified coverage and have a valid source receipt. */
    public static int validateExistingTargetDates(SyncRunLedger ledger, Coverage coverage, LocalDate bootstrapFrom,
            ExchangeCalendarReadRepository calendars, DailyWriteSession writer) throws Exception {
        var actualDates = writer.readExistingDates();
        if (actualDates.isEmpty()) return 0;
        if (coverage == null) throw new IllegalStateException("Daily target contains data without same-target verified ledger coverage");
        if (actualDates.getFirst().isBefore(coverage.from()) || actualDates.getLast().isAfter(coverage.through()))
            throw new IllegalStateException("Daily target date interval extends beyond same-target verified ledger coverage");
        if (actualDates.getFirst().isBefore(bootstrapFrom))
            throw new IllegalStateException("Daily target contains data earlier than the explicit bootstrap floor");
        long spanDays = Duration.between(actualDates.getFirst().atStartOfDay(), actualDates.getLast().plusDays(1).atStartOfDay()).toDays();
        if (spanDays > MAX_TARGET_INTERVAL_DAYS)
            throw new IllegalStateException("Daily target interval exceeds the bounded 10000-calendar-day ledger reconciliation limit");

        var openSessions = new HashSet<LocalDate>();
        LocalDate chunkFrom = actualDates.getFirst();
        LocalDate end = actualDates.getLast();
        while (!chunkFrom.isAfter(end)) {
            LocalDate chunkTo = chunkFrom.plusDays(DailyTradingSessions.MAX_WINDOW_DAYS - 1L);
            if (chunkTo.isAfter(end)) chunkTo = end;
            openSessions.addAll(DailyTradingSessions.read(calendars, chunkFrom, chunkTo));
            chunkFrom = chunkTo.plusDays(1);
        }
        for (var date : actualDates) {
            if (!openSessions.contains(date))
                throw new IllegalStateException("Daily target contains a non-session date without an explainable source slice: " + date);
        }
        var latestRuns = latestRunsForDates(actualDates, coverage.verifiedRuns());
        var receipts = receiptsForDates(ledger, latestRuns);
        for (var date : actualDates) {
            var receipt = receipts.get(date);
            if (receipt == null) throw new IllegalStateException("Daily target date lacks a same-target verified source receipt: " + date);
            DailySource.reopen(receipt.path(), receipt.fingerprint(), date);
        }
        return actualDates.size();
    }

    /** Revalidates every session's saved source page against the actual QuestDB date before reuse. */
    public static int validateOverlap(SyncRunLedger ledger, Coverage coverage, LocalDate overlapFrom,
            ExchangeCalendarReadRepository calendars, DailyWriteSession writer) throws Exception {
        if (overlapFrom.isAfter(coverage.through())) throw new IllegalArgumentException("Checkpoint overlap is empty");
        var expectedDays = DailyTradingSessions.read(calendars, overlapFrom, coverage.through());
        var latestRuns = latestRunsForDates(expectedDays, coverage.verifiedRuns());
        var receipts = receiptsForDates(ledger, latestRuns);
        for (var date : expectedDays) {
            var receipt = receipts.get(date);
            if (receipt == null) throw new IllegalStateException("Daily checkpoint lacks a source receipt for an open session");
            var source = DailySource.reopen(receipt.path(), receipt.fingerprint(), date).rows();
            var actual = writer.readDate(date);
            if (!sameRows(source, actual, writer.codec())) throw new IllegalStateException("QuestDB values differ from verified daily checkpoint on " + date);
        }
        return expectedDays.size();
    }

    static boolean sameRows(List<DailyMarketBar> expected, List<DailyMarketBar> actual, VerifiedBatchExecutor.Codec<DailyMarketBar, DailyMarketBar.Key> codec) {
        if (expected.size() != actual.size()) return false;
        var left = new HashMap<DailyMarketBar.Key, byte[]>();
        var right = new HashMap<DailyMarketBar.Key, byte[]>();
        for (var row : expected) if (left.putIfAbsent(row.key(), codec.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (right.putIfAbsent(row.key(), codec.canonicalBytes(row)) != null) return false;
        if (!left.keySet().equals(right.keySet())) return false;
        return left.keySet().stream().allMatch(key -> Arrays.equals(left.get(key), right.get(key)));
    }

    static Map<LocalDate, VerifiedRun> latestRunsForDates(List<LocalDate> dates, List<VerifiedRun> runs) {
        var requested = new HashSet<>(dates);
        var selected = new HashMap<LocalDate, VerifiedRun>();
        var orderedRuns = runs.stream().sorted(Comparator.comparing(VerifiedRun::verifiedAt)
                .thenComparing(VerifiedRun::runId)).toList();
        // Ledger history is keyed by immutable run ID, so verifiedAt is the primary order and runId is only a stable tie-breaker.
        for (var run : orderedRuns) {
            LocalDate date = run.from();
            while (!date.isAfter(run.through())) {
                if (requested.contains(date)) selected.put(date, run);
                date = date.plusDays(1);
            }
        }
        return Map.copyOf(selected);
    }

    static LocalDate contiguousIncrementalThrough(LocalDate bootstrapFrom, List<VerifiedRun> runs) {
        var incremental = runs.stream().filter(run -> run.mode() == SyncJobDefinition.Mode.INCREMENTAL)
                .sorted(Comparator.comparing(VerifiedRun::from).thenComparing(VerifiedRun::through)
                        .thenComparing(VerifiedRun::verifiedAt).thenComparing(VerifiedRun::runId)).toList();
        LocalDate through = bootstrapFrom.minusDays(1);
        boolean anchored = false;
        for (var run : incremental) {
            if (run.through().isBefore(bootstrapFrom)) continue;
            if (!anchored) {
                if (!run.from().equals(bootstrapFrom)) continue;
                anchored = true;
            }
            if (run.from().isAfter(through.plusDays(1))) break;
            if (run.through().isAfter(through)) through = run.through();
        }
        return anchored && !through.isBefore(bootstrapFrom) ? through : null;
    }

    private static Map<LocalDate, Receipt> receiptsForDates(SyncRunLedger ledger,
            Map<LocalDate, VerifiedRun> latestRuns) throws Exception {
        var runDates = new HashMap<String, Set<LocalDate>>();
        latestRuns.forEach((date, run) -> runDates.computeIfAbsent(run.runId(), ignored -> new HashSet<>()).add(date));
        var receipts = new HashMap<LocalDate, Receipt>();
        var json = JobDefinitionJson.mapper();
        for (var runEntry : runDates.entrySet()) {
            var run = ledger.entries(runEntry.getKey(), null, 1000);
            if (run.size() >= 1000) throw new IllegalStateException("Daily verified run has too many ledger entries to scan safely");
            var expectedDates = runEntry.getValue();
            for (var entry : run) {
                if (entry.kind() != SyncRunLedger.Kind.SLICE
                        || entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY) continue;
                String fetched = null;
                for (var event : ledger.events(entry.id(), -1, 20)) {
                    if (event.state() == SyncRunState.FETCHED) { fetched = event.payloadJson(); break; }
                }
                if (fetched == null) continue;
                JsonNode proof = json.readTree(fetched);
                String cursor = proof.path("cursor").asText("");
                if (!cursor.matches("[0-9]{8}")) continue;
                LocalDate date = LocalDate.parse(cursor, DateTimeFormatter.BASIC_ISO_DATE);
                if (!expectedDates.contains(date)) continue;
                String evidence = proof.path("responseEvidence").asText("");
                String fingerprint = proof.path("sourceFingerprint").asText("");
                if (evidence.isBlank() || !fingerprint.matches("[0-9a-f]{64}"))
                    throw new IllegalStateException("Daily checkpoint has an invalid source receipt reference");
                if (receipts.putIfAbsent(date, new Receipt(Path.of(evidence), fingerprint)) != null)
                    throw new IllegalStateException("Daily verified run contains duplicate receipts for one trade date");
            }
        }
        return receipts;
    }

    private record Interval(String runId, LocalDate from, LocalDate through, Instant verifiedAt,
                            SyncJobDefinition.Mode mode) {}
    private record Receipt(Path path, String fingerprint) {}
}
