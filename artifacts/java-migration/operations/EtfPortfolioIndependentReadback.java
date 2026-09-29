import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Operational D018 receipt-to-QuestDB verifier. It interprets source JSON independently and never
 * calls EtfPortfolioSource, EtfPortfolioMapper, or EtfPortfolioWritePort to construct expected rows.
 */
public final class EtfPortfolioIndependentReadback {
    private static final String JOB_ID = "data.etf_portfolio";
    private static final int JOB_VERSION = 1;
    private static final int MAX_WINDOW_DAYS = 45;
    private static final int PAGE_SIZE = 8_000;
    private static final int ROW_CAP_PER_DATE = 256_000;
    private static final int SOURCE_PAGE_CAP = 33;
    private static final int RUNNER_CHUNK_ROWS = 10_000;
    private static final int RUNNER_CHUNK_CAP = 26;
    private static final int MAX_RECEIPT_BYTES = 96 * 1024 * 1024;
    private static final long MAX_RUN_EVIDENCE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> SOURCE_FIELDS = List.of("ts_code", "ann_date", "end_date", "symbol",
            "mkv", "amount", "stk_mkv_ratio", "stk_float_ratio");
    private static final Set<String> SOURCE_FIELD_SET = Set.copyOf(SOURCE_FIELDS);
    private static final List<String> PHYSICAL_FIELDS = List.of("ts_code", "ann_date", "end_date", "symbol",
            "mkv", "amount", "stk_mkv_ratio", "stk_float_ratio", "update_time");
    private static final String READ_SQL = "SELECT ts_code,cast(ann_date AS long) AS ann_date_micros,"
            + "cast(end_date AS long) AS end_date_micros,symbol,mkv,amount,stk_mkv_ratio,stk_float_ratio,"
            + "cast(update_time AS long) AS update_time_micros FROM \"%s\" "
            + "WHERE ann_date=cast(? AS TIMESTAMP) ORDER BY ts_code,end_date,symbol LIMIT " + (ROW_CAP_PER_DATE + 1);

    private EtfPortfolioIndependentReadback() {}

    public static Map<String, Object> verify(JdbcTemplate jdbc, Path ledger, String table, String runId)
            throws Exception {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(ledger); Objects.requireNonNull(table); Objects.requireNonNull(runId);
        if (!table.matches("java_d018_etf_portfolio_[A-Za-z0-9_]{1,80}")
                || !runId.matches("[A-Za-z0-9_.-]{1,128}") || runId.contains(".."))
            throw new IllegalArgumentException("A D018 isolated table and bounded run ID are required");

        Path ledgerPath = ledger.toAbsolutePath().normalize();
        var json = JobDefinitionJson.mapper();
        var history = SyncRunLedger.openReadOnly(ledgerPath);
        var runEntry = history.get(runId);
        var run = history.getRun(runId);
        if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(runEntry.state())
                || !JOB_ID.equals(run.jobId()) || run.jobVersion() != JOB_VERSION)
            throw new IllegalStateException("A verified D018 run is required");
        JsonNode frozen = json.readTree(run.frozenJson());
        if (!JOB_ID.equals(frozen.path("definition").path("jobId").asText())
                || frozen.path("definition").path("version").asInt(-1) != JOB_VERSION)
            throw new IllegalStateException("Frozen request definition differs from D018");

