package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Verified incremental coverage for one daily_basic target, route, schema and bootstrap anchor. */
public final class DailyBasicCoverage {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_HISTORY = 10_000;
    private DailyBasicCoverage() {}

    public record Coverage(LocalDate anchor, LocalDate from, LocalDate through, boolean includesRows,
                           LocalDate firstRowDate, LocalDate lastRowDate) {
        public Coverage {
            Objects.requireNonNull(anchor); Objects.requireNonNull(from); Objects.requireNonNull(through);
            if (through.isBefore(anchor) || from.isAfter(through))
                throw new IllegalArgumentException("Invalid verified daily_basic coverage");
            if ((firstRowDate == null) != (lastRowDate == null)
                    || firstRowDate != null && firstRowDate.isAfter(lastRowDate)
                    || includesRows != (firstRowDate != null))
                throw new IllegalArgumentException("Invalid verified daily_basic row-date evidence");
        }
    }
    private record Interval(LocalDate anchor, LocalDate from, LocalDate to,
                            LocalDate firstRowDate, LocalDate lastRowDate) {
        private Interval {
            if (anchor == null || from == null || to == null || from.isAfter(to) || to.isBefore(anchor))
                throw new IllegalArgumentException("Invalid verified daily_basic interval");
            if ((firstRowDate == null) != (lastRowDate == null)
                    || firstRowDate != null && firstRowDate.isAfter(lastRowDate))
                throw new IllegalArgumentException("Invalid daily_basic interval row evidence");
        }
    }
    private record RowDates(LocalDate first, LocalDate last, int pages) {}

    /** A schedule-only SQLite file has no run history; partial run schemas fail closed. */
    public static boolean hasHistorySchema(Path path) throws SQLException {
        var names = SqliteLedgerSchema.tableNames(path);
        var required = Set.of("ledger_meta", "sync_runs", "sync_entries");
        if (java.util.Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new SQLException("Partial sync-run ledger schema");
        return true;
    }

    public static Optional<Coverage> checkpoint(Path ledgerPath, String targetId) throws Exception {
        Objects.requireNonNull(ledgerPath); Objects.requireNonNull(targetId);
        if (!Files.isRegularFile(ledgerPath) || !hasHistorySchema(ledgerPath)) return Optional.empty();
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var json = JobDefinitionJson.mapper();
        JsonNode expectedDefinition = json.valueToTree(DailyBasicSyncAdapter.definition(true));
        var intervals = new ArrayList<Interval>();
        String after = null;
        int seen = 0;
        while (true) {
            var page = ledger.history("data.daily_basic", after, PAGE_SIZE);
            for (var summary : page) {
                if (++seen > MAX_HISTORY) throw new IllegalStateException("daily_basic checkpoint history exceeds bounded scan");
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion() != 1) continue;
                var run = ledger.getRun(summary.id());
                if (!targetId.equals(run.targetId()) || !"data.daily_basic".equals(run.jobId()) || run.jobVersion() != 1) continue;
                JsonNode frozen = json.readTree(run.frozenJson());
                // Exact frozen definition equality filters mismatched owner, source route, policies and schema.
                if (!sameDefinition(expectedDefinition, frozen.path("definition"))
                        || !"INCREMENTAL".equals(frozen.path("mode").asText())) continue;
                JsonNode parameters = frozen.path("parameters");
                if (!parameters.isObject() || parameters.size() != 2 || !parameters.has("trade_dates")) continue;
                JsonNode anchorNode = parameters.get("checkpointAnchor");
                // Pre-anchor runs cannot establish which continuous bootstrap chain they belong to.
                if (anchorNode == null || !anchorNode.isTextual()) continue;
                LocalDate anchor;
                try { anchor = LocalDate.parse(anchorNode.textValue()); }
                catch (RuntimeException malformed) { continue; }
                JsonNode fromNode = frozen.get("from"), toNode = frozen.get("to");
                if (fromNode == null || !fromNode.isTextual() || toNode == null || !toNode.isTextual()) continue;
                LocalDate from, to;
                try { from = LocalDate.parse(fromNode.textValue()); to = LocalDate.parse(toNode.textValue()); }
                catch (RuntimeException malformed) { continue; }
                long days = ChronoUnit.DAYS.between(from, to) + 1;
                if (days < 1 || days > DailyBasicSyncAdapter.definition(true).budget().maxWindowDays()
                        || to.isBefore(anchor) || !validFrozenDates(parameters.path("trade_dates"), from, to)) continue;
                var tradeDates = parseFrozenDates(parameters.path("trade_dates"));
                if (tradeDates == null) continue;
                RowDates rows = verifiedRowDates(ledger, ledgerPath, summary.id(), tradeDates, summary.state());
                intervals.add(new Interval(anchor, from, to, rows.first(), rows.last()));
            }
            if (page.size() < PAGE_SIZE) break;
            after = page.getLast().id();
        }
        return mergeLatestContinuous(intervals);
    }

