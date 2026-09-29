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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Independent D016 raw receipt-to-QuestDB check. It uses only read-only ledger access and SELECTs. */
public final class EtfShareIndependentReadback {
    private static final List<String> SOURCE_FIELDS = List.of("ts_code", "trade_date", "fd_share", "fund_type", "market");
    private static final List<String> PHYSICAL_FIELDS = List.of("ts_code", "timestamp", "fd_share", "fund_type", "market", "update_time");
    private static final List<String> MARKETS = List.of("SH", "SZ", "O");
    private static final int SOURCE_ROW_CAP = 2_000;
    private static final int MAX_ROWS_PER_DATE = 3 * (SOURCE_ROW_CAP - 1);
    private static final int MAX_WINDOW_DAYS = 366;
    private static final long MAX_RECEIPT_BYTES = 32L * 1024 * 1024;
    private static final int MAX_LEDGER_ENTRIES = 2_000;
    private static final int MAX_REPORT_SAMPLES = 20;

    private record Key(String tsCode, long timestampMicros) {}
    private record Expected(String tsCode, long timestampMicros, Double fdShare, String fundType,
                            String market, long updateTimeMicros) {}
    private record Actual(String tsCode, long timestampMicros, Double fdShare, String fundType,
                          String market, long updateTimeMicros) {}
    private record ReceiptSummary(String date, String path, String fingerprint, int rows) {}

    private EtfShareIndependentReadback() {}