        JsonNode parameters = frozen.path("parameters");
        String targetId = requiredText(parameters, "targetId");
        if (!targetId.equals(run.targetId()) || !targetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalStateException("Frozen D018 logical/physical target identity differs from ledger");
        String mode = frozen.path("mode").asText("");
        if (!Set.of("INCREMENTAL", "BACKFILL").contains(mode))
            throw new IllegalStateException("Unsupported D018 frozen mode");
        LocalDate from = iso(frozen.path("from").asText());
        LocalDate to = iso(frozen.path("to").asText());
        LocalDate logicalDate = iso(frozen.path("logicalDate").asText());
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (from.isAfter(to) || span < 1 || span > MAX_WINDOW_DAYS || to.isAfter(logicalDate))
            throw new IllegalStateException("Frozen D018 date range is invalid or exceeds its bound");
        Instant observedAt = canonicalInstant(requiredText(parameters, "observedAt"));
        List<LocalDate> dates = dates(from, to);
        String encodedDates = dates.stream().map(BASIC::format).reduce((left, right) -> left + "," + right).orElse("");
        if (!encodedDates.equals(requiredText(parameters, "ann_dates")))
            throw new IllegalStateException("Frozen D018 announcement dates are incomplete or differ from bounds");
        requireTargetIdentity(jdbc, table, targetId);

        Path evidenceRoot = ledgerPath.getParent().resolve("sync-evidence").resolve(runId).resolve("source").toRealPath();
        Map<LocalDate, List<FetchedChunk>> slices = fetchedChunks(history, runId, dates);
        var sourceReceipts = new ArrayList<Map<String, Object>>();
        long sourceRows = 0, actualRows = 0, matchedRows = 0, mismatches = 0;
        long sourceDuplicateKeys = 0, duplicateKeys = 0, missingKeys = 0, unexpectedKeys = 0;
        long sourcePages = 0, sourceEvidenceBytes = 0;
        var emptyDates = new ArrayList<String>(); var mismatchSamples = new ArrayList<String>();
        String query = READ_SQL.formatted(table);

        for (LocalDate date : dates) {
            List<FetchedChunk> fetched = slices.get(date);
            if (fetched == null || fetched.isEmpty()) throw new IllegalStateException("D018 run lacks an announcement-date receipt: " + date);
            fetched = fetched.stream().sorted(Comparator.comparingInt(FetchedChunk::index)).toList();
            if (fetched.size() > RUNNER_CHUNK_CAP) throw new IllegalStateException("D018 runner chunk count exceeds bound");

            var first = fetched.getFirst();
            var firstChunkFile = readEvidence(evidenceRoot, first.path(), first.fingerprint());
            sourceEvidenceBytes = Math.addExact(sourceEvidenceBytes, firstChunkFile.bytes().length);
            JsonNode firstChunkBody = firstChunkFile.body();
            String globalFingerprint = requiredText(firstChunkBody, "sourceFingerprint");
            String sourceName = requiredText(firstChunkBody, "sourceReceipt");
            String basic = BASIC.format(date);
            if (!globalFingerprint.matches("[0-9a-f]{64}")
                    || !sourceName.equals("source-" + basic + "-" + globalFingerprint + ".json"))
                throw new IllegalStateException("D018 chunk source receipt link is not a SHA-addressed sibling file");
            Path fullPath = firstChunkFile.path().getParent().resolve(sourceName);
            var fullFile = readEvidence(evidenceRoot, fullPath, globalFingerprint);
            sourceEvidenceBytes = Math.addExact(sourceEvidenceBytes, fullFile.bytes().length);
            JsonNode full = fullFile.body();
            int totalRows = validateFullReceipt(full, fullFile.path(), date, observedAt);
            int sourcePageCount = full.path("sourcePages").asInt(-1);
            sourcePages = Math.addExact(sourcePages, sourcePageCount);
            int chunkCount = Math.max(1, Math.toIntExact((totalRows + (long) RUNNER_CHUNK_ROWS - 1) / RUNNER_CHUNK_ROWS));
            if (chunkCount > RUNNER_CHUNK_CAP || fetched.size() != chunkCount)
                throw new IllegalStateException("D018 ledger does not contain the complete runner chunk sequence for " + date);

            JsonNode rawRows = full.path("rawRows");
            var expected = new HashMap<Key, Expected>();
            var chunkReports = new ArrayList<Map<String, Object>>();
            long countedChunkRows = 0;
            for (int index = 0; index < chunkCount; index++) {
                FetchedChunk ref = fetched.get(index);
                int expectedOffset = index * RUNNER_CHUNK_ROWS;
                int expectedRows = Math.max(0, Math.min(RUNNER_CHUNK_ROWS, totalRows - expectedOffset));
                if (ref.index() != index || ref.entryRows() != expectedRows || ref.stateEmpty() != (expectedRows == 0))
                    throw new IllegalStateException("D018 ledger chunks have a gap, duplicate, wrong count or state");
                var chunkFile = index == 0 ? firstChunkFile : readEvidence(evidenceRoot, ref.path(), ref.fingerprint());
                if (index > 0) sourceEvidenceBytes = Math.addExact(sourceEvidenceBytes, chunkFile.bytes().length);
                JsonNode chunk = chunkFile.body();
                validateChunkReceipt(chunk, chunkFile.path(), ref, fullFile, full, date, observedAt,
                        totalRows, chunkCount, expectedOffset, expectedRows);
                countedChunkRows = Math.addExact(countedChunkRows, expectedRows);
                chunkReports.add(Map.of("index", index, "rows", expectedRows, "path", chunkFile.path().toString(),
                        "sha256", chunkFile.sha256(), "cursor", ref.cursor()));
            }
            if (countedChunkRows != totalRows) throw new IllegalStateException("D018 chunk row total differs from full source receipt");

            long sourceDuplicatesForDate = 0;
            for (JsonNode raw : rawRows) {
                Expected row = normalize(raw, date, observedAt);
                if (expected.putIfAbsent(row.key(), row) != null) {
                    sourceDuplicateKeys++;
                    sourceDuplicatesForDate++;
                    addSample(mismatchSamples, "duplicate-source-key:" + row.key());
                }
            }
            if (expected.size() + sourceDuplicatesForDate != totalRows)
                throw new IllegalStateException("D018 source key accounting is inconsistent");
            if (totalRows == 0) emptyDates.add(date.toString());
            sourceRows = Math.addExact(sourceRows, totalRows);

            List<Actual> actual = readActualDate(jdbc, query, date);
            actualRows = Math.addExact(actualRows, actual.size());
            Comparison comparison = compare(expected, actual, date, mismatchSamples);
            matchedRows = Math.addExact(matchedRows, comparison.matches());
            mismatches = Math.addExact(mismatches, comparison.mismatches());
            duplicateKeys = Math.addExact(duplicateKeys, comparison.duplicateKeys());
            missingKeys = Math.addExact(missingKeys, comparison.missingKeys());
            unexpectedKeys = Math.addExact(unexpectedKeys, comparison.unexpectedKeys());
            sourceReceipts.add(Map.of("annDate", date.toString(), "sourceRows", totalRows,
                    "sourceFingerprint", fullFile.sha256(), "sourceReceipt", fullFile.path().toString(),
                    "sourcePages", sourcePageCount, "runnerChunks", chunkReports));
            if (sourceEvidenceBytes > MAX_RUN_EVIDENCE_BYTES)
                throw new IllegalStateException("D018 source evidence exceeds independent verifier bound");
        }

        if (runEntry.state() == SyncRunState.VERIFIED_EMPTY && sourceRows != 0
                || runEntry.state() == SyncRunState.VERIFIED && sourceRows == 0)
            throw new IllegalStateException("D018 run state conflicts with source receipt totals");
        String status = mismatches == 0 && duplicateKeys == 0 && missingKeys == 0 && unexpectedKeys == 0
                && sourceDuplicateKeys == 0 && sourceRows == actualRows && matchedRows == sourceRows
                ? "MATCHED" : "MISMATCH";
        var report = new LinkedHashMap<String, Object>();
        report.put("task", "D018"); report.put("runId", runId); report.put("status", status);
        report.put("target", table); report.put("targetId", targetId); report.put("mode", mode);
        report.put("requestFrom", from.toString()); report.put("requestTo", to.toString());
        report.put("logicalDate", logicalDate.toString()); report.put("observedAt", observedAt.toString());
        report.put("announcementDates", dates.stream().map(LocalDate::toString).toList());
        report.put("sourceCalls", sourcePages); report.put("runnerChunks", slices.values().stream().mapToInt(List::size).sum());
        report.put("sourceRows", sourceRows); report.put("actualRows", actualRows); report.put("matchedRows", matchedRows);
        report.put("mismatches", mismatches); report.put("sourceDuplicateKeys", sourceDuplicateKeys);
        report.put("duplicateKeys", duplicateKeys); report.put("missingKeys", missingKeys);
        report.put("unexpectedKeys", unexpectedKeys); report.put("emptyAnnouncementDates", emptyDates);
        report.put("sourceEvidenceBytes", sourceEvidenceBytes); report.put("comparedColumns", PHYSICAL_FIELDS);
        report.put("readbackQuery", query); report.put("queryParameters", dates.stream().map(LocalDate::toString).toList());
        report.put("sourceReceipts", sourceReceipts); report.put("mismatchSamples", mismatchSamples);
        report.put("passed", "MATCHED".equals(status));
        return Map.copyOf(report);
    }

