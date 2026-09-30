package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.EtfPortfolio;
import com.zoutrankil.data.domain.EtfPortfolioKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.EtfPortfolioWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Receipt-backed contiguous calendar-day checkpoint and full physical coverage reconciliation. */
public final class EtfPortfolioCoverage {
    private static final int HISTORY_PAGE = 100;
    private static final int MAX_HISTORY = 10_000;
    private static final int MAX_DATES = 10_000;
    private EtfPortfolioCoverage() {}

    public record ChunkRef(Path path, String fingerprint, int index, int offset, int rows) {
        public ChunkRef { Objects.requireNonNull(path); Objects.requireNonNull(fingerprint); }
    }
    public record Receipt(Path sourcePath, String sourceFingerprint, LocalDate date, Instant observedAt,
                          int sourceRows, int sourcePages, String runId, Instant verifiedAt, List<ChunkRef> chunks) {
        public Receipt {
            Objects.requireNonNull(sourcePath); Objects.requireNonNull(sourceFingerprint);
            Objects.requireNonNull(date); Objects.requireNonNull(observedAt); Objects.requireNonNull(runId);
            Objects.requireNonNull(verifiedAt); chunks = List.copyOf(chunks);
        }
    }
    public record Coverage(LocalDate anchor, LocalDate through, Map<LocalDate, Receipt> receipts, Instant latestVerifiedAt) {
        public Coverage {
            Objects.requireNonNull(anchor); Objects.requireNonNull(through); Objects.requireNonNull(latestVerifiedAt);
            if (through.isBefore(anchor)) throw new IllegalArgumentException("Invalid etf_portfolio verified interval");
            receipts = Map.copyOf(receipts);
        }
    }
    private record Interval(String runId, LocalDate anchor, LocalDate from, LocalDate to,
                            Instant verifiedAt, Map<LocalDate, Receipt> receipts) {}
    private record Backfill(String runId, LocalDate from, LocalDate to, SyncRunState runState,
                            Instant verifiedAt, Instant observedAt) {}
    private record TimedReceipt(Instant verifiedAt, String runId, Receipt receipt) {}