    /** Verify each frozen source receipt independently against every physical row in the frozen date window. */
    public static Map<String, Object> verify(JdbcTemplate jdbc, Path ledgerPath, String table, String runId) throws Exception {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(ledgerPath); Objects.requireNonNull(runId);
        if (table == null || !table.matches("java_d016_etf_share_[A-Za-z0-9_]{1,80}"))
            throw new IllegalArgumentException("D016 isolated target table required");

        Path absoluteLedger = ledgerPath.toAbsolutePath().normalize();
        SyncRunLedger ledger = SyncRunLedger.openReadOnly(absoluteLedger);
        var runEntry = ledger.get(runId);
        var run = ledger.getRun(runId);
        if (runEntry.kind() != SyncRunLedger.Kind.RUN || !runEntry.id().equals(runId)
                || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(runEntry.state())
                || !"data.etf_share".equals(run.jobId()) || run.jobVersion() != 3)
            throw new IllegalStateException("Verified D016 job version 3 run required");

        var json = JobDefinitionJson.mapper();
        JsonNode frozen = json.readTree(run.frozenJson());
        JsonNode definition = frozen.path("definition");
        JsonNode parameters = frozen.path("parameters");
        String targetId = run.targetId();
        if (!"data.etf_share".equals(definition.path("jobId").asText())
                || definition.path("version").asInt(-1) != 3
                || !"etf_share".equals(definition.path("datasetId").asText())
                || definition.path("datasetVersion").asInt(-1) != 1
                || !targetId.equals(parameters.path("targetId").asText()))
            throw new IllegalStateException("Frozen D016 job, dataset or target identity differs from its ledger run");

        LocalDate from = parseIsoDate(frozen.path("from").asText());
        LocalDate to = parseIsoDate(frozen.path("to").asText());
        long windowDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (from.isAfter(to) || windowDays < 1 || windowDays > MAX_WINDOW_DAYS)
            throw new IllegalStateException("Frozen D016 date window exceeds its finite 366-day bound");
        List<LocalDate> requestedDates = parseTradeDates(parameters.path("trade_dates").asText(), from, to);
        String observedText = parameters.path("observedAt").asText();
        Instant observedAt = Instant.parse(observedText);
        if (observedAt.getNano() % 1_000 != 0 || !observedAt.toString().equals(observedText))
            throw new IllegalStateException("Frozen D016 observedAt must be canonical UTC with microsecond precision");
        long observedMicros = epochMicros(observedAt);

        Path evidenceRoot = absoluteLedger.getParent().resolve("sync-evidence").toRealPath();
        Map<Key, Expected> expectedRows = new LinkedHashMap<>();
        Map<LocalDate, ReceiptSummary> receiptSummaries = new LinkedHashMap<>();
        Set<LocalDate> expectedDateSet = new HashSet<>(requestedDates);
        String afterId = null;
        int entriesSeen = 0;
        while (true) {
            var page = ledger.entries(runId, afterId, 1_000);
            for (var entry : page) {
                if (++entriesSeen > MAX_LEDGER_ENTRIES) throw new IllegalStateException("D016 ledger run exceeds bounded entry scan");
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(entry.state()))
                    throw new IllegalStateException("Verified D016 run contains a nonverified slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) {
                    if (event.state() == SyncRunState.FETCHED) {
                        if (fetched != null) throw new IllegalStateException("D016 slice has duplicate FETCHED evidence");
                        fetched = json.readTree(event.payloadJson());
                    }
                }
                if (fetched == null) throw new IllegalStateException("D016 slice lacks FETCHED source receipt metadata");
                String cursor = fetched.path("cursor").asText();
                if (!cursor.matches("[0-9]{8}")) throw new IllegalStateException("D016 slice cursor must be BASIC date");
                LocalDate date = LocalDate.parse(cursor, DateTimeFormatter.BASIC_ISO_DATE);
                if (!expectedDateSet.contains(date) || receiptSummaries.containsKey(date))
                    throw new IllegalStateException("D016 receipt date is outside frozen sessions or duplicated");
                String fingerprint = fetched.path("sourceFingerprint").asText();
                String receiptName = fetched.path("responseEvidence").asText();
                int sliceRows = fetched.path("returnedRows").asInt(-1);
                if (!fingerprint.matches("[0-9a-f]{64}") || receiptName.isBlank() || sliceRows < 0
                        || (entry.state() == SyncRunState.VERIFIED_EMPTY) != (sliceRows == 0))
                    throw new IllegalStateException("D016 FETCHED receipt metadata is invalid");
                Path receiptPath = Path.of(receiptName).toRealPath();
                if (!receiptPath.startsWith(evidenceRoot) || !Files.isRegularFile(receiptPath)
                        || Files.size(receiptPath) > MAX_RECEIPT_BYTES)
                    throw new IllegalStateException("D016 source receipt is outside bounded evidence storage");
                byte[] receiptBytes = Files.readAllBytes(receiptPath);
                String actualFingerprint = sha256(receiptBytes);
                if (!actualFingerprint.equals(fingerprint))
                    throw new IllegalStateException("D016 source receipt SHA-256 differs from ledger");
                JsonNode receipt = json.readTree(receiptBytes);
                int parsedRows = parseSourceReceipt(receipt, date, observedText, observedMicros, expectedRows);
                if (parsedRows != sliceRows)
                    throw new IllegalStateException("D016 slice row count differs from its raw receipt");
                receiptSummaries.put(date, new ReceiptSummary(date.toString(), receiptPath.toString(), fingerprint, parsedRows));
            }
            if (page.size() < 1_000) break;
            afterId = page.getLast().id();
        }
        if (!receiptSummaries.keySet().equals(expectedDateSet))
            throw new IllegalStateException("D016 verified run lacks a receipt for every frozen SSE session");
        if (expectedRows.size() > requestedDates.size() * MAX_ROWS_PER_DATE)
            throw new IllegalStateException("D016 raw receipts exceed the finite per-date row budget");
        boolean expectedEmpty = expectedRows.isEmpty();
        if (expectedEmpty != (runEntry.state() == SyncRunState.VERIFIED_EMPTY))
            throw new IllegalStateException("D016 run terminal state differs from raw source receipt rows");
        JsonNode terminal = json.readTree(runEntry.payloadJson());
        int ledgerExpected = expectedEmpty ? terminal.path("returnedRows").asInt(-1)
                : terminal.path("verification").path("expectedRows").asInt(-1);
        if (ledgerExpected != expectedRows.size())
            throw new IllegalStateException("D016 run terminal row count differs from raw source receipts");