    private static Map<LocalDate, List<FetchedChunk>> fetchedChunks(SyncRunLedger ledger, String runId,
            List<LocalDate> dates) throws Exception {
        var found = new HashMap<LocalDate, List<FetchedChunk>>();
        var allowedDates = Set.copyOf(dates); String after = null; int seen = 0;
        while (true) {
            var entries = ledger.entries(runId, after, 1000);
            for (var entry : entries) {
                if (++seen > MAX_WINDOW_DAYS * RUNNER_CHUNK_CAP + 2)
                    throw new IllegalStateException("D018 run has more ledger entries than the frozen page budget");
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("D018 run contains an unverified slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 1000)) if (event.state() == SyncRunState.FETCHED) {
                    if (fetched != null) throw new IllegalStateException("D018 slice has duplicate FETCHED events");
                    fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                }
                if (fetched == null) throw new IllegalStateException("D018 verified slice lacks FETCHED evidence");
                String cursor = requiredText(fetched, "cursor");
                if (!cursor.matches("[0-9]{8}#[0-9]+")) throw new IllegalStateException("D018 FETCHED cursor must be date#chunkIndex");
                String[] parts = cursor.split("#", -1);
                LocalDate date = LocalDate.parse(parts[0], BASIC);
                int index = Integer.parseInt(parts[1]);
                if (!allowedDates.contains(date) || index < 0 || index >= RUNNER_CHUNK_CAP)
                    throw new IllegalStateException("D018 slice is outside frozen date/chunk scope");
                int returned = fetched.path("returnedRows").asInt(-1);
                String fingerprint = requiredText(fetched, "sourceFingerprint");
                String evidence = requiredText(fetched, "responseEvidence");
                if (returned < 0 || returned > RUNNER_CHUNK_ROWS || !fingerprint.matches("[0-9a-f]{64}"))
                    throw new IllegalStateException("D018 FETCHED row count or chunk SHA is invalid");
                Path path = Path.of(evidence).toAbsolutePath().normalize();
                var chunk = new FetchedChunk(entry.id(), date, index, cursor, returned,
                        entry.state() == SyncRunState.VERIFIED_EMPTY, path, fingerprint);
                found.computeIfAbsent(date, ignored -> new ArrayList<>()).add(chunk);
            }
            if (entries.size() < 1000) break;
            after = entries.getLast().id();
        }
        if (!found.keySet().equals(allowedDates)) throw new IllegalStateException("D018 ledger date receipts differ from frozen window");
        return found;
    }