    public static Optional<Coverage> checkpoint(Path path, String targetId) throws Exception {
        Objects.requireNonNull(path); Objects.requireNonNull(targetId);
        if (!Files.isRegularFile(path) || !hasHistorySchema(path)) return Optional.empty();
        var ledger = SyncRunLedger.openReadOnly(path); var json = JobDefinitionJson.mapper();
        var intervals = new ArrayList<Interval>(); var backfills = new ArrayList<Backfill>();
        String after = null; int seen = 0;
        while (true) {
            var summaries = ledger.history("data.etf_portfolio", after, HISTORY_PAGE);
            for (var summary : summaries) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("etf_portfolio checkpoint history exceeds bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion() != 1) continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !"data.etf_portfolio".equals(run.jobId()) || run.jobVersion() != 1) continue;
                JsonNode frozen = json.readTree(run.frozenJson());
                if (!sameDefinition(EtfPortfolioSyncJobOwner.DEFINITION, frozen.path("definition"))) continue;
                String mode = frozen.path("mode").asText();
                if (!"INCREMENTAL".equals(mode) && !"BACKFILL".equals(mode)) continue;
                JsonNode params = frozen.path("parameters");
                if (!params.isObject() || !targetId.equals(params.path("targetId").asText()))
                    throw new IllegalStateException("Verified etf_portfolio run lacks exact frozen target identity");
                LocalDate from = parseIsoDate(frozen.path("from").asText());
                LocalDate to = parseIsoDate(frozen.path("to").asText());
                long span = ChronoUnit.DAYS.between(from, to) + 1;
                if (from.isAfter(to) || span > EtfPortfolioSyncJobOwner.MAX_WINDOW_DAYS)
                    throw new IllegalStateException("Invalid verified etf_portfolio interval");
                var dates = dates(from, to);
                if (!EtfPortfolioSyncAdapter.encodeAnnouncementDates(dates).equals(params.path("ann_dates").asText()))
                    throw new IllegalStateException("Frozen etf_portfolio date list is incomplete or differs from interval");
                Instant observedAt = parseObserved(params.path("observedAt").asText());
                Instant verifiedAt = Instant.parse(summary.updatedAt());
                if ("INCREMENTAL".equals(mode)) {
                    LocalDate anchor = parseIsoDate(params.path("checkpointAnchor").asText());
                    if (from.isBefore(anchor) || to.isBefore(anchor))
                        throw new IllegalStateException("Invalid verified etf_portfolio incremental interval");
                    Map<LocalDate, Receipt> runReceipts = receipts(path, ledger, summary.id(), dates,
                            summary.state(), observedAt, verifiedAt);
                    intervals.add(new Interval(summary.id(), anchor, from, to, verifiedAt, runReceipts));
                } else {
                    if (params.has("checkpointAnchor") || params.has("checkpointBefore"))
                        throw new IllegalStateException("Verified etf_portfolio BACKFILL carries incremental checkpoint metadata");
                    backfills.add(new Backfill(summary.id(), from, to, summary.state(), verifiedAt, observedAt));
                }
            }
            if (summaries.size() < HISTORY_PAGE) break;
            after = summaries.getLast().id();
        }
        Optional<Coverage> incremental = mergeLatestContinuous(intervals);
        if (incremental.isEmpty() || backfills.isEmpty()) return incremental;
        Coverage base = incremental.get();
        var overlaid = new HashMap<>(base.receipts());
        Instant latestVerifiedAt = base.latestVerifiedAt();
        var winningBackfillByDate = new HashMap<LocalDate, Backfill>();
        for (var backfill : backfills) {
            LocalDate from = backfill.from().isBefore(base.anchor()) ? base.anchor() : backfill.from();
            LocalDate to = backfill.to().isAfter(base.through()) ? base.through() : backfill.to();
            if (from.isAfter(to)) continue;
            for (LocalDate date : dates(from, to)) {
                Receipt incrementalReceipt = base.receipts().get(date);
                if (incrementalReceipt == null || !newer(backfill.verifiedAt(), backfill.runId(), incrementalReceipt)) continue;
                Backfill old = winningBackfillByDate.get(date);
                if (old == null || newer(backfill, old)) winningBackfillByDate.put(date, backfill);
            }
        }
        var selectedBackfills = new HashMap<String, Backfill>();
        winningBackfillByDate.values().forEach(backfill -> selectedBackfills.put(backfill.runId(), backfill));
        for (var backfill : selectedBackfills.values()) {
            List<LocalDate> runDates = dates(backfill.from(), backfill.to());
            Map<LocalDate, Receipt> runReceipts = receipts(path, ledger, backfill.runId(), runDates,
                    backfill.runState(), backfill.observedAt(), backfill.verifiedAt());
            for (var chosen : winningBackfillByDate.entrySet()) {
                if (!backfill.runId().equals(chosen.getValue().runId())) continue;
                Receipt update = runReceipts.get(chosen.getKey());
                if (update == null) throw new IllegalStateException("Selected etf_portfolio BACKFILL lacks its date receipt");
                overlaid.put(chosen.getKey(), update);
                if (update.verifiedAt().isAfter(latestVerifiedAt)) latestVerifiedAt = update.verifiedAt();
            }
        }
        return Optional.of(new Coverage(base.anchor(), base.through(), overlaid, latestVerifiedAt));
    }