        JdbcTemplate readOnlyJdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        readOnlyJdbc.setQueryTimeout(60);
        requireTargetIdentity(readOnlyJdbc, table, targetId);
        int maxWindowRows = Math.toIntExact(Math.multiplyExact(windowDays, MAX_ROWS_PER_DATE));
        readOnlyJdbc.setMaxRows(maxWindowRows + 1);
        long startMicros = epochMicros(from);
        long endExclusiveMicros = epochMicros(to.plusDays(1));
        String query = "SELECT ts_code, cast(timestamp AS long) AS timestamp_micros, fd_share, fund_type, market, "
                + "cast(update_time AS long) AS update_time_micros FROM \"" + table + "\" "
                + "WHERE timestamp >= cast(? AS TIMESTAMP) AND timestamp < cast(? AS TIMESTAMP) "
                + "ORDER BY timestamp, ts_code LIMIT " + (maxWindowRows + 1);
        List<Actual> actualRows = readOnlyJdbc.query(query, (rs, row) -> actual(rs), startMicros, endExclusiveMicros);
        requireTargetIdentity(readOnlyJdbc, table, targetId);
        boolean actualOverBound = actualRows.size() > maxWindowRows;

        Map<Key, Actual> actualByKey = new HashMap<>();
        var duplicateKeys = new TreeSet<String>();
        for (Actual row : actualRows) {
            Key key = new Key(row.tsCode(), row.timestampMicros());
            if (actualByKey.putIfAbsent(key, row) != null) duplicateKeys.add(keyLabel(key));
        }

        var extraKeys = new TreeSet<String>();
        var missingKeys = new TreeSet<String>();
        var mismatchExamples = new ArrayList<Map<String, Object>>();
        var mismatchFields = new LinkedHashMap<String, Integer>();
        int matched = 0;
        int mismatchedRows = 0;
        int mismatchedValues = 0;
        for (var wantEntry : expectedRows.entrySet()) {
            Expected want = wantEntry.getValue();
            Key key = new Key(want.tsCode(), want.timestampMicros());
            Actual actual = actualByKey.get(key);
            if (actual == null) {
                missingKeys.add(keyLabel(key));
                continue;
            }
            var changed = differingColumns(want, actual);
            if (changed.isEmpty()) matched++;
            else {
                mismatchedRows++;
                mismatchedValues += changed.size();
                for (String field : changed) mismatchFields.merge(field, 1, Integer::sum);
                if (mismatchExamples.size() < MAX_REPORT_SAMPLES) {
                    var sample = new LinkedHashMap<String, Object>();
                    sample.put("key", keyLabel(key)); sample.put("columns", changed);
                    mismatchExamples.add(sample);
                }
            }
        }
        for (Key key : actualByKey.keySet()) if (!expectedRows.containsKey(key))
            extraKeys.add(keyLabel(key));

