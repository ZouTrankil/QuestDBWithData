package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MoneyflowThs;
import com.zoutrankil.data.domain.MoneyflowThsKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.flow.port.MoneyflowThsWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** D025 receipt-backed incremental checkpoint and physical-row coverage checks. */
public final class MoneyflowThsCoverage {
    private static final int HISTORY_PAGE = 100, MAX_HISTORY = 10_000, MAX_CHILDREN = 1000, MAX_DATES = 10_000;
    private MoneyflowThsCoverage() {}

    public record Checkpoint(LocalDate anchor, LocalDate through, Instant latestVerifiedAt) {
        public Checkpoint {
            Objects.requireNonNull(anchor); Objects.requireNonNull(through); Objects.requireNonNull(latestVerifiedAt);
            if (through.isBefore(anchor)) throw new IllegalArgumentException("Invalid D025 checkpoint interval");
        }
    }
    private record Interval(String runId, SyncJobDefinition.Mode mode, LocalDate from, LocalDate to,
            LocalDate anchor, Instant verifiedAt, Map<LocalDate, SyncJobRunner.Page<MoneyflowThs>> pages) {}

    public static Checkpoint checkpoint(Path ledgerPath, String targetId,
            ExchangeCalendarReadPort calendars) throws Exception {
        var intervals = verifiedIntervals(ledgerPath, targetId, calendars,
                EnumSet.of(SyncJobDefinition.Mode.INCREMENTAL));
        var byAnchor = new HashMap<LocalDate, List<Interval>>();
        for (var interval : intervals) {
            if (interval.anchor() == null) throw new IllegalStateException("D025 incremental receipt lacks its bootstrap anchor");
            byAnchor.computeIfAbsent(interval.anchor(), ignored -> new ArrayList<>()).add(interval);
        }
        var checkpoints = new ArrayList<Checkpoint>();
        for (var entry : byAnchor.entrySet()) {
            LocalDate anchor = entry.getKey(), through = anchor.minusDays(1);
            boolean started = false; Instant latest = Instant.MIN;
            var ordered = entry.getValue().stream().sorted(Comparator.comparing(Interval::from)
                    .thenComparing(Interval::to).thenComparing(Interval::verifiedAt).thenComparing(Interval::runId)).toList();
            for (var interval : ordered) {
                if (!started) {
                    if (!interval.from().equals(anchor)) continue;
                    started = true;
                }
                if (interval.from().isAfter(through.plusDays(1))) break;
                if (interval.to().isAfter(through)) through = interval.to();
                if (interval.verifiedAt().isAfter(latest)) latest = interval.verifiedAt();
            }
            if (started && !through.isBefore(anchor)) checkpoints.add(new Checkpoint(anchor, through, latest));
        }
        return checkpoints.stream().max(Comparator.comparing(Checkpoint::through)
                .thenComparing(Checkpoint::latestVerifiedAt).thenComparing(Checkpoint::anchor, Comparator.reverseOrder()))
                .orElse(null);
    }

    /** Every actual target date must have a same-target verified source page with identical complete rows. */
    public static int validateExistingTarget(Path ledgerPath, String targetId,
            ExchangeCalendarReadPort calendars, MoneyflowThsWriteSession writer) throws Exception {
        var targetDates = writer.readExistingDates();
        if (targetDates.isEmpty()) return 0;
        var intervals = verifiedIntervals(ledgerPath, targetId, calendars, EnumSet.of(
                SyncJobDefinition.Mode.INCREMENTAL, SyncJobDefinition.Mode.BACKFILL, SyncJobDefinition.Mode.RECONCILE));
        var latest = new HashMap<LocalDate, Interval>();
        for (var interval : intervals) for (LocalDate date : interval.pages().keySet()) {
            Interval old = latest.get(date);
            if (old == null || interval.verifiedAt().isAfter(old.verifiedAt())
                    || interval.verifiedAt().equals(old.verifiedAt()) && interval.runId().compareTo(old.runId()) > 0)
                latest.put(date, interval);
        }
        if (latest.isEmpty()) {
            if (!targetDates.isEmpty()) throw new IllegalStateException("D025 target contains rows without same-target verified receipts");
            return 0;
        }
        if (targetDates.size() > MAX_DATES || latest.size() > MAX_DATES)
            throw new IllegalStateException("D025 physical/source date scan exceeds its evidence bound");
        for (LocalDate date : targetDates) if (!latest.containsKey(date))
            throw new IllegalStateException("D025 physical date is outside verified source coverage: " + date);
        int physicalRows = 0;
        for (var entry : latest.entrySet()) {
            var expected = entry.getValue().pages().get(entry.getKey()).rows();
            var actual = writer.readDate(entry.getKey());
            if (!sameRows(expected, actual, writer.codec()))
                throw new IllegalStateException("D025 target values differ from the latest verified raw source receipt on " + entry.getKey());
            physicalRows = Math.addExact(physicalRows, actual.size());
        }
        return physicalRows;
    }