    /** Selects the chain with the latest covered date; disconnected older backfills cannot mask it. */
    static Optional<Coverage> mergeLatestContinuous(List<Interval> intervals) {
        if (intervals.isEmpty()) return Optional.empty();
        var byAnchor = new TreeMap<LocalDate, List<Interval>>();
        for (var interval : intervals) byAnchor.computeIfAbsent(interval.anchor(), ignored -> new ArrayList<>()).add(interval);
        var candidates = new ArrayList<Coverage>();
        for (var entry : byAnchor.entrySet()) {
            LocalDate anchor = entry.getKey();
            LocalDate through = anchor.minusDays(1);
            LocalDate coveredFrom = null;
            LocalDate firstRowDate = null, lastRowDate = null;
            var ordered = entry.getValue().stream()
                    .sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to)).toList();
            for (var interval : ordered) {
                if (interval.to().isBefore(anchor)) continue;
                if (interval.from().isAfter(through.plusDays(1))) break;
                if (coveredFrom == null || interval.from().isBefore(coveredFrom)) coveredFrom = interval.from();
                if (interval.to().isAfter(through)) through = interval.to();
                if (interval.firstRowDate() != null && (firstRowDate == null || interval.firstRowDate().isBefore(firstRowDate)))
                    firstRowDate = interval.firstRowDate();
                if (interval.lastRowDate() != null && (lastRowDate == null || interval.lastRowDate().isAfter(lastRowDate)))
                    lastRowDate = interval.lastRowDate();
            }
            if (!through.isBefore(anchor)) candidates.add(new Coverage(anchor, coveredFrom, through,
                    firstRowDate != null, firstRowDate, lastRowDate));
        }
        return candidates.stream().max(Comparator.comparing(Coverage::through)
                .thenComparing(Coverage::anchor, Comparator.reverseOrder()));
    }

    private static boolean validFrozenDates(JsonNode dates, LocalDate from, LocalDate to) {
        List<LocalDate> parsed = parseFrozenDates(dates);
        return parsed != null && parsed.stream().allMatch(date -> !date.isBefore(from) && !date.isAfter(to));
    }

    private static List<LocalDate> parseFrozenDates(JsonNode dates) {
        if (!dates.isTextual()) return null;
        String encoded = dates.asText();
        if (encoded.equals("NONE")) return List.of();
        if (encoded.isBlank()) return null;
        LocalDate prior = null;
        var result = new ArrayList<LocalDate>();
        for (String value : encoded.split(",", -1)) {
            if (!value.matches("[0-9]{8}")) return null;
            LocalDate date;
            try { date = LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE); }
            catch (RuntimeException invalid) { return null; }
            if (prior != null && !date.isAfter(prior)) return null;
            result.add(date);
            prior = date;
        }
        return List.copyOf(result);
    }

    /** Read every frozen trade-date receipt; empty trading dates remain valid and do not imply target rows. */
    private static RowDates verifiedRowDates(SyncRunLedger ledger, Path ledgerPath, String runId,
            List<LocalDate> expectedDates, SyncRunState runState) throws Exception {
        var actualDates = new HashSet<LocalDate>();
        LocalDate first = null, last = null;
        int pages = 0, rows = 0;
        String after = null;
        while (true) {
            var entries = ledger.entries(runId, after, 1000);
            for (var entry : entries) {
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("Verified daily_basic run contains an unverified date slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) {
                    if (event.state() == SyncRunState.FETCHED) {
                        if (fetched != null) throw new IllegalStateException("Duplicate daily_basic fetched receipt event");
                        fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                    }
                }
                if (fetched == null || !fetched.path("responseEvidence").isTextual())
                    throw new IllegalStateException("Verified daily_basic slice has no source receipt path");
                Path evidencePath = Path.of(fetched.path("responseEvidence").textValue()).toAbsolutePath().normalize();
                Path evidenceRoot = ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").normalize();
                if (!evidencePath.startsWith(evidenceRoot) || !Files.isRegularFile(evidencePath))
                    throw new IllegalStateException("daily_basic source receipt is absent, oversized or outside its evidence root");
                Path realEvidenceRoot = evidenceRoot.toRealPath();
                Path realEvidencePath = evidencePath.toRealPath();
                if (!realEvidencePath.startsWith(realEvidenceRoot) || Files.size(realEvidencePath) > 16L * 1024 * 1024)
                    throw new IllegalStateException("daily_basic source receipt is oversized or resolves outside its evidence root");
                JsonNode evidence = JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(
                        realEvidencePath, 16 * 1024 * 1024, () -> new IllegalStateException(
                                "daily_basic source receipt is oversized or resolves outside its evidence root")));
                if (!"daily_basic".equals(evidence.path("endpoint").asText())
                        || !evidence.path("sourceComplete").asBoolean(false)
                        || !evidence.path("tradeDate").isTextual()
                        || !evidence.path("rows").isArray())
                    throw new IllegalStateException("daily_basic source receipt is incomplete");
                LocalDate tradeDate;
                try { tradeDate = LocalDate.parse(evidence.path("tradeDate").asText()); }
                catch (RuntimeException malformed) { throw new IllegalStateException("Invalid daily_basic receipt trade date", malformed); }
                if (!expectedDates.contains(tradeDate) || !actualDates.add(tradeDate)
                        || !tradeDate.toString().replace("-", "").equals(
                                evidence.path("parameters").path("trade_date").asText()))
                    throw new IllegalStateException("daily_basic source receipts do not match the frozen trade dates");
                JsonNode rawRows = evidence.path("rows");
                int returnedRows = fetched.path("returnedRows").canConvertToInt() ? fetched.path("returnedRows").asInt() : -1;
                if (rawRows.size() > DailyBasicSource.API_ROW_CAP || rawRows.size() != returnedRows
                        || rawRows.size() == 0 && entry.state() != SyncRunState.VERIFIED_EMPTY
                        || rawRows.size() > 0 && entry.state() != SyncRunState.VERIFIED)
                    throw new IllegalStateException("daily_basic slice state differs from its source receipt");
                validateFingerprint(fetched, evidence, tradeDate, rawRows);
                pages++;
                rows = Math.addExact(rows, rawRows.size());
                for (JsonNode row : rawRows) {
                    if (!row.path("trade_date").isTextual()
                            || !tradeDate.toString().replace("-", "").equals(row.path("trade_date").asText())
                            || !row.path("ts_code").isTextual()
                            || !row.path("ts_code").asText().matches("[0-9]{6}\\.(SZ|SH|BJ)"))
                        throw new IllegalStateException("daily_basic receipt contains a row outside its complete business key");
                }
                if (!rawRows.isEmpty()) {
                    if (first == null || tradeDate.isBefore(first)) first = tradeDate;
                    if (last == null || tradeDate.isAfter(last)) last = tradeDate;
                }
            }
            if (entries.size() < 1000) break;
            after = entries.getLast().id();
        }
        if (pages != expectedDates.size() || !actualDates.equals(new HashSet<>(expectedDates)))
            throw new IllegalStateException("Verified daily_basic run does not contain one source receipt per frozen trade date");
        if ((runState == SyncRunState.VERIFIED_EMPTY) != (rows == 0))
            throw new IllegalStateException("daily_basic run state differs from its verified source row count");
        return new RowDates(first, last, pages);
    }

    private static void validateFingerprint(JsonNode fetched, JsonNode evidence, LocalDate tradeDate,
            JsonNode rawRows) throws Exception {
        if (!fetched.path("sourceFingerprint").isTextual()
                || !DailyBasicSource.FIELDS.equals(JobDefinitionJson.mapper().convertValue(
                        evidence.path("fields"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {})))
            throw new IllegalStateException("daily_basic source receipt fields or fingerprint are absent");
        var rows = new ArrayList<Map<String, JsonNode>>(rawRows.size());
        for (JsonNode rawRow : rawRows) {
            if (!rawRow.isObject()) throw new IllegalStateException("daily_basic source receipt row is not an object");
            var values = new LinkedHashMap<String, JsonNode>();
            rawRow.fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue()));
            rows.add(values);
        }
        var fingerprintBody = new LinkedHashMap<String, Object>();
        fingerprintBody.put("endpoint", "daily_basic");
        fingerprintBody.put("parameters", Map.of("trade_date", tradeDate.toString().replace("-", "")));
        fingerprintBody.put("fields", DailyBasicSource.FIELDS);
        fingerprintBody.put("tradeDate", tradeDate);
        fingerprintBody.put("rows", rows);
        if (evidence.path("sourceVersion").isTextual())
            fingerprintBody.put("sourceVersion", evidence.path("sourceVersion").textValue());
        var canonical = JobDefinitionJson.canonicalMapper()
                .writeValueAsBytes(fingerprintBody);
        String actual = FileEvidenceStore.sha256(canonical);
        if (!actual.equals(fetched.path("sourceFingerprint").asText()))
            throw new IllegalStateException("daily_basic source receipt fingerprint differs from the ledger");
    }

    private static boolean sameDefinition(JsonNode expected, JsonNode actual) {
        if (!expected.isObject() || !actual.isObject()) return false;
        var expectedModes = new TreeSet<String>();
        var actualModes = new TreeSet<String>();
        expected.path("supportedModes").forEach(value -> expectedModes.add(value.asText()));
        actual.path("supportedModes").forEach(value -> actualModes.add(value.asText()));
        if (!expectedModes.equals(actualModes)) return false;
        var expectedCopy = expected.deepCopy();
        var actualCopy = actual.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) expectedCopy).remove("supportedModes");
        ((com.fasterxml.jackson.databind.node.ObjectNode) actualCopy).remove("supportedModes");
        return expectedCopy.equals(actualCopy);
    }
}
