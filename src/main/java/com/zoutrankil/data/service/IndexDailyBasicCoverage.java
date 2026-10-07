package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.IndexDailyBasicWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Receipt-backed D020 incremental checkpoint and same-target actual-row reconciliation. */
public final class IndexDailyBasicCoverage {
    private static final int HISTORY_PAGE = 100, MAX_HISTORY = 10_000, MAX_CHILDREN = 100, MAX_COVERED_DATES = 20_000;
    private IndexDailyBasicCoverage() {}
    public record Checkpoint(LocalDate anchor, LocalDate through, Instant latestVerifiedAt) {
        public Checkpoint {
            Objects.requireNonNull(anchor); Objects.requireNonNull(through); Objects.requireNonNull(latestVerifiedAt);
            if (through.isBefore(anchor)) throw new IllegalArgumentException("Invalid D020 checkpoint interval");
        }
    }
    private record Interval(String runId, LocalDate anchor, LocalDate from, LocalDate to, Instant verifiedAt,
            SyncJobRunner.Page<IndexDailyBasic> page) {}

    public static Optional<Checkpoint> checkpoint(Path ledgerPath, String targetId, String code) throws Exception {
        String canonical = IndexDailyBasicSource.requireCode(code);
        var intervals = verifiedIntervals(ledgerPath, targetId, canonical, EnumSet.of(SyncJobDefinition.Mode.INCREMENTAL));
        var byAnchor = new HashMap<LocalDate,List<Interval>>();
        for (var interval : intervals) {
            if (interval.anchor() == null) throw new IllegalStateException("D020 incremental source proof lacks bootstrap anchor");
            byAnchor.computeIfAbsent(interval.anchor(), ignored -> new ArrayList<>()).add(interval);
        }
        var candidates = new ArrayList<Checkpoint>();
        for (var entry : byAnchor.entrySet()) {
            LocalDate anchor = entry.getKey(), through = anchor.minusDays(1); boolean started = false;
            Instant latest = Instant.MIN;
            var ordered = entry.getValue().stream().sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to)
                    .thenComparing(Interval::verifiedAt).thenComparing(Interval::runId)).toList();
            for (var interval : ordered) {
                if (!started) { if (!interval.from().equals(anchor)) continue; started = true; }
                if (interval.from().isAfter(through.plusDays(1))) break;
                if (interval.to().isAfter(through)) through = interval.to();
                if (interval.verifiedAt().isAfter(latest)) latest = interval.verifiedAt();
            }
            if (started && !through.isBefore(anchor)) candidates.add(new Checkpoint(anchor, through, latest));
        }
        return candidates.stream().max(Comparator.comparing(Checkpoint::through).thenComparing(Checkpoint::latestVerifiedAt)
                .thenComparing(Checkpoint::anchor, Comparator.reverseOrder()));
    }

    /** Ensures every physical row is covered by the latest full source receipt for its day. */
    public static int validateExistingTarget(Path ledgerPath, String targetId, String code,
            IndexDailyBasicWritePort writer) throws Exception {
        String canonical = IndexDailyBasicSource.requireCode(code); var actual = writer.readExistingRows(canonical);
        var intervals = verifiedIntervals(ledgerPath, targetId, canonical,
                EnumSet.of(SyncJobDefinition.Mode.INCREMENTAL, SyncJobDefinition.Mode.BACKFILL, SyncJobDefinition.Mode.RECONCILE));
        if (actual.isEmpty() && intervals.isEmpty()) return 0;
        if (!actual.isEmpty() && intervals.isEmpty()) throw new IllegalStateException("D020 target rows lack same-target verified source receipts");
        var newest = new HashMap<LocalDate,Interval>();
        for (var interval : intervals) {
            long days = ChronoUnit.DAYS.between(interval.from(), interval.to()) + 1;
            if (days < 1 || days > IndexDailyBasicSource.MAX_WINDOW_DAYS) throw new IllegalStateException("D020 receipt exceeds window bound");
            for (LocalDate day = interval.from(); !day.isAfter(interval.to()); day = day.plusDays(1)) {
                var prior = newest.get(day);
                if (prior == null || interval.verifiedAt().isAfter(prior.verifiedAt())
                        || interval.verifiedAt().equals(prior.verifiedAt()) && interval.runId().compareTo(prior.runId()) > 0)
                    newest.put(day, interval);
                if (newest.size() > MAX_COVERED_DATES) throw new IllegalStateException("D020 receipt coverage exceeds date budget");
            }
        }
        var expected = new HashMap<LocalDate,List<IndexDailyBasic>>();
        for (var entry : newest.entrySet()) expected.put(entry.getKey(), entry.getValue().page().rows().stream()
                .filter(row -> row.tradeDate().equals(entry.getKey())).toList());
        var actualByDate = new HashMap<LocalDate,List<IndexDailyBasic>>();
        for (var row : actual) actualByDate.computeIfAbsent(row.tradeDate(), ignored -> new ArrayList<>()).add(row);
        if (!expected.keySet().containsAll(actualByDate.keySet())) throw new IllegalStateException("D020 target contains an uncovered code/date");
        for (var entry : expected.entrySet()) if (!sameRows(entry.getValue(), actualByDate.getOrDefault(entry.getKey(), List.of())))
            throw new IllegalStateException("D020 physical values differ from newest verified source receipt at " + entry.getKey());
        return actual.size();
    }

    private static List<Interval> verifiedIntervals(Path path, String targetId, String code,
            Set<SyncJobDefinition.Mode> modes) throws Exception {
        Objects.requireNonNull(path); Objects.requireNonNull(targetId);
        if (!Files.isRegularFile(path) || !hasHistorySchema(path)) return List.of();
        var ledger = SyncRunLedger.openReadOnly(path); var json = JobDefinitionJson.mapper();
        JsonNode expected = json.valueToTree(IndexDailyBasicSyncJobOwner.DEFINITION);
        var found = new ArrayList<Interval>(); String after = null; int seen = 0;
        while (true) {
            var summaries = ledger.history(IndexDailyBasicSyncJobOwner.DEFINITION.jobId(), after, HISTORY_PAGE);
            for (var summary : summaries) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("D020 ledger history exceeds bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion() != IndexDailyBasicSyncJobOwner.DEFINITION.version()) continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !IndexDailyBasicSyncJobOwner.DEFINITION.jobId().equals(run.jobId())
                        || run.jobVersion() != IndexDailyBasicSyncJobOwner.DEFINITION.version()) continue;
                JsonNode frozen = json.readTree(run.frozenJson());
                if (!sameDefinition(expected, frozen.path("definition"))) continue;
                SyncJobDefinition.Mode mode;
                try { mode = SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText()); }
                catch (RuntimeException invalid) { throw new IllegalStateException("Invalid D020 frozen mode", invalid); }
                if (!modes.contains(mode) || !targetId.equals(frozen.path("parameters").path("targetId").asText())
                        || !code.equals(frozen.path("parameters").path("tsCode").asText())) continue;
                LocalDate from = date(frozen.path("from").asText()), to = date(frozen.path("to").asText());
                if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > IndexDailyBasicSource.MAX_WINDOW_DAYS)
                    throw new IllegalStateException("Invalid D020 frozen date window");
                LocalDate anchor = null;
                if (mode == SyncJobDefinition.Mode.INCREMENTAL) {
                    var a = frozen.path("parameters").path("checkpointAnchor");
                    if (!a.isTextual()) throw new IllegalStateException("D020 incremental receipt lacks anchor");
                    anchor = date(a.asText());
                    if (from.isBefore(anchor) || to.isBefore(anchor)) throw new IllegalStateException("D020 window precedes bootstrap anchor");
                }
                found.add(readVerifiedSlice(ledger, summary.id(), summary.state(), targetId, code, mode, anchor,
                        from, to, Instant.parse(summary.updatedAt())));
            }
            if (summaries.size() < HISTORY_PAGE) break;
            after = summaries.getLast().id();
        }
        return List.copyOf(found);
    }
    private static Interval readVerifiedSlice(SyncRunLedger ledger, String runId, SyncRunState runState, String targetId,
            String code, SyncJobDefinition.Mode mode, LocalDate anchor, LocalDate from, LocalDate to, Instant verifiedAt) throws Exception {
        var slices = new ArrayList<SyncRunLedger.Entry>(); String after = null; int seen = 0;
        while (true) {
            var entries = ledger.entries(runId, after, MAX_CHILDREN);
            for (var entry : entries) { if (++seen > MAX_CHILDREN) throw new IllegalStateException("D020 run has too many children");
                if (entry.kind() == SyncRunLedger.Kind.SLICE) slices.add(entry); }
            if (entries.size() < MAX_CHILDREN) break; after = entries.getLast().id();
        }
        if (slices.size() != 1) throw new IllegalStateException("D020 run must contain exactly one source slice");
        var slice = slices.getFirst();
        if (slice.state() != SyncRunState.VERIFIED && slice.state() != SyncRunState.VERIFIED_EMPTY)
            throw new IllegalStateException("D020 run contains nonverified slice");
        JsonNode fetched = null;
        for (var event : ledger.events(slice.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
            if (fetched != null) throw new IllegalStateException("Duplicate D020 fetched receipt event");
            fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
        }
        if (fetched == null || !code.equals(fetched.path("cursor").asText())) throw new IllegalStateException("D020 receipt source identity differs");
        String path = fetched.path("responseEvidence").asText(""), fingerprint = fetched.path("sourceFingerprint").asText("");
        if (path.isBlank() || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid D020 receipt reference");
        var page = IndexDailyBasicSource.reopen(Path.of(path), fingerprint, code, from, to);
        if (page.rows().isEmpty() != (slice.state() == SyncRunState.VERIFIED_EMPTY)
                || page.rows().isEmpty() != (runState == SyncRunState.VERIFIED_EMPTY))
            throw new IllegalStateException("D020 empty verification differs from source receipt");
        return new Interval(runId, anchor, from, to, verifiedAt, page);
    }
    private static boolean sameRows(List<IndexDailyBasic> expected, List<IndexDailyBasic> actual) {
        if (expected.size() != actual.size()) return false;
        var left = new HashMap<IndexDailyBasicKey,byte[]>(); var right = new HashMap<IndexDailyBasicKey,byte[]>();
        for (var row : expected) if (left.putIfAbsent(row.key(), IndexDailyBasicWritePort.CODEC.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (right.putIfAbsent(row.key(), IndexDailyBasicWritePort.CODEC.canonicalBytes(row)) != null) return false;
        return left.keySet().equals(right.keySet()) && left.keySet().stream().allMatch(key -> Arrays.equals(left.get(key), right.get(key)));
    }
    private static boolean sameDefinition(JsonNode expected, JsonNode actual) {
        try { var json = JobDefinitionJson.mapper(); return json.treeToValue(expected, SyncJobDefinition.class).equals(json.treeToValue(actual, SyncJobDefinition.class)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return false; }
    }
    private static LocalDate date(String value) {
        try { if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException(); return LocalDate.parse(value); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid frozen D020 date", invalid); }
    }
    private static boolean hasHistorySchema(Path path) throws Exception {
        var names = SqliteLedgerSchema.tableNames(path);
        var required = Set.of("ledger_meta", "sync_runs", "sync_entries", "sync_events");
        if (java.util.Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial D020 sync ledger schema");
        return true;
    }
}