    static boolean sameRows(List<MoneyflowThs> expected, List<MoneyflowThs> actual, VerifiedBatchExecutor.Codec<MoneyflowThs,MoneyflowThsKey> codec) {
        if (expected.size() != actual.size()) return false;
        var left = new HashMap<MoneyflowThsKey, byte[]>(); var right = new HashMap<MoneyflowThsKey, byte[]>();
        for (var row : expected) if (left.putIfAbsent(row.key(), codec.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (right.putIfAbsent(row.key(), codec.canonicalBytes(row)) != null) return false;
        return left.keySet().equals(right.keySet())
                && left.keySet().stream().allMatch(key -> Arrays.equals(left.get(key), right.get(key)));
    }

    private static List<Interval> verifiedIntervals(Path ledgerPath, String targetId,
            ExchangeCalendarReadPort calendars, Set<SyncJobDefinition.Mode> acceptedModes) throws Exception {
        Objects.requireNonNull(ledgerPath); Objects.requireNonNull(targetId); Objects.requireNonNull(calendars);
        if (!Files.isRegularFile(ledgerPath) || !hasHistorySchema(ledgerPath)) return List.of();
        Path evidenceRoot = ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").normalize();
        if (Files.exists(evidenceRoot)) evidenceRoot = evidenceRoot.toRealPath();
        var ledger = SyncRunLedger.openReadOnly(ledgerPath); var json = JobDefinitionJson.mapper();
        JsonNode definition = json.valueToTree(MoneyflowThsSyncJobOwner.DEFINITION);
        var found = new ArrayList<Interval>(); String after = null; int scanned = 0;
        while (true) {
            var summaries = ledger.history(MoneyflowThsSyncJobOwner.DEFINITION.jobId(), after, HISTORY_PAGE);
            for (var summary : summaries) {
                if (++scanned > MAX_HISTORY) throw new IllegalStateException("D025 ledger history exceeds its bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId())
                        || summary.jobVersion() != MoneyflowThsSyncJobOwner.DEFINITION.version()) continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !MoneyflowThsSyncJobOwner.DEFINITION.jobId().equals(run.jobId())
                        || run.jobVersion() != MoneyflowThsSyncJobOwner.DEFINITION.version()) continue;
                JsonNode frozen = json.readTree(run.frozenJson());
                if (!sameDefinition(definition, frozen.path("definition"))) continue;
                SyncJobDefinition.Mode mode;
                try { mode = SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText()); }
                catch (RuntimeException invalid) { throw new IllegalStateException("Invalid frozen D025 mode", invalid); }
                if (!acceptedModes.contains(mode) || !targetId.equals(frozen.path("parameters").path("targetId").asText())) continue;
                LocalDate from = LocalDate.parse(requiredDate(frozen.path("from"))), to = LocalDate.parse(requiredDate(frozen.path("to")));
                long days = ChronoUnit.DAYS.between(from, to) + 1;
                if (days < 1 || days > MoneyflowThsSyncJobOwner.MAX_WINDOW_DAYS || to.isAfter(LocalDate.parse(run.logicalDate())))
                    throw new IllegalStateException("D025 frozen run has an invalid or unbounded date window");
                LocalDate anchor = null;
                if (mode == SyncJobDefinition.Mode.INCREMENTAL) {
                    anchor = LocalDate.parse(requiredDate(frozen.path("parameters").path("checkpointAnchor")));
                    if (from.isBefore(anchor) || to.isBefore(anchor)) throw new IllegalStateException("D025 run window precedes its checkpoint anchor");
                } else if (!frozen.path("parameters").path("checkpointAnchor").isMissingNode()
                        || !frozen.path("parameters").path("checkpointBefore").isMissingNode())
                    throw new IllegalStateException("D025 non-incremental run contains checkpoint parameters");
                var pages = verifiedPages(ledger, summary.id(), summary.state(), from, to, calendars, evidenceRoot);
                found.add(new Interval(summary.id(), mode, from, to, anchor, Instant.parse(summary.updatedAt()), pages));
            }
            if (summaries.size() < HISTORY_PAGE) break;
            after = summaries.getLast().id();
        }
        return List.copyOf(found);
    }