    private static int validateFullReceipt(JsonNode full, Path path, LocalDate date, Instant observedAt) {
        String basic = BASIC.format(date);
        if (!"tushare".equals(full.path("sourceKind").asText())
                || !"fund_portfolio".equals(full.path("endpoint").asText())
                || full.path("sourceContractVersion").asInt(-1) != 1
                || !full.path("sourceComplete").asBoolean(false)
                || !date.toString().equals(full.path("announcementDate").asText())
                || !observedAt.toString().equals(full.path("observedAt").asText())
                || !JobDefinitionJson.mapper().valueToTree(SOURCE_FIELDS).equals(full.path("fields"))
                || !full.path("parameters").isObject() || full.path("parameters").size() != 1
                || !basic.equals(full.path("parameters").path("ann_date").asText())
                || full.path("pageSize").asInt(-1) != PAGE_SIZE
                || full.path("annDateRowCap").asInt(-1) != ROW_CAP_PER_DATE
                || !full.path("rawRows").isArray()
                || full.path("returnedRows").asInt(-1) != full.path("rawRows").size()
                || full.path("rawRows").size() > ROW_CAP_PER_DATE
                || !path.getFileName().toString().equals("source-" + basic + "-" + pathHashFromName(path) + ".json"))
            throw new IllegalStateException("D018 complete source receipt scope, fields, path or row count differs");
        int rows = full.path("rawRows").size();
        int pages = full.path("sourcePages").asInt(-1);
        if (pages < 1 || pages > SOURCE_PAGE_CAP || !validPageEvidence(full.path("pageEvidence"), rows, pages))
            throw new IllegalStateException("D018 complete source receipt paging manifest is invalid");
        return rows;
    }

