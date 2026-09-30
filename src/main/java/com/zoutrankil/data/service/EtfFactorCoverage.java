package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.repository.EtfFactorWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Receipt-backed D017 checkpoint and exact full-date physical reconciliation. */
public final class EtfFactorCoverage {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_HISTORY = 10_000;
    private static final int MAX_DATE_RECEIPTS = 10_000;
    private EtfFactorCoverage() {}
    public record Receipt(Path path, String fingerprint, LocalDate date) {
        public Receipt { Objects.requireNonNull(path); Objects.requireNonNull(fingerprint); Objects.requireNonNull(date); }
    }
    public record Coverage(LocalDate anchor, LocalDate through, Map<LocalDate,Receipt> receipts, Instant verifiedAt) {
        public Coverage {
            Objects.requireNonNull(anchor); Objects.requireNonNull(through); Objects.requireNonNull(verifiedAt);
            if (through.isBefore(anchor)) throw new IllegalArgumentException("Invalid etf_factor checkpoint interval");
            receipts = Map.copyOf(receipts);
        }
    }
    private record Interval(String id, LocalDate anchor, LocalDate from, LocalDate to, Instant at, Map<LocalDate,Receipt> receipts) {}
    private record Timed(Instant at, String runId, Receipt receipt) {}

    static boolean hasHistorySchema(Path path) throws Exception {
        var names = new HashSet<String>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro");
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        Set<String> required = Set.of("ledger_meta", "sync_runs", "sync_entries", "sync_events");
        if (Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial D017 sync ledger schema");
        return true;
    }

    public static Optional<Coverage> checkpoint(Path path, String targetId, EtfFactorTradingDates calendars) throws Exception {
        Objects.requireNonNull(path); Objects.requireNonNull(targetId); Objects.requireNonNull(calendars);
        if (!Files.isRegularFile(path) || !hasHistorySchema(path)) return Optional.empty();
        var ledger = SyncRunLedger.openReadOnly(path); var json = JobDefinitionJson.mapper();
        var intervals = new ArrayList<Interval>(); String after = null; int seen = 0;
        while (true) {
            var summaries = ledger.history("data.etf_factor", after, PAGE_SIZE);
            for (var summary : summaries) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("etf_factor checkpoint scan exceeded 10000 runs");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion() != EtfFactorSyncJobOwner.DEFINITION.version()) continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !"data.etf_factor".equals(run.jobId())
                        || run.jobVersion() != EtfFactorSyncJobOwner.DEFINITION.version()) continue;
                JsonNode frozen = json.readTree(run.frozenJson());
                if (!EtfFactorSyncJobOwner.DEFINITION.equals(json.treeToValue(frozen.path("definition"), SyncJobDefinition.class))
                        || !"INCREMENTAL".equals(frozen.path("mode").asText())) continue;
                JsonNode params = frozen.path("parameters");
                if (!targetId.equals(params.path("targetId").asText())) continue;
                JsonNode anchorNode = params.get("checkpointAnchor");
                if (anchorNode == null || !anchorNode.isTextual()) throw new IllegalStateException("Verified etf_factor run lacks frozen bootstrap anchor");
                LocalDate anchor = LocalDate.parse(anchorNode.asText());
                LocalDate from = LocalDate.parse(frozen.path("from").asText()); LocalDate to = LocalDate.parse(frozen.path("to").asText());
                long span = ChronoUnit.DAYS.between(from, to) + 1;
                if (from.isAfter(to) || span < 1 || span > EtfFactorSyncJobOwner.MAX_WINDOW_DAYS || from.isBefore(anchor) || to.isBefore(anchor))
                    throw new IllegalStateException("Invalid verified etf_factor interval");
                var dates = calendars.read(from, to);
                if (!EtfFactorSyncAdapter.encodeDates(dates).equals(params.path("trade_dates").asText()))
                    throw new IllegalStateException("Verified etf_factor request calendar differs from D001");
                Map<LocalDate,Receipt> receipts = receipts(ledger, summary.id(), dates, summary.state());
                intervals.add(new Interval(summary.id(), anchor, from, to, Instant.parse(summary.updatedAt()), receipts));
            }
            if (summaries.size() < PAGE_SIZE) break;
            after = summaries.getLast().id();
        }
        return merge(intervals);
    }

    private static Map<LocalDate,Receipt> receipts(SyncRunLedger ledger, String runId, List<LocalDate> dates,
                                                    SyncRunState state) throws Exception {
        Set<LocalDate> expected = new HashSet<>(dates); var found = new HashMap<LocalDate,Receipt>();
        String after = null; int scanned = 0;
        while (true) {
            var entries = ledger.entries(runId, after, 1000);
            for (var entry : entries) {
                if (++scanned > MAX_DATE_RECEIPTS) throw new IllegalStateException("etf_factor run exceeds date-slice bound");
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("Verified etf_factor run has an unverified date slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
                    if (fetched != null) throw new IllegalStateException("Duplicate D017 FETCHED receipt event");
                    fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                }
                if (fetched == null) throw new IllegalStateException("Verified etf_factor slice lacks source receipt");
                String cursor = fetched.path("cursor").asText("");
                if (!cursor.matches("[0-9]{8}")) throw new IllegalStateException("Invalid D017 trade-date cursor");
                LocalDate date = LocalDate.parse(cursor, DateTimeFormatter.BASIC_ISO_DATE);
                if (!expected.contains(date)) throw new IllegalStateException("etf_factor receipt outside frozen dates");
                Path path = Path.of(fetched.path("responseEvidence").asText("")); String hash = fetched.path("sourceFingerprint").asText("");
                if (path.toString().isBlank() || !hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid D017 receipt ref/hash");
                var source = EtfFactorSource.reopen(path, hash, date);
                if (source.rows().isEmpty() != (entry.state() == SyncRunState.VERIFIED_EMPTY))
                    throw new IllegalStateException("D017 source rows disagree with verified-empty status");
                if (found.putIfAbsent(date, new Receipt(path, hash, date)) != null) throw new IllegalStateException("Duplicate D017 date receipt");
            }
            if (entries.size() < 1000) break;
            after = entries.getLast().id();
        }
        if (!found.keySet().equals(expected)) throw new IllegalStateException("Verified etf_factor interval lacks exact date receipts");
        boolean hasRows = found.values().stream().anyMatch(receipt -> {
            try { return !EtfFactorSource.reopen(receipt.path(), receipt.fingerprint(), receipt.date()).rows().isEmpty(); }
            catch (Exception failure) { throw new IllegalStateException("Cannot reopen D017 receipt", failure); }
        });
        if ((state == SyncRunState.VERIFIED_EMPTY) == hasRows)
            throw new IllegalStateException("D017 run terminal state disagrees with its source receipts");
        return Map.copyOf(found);
    }

    private static Optional<Coverage> merge(List<Interval> intervals) {
        if (intervals.isEmpty()) return Optional.empty();
        var groups = new HashMap<LocalDate,List<Interval>>();
        intervals.forEach(item -> groups.computeIfAbsent(item.anchor(), ignored -> new ArrayList<>()).add(item));
        var candidates = new ArrayList<Coverage>();
        for (var group : groups.entrySet()) {
            LocalDate anchor = group.getKey(), through = anchor.minusDays(1); boolean started = false; Instant newest = Instant.MIN;
            var chosen = new HashMap<LocalDate,Timed>();
            var ordered = group.getValue().stream().sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to)
                    .thenComparing(Interval::at).thenComparing(Interval::id)).toList();
            for (var item : ordered) {
                if (!started) { if (!item.from().equals(anchor)) continue; started = true; }
                if (item.from().isAfter(through.plusDays(1))) break;
                if (item.to().isAfter(through)) through = item.to();
                if (item.at().isAfter(newest)) newest = item.at();
                item.receipts().forEach((date, receipt) -> {
                    Timed old = chosen.get(date);
                    if (old == null || item.at().isAfter(old.at()) || item.at().equals(old.at()) && item.id().compareTo(old.runId()) > 0)
                        chosen.put(date, new Timed(item.at(), item.id(), receipt));
                });
            }
            if (started && !through.isBefore(anchor)) {
                var receipts = new HashMap<LocalDate,Receipt>(); chosen.forEach((date, value) -> receipts.put(date, value.receipt()));
                candidates.add(new Coverage(anchor, through, receipts, newest));
            }
        }
        return candidates.stream().max(Comparator.comparing(Coverage::through).thenComparing(Coverage::verifiedAt)
                .thenComparing(Coverage::anchor, Comparator.reverseOrder()));
    }

    /** Reject any physical row not justified by a full, latest verified receipt and compare every D017 field. */
    public static void validateExistingTarget(Coverage coverage, EtfFactorTradingDates calendars, EtfFactorWritePort port) throws Exception {
        var actualDates = new HashSet<>(port.readExistingDates());
        if (coverage == null) {
            if (!actualDates.isEmpty()) throw new IllegalStateException("etf_factor target has data without same-target receipt-backed incremental checkpoint");
            return;
        }
        if (ChronoUnit.DAYS.between(coverage.anchor(), coverage.through()) + 1 > 10_000)
            throw new IllegalStateException("etf_factor verified interval exceeds 10000-day reconciliation bound");
        var expectedDates = new HashSet<LocalDate>(); var nonempty = new HashSet<LocalDate>();
        LocalDate from = coverage.anchor();
        while (!from.isAfter(coverage.through())) {
            LocalDate to = from.plusDays(EtfFactorTradingDates.MAX_WINDOW_DAYS - 1L);
            if (to.isAfter(coverage.through())) to = coverage.through();
            for (LocalDate date : calendars.read(from, to)) expectedDates.add(date);
            from = to.plusDays(1);
        }
        if (!coverage.receipts().keySet().equals(expectedDates)) throw new IllegalStateException("D017 checkpoint lacks a complete calendar receipt chain");
        for (var entry : coverage.receipts().entrySet()) {
            var page = EtfFactorSource.reopen(entry.getValue().path(), entry.getValue().fingerprint(), entry.getKey());
            var actual = port.readDate(entry.getKey());
            if (!sameRows(page.rows(), actual)) throw new IllegalStateException("etf_factor target differs from latest source receipt on " + entry.getKey());
            if (!page.rows().isEmpty()) nonempty.add(entry.getKey());
        }
        if (!actualDates.equals(nonempty)) throw new IllegalStateException("etf_factor physical date inventory is unexplained or missing receipt-backed rows");
    }
    private static boolean sameRows(List<EtfFactor> expected, List<EtfFactor> actual) {
        if (expected.size() != actual.size()) return false;
        var left = new HashMap<EtfFactorKey,byte[]>(); var right = new HashMap<EtfFactorKey,byte[]>();
        for (var row : expected) if (left.putIfAbsent(row.key(), EtfFactorWritePort.CODEC.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (right.putIfAbsent(row.key(), EtfFactorWritePort.CODEC.canonicalBytes(row)) != null) return false;
        return left.keySet().equals(right.keySet()) && left.keySet().stream().allMatch(key -> Arrays.equals(left.get(key), right.get(key)));
    }
}