    private static Map<LocalDate, SyncJobRunner.Page<MoneyflowThs>> verifiedPages(SyncRunLedger ledger, String runId,
            SyncRunState runState, LocalDate from, LocalDate to, ExchangeCalendarReadPort calendars,
            Path evidenceRoot) throws Exception {
        var dates = DailyTradingSessions.read(calendars, from, to);
        if (dates.isEmpty()) throw new IllegalStateException("D025 verified run must cover at least one open SSE session");
        var expected = new HashSet<>(dates); var found = new HashMap<LocalDate, SyncJobRunner.Page<MoneyflowThs>>();
        int childrenSeen = 0, totalRows = 0;
        var entries = ledger.entries(runId, null, MAX_CHILDREN);
        if (entries.size() >= MAX_CHILDREN) throw new IllegalStateException("D025 run has too many ledger entries");
            for (var entry : entries) {
                childrenSeen++;
                if (childrenSeen > MAX_CHILDREN) throw new IllegalStateException("D025 run has too many ledger children");
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("D025 terminal run includes an unverified source slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
                    if (fetched != null) throw new IllegalStateException("D025 slice has duplicate FETCHED events");
                    fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                }
                if (fetched == null) throw new IllegalStateException("D025 verified slice lacks its FETCHED receipt event");
                String cursor = fetched.path("cursor").asText("");
                if (!cursor.matches("[0-9]{8}")) throw new IllegalStateException("D025 slice cursor is not YYYYMMDD");
                LocalDate date = LocalDate.parse(cursor, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                if (!expected.contains(date) || found.containsKey(date)) throw new IllegalStateException("D025 unexpected or duplicate calendar date slice");
                String evidence = fetched.path("responseEvidence").asText("");
                String fingerprint = fetched.path("sourceFingerprint").asText("");
                if (evidence.isBlank() || !fingerprint.matches("[0-9a-f]{64}"))
                    throw new IllegalStateException("D025 verified slice has an invalid source receipt reference");
                Path receipt = Path.of(evidence).toAbsolutePath().normalize().toRealPath();
                if (!receipt.startsWith(evidenceRoot)) throw new IllegalStateException("D025 source receipt escapes ledger evidence root");
                var page = MoneyflowThsSource.reopen(receipt, fingerprint, date);
                if (page.rows().size() != fetched.path("returnedRows").asInt(-1)
                        || page.rows().isEmpty() != (entry.state() == SyncRunState.VERIFIED_EMPTY))
                    throw new IllegalStateException("D025 slice state or source row count differs from its receipt");
                totalRows = Math.addExact(totalRows, page.rows().size()); found.put(date, page);
            }
        if (!found.keySet().equals(expected)) throw new IllegalStateException("D025 run does not cover every open SSE source date");
        if ((runState == SyncRunState.VERIFIED_EMPTY) != (totalRows == 0))
            throw new IllegalStateException("D025 run terminal state disagrees with verified source row count");
        return Map.copyOf(found);
    }

    private static boolean sameDefinition(JsonNode expected, JsonNode actual) {
        try { var json = JobDefinitionJson.mapper(); return json.treeToValue(expected, SyncJobDefinition.class)
                .equals(json.treeToValue(actual, SyncJobDefinition.class)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return false; }
    }
    private static String requiredDate(JsonNode value) {
        if (value == null || !value.isTextual()) throw new IllegalStateException("D025 frozen date is missing");
        return value.asText();
    }
    private static boolean hasHistorySchema(Path path) throws Exception {
        var names = SqliteLedgerSchema.tableNames(path);
        var required = Set.of("ledger_meta", "sync_runs", "sync_entries", "sync_events");
        if (java.util.Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial D025 ledger schema");
        return true;
    }
}