    private static void validateChunkReceipt(JsonNode chunk, Path chunkPath, FetchedChunk ref,
            ReceiptFile fullFile, JsonNode full, LocalDate date, Instant observedAt,
            int totalRows, int chunkCount, int expectedOffset, int expectedRows) {
        String basic = BASIC.format(date);
        if (!"tushare".equals(chunk.path("sourceKind").asText())
                || !"fund_portfolio".equals(chunk.path("endpoint").asText())
                || chunk.path("sourceContractVersion").asInt(-1) != 1
                || !chunk.path("sourceComplete").asBoolean(false)
                || !date.toString().equals(chunk.path("announcementDate").asText())
                || !observedAt.toString().equals(chunk.path("observedAt").asText())
                || !JobDefinitionJson.mapper().valueToTree(SOURCE_FIELDS).equals(chunk.path("fields"))
                || chunk.path("pageSize").asInt(-1) != PAGE_SIZE
                || chunk.path("annDateRowCap").asInt(-1) != ROW_CAP_PER_DATE
                || !basic.equals(chunk.path("parameters").path("ann_date").asText())
                || !fullFile.sha256().equals(chunk.path("sourceFingerprint").asText())
                || !fullFile.path().getFileName().toString().equals(chunk.path("sourceReceipt").asText())
                || chunk.path("totalReturnedRows").asInt(-1) != totalRows
                || chunk.path("chunkIndex").asInt(-1) != ref.index()
                || chunk.path("chunkCount").asInt(-1) != chunkCount
                || chunk.path("chunkOffset").asInt(-1) != expectedOffset
                || chunk.path("returnedRows").asInt(-1) != expectedRows
                || !chunk.path("rawRows").isArray() || chunk.path("rawRows").size() != expectedRows
                || !chunk.path("pageEvidence").equals(full.path("pageEvidence")))
            throw new IllegalStateException("D018 chunk receipt differs from ledger/full source manifest");
        String chunkName = "chunk-" + basic + "-" + fullFile.sha256() + "-" + ref.index() + ".json";
        if (!chunkName.equals(chunkPath.getFileName().toString()) || !ref.cursor().equals(basic + "#" + ref.index()))
            throw new IllegalStateException("D018 chunk filename/cursor differs from its manifest");
        JsonNode raw = chunk.path("rawRows"); JsonNode all = full.path("rawRows");
        for (int i = 0; i < expectedRows; i++) if (!raw.get(i).equals(all.get(expectedOffset + i)))
            throw new IllegalStateException("D018 chunk raw rows differ from the complete source receipt");
    }

    private static boolean validPageEvidence(JsonNode pages, int totalRows, int expectedPages) {
        if (!pages.isArray() || pages.size() != expectedPages || pages.size() < 1 || pages.size() > SOURCE_PAGE_CAP)
            return false;
        long offset = 0; int rows = 0;
        for (int i = 0; i < pages.size(); i++) {
            JsonNode page = pages.get(i); int count = page.path("rows").asInt(-1);
            if (page.path("offset").asLong(-1) != offset || page.path("limit").asInt(-1) != PAGE_SIZE
                    || count < 0 || count > PAGE_SIZE || count < PAGE_SIZE && i != pages.size() - 1)
                return false;
            offset += count; rows = Math.addExact(rows, count);
        }
        return rows == totalRows && rows <= ROW_CAP_PER_DATE
                && pages.get(pages.size() - 1).path("rows").asInt() < PAGE_SIZE;
    }

    private static Expected normalize(JsonNode raw, LocalDate expectedDate, Instant observedAt) {
        if (raw == null || !raw.isObject() || raw.size() != SOURCE_FIELDS.size())
            throw new IllegalStateException("D018 raw source row must contain exactly eight fields");
        var names = new HashSet<String>(); raw.fieldNames().forEachRemaining(names::add);
        if (!names.equals(SOURCE_FIELD_SET)) throw new IllegalStateException("D018 raw source field set differs");
        String fund = text(raw, "ts_code"), annText = text(raw, "ann_date");
        String endText = text(raw, "end_date"), symbol = text(raw, "symbol");
        if (!fund.matches("[A-Za-z0-9]{1,16}\\.[A-Z]{2,3}")
                || !symbol.matches("[A-Za-z0-9]{1,16}\\.[A-Z]{2,3}"))
            throw new IllegalStateException("D018 source code lacks an exchange suffix");
        LocalDate ann = basicDate(annText), end = basicDate(endText);
        if (!ann.equals(expectedDate) || end.isAfter(ann))
            throw new IllegalStateException("D018 raw row escapes ann_date or has a future report period");
        long observedMicros = epochMicros(observedAt);
        return new Expected(new Key(fund, ann, end, symbol), numeric(raw, "mkv"), numeric(raw, "amount"),
                numeric(raw, "stk_mkv_ratio"), numeric(raw, "stk_float_ratio"), observedMicros);
    }