    private static Map<LocalDate, Receipt> receipts(Path ledgerPath, SyncRunLedger ledger, String runId,
            List<LocalDate> expectedDates, SyncRunState runState, Instant observedAt, Instant verifiedAt) throws Exception {
        var expected = new HashSet<>(expectedDates);
        var byDate = new HashMap<LocalDate, List<ChunkRef>>();
        var metadata = new HashMap<LocalDate, EtfPortfolioSource.Chunk>();
        Path evidenceRoot = ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).normalize();
        String after = null; int seen = 0;
        while (true) {
            var entries = ledger.entries(runId, after, 1000);
            for (var entry : entries) {
                if (++seen > MAX_DATES) throw new IllegalStateException("etf_portfolio run has too many slices");
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("Verified etf_portfolio run contains an unverified date slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
                    if (fetched != null) throw new IllegalStateException("Duplicate etf_portfolio FETCHED receipt event");
                    fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                }
                if (fetched == null) throw new IllegalStateException("Verified etf_portfolio slice lacks FETCHED source receipt");
                String cursor = fetched.path("cursor").asText("");
                if (!cursor.matches("[0-9]{8}#[0-9]+")) throw new IllegalStateException("Invalid etf_portfolio receipt chunk cursor");
                String[] cursorParts = cursor.split("#", -1);
                LocalDate date = LocalDate.parse(cursorParts[0], DateTimeFormatter.BASIC_ISO_DATE);
                int cursorIndex;
                try { cursorIndex = Integer.parseInt(cursorParts[1]); }
                catch (NumberFormatException invalid) { throw new IllegalStateException("Invalid etf_portfolio runner chunk index", invalid); }
                if (!expected.contains(date)) throw new IllegalStateException("etf_portfolio receipt outside frozen date interval");
                String evidence = fetched.path("responseEvidence").asText("");
                String fingerprint = fetched.path("sourceFingerprint").asText("");
                if (evidence.isBlank() || !fingerprint.matches("[0-9a-f]{64}")
                        || fetched.path("returnedRows").asInt(-1) < 0)
                    throw new IllegalStateException("Invalid etf_portfolio receipt path, count or fingerprint");
                Path receipt = requireEvidencePath(Path.of(evidence), evidenceRoot);
                var chunk = EtfPortfolioSource.reopen(receipt, fingerprint, date, observedAt);
                if (chunk.index() != cursorIndex || chunk.rows().size() != fetched.path("returnedRows").asInt()
                        || chunk.rows().isEmpty() != (entry.state() == SyncRunState.VERIFIED_EMPTY))
                    throw new IllegalStateException("etf_portfolio ledger counts/state differ from source receipt");
                Path sourceReceipt = requireEvidencePath(chunk.sourceReceipt(), evidenceRoot);
                var first = metadata.putIfAbsent(date, chunk);
                if (first != null && (!first.sourceFingerprint().equals(chunk.sourceFingerprint())
                        || !requireEvidencePath(first.sourceReceipt(), evidenceRoot).equals(sourceReceipt)
                        || first.totalRows() != chunk.totalRows()
                        || first.sourcePages() != chunk.sourcePages() || first.count() != chunk.count()))
                    throw new IllegalStateException("etf_portfolio runner chunks refer to different complete source responses");
                byDate.computeIfAbsent(date, ignored -> new ArrayList<>())
                        .add(new ChunkRef(receipt, fingerprint, chunk.index(), chunk.offset(), chunk.rows().size()));
            }
            if (entries.size() < 1000) break;
            after = entries.getLast().id();
        }
        if (!byDate.keySet().equals(expected)) throw new IllegalStateException("Verified etf_portfolio interval lacks date receipts");
        var found = new HashMap<LocalDate, Receipt>(); boolean hasRows = false;
        for (LocalDate date : expectedDates) {
            var chunks = byDate.get(date).stream().sorted(Comparator.comparingInt(ChunkRef::index)).toList();
            var first = metadata.get(date);
            if (first == null || chunks.size() != first.count())
                throw new IllegalStateException("etf_portfolio date lacks its full runner chunk sequence");
            int counted = 0;
            for (int index = 0; index < chunks.size(); index++) {
                var ref = chunks.get(index);
                int expectedOffset = index * EtfPortfolioSource.RUNNER_PAGE_ROWS;
                int expectedSize = Math.max(0, Math.min(EtfPortfolioSource.RUNNER_PAGE_ROWS, first.totalRows() - expectedOffset));
                if (ref.index() != index || ref.offset() != expectedOffset || ref.rows() != expectedSize)
                    throw new IllegalStateException("etf_portfolio runner chunks have a gap, overlap or wrong size");
                counted = Math.addExact(counted, ref.rows());
            }
            if (counted != first.totalRows()) throw new IllegalStateException("etf_portfolio chunk rows differ from complete source count");
            var sourceReceipt = requireEvidencePath(first.sourceReceipt(), evidenceRoot);
            var receipt = new Receipt(sourceReceipt, first.sourceFingerprint(), date, observedAt,
                    first.totalRows(), first.sourcePages(), runId, verifiedAt, chunks);
            found.put(date, receipt);
            hasRows |= first.totalRows() > 0;
        }
        if (runState == SyncRunState.VERIFIED_EMPTY && hasRows || runState == SyncRunState.VERIFIED && !hasRows)
            throw new IllegalStateException("etf_portfolio run state conflicts with its receipt row counts");
        return Map.copyOf(found);
    }

    private static Optional<Coverage> mergeLatestContinuous(List<Interval> intervals) {
        if (intervals.isEmpty()) return Optional.empty();
        var grouped = new HashMap<LocalDate, List<Interval>>();
        intervals.forEach(interval -> grouped.computeIfAbsent(interval.anchor(), ignored -> new ArrayList<>()).add(interval));
        var candidates = new ArrayList<Coverage>();
        for (var group : grouped.entrySet()) {
            LocalDate anchor = group.getKey(), through = anchor.minusDays(1); boolean started = false;
            Instant latest = Instant.MIN; var selected = new HashMap<LocalDate, TimedReceipt>();
            var ordered = group.getValue().stream().sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to)
                    .thenComparing(Interval::verifiedAt).thenComparing(Interval::runId)).toList();
            for (var interval : ordered) {
                if (!started) {
                    if (!interval.from().equals(anchor)) continue;
                    started = true;
                }
                if (interval.from().isAfter(through.plusDays(1))) break;
                if (interval.to().isAfter(through)) through = interval.to();
                if (interval.verifiedAt().isAfter(latest)) latest = interval.verifiedAt();
                interval.receipts().forEach((date, receipt) -> {
                    TimedReceipt old = selected.get(date);
                    if (old == null || interval.verifiedAt().isAfter(old.verifiedAt())
                            || interval.verifiedAt().equals(old.verifiedAt()) && interval.runId().compareTo(old.runId()) > 0)
                        selected.put(date, new TimedReceipt(interval.verifiedAt(), interval.runId(), receipt));
                });
            }
            if (started && !through.isBefore(anchor)) {
                var receipts = new HashMap<LocalDate, Receipt>(); selected.forEach((date, value) -> receipts.put(date, value.receipt()));
                candidates.add(new Coverage(anchor, through, receipts, latest));
            }
        }
        return candidates.stream().max(Comparator.comparing(Coverage::through).thenComparing(Coverage::latestVerifiedAt)
                .thenComparing(Coverage::anchor, Comparator.reverseOrder()));
    }

    private static boolean newer(Instant candidateTime, String candidateRunId, Receipt existing) {
        int timeOrder = candidateTime.compareTo(existing.verifiedAt());
        return timeOrder > 0 || timeOrder == 0 && candidateRunId.compareTo(existing.runId()) > 0;
    }

    private static boolean newer(Backfill candidate, Backfill existing) {
        int timeOrder = candidate.verifiedAt().compareTo(existing.verifiedAt());
        return timeOrder > 0 || timeOrder == 0 && candidate.runId().compareTo(existing.runId()) > 0;
    }

    /** Every physical date/value is explained by the latest verified receipt; only INCREMENTAL runs move the checkpoint. */
    public static int validateExistingTarget(Path ledgerPath, Coverage coverage, String targetId,
            EtfPortfolioWritePort writer) throws Exception {
        var actualDates = writer.readExistingAnnouncementDates();
        if (actualDates.isEmpty()) {
            if (coverage != null) validateAllReceiptDays(ledgerPath, coverage, writer, Set.of());
            return 0;
        }
        if (coverage == null) throw new IllegalStateException("etf_portfolio target has rows without same-target verified incremental coverage");
        if (actualDates.getFirst().isBefore(coverage.anchor()) || actualDates.getLast().isAfter(coverage.through()))
            throw new IllegalStateException("etf_portfolio rows exceed same-target verified announcement-date coverage");
        validateAllReceiptDays(ledgerPath, coverage, writer, new HashSet<>(actualDates));
        return actualDates.size();
    }

    private static void validateAllReceiptDays(Path ledgerPath, Coverage coverage, EtfPortfolioWritePort writer,
                                               Set<LocalDate> actualDates) throws Exception {
        long count = ChronoUnit.DAYS.between(coverage.anchor(), coverage.through()) + 1;
        if (count < 1 || count > MAX_DATES || coverage.receipts().size() != count)
            throw new IllegalStateException("etf_portfolio checkpoint exceeds bounded date/evidence inventory");
        LocalDate date = coverage.anchor();
        while (!date.isAfter(coverage.through())) {
            var receipt = coverage.receipts().get(date);
            if (receipt == null) throw new IllegalStateException("Missing etf_portfolio checkpoint receipt: " + date);
            Path evidenceRoot = ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").normalize();
            Path sourcePath = requireEvidencePath(receipt.sourcePath(), evidenceRoot);
            var expected = new ArrayList<EtfPortfolio>();
            for (var chunkRef : receipt.chunks()) {
                Path chunkPath = requireEvidencePath(chunkRef.path(), evidenceRoot);
                var chunk = EtfPortfolioSource.reopen(chunkPath, chunkRef.fingerprint(), date, receipt.observedAt());
                if (chunk.index() != chunkRef.index() || chunk.offset() != chunkRef.offset()
                        || chunk.totalRows() != receipt.sourceRows() || chunk.sourcePages() != receipt.sourcePages()
                        || !chunk.sourceFingerprint().equals(receipt.sourceFingerprint())
                        || !requireEvidencePath(chunk.sourceReceipt(), evidenceRoot).equals(sourcePath))
                    throw new IllegalStateException("etf_portfolio chunk no longer matches verified complete receipt metadata");
                expected.addAll(chunk.rows());
            }
            var physical = writer.readAnnouncementDate(date);
            if (!sameRows(expected, physical))
                throw new IllegalStateException("QuestDB etf_portfolio differs from its latest verified receipt on " + date);
            if (expected.isEmpty() && actualDates.contains(date) || !expected.isEmpty() && !actualDates.contains(date))
                throw new IllegalStateException("QuestDB etf_portfolio announcement-date inventory differs from source receipt: " + date);
            date = date.plusDays(1);
        }
        if (!coverage.receipts().keySet().equals(new HashSet<>(dates(coverage.anchor(), coverage.through())))
                || !dates(coverage.anchor(), coverage.through()).containsAll(actualDates))
            throw new IllegalStateException("etf_portfolio physical date lacks exact checkpoint coverage");
    }

    public static boolean sameRows(List<EtfPortfolio> expected, List<EtfPortfolio> actual) {
        if (expected.size() != actual.size()) return false;
        var left = new HashMap<EtfPortfolioKey, byte[]>(); var right = new HashMap<EtfPortfolioKey, byte[]>();
        for (var row : expected) if (left.putIfAbsent(row.key(), EtfPortfolioWritePort.CODEC.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (right.putIfAbsent(row.key(), EtfPortfolioWritePort.CODEC.canonicalBytes(row)) != null) return false;
        return left.keySet().equals(right.keySet()) && left.keySet().stream()
                .allMatch(key -> Arrays.equals(left.get(key), right.get(key)));
    }

    private static List<LocalDate> dates(LocalDate from, LocalDate to) {
        long count = ChronoUnit.DAYS.between(from, to) + 1;
        if (count < 1 || count > MAX_DATES)
            throw new IllegalArgumentException("etf_portfolio date range exceeds the 10000-day evidence bound");
        var dates = new ArrayList<LocalDate>((int) count);
        for (LocalDate value = from; !value.isAfter(to); value = value.plusDays(1)) dates.add(value);
        return List.copyOf(dates);
    }
    private static LocalDate parseIsoDate(String value) {
        try { return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid frozen etf_portfolio date", invalid); }
    }
    private static Instant parseObserved(String value) {
        try {
            Instant result = Instant.parse(value);
            com.zoutrankil.data.domain.temporal.TemporalValues.requirePrecision(result,
                    com.zoutrankil.data.domain.temporal.TemporalValues.Precision.MICROS);
            if (!result.toString().equals(value)) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid frozen etf_portfolio observation", invalid); }
    }
    private static Path requireEvidencePath(Path path, Path evidenceRoot) throws Exception {
        Path root = evidenceRoot.toAbsolutePath().normalize().toRealPath();
        Path real = path.toAbsolutePath().normalize().toRealPath();
        if (!real.startsWith(root)) throw new IllegalStateException("etf_portfolio receipt escapes its evidence root");
        return real;
    }
    private static boolean sameDefinition(SyncJobDefinition expected, JsonNode actual) throws Exception {
        if (actual == null || !actual.isObject()) return false;
        return expected.equals(JobDefinitionJson.mapper().treeToValue(actual, SyncJobDefinition.class));
    }
    private static boolean hasHistorySchema(Path path) throws Exception {
        var names = new HashSet<String>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro");
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        Set<String> required = Set.of("ledger_meta", "sync_runs", "sync_entries", "sync_events");
        if (Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial sync-run ledger schema");
        return true;
    }
}