        var report = new LinkedHashMap<String, Object>();
        report.put("task", "D016");
        report.put("runId", runId);
        report.put("target", table);
        report.put("targetId", targetId);
        report.put("status", !actualOverBound && duplicateKeys.isEmpty() && extraKeys.isEmpty() && missingKeys.isEmpty()
                && mismatchedRows == 0 ? "MATCHED" : "MISMATCH");
        report.put("windowFrom", from.toString());
        report.put("windowTo", to.toString());
        report.put("frozenSessions", requestedDates.size());
        report.put("sourceReceiptCount", receiptSummaries.size());
        report.put("sourceRows", expectedRows.size());
        report.put("actualWindowRows", actualRows.size());
        report.put("actualWindowOverBound", actualOverBound);
        report.put("matchedRows", matched);
        report.put("mismatchedRows", mismatchedRows);
        report.put("mismatchedValues", mismatchedValues);
        report.put("mismatchedFieldCounts", mismatchFields);
        report.put("missingKeyCount", missingKeys.size());
        report.put("missingKeySamples", samples(missingKeys));
        report.put("extraKeyCount", extraKeys.size());
        report.put("extraKeySamples", samples(extraKeys));
        report.put("duplicateKeyCount", duplicateKeys.size());
        report.put("duplicateKeySamples", samples(duplicateKeys));
        report.put("mismatchSamples", mismatchExamples);
        report.put("comparedColumns", PHYSICAL_FIELDS);
        report.put("receipts", receiptSummaries.values().stream().map(EtfShareIndependentReadback::receiptMap).toList());
        report.put("readOnlyQuery", query);
        return report;
    }

    private static int parseSourceReceipt(JsonNode receipt, LocalDate date, String observedText, long observedMicros,
                                          Map<Key, Expected> expectedRows) throws Exception {
        if (!"tushare".equals(receipt.path("sourceKind").asText())
                || !"fund_share".equals(receipt.path("endpoint").asText())
                || !receipt.path("sourceComplete").asBoolean(false)
                || !date.toString().equals(receipt.path("tradeDate").asText())
                || !observedText.equals(receipt.path("observedAt").asText())
                || receipt.path("requestCap").asInt(-1) != SOURCE_ROW_CAP
                || receipt.path("dateRowCap").asInt(-1) != MAX_ROWS_PER_DATE
                || !JobDefinitionJson.mapper().valueToTree(SOURCE_FIELDS).equals(receipt.path("fields"))
                || receipt.path("requests").size() != MARKETS.size()
                || receipt.path("marketResponses").size() != MARKETS.size()
                || receipt.path("rawRowsByMarket").size() != MARKETS.size())
            throw new IllegalStateException("D016 raw source receipt envelope differs from the frozen five-field contract");

        int total = 0;
        for (int index = 0; index < MARKETS.size(); index++) {
            String market = MARKETS.get(index);
            JsonNode request = receipt.path("requests").get(index);
            JsonNode summary = receipt.path("marketResponses").get(index);
            JsonNode rows = receipt.path("rawRowsByMarket").get(index);
            if (!request.path("trade_date").asText().equals(date.format(DateTimeFormatter.BASIC_ISO_DATE))
                    || !request.path("market").asText().equals(market)
                    || !summary.path("market").asText().equals(market)
                    || !rows.isArray() || rows.size() >= SOURCE_ROW_CAP || summary.path("rows").asInt(-1) != rows.size())
                throw new IllegalStateException("D016 per-market receipt scope/count is invalid or at source cap");
            String previousCode = null;
            for (JsonNode row : rows) {
                if (!fieldNames(row).equals(new TreeSet<>(SOURCE_FIELDS)))
                    throw new IllegalStateException("D016 raw row does not contain exactly the five source fields");
                String code = requiredText(row.get("ts_code"), "ts_code");
                String basicDate = requiredText(row.get("trade_date"), "trade_date");
                String rowMarket = requiredText(row.get("market"), "market");
                if (!basicDate.equals(date.format(DateTimeFormatter.BASIC_ISO_DATE))
                        || !market.equals(rowMarket) || !validCodeForMarket(code, market))
                    throw new IllegalStateException("D016 source row escaped its date or market partition");
                if (previousCode != null && previousCode.compareTo(code) >= 0)
                    throw new IllegalStateException("D016 source receipt rows are not strictly canonical by ts_code");
                previousCode = code;
                Double fdShare = nullableFiniteDouble(row.get("fd_share"));
                String fundType = nullableNonblankText(row.get("fund_type"));
                long timestampMicros = epochMicros(date);
                var expected = new Expected(code, timestampMicros, fdShare, fundType, rowMarket, observedMicros);
                if (expectedRows.putIfAbsent(new Key(code, timestampMicros), expected) != null)
                    throw new IllegalStateException("Duplicate D016 source business key across receipts");
                total++;
            }
        }
        if (total != receipt.path("returnedRows").asInt(-1))
            throw new IllegalStateException("D016 raw receipt total differs from its market rows");
        return total;
    }

    private static List<LocalDate> parseTradeDates(String encoded, LocalDate from, LocalDate to) {
        if ("NONE".equals(encoded)) return List.of();
        if (encoded == null || encoded.isBlank()) throw new IllegalStateException("Frozen D016 trade_dates is missing");
        var dates = Arrays.stream(encoded.split(",", -1)).map(value -> {
            if (!value.matches("[0-9]{8}")) throw new IllegalStateException("D016 trade_dates must use BASIC ISO dates");
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        }).toList();
        if (dates.size() > MAX_WINDOW_DAYS || dates.stream().distinct().count() != dates.size()
                || !dates.equals(dates.stream().sorted().toList())
                || dates.stream().anyMatch(date -> date.isBefore(from) || date.isAfter(to)))
            throw new IllegalStateException("D016 trade_dates are duplicate, unordered or outside frozen window");
        return dates;
    }

    private static boolean validCodeForMarket(String code, String market) {
        if (code == null || !code.matches("[0-9]{6}\\.(?:SH|SZ|OF)")) return false;
        String suffix = code.substring(code.length() - 2);
        return (suffix.equals("OF") ? "O" : suffix).equals(market);
    }

    private static Actual actual(ResultSet rs) throws SQLException {
        Object shares = rs.getObject("fd_share");
        Object timestamp = rs.getObject("timestamp_micros");
        Object update = rs.getObject("update_time_micros");
        if (timestamp == null || !(timestamp instanceof Number timestampNumber)
                || update == null || !(update instanceof Number updateNumber)
                || shares != null && !(shares instanceof Number))
            throw new SQLException("Invalid D016 physical numeric field");
        Double fdShare = shares == null ? null : ((Number) shares).doubleValue();
        if (fdShare != null && !Double.isFinite(fdShare)) throw new SQLException("Non-finite D016 physical fd_share");
        return new Actual(rs.getString("ts_code"), timestampNumber.longValue(), fdShare,
                rs.getString("fund_type"), rs.getString("market"), updateNumber.longValue());
    }

    private static List<String> differingColumns(Expected expected, Actual actual) {
        var changed = new ArrayList<String>();
        if (!Objects.equals(expected.tsCode(), actual.tsCode())) changed.add("ts_code");
        if (expected.timestampMicros() != actual.timestampMicros()) changed.add("timestamp");
        if (!sameDouble(expected.fdShare(), actual.fdShare())) changed.add("fd_share");
        if (!Objects.equals(expected.fundType(), actual.fundType())) changed.add("fund_type");
        if (!Objects.equals(expected.market(), actual.market())) changed.add("market");
        if (expected.updateTimeMicros() != actual.updateTimeMicros()) changed.add("update_time");
        return List.copyOf(changed);
    }

    private static boolean sameDouble(Double left, Double right) {
        return left == null ? right == null : right != null
                && Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
    }

    private static Set<String> fieldNames(JsonNode row) {
        if (row == null || !row.isObject()) return Set.of();
        var fields = new TreeSet<String>(); row.fieldNames().forEachRemaining(fields::add); return fields;
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.textValue().isBlank())
            throw new IllegalStateException("Required D016 source text field is missing: " + field);
        return node.textValue();
    }

    private static String nullableNonblankText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || node.textValue().isBlank())
            throw new IllegalStateException("D016 fund_type must be provider text or JSON null");
        return node.textValue();
    }

    private static Double nullableFiniteDouble(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isNumber() && !node.isTextual()) throw new IllegalStateException("D016 fd_share is not numeric");
        double value;
        try { value = new BigDecimal(node.asText()).doubleValue(); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("D016 fd_share is malformed", invalid); }
        if (!Double.isFinite(value)) throw new IllegalStateException("D016 fd_share is not finite");
        return value;
    }

    private static LocalDate parseIsoDate(String value) {
        try { return LocalDate.parse(value); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid frozen D016 date", invalid); }
    }

    private static long epochMicros(LocalDate date) { return epochMicros(date.atStartOfDay().toInstant(ZoneOffset.UTC)); }

    private static long epochMicros(Instant instant) {
        if (instant.getNano() % 1_000 != 0) throw new IllegalArgumentException("D016 timestamp must have microsecond precision");
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void requireTargetIdentity(JdbcTemplate jdbc, String table, String expectedTargetId) {
        var identity = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (identity.size() != 1 || !(identity.getFirst().get("id") instanceof Number id)
                || !(identity.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D016 isolated table target identity differs from frozen run");
    }

    private static String keyLabel(Key key) { return key.tsCode() + "@" + key.timestampMicros(); }

    private static Map<String, Object> receiptMap(ReceiptSummary summary) {
        var result = new LinkedHashMap<String, Object>();
        result.put("date", summary.date()); result.put("path", summary.path());
        result.put("fingerprint", summary.fingerprint()); result.put("rows", summary.rows());
        return result;
    }

    private static List<String> samples(Set<String> keys) {
        return keys.stream().limit(MAX_REPORT_SAMPLES).toList();
    }
}