    private static List<Actual> readActualDate(JdbcTemplate jdbc, String query, LocalDate date) {
        long micros = calendarMicros(date);
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(query);
            statement.setQueryTimeout(90); statement.setFetchSize(1000); statement.setMaxRows(ROW_CAP_PER_DATE + 1);
            statement.setLong(1, micros); return statement;
        }, rows -> {
            var actual = new ArrayList<Actual>();
            while (rows.next()) {
                if (actual.size() >= ROW_CAP_PER_DATE) throw new SQLException("D018 physical date exceeds independent readback row cap");
                String fund = rows.getString("ts_code"), symbol = rows.getString("symbol");
                LocalDate ann = calendarDate(rows, "ann_date_micros"), end = calendarDate(rows, "end_date_micros");
                Object rawUpdate = rows.getObject("update_time_micros");
                if (!(rawUpdate instanceof Number update)) throw new SQLException("D018 update_time is null/non-numeric");
                if (fund == null || symbol == null || ann == null || end == null)
                    throw new SQLException("D018 physical row has a null business key");
                actual.add(new Actual(new Key(fund, ann, end, symbol), numeric(rows, "mkv"), numeric(rows, "amount"),
                        numeric(rows, "stk_mkv_ratio"), numeric(rows, "stk_float_ratio"), update.longValue()));
            }
            return List.copyOf(actual);
        });
    }

    private static Comparison compare(Map<Key, Expected> expected, List<Actual> actual,
            LocalDate date, List<String> samples) {
        var found = new HashMap<Key, Actual>(); long matches = 0, mismatches = 0, duplicates = 0, unexpected = 0;
        for (Actual row : actual) {
            if (!row.key().annDate().equals(date)) throw new IllegalStateException("D018 physical SELECT returned an out-of-date row");
            if (found.putIfAbsent(row.key(), row) != null) {
                duplicates++; addSample(samples, "duplicate-physical-key:" + row.key()); continue;
            }
            Expected source = expected.get(row.key());
            if (source == null) { unexpected++; addSample(samples, "unexpected-physical-key:" + row.key()); }
            else if (sameValues(source, row)) matches++;
            else { mismatches++; addSample(samples, "value-mismatch:" + row.key()); }
        }
        long missing = 0;
        for (Key key : expected.keySet()) if (!found.containsKey(key)) {
            missing++; addSample(samples, "missing-physical-key:" + key);
        }
        return new Comparison(matches, mismatches, duplicates, missing, unexpected);
    }

    private static boolean sameValues(Expected expected, Actual actual) {
        return sameDouble(expected.mkv(), actual.mkv()) && sameDouble(expected.amount(), actual.amount())
                && sameDouble(expected.stkMkvRatio(), actual.stkMkvRatio())
                && sameDouble(expected.stkFloatRatio(), actual.stkFloatRatio())
                && expected.updateMicros() == actual.updateMicros();
    }
    private static boolean sameDouble(Double left, Double right) {
        return left == null ? right == null : right != null
                && Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
    }

    private static ReceiptFile readEvidence(Path root, Path requested, String expectedHash) throws Exception {
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("D018 receipt SHA-256 required");
        Path realRoot = root.toRealPath(); Path path = requested.toAbsolutePath().normalize().toRealPath();
        if (!path.startsWith(realRoot) || path.equals(realRoot) || Files.size(path) < 1
                || Files.size(path) > MAX_RECEIPT_BYTES)
            throw new IllegalStateException("D018 receipt is outside source evidence root or exceeds byte bound");
        byte[] bytes = Files.readAllBytes(path); String actualHash = sha256(bytes);
        if (!actualHash.equals(expectedHash)) throw new IllegalStateException("D018 receipt SHA-256 differs from FETCHED/source link");
        return new ReceiptFile(path, actualHash, bytes, JobDefinitionJson.mapper().readTree(bytes));
    }

    private static void requireTargetIdentity(JdbcTemplate jdbc, String table, String targetId) {
        var identity = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (identity.size() != 1 || !(identity.getFirst().get("id") instanceof Number id)
                || !(identity.getFirst().get("directoryName") instanceof String directory)
                || !targetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D018 live isolated table identity differs from frozen run target");
    }

    private static String pathHashFromName(Path path) {
        String name = path.getFileName().toString();
        String prefix = "source-";
        String suffix = ".json";
        if (!name.startsWith(prefix) || !name.endsWith(suffix)) return "";
        String value = name.substring(prefix.length(), name.length() - suffix.length());
        int split = value.lastIndexOf('-');
        return split < 0 ? "" : value.substring(split + 1);
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank())
            throw new IllegalStateException("D018 required raw text field missing: " + field);
        return value.asText();
    }
    private static Double numeric(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() && !value.isTextual()) throw new IllegalStateException("D018 numeric/null field required: " + field);
        String raw = value.asText();
        if (raw.isBlank()) throw new IllegalStateException("D018 blank nonnull number: " + field);
        double parsed;
        try { parsed = new BigDecimal(raw).doubleValue(); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("D018 invalid decimal: " + field, invalid); }
        if (!Double.isFinite(parsed)) throw new IllegalStateException("D018 non-finite source number: " + field);
        return parsed;
    }
    private static Double numeric(java.sql.ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new SQLException("Invalid D018 physical numeric column: " + column);
        return number.doubleValue();
    }
    private static LocalDate basicDate(String value) {
        if (value == null || !value.matches("[0-9]{8}")) throw new IllegalStateException("D018 BASIC calendar date required");
        LocalDate date = LocalDate.parse(value, BASIC);
        if (!BASIC.format(date).equals(value)) throw new IllegalStateException("Noncanonical D018 BASIC calendar date");
        return date;
    }
    private static LocalDate calendarDate(java.sql.ResultSet rs, String column) throws SQLException {
        Object raw = rs.getObject(column);
        if (!(raw instanceof Number number)) throw new SQLException("D018 physical calendar timestamp is null/non-numeric");
        long micros = number.longValue(); long seconds = Math.floorDiv(micros, 1_000_000L);
        long remainder = Math.floorMod(micros, 1_000_000L);
        LocalDateTime value = LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds, remainder * 1_000L), ZoneOffset.UTC);
        if (!value.toLocalTime().equals(java.time.LocalTime.MIDNIGHT))
            throw new SQLException("D018 business date is not a UTC-midnight calendar carrier");
        return value.toLocalDate();
    }

    private static long calendarMicros(LocalDate date) {
        return Math.multiplyExact(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC), 1_000_000L);
    }
    private static long epochMicros(Instant instant) {
        if (instant.getNano() % 1_000 != 0) throw new IllegalStateException("D018 observation instant must have microsecond precision");
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }
    private static Instant canonicalInstant(String value) {
        try {
            Instant parsed = Instant.parse(value);
            epochMicros(parsed);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException invalid) { throw new IllegalStateException("Canonical frozen D018 observation instant required", invalid); }
    }
    private static LocalDate iso(String value) {
        try { return LocalDate.parse(value); }
        catch (RuntimeException invalid) { throw new IllegalStateException("ISO D018 calendar date required", invalid); }
    }
    private static List<LocalDate> dates(LocalDate from, LocalDate to) {
        var result = new ArrayList<LocalDate>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) result.add(day);
        return List.copyOf(result);
    }
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("Required D018 evidence field: " + field);
        return value.asText();
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void addSample(List<String> samples, String value) {
        if (samples.size() < 30) samples.add(value);
    }

    private record Key(String fund, LocalDate annDate, LocalDate endDate, String symbol) {}
    private record Expected(Key key, Double mkv, Double amount, Double stkMkvRatio, Double stkFloatRatio,
                            long updateMicros) {}
    private record Actual(Key key, Double mkv, Double amount, Double stkMkvRatio, Double stkFloatRatio,
                          long updateMicros) {}
    private record FetchedChunk(String entryId, LocalDate date, int index, String cursor, int entryRows,
                                boolean stateEmpty, Path path, String fingerprint) {}
    private record ReceiptFile(Path path, String sha256, byte[] bytes, JsonNode body) {}
    private record Comparison(long matches, long mismatches, long duplicateKeys, long missingKeys,
                              long unexpectedKeys) {}
}
