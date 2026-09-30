package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.IndexDailyMarketWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Receipt-backed D019 checkpoint and actual-target reconciliation for one exact index code. */
public final class IndexDailyMarketCoverage {
    private static final int HISTORY_PAGE = 100;
    private static final int MAX_HISTORY = 10_000;
    private static final int MAX_LEDGER_CHILDREN = 100;
    private static final int MAX_COVERED_DATES = 20_000;
    private IndexDailyMarketCoverage() {}

    public record Checkpoint(LocalDate anchor, LocalDate through, Instant latestVerifiedAt) {
        public Checkpoint {
            Objects.requireNonNull(anchor); Objects.requireNonNull(through); Objects.requireNonNull(latestVerifiedAt);
            if (through.isBefore(anchor)) throw new IllegalArgumentException("Invalid D019 checkpoint interval");
        }
    }
    private record Interval(String runId, String targetId, String tsCode, IndexDailyMarketUniverse.Route route,
            SyncJobDefinition.Mode mode, LocalDate anchor, LocalDate from, LocalDate to,
            Instant verifiedAt, SyncJobRunner.Page<IndexDailyMarket> page) {}

    public static boolean hasHistorySchema(Path path) throws Exception {
        if (!Files.isRegularFile(path)) return false;
        var names = new HashSet<String>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro");
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        Set<String> required = Set.of("ledger_meta", "sync_runs", "sync_entries", "sync_events");
        if (Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial D019 sync ledger schema");
        return true;
    }

    /** Only same-target, same-code, same-route VERIFIED incremental receipts advance this watermark. */
    public static Optional<Checkpoint> checkpoint(Path ledgerPath, String targetId, String tsCode) throws Exception {
        var index = requireIndex(tsCode);
        List<Interval> incremental = verifiedIntervals(ledgerPath, targetId, index, EnumSet.of(SyncJobDefinition.Mode.INCREMENTAL));
        if (incremental.isEmpty()) return Optional.empty();
        var byAnchor = new HashMap<LocalDate,List<Interval>>();
        for (var interval : incremental) {
            if (interval.anchor() == null) throw new IllegalStateException("D019 incremental receipt lacks its bootstrap anchor");
            byAnchor.computeIfAbsent(interval.anchor(), ignored -> new ArrayList<>()).add(interval);
        }
        var candidates = new ArrayList<Checkpoint>();
        for (var entry : byAnchor.entrySet()) {
            LocalDate anchor = entry.getKey(), through = anchor.minusDays(1); boolean anchored = false;
            Instant latest = Instant.MIN;
            var ordered = entry.getValue().stream().sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to)
                    .thenComparing(Interval::verifiedAt).thenComparing(Interval::runId)).toList();
            for (var interval : ordered) {
                if (!anchored) {
                    if (!interval.from().equals(anchor)) continue;
                    anchored = true;
                }
                if (interval.from().isAfter(through.plusDays(1))) break;
                if (interval.to().isAfter(through)) through = interval.to();
                if (interval.verifiedAt().isAfter(latest)) latest = interval.verifiedAt();
            }
            if (anchored && !through.isBefore(anchor)) candidates.add(new Checkpoint(anchor, through, latest));
        }
        return candidates.stream().max(Comparator.comparing(Checkpoint::through)
                .thenComparing(Checkpoint::latestVerifiedAt).thenComparing(Checkpoint::anchor, Comparator.reverseOrder()));
    }

    /** Reconcile every existing row for this code with the newest verified receipt covering its day. */
    public static int validateExistingTarget(Path ledgerPath, String targetId, String tsCode,
            IndexDailyMarketWritePort writer) throws Exception {
        var index = requireIndex(tsCode);
        var actual = writer.readExistingRows(tsCode);
        List<Interval> intervals = verifiedIntervals(ledgerPath, targetId, index,
                EnumSet.of(SyncJobDefinition.Mode.INCREMENTAL, SyncJobDefinition.Mode.BACKFILL, SyncJobDefinition.Mode.RECONCILE));
        if (actual.isEmpty() && intervals.isEmpty()) return 0;
        if (!actual.isEmpty() && intervals.isEmpty())
            throw new IllegalStateException("D019 target has rows without same-target verified source receipts");
        var latestByDate = new HashMap<LocalDate,Interval>();
        for (var interval : intervals) {
            long dayCount = ChronoUnit.DAYS.between(interval.from(), interval.to()) + 1;
            if (dayCount < 1 || dayCount > IndexDailyMarketSource.MAX_WINDOW_DAYS)
                throw new IllegalStateException("D019 receipt interval exceeds bounded reconciliation window");
            for (LocalDate day = interval.from(); !day.isAfter(interval.to()); day = day.plusDays(1)) {
                Interval prior = latestByDate.get(day);
                if (prior == null || interval.verifiedAt().isAfter(prior.verifiedAt())
                        || interval.verifiedAt().equals(prior.verifiedAt()) && interval.runId().compareTo(prior.runId()) > 0)
                    latestByDate.put(day, interval);
                if (latestByDate.size() > MAX_COVERED_DATES)
                    throw new IllegalStateException("D019 source receipt coverage exceeds 20000 distinct calendar days");
            }
        }
        var expectedByDate = new HashMap<LocalDate,List<IndexDailyMarket>>();
        for (var entry : latestByDate.entrySet()) {
            Interval interval = entry.getValue();
            expectedByDate.put(entry.getKey(), interval.page().rows().stream()
                    .filter(row -> row.tradeDate().equals(entry.getKey())).toList());
        }
        var actualByDate = new HashMap<LocalDate,List<IndexDailyMarket>>();
        for (var row : actual) actualByDate.computeIfAbsent(row.tradeDate(), ignored -> new ArrayList<>()).add(row);
        if (!expectedByDate.keySet().containsAll(actualByDate.keySet()))
            throw new IllegalStateException("D019 target contains a code/date without a verified covering receipt");
        for (var entry : expectedByDate.entrySet()) {
            if (!sameRows(entry.getValue(), actualByDate.getOrDefault(entry.getKey(), List.of())))
                throw new IllegalStateException("D019 target full-key values differ from latest verified receipt on " + entry.getKey());
        }
        return actual.size();
    }

    private static List<Interval> verifiedIntervals(Path ledgerPath, String targetId,
            IndexDailyMarketUniverse.Index expectedIndex, Set<SyncJobDefinition.Mode> modes) throws Exception {
        Objects.requireNonNull(ledgerPath); Objects.requireNonNull(targetId);
        if (!Files.isRegularFile(ledgerPath) || !hasHistorySchema(ledgerPath)) return List.of();
        var ledger = SyncRunLedger.openReadOnly(ledgerPath); var json = JobDefinitionJson.mapper();
        JsonNode expectedDefinition = json.valueToTree(IndexDailyMarketSyncJobOwner.DEFINITION);
        var found = new ArrayList<Interval>(); String after = null; int seen = 0;
        while (true) {
            var summaries = ledger.history(IndexDailyMarketSyncJobOwner.DEFINITION.jobId(), after, HISTORY_PAGE);
            for (var summary : summaries) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("D019 verified run history exceeds bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion() != IndexDailyMarketSyncJobOwner.DEFINITION.version()) continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !IndexDailyMarketSyncJobOwner.DEFINITION.jobId().equals(run.jobId())
                        || run.jobVersion() != IndexDailyMarketSyncJobOwner.DEFINITION.version()) continue;
                JsonNode frozen = json.readTree(run.frozenJson());
                if (!sameDefinition(expectedDefinition, frozen.path("definition"))) continue;
                SyncJobDefinition.Mode mode;
                try { mode = SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText()); }
                catch (RuntimeException invalid) { throw new IllegalStateException("Invalid D019 frozen run mode", invalid); }
                if (!modes.contains(mode)) continue;
                JsonNode parameters = frozen.path("parameters");
                if (!targetId.equals(parameters.path("targetId").asText())
                        || !expectedIndex.tsCode().equals(parameters.path("tsCode").asText())
                        || !expectedIndex.route().name().equals(parameters.path("route").asText())) continue;
                LocalDate from = parseDate(frozen.path("from").asText()), to = parseDate(frozen.path("to").asText());
                long span = ChronoUnit.DAYS.between(from, to) + 1;
                if (from.isAfter(to) || span > IndexDailyMarketSource.MAX_WINDOW_DAYS)
                    throw new IllegalStateException("Invalid D019 verified range");
                LocalDate anchor = null;
                if (mode == SyncJobDefinition.Mode.INCREMENTAL) {
                    JsonNode node = parameters.get("checkpointAnchor");
                    if (node == null || !node.isTextual()) throw new IllegalStateException("D019 incremental run lacks bootstrap anchor");
                    anchor = parseDate(node.asText());
                    if (from.isBefore(anchor) || to.isBefore(anchor)) throw new IllegalStateException("D019 incremental range precedes its anchor");
                }
                Instant observedAt;
                try { observedAt = Instant.parse(parameters.path("observedAt").asText()); }
                catch (RuntimeException invalid) { throw new IllegalStateException("D019 verified run lacks frozen observation time", invalid); }
                Interval interval = readVerifiedSlice(ledger, summary.id(), summary.state(), targetId, expectedIndex,
                        mode, anchor, from, to, observedAt, Instant.parse(summary.updatedAt()));
                found.add(interval);
            }
            if (summaries.size() < HISTORY_PAGE) break;
            after = summaries.getLast().id(); // IDs page history; updatedAt defines chronology below.
        }
        return List.copyOf(found);
    }

    private static Interval readVerifiedSlice(SyncRunLedger ledger, String runId, SyncRunState runState,
            String targetId, IndexDailyMarketUniverse.Index index, SyncJobDefinition.Mode mode,
            LocalDate anchor, LocalDate from, LocalDate to, Instant observedAt, Instant verifiedAt) throws Exception {
        var slices = new ArrayList<SyncRunLedger.Entry>(); String after = null; int seen = 0;
        while (true) {
            var entries = ledger.entries(runId, after, MAX_LEDGER_CHILDREN);
            for (var entry : entries) {
                if (++seen > MAX_LEDGER_CHILDREN) throw new IllegalStateException("D019 run has too many ledger children");
                if (entry.kind() == SyncRunLedger.Kind.SLICE) slices.add(entry);
            }
            if (entries.size() < MAX_LEDGER_CHILDREN) break;
            after = entries.getLast().id();
        }
        if (slices.size() != 1) throw new IllegalStateException("D019 verified run must contain exactly one source slice");
        var slice = slices.getFirst();
        if (slice.state() != SyncRunState.VERIFIED && slice.state() != SyncRunState.VERIFIED_EMPTY)
            throw new IllegalStateException("D019 verified run contains a nonverified slice");
        JsonNode fetched = null;
        for (var event : ledger.events(slice.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
            if (fetched != null) throw new IllegalStateException("Duplicate D019 FETCHED receipt event");
            fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
        }
        if (fetched == null || !index.tsCode().equals(fetched.path("cursor").asText()))
            throw new IllegalStateException("D019 verified slice cursor/source identity differs");
        String path = fetched.path("responseEvidence").asText(""), fingerprint = fetched.path("sourceFingerprint").asText("");
        if (path.isBlank() || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid D019 receipt reference");
        var page = IndexDailyMarketSource.reopen(Path.of(path), fingerprint, index.tsCode(), from, to, observedAt);
        if (page.rows().isEmpty() != (slice.state() == SyncRunState.VERIFIED_EMPTY)
                || page.rows().isEmpty() != (runState == SyncRunState.VERIFIED_EMPTY))
            throw new IllegalStateException("D019 empty verification state differs from source receipt rows");
        return new Interval(runId, targetId, index.tsCode(), index.route(), mode, anchor, from, to, verifiedAt, page);
    }

    private static boolean sameRows(List<IndexDailyMarket> expected, List<IndexDailyMarket> actual) {
        if (expected.size() != actual.size()) return false;
        var left = new HashMap<IndexDailyMarketKey,byte[]>(); var right = new HashMap<IndexDailyMarketKey,byte[]>();
        for (var row : expected) if (left.putIfAbsent(row.key(), IndexDailyMarketWritePort.CODEC.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (right.putIfAbsent(row.key(), IndexDailyMarketWritePort.CODEC.canonicalBytes(row)) != null) return false;
        return left.keySet().equals(right.keySet()) && left.keySet().stream().allMatch(key -> Arrays.equals(left.get(key), right.get(key)));
    }
    private static boolean sameDefinition(JsonNode expected, JsonNode actual) {
        try { var json = JobDefinitionJson.mapper(); return json.treeToValue(expected, SyncJobDefinition.class).equals(json.treeToValue(actual, SyncJobDefinition.class)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return false; }
    }
    private static LocalDate parseDate(String value) {
        try { if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException(); return LocalDate.parse(value); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid frozen D019 date", invalid); }
    }
    private static IndexDailyMarketUniverse.Index requireIndex(String tsCode) {
        var value = IndexDailyMarketUniverse.resolve(tsCode);
        if (value == null) throw new IllegalArgumentException("Known D019 ts_code required");
        return value;
    }
}
