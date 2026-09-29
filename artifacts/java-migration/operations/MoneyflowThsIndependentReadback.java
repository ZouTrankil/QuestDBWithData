import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
import java.util.TreeMap;

/** Independent D025 raw-receipt to QuestDB oracle; it does not use the D025 mapper, source, or writer as truth. */
public final class MoneyflowThsIndependentReadback {
    private static final String JOB_ID = "data.moneyflow_ths";
    private static final int JOB_VERSION = 1;
    private static final int MAX_OPEN_DATES = 10;
    private static final int API_ROW_CAP = 6_000;
    private static final int MAX_RECEIPT_BYTES_PER_DATE = 16 * 1024 * 1024;
    private static final long MAX_ROWS = (long) MAX_OPEN_DATES * API_ROW_CAP;
    private static final long MAX_RECEIPT_BYTES = (long) MAX_OPEN_DATES * MAX_RECEIPT_BYTES_PER_DATE;
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> FIELDS = List.of("ts_code", "trade_date", "name", "pct_change", "latest",
            "net_amount", "net_d5_amount", "buy_lg_amount", "buy_lg_amount_rate", "buy_md_amount",
            "buy_md_amount_rate", "buy_sm_amount", "buy_sm_amount_rate");
    private static final List<String> VALUE_FIELDS = FIELDS.subList(2, FIELDS.size());
    private static final List<String> NUMERIC_FIELDS = VALUE_FIELDS.subList(1, VALUE_FIELDS.size());

    private record Key(LocalDate date, String code) {}
    private record Expected(Key key, List<Object> values) {}
    private record Actual(Key key, long tradeMicros, List<Object> values) {}
    private record Receipt(LocalDate date, int rows, String fingerprint, String evidence, long evidenceBytes,
                          List<Expected> expectedRows) {}

    private MoneyflowThsIndependentReadback() {}

    public static Map<String, Object> verify(JdbcTemplate jdbc, Path ledgerPath, String table, String runId)
            throws Exception {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(ledgerPath);
        if (table == null || !table.matches("java_d025_moneyflow_ths_[A-Za-z0-9_]{1,80}")
                || runId == null || !runId.matches("[A-Za-z0-9_.-]{1,128}") || runId.contains(".."))
            throw new IllegalArgumentException("Explicit isolated D025 table and bounded run ID required");

        Path ledger = ledgerPath.toAbsolutePath().normalize();
        ObjectMapper json = JobDefinitionJson.mapper();
        SyncRunLedger history = SyncRunLedger.openReadOnly(ledger);
        SyncRunLedger.Run run = history.getRun(runId);
        SyncRunLedger.Entry runEntry = history.get(runId);
        if (!JOB_ID.equals(run.jobId()) || run.jobVersion() != JOB_VERSION
                || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(runEntry.state()))
            throw new IllegalStateException("Terminal verified D025 run required");

        JsonNode frozen = json.readTree(run.frozenJson());
        JsonNode definition = frozen.path("definition");
        JsonNode params = frozen.path("parameters");
        String targetId = required(params, "targetId");
        if (!JOB_ID.equals(required(definition, "jobId")) || definition.path("version").asInt(-1) != JOB_VERSION
                || !targetId.equals(run.targetId()) || !targetId.matches("static-v2-[0-9a-f]{64}")
                || !LocalDate.parse(run.logicalDate()).equals(LocalDate.parse(required(frozen, "logicalDate"))))
            throw new IllegalStateException("Frozen D025 job or physical target identity differs from ledger run");
        verifyPhysicalTarget(jdbc, table, targetId);

        LocalDate from = LocalDate.parse(required(frozen, "from"));
        LocalDate to = LocalDate.parse(required(frozen, "to"));
        LocalDate logicalDate = LocalDate.parse(required(frozen, "logicalDate"));
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (from.isAfter(to) || span < 1 || span > 366 || to.isAfter(logicalDate))
            throw new IllegalStateException("Frozen D025 window is invalid or exceeds the 366-day job bound");

        List<LocalDate> openDates = readSseOpenDates(jdbc, from, to);
        if (openDates.isEmpty() || openDates.size() > MAX_OPEN_DATES)
            throw new IllegalStateException("Independent D025 readback is bounded to 1..10 SSE open dates per run");
        Path runEvidenceRoot = ledger.getParent().resolve("sync-evidence").resolve(runId).toRealPath();
        Path sourceRoot = containedDirectory(runEvidenceRoot, runEvidenceRoot.resolve("source"));
        List<Receipt> receipts = readReceipts(history, json, runId, openDates, sourceRoot);

        long sourceRows = receipts.stream().mapToLong(Receipt::rows).sum();
        long evidenceBytes = receipts.stream().mapToLong(Receipt::evidenceBytes).sum();
        if (sourceRows > MAX_ROWS || evidenceBytes > MAX_RECEIPT_BYTES)
            throw new IllegalStateException("Independent D025 source evidence exceeds its 10-session row/byte bound");

        Path completionPath = safeEvidence(runEvidenceRoot, runEvidenceRoot.resolve("complete-window.json"), 16 * 1024 * 1024L);
        JsonNode completion = json.readTree(completionPath.toFile());
        verifyCompletion(json, completion, frozen, targetId, from, to, logicalDate, openDates, receipts, sourceRows);
        verifyTerminalRunProof(history, json, runId, runEntry.state(), sourceRows, receipts, completionPath);

        Comparator<Key> ordering = Comparator.comparing(Key::date).thenComparing(Key::code);
        var expected = new TreeMap<Key, Expected>(ordering);
        long sourceDuplicateKeys = 0;
        for (Receipt receipt : receipts) for (Expected row : receipt.expectedRows()) {
            if (expected.putIfAbsent(row.key(), row) != null) sourceDuplicateKeys++;
        }

        String sql = "SELECT ts_code,cast(trade_date AS long) AS trade_micros,name,pct_change,latest,net_amount,"
                + "net_d5_amount,buy_lg_amount,buy_lg_amount_rate,buy_md_amount,buy_md_amount_rate,buy_sm_amount,"
                + "buy_sm_amount_rate FROM \"" + table + "\" WHERE trade_date>=cast(? AS TIMESTAMP) "
                + "AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT " + (MAX_ROWS + 1);
        List<Actual> actualRows = jdbc.query(sql, actualMapper(), micros(from), micros(to.plusDays(1)));
        if (actualRows.size() > MAX_ROWS) throw new IllegalStateException("D025 physical readback exceeds 10-session row bound");
        var actual = new HashMap<Key, Actual>();
        long duplicateTargetKeys = 0, extraKeys = 0;
        var samples = new ArrayList<String>();
        for (Actual row : actualRows) {
            if (actual.putIfAbsent(row.key(), row) != null) {
                duplicateTargetKeys++; sample(samples, "duplicate-target:" + row.key());
            }
            if (!expected.containsKey(row.key())) {
                extraKeys++; sample(samples, "unexpected-target:" + row.key());
            }
        }

        long missingKeys = 0, mismatchedRows = 0, matchedRows = 0, fieldsCompared = 0;
        for (var entry : expected.entrySet()) {
            Actual row = actual.get(entry.getKey());
            if (row == null) { missingKeys++; sample(samples, "missing:" + entry.getKey()); continue; }
            fieldsCompared += FIELDS.size();
            boolean mismatch = row.tradeMicros() != micros(entry.getKey().date());
            Expected source = entry.getValue();
            for (int index = 0; index < VALUE_FIELDS.size(); index++) {
                String field = VALUE_FIELDS.get(index);
                if (!same(source.values().get(index), row.values().get(index))) {
                    mismatch = true; sample(samples, "value:" + entry.getKey() + ":" + field);
                }
            }
            if (mismatch) mismatchedRows++; else matchedRows++;
        }
        boolean passed = sourceDuplicateKeys == 0 && duplicateTargetKeys == 0 && extraKeys == 0
                && missingKeys == 0 && mismatchedRows == 0 && actualRows.size() == expected.size();
        var result = new LinkedHashMap<String, Object>();
        result.put("status", passed ? "MATCHED" : "MISMATCH");
        result.put("dataset", "moneyflow_ths"); result.put("jobId", JOB_ID); result.put("runId", runId);
        result.put("table", table); result.put("targetId", targetId);
        result.put("fromInclusive", from.toString()); result.put("toInclusive", to.toString());
        result.put("sseOpenDates", openDates.stream().map(LocalDate::toString).toList());
        result.put("comparedColumns", FIELDS); result.put("fieldsCompared", fieldsCompared);
        result.put("sourceRows", sourceRows); result.put("uniqueSourceKeys", expected.size());
        result.put("actualRows", actualRows.size()); result.put("matchedRows", matchedRows);
        result.put("mismatchedRows", mismatchedRows); result.put("sourceDuplicateKeys", sourceDuplicateKeys);
        result.put("duplicateKeys", duplicateTargetKeys); result.put("missingKeys", missingKeys);
        result.put("extraKeys", extraKeys); result.put("receiptBytes", evidenceBytes);
        result.put("sourceFingerprints", receipts.stream().map(Receipt::fingerprint).toList());
        result.put("completionEvidence", completionPath.toString()); result.put("query", sql);
        result.put("mismatchSamples", samples); result.put("passed", passed);
        return Map.copyOf(result);
    }

    private static List<Receipt> readReceipts(SyncRunLedger history, ObjectMapper json, String runId,
            List<LocalDate> openDates, Path sourceRoot) throws Exception {
        var slices = history.entries(runId, null, 1000).stream()
                .filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).toList();
        if (slices.size() != openDates.size())
            throw new IllegalStateException("D025 ledger slice inventory differs from complete SSE open-date coverage");
        var byDate = new HashMap<LocalDate, SyncRunLedger.Entry>();
        for (var slice : slices) {
            if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(slice.state()))
                throw new IllegalStateException("D025 run contains a non-verified source slice");
            var fetchedEvents = history.events(slice.id(), -1, 100).stream()
                    .filter(event -> event.state() == SyncRunState.FETCHED).toList();
            if (fetchedEvents.size() != 1) throw new IllegalStateException("Each D025 slice needs one FETCHED source receipt");
            JsonNode fetched = json.readTree(fetchedEvents.getFirst().payloadJson());
            String cursor = required(fetched, "cursor");
            if (!cursor.matches("[0-9]{8}")) throw new IllegalStateException("D025 cursor must be YYYYMMDD");
            LocalDate date = LocalDate.parse(cursor, BASIC);
            if (!openDates.contains(date) || byDate.putIfAbsent(date, slice) != null)
                throw new IllegalStateException("D025 source slice is duplicate or outside SSE open dates");
        }

        var result = new ArrayList<Receipt>(openDates.size());
        long totalBytes = 0;
        for (LocalDate date : openDates) {
            SyncRunLedger.Entry slice = byDate.get(date);
            if (slice == null) throw new IllegalStateException("D025 run omitted an SSE open date");
            JsonNode fetched = json.readTree(history.events(slice.id(), -1, 100).stream()
                    .filter(event -> event.state() == SyncRunState.FETCHED).findFirst().orElseThrow().payloadJson());
            int rowCount = fetched.path("returnedRows").asInt(-1);
            String fingerprint = required(fetched, "sourceFingerprint");
            String evidence = required(fetched, "responseEvidence");
            if (rowCount < 0 || rowCount >= API_ROW_CAP
                    || !fingerprint.matches("[0-9a-f]{64}") || !date.format(BASIC).equals(cursor(fetched)))
                throw new IllegalStateException("D025 FETCHED event violates receipt date/cap/hash contract");
            Path receiptPath = safeEvidence(sourceRoot, Path.of(evidence), MAX_RECEIPT_BYTES_PER_DATE);
            byte[] bytes = Files.readAllBytes(receiptPath);
            totalBytes = Math.addExact(totalBytes, bytes.length);
            if (totalBytes > MAX_RECEIPT_BYTES || !sha(bytes).equals(fingerprint))
                throw new IllegalStateException("D025 raw response receipt hash or aggregate byte bound failed");
            JsonNode receipt = json.readTree(bytes);
            validateReceipt(receipt, date, rowCount);
            if ((rowCount == 0) != (slice.state() == SyncRunState.VERIFIED_EMPTY))
                throw new IllegalStateException("D025 slice state disagrees with original raw response size");
            var normalized = new ArrayList<Expected>(rowCount);
            for (JsonNode raw : receipt.path("rawRows")) normalized.add(parseRawRow(raw, date));
            result.add(new Receipt(date, rowCount, fingerprint, evidence, bytes.length,
                    java.util.Collections.unmodifiableList(new ArrayList<>(normalized))));
        }
        return List.copyOf(result);
    }

    private static void verifyCompletion(ObjectMapper json, JsonNode proof, JsonNode frozen, String targetId,
            LocalDate from, LocalDate to, LocalDate logicalDate, List<LocalDate> dates,
            List<Receipt> receipts, long sourceRows) {
        if (!proof.path("complete").asBoolean(false) || !JOB_ID.equals(proof.path("jobId").asText())
                || proof.path("jobVersion").asInt(-1) != JOB_VERSION
                || !"moneyflow_ths".equals(proof.path("datasetId").asText())
                || !frozen.path("mode").asText().equals(proof.path("mode").asText())
                || !targetId.equals(proof.path("targetId").asText())
                || !from.toString().equals(proof.path("fromInclusive").asText())
                || !to.toString().equals(proof.path("toInclusive").asText())
                || !logicalDate.toString().equals(proof.path("logicalDate").asText())
                || !json.valueToTree(dates).equals(proof.path("tradeDates"))
                || proof.path("completedDateSlices").asInt(-1) != dates.size()
                || proof.path("sourceRows").asLong(-1) != sourceRows
                || !json.valueToTree(receiptSummaries(receipts)).equals(proof.path("sourceReceipts")))
            throw new IllegalStateException("D025 deterministic completion evidence differs from frozen source receipts");
    }

    private static List<Map<String, Object>> receiptSummaries(List<Receipt> receipts) {
        var values = new ArrayList<Map<String, Object>>(receipts.size());
        for (Receipt receipt : receipts) values.add(Map.of("tradeDate", receipt.date(), "rows", receipt.rows(),
                "sourceFingerprint", receipt.fingerprint(), "responseEvidence", receipt.evidence()));
        return values;
    }

    private static void verifyTerminalRunProof(SyncRunLedger history, ObjectMapper json, String runId,
            SyncRunState state, long rows, List<Receipt> receipts, Path completionPath) throws Exception {
        var events = history.events(runId, -1, 100);
        if (events.isEmpty() || events.getLast().state() != state)
            throw new IllegalStateException("D025 terminal run event is missing or disagrees with ledger state");
        JsonNode proof = json.readTree(events.getLast().payloadJson());
        if (state == SyncRunState.VERIFIED_EMPTY) {
            if (rows != 0 || !proof.path("sourceComplete").asBoolean(false)
                    || proof.path("returnedRows").asInt(-1) != 0 || proof.path("submittedRows").asInt(-1) != 0
                    || !completionPath.toString().equals(proof.path("responseEvidence").asText()))
                throw new IllegalStateException("D025 VERIFIED_EMPTY lacks a zero-row complete-source proof");
            return;
        }
        JsonNode verification = proof.path("verification");
        String expectedFingerprint = combinedFingerprint(receipts);
        if (!verification.path("passed").asBoolean(false)
                || verification.path("expectedRows").asLong(-1) != rows
                || verification.path("actualRows").asLong(-1) != rows
                || verification.path("matchedRows").asLong(-1) != rows
                || verification.path("mismatchedRows").asLong(-1) != 0
                || verification.path("duplicateKeys").asLong(-1) != 0
                || verification.path("missingKeys").asLong(-1) != 0
                || !expectedFingerprint.equals(verification.path("sourceFingerprint").asText()))
            throw new IllegalStateException("D025 terminal ledger proof differs from verified slice receipt inventory");
    }

    private static List<LocalDate> readSseOpenDates(JdbcTemplate jdbc, LocalDate from, LocalDate to) {
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span < 1 || span > 366) throw new IllegalStateException("D025 calendar verification exceeds 366 days");
        String sql = "SELECT cast(cal_date AS long) AS date_micros,is_open FROM \"exchange_calendar\" "
                + "WHERE exchange=? AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) "
                + "ORDER BY cal_date LIMIT " + (span + 1);
        List<Map.Entry<LocalDate, Boolean>> rows = jdbc.query(sql, (rs, n) -> {
            Object micros = rs.getObject("date_micros"), open = rs.getObject("is_open");
            if (!(micros instanceof Number time) || !(open instanceof Number flag)
                    || (flag.intValue() != 0 && flag.intValue() != 1))
                throw new SQLException("Invalid physical SSE calendar row");
            return Map.entry(dateFromMicros(time.longValue()), flag.intValue() == 1);
        }, "SSE", micros(from), micros(to.plusDays(1)));
        if (rows.size() != span) throw new IllegalStateException("Physical exchange_calendar does not cover frozen D025 range");
        var all = new HashSet<LocalDate>(); var openDates = new ArrayList<LocalDate>();
        for (var row : rows) {
            if (!all.add(row.getKey()) || row.getKey().isBefore(from) || row.getKey().isAfter(to))
                throw new IllegalStateException("Physical SSE calendar has duplicate/out-of-range days");
            if (row.getValue()) openDates.add(row.getKey());
        }
        return List.copyOf(openDates);
    }

    private static void validateReceipt(JsonNode receipt, LocalDate date, int rowCount) {
        ObjectMapper json = JobDefinitionJson.mapper();
        if (!"tushare".equals(receipt.path("sourceKind").asText())
                || !"moneyflow_ths".equals(receipt.path("endpoint").asText())
                || receipt.path("sourceContractVersion").asInt(-1) != 1
                || !receipt.path("sourceComplete").asBoolean(false)
                || !date.toString().equals(receipt.path("tradeDate").asText())
                || receipt.path("apiMaximumRows").asInt(-1) != API_ROW_CAP
                || receipt.path("returnedRows").asInt(-1) != rowCount
                || rowCount >= API_ROW_CAP
                || !json.valueToTree(FIELDS).equals(receipt.path("fields"))
                || !date.format(BASIC).equals(receipt.path("parameters").path("trade_date").asText())
                || !receipt.path("rawRows").isArray() || receipt.path("rawRows").size() != rowCount)
            throw new IllegalStateException("D025 original Tushare receipt differs from endpoint/date/field/cap contract");
    }

    private static Expected parseRawRow(JsonNode row, LocalDate requestedDate) {
        var names = new HashSet<String>(); row.fieldNames().forEachRemaining(names::add);
        if (!names.equals(new HashSet<>(FIELDS))) throw new IllegalStateException("D025 raw row has missing/extra source fields");
        String code = required(row, "ts_code");
        if (!code.matches("[0-9]{6}\\.(?:SH|SZ|BJ)") || !requestedDate.format(BASIC).equals(required(row, "trade_date")))
            throw new IllegalStateException("D025 original source row has invalid complete key or wrong trade date");
        var values = new ArrayList<Object>(VALUE_FIELDS.size());
        JsonNode name = row.get("name");
        if (name != null && !name.isNull() && !name.isTextual())
            throw new IllegalStateException("D025 source name must be a string or null");
        values.add(name == null || name.isNull() ? null : name.textValue());
        for (String field : NUMERIC_FIELDS) values.add(decimalAsDouble(row.get(field), field));
        return new Expected(new Key(requestedDate, code), java.util.Collections.unmodifiableList(values));
    }

    private static RowMapper<Actual> actualMapper() {
        return (rs, n) -> {
            Object rawMicros = rs.getObject("trade_micros");
            if (!(rawMicros instanceof Number time)) throw new SQLException("D025 actual business timestamp missing");
            LocalDate date;
            try { date = dateFromMicros(time.longValue()); }
            catch (RuntimeException invalid) { throw new SQLException("Invalid D025 physical date", invalid); }
            String code = rs.getString("ts_code");
            var values = new ArrayList<Object>(VALUE_FIELDS.size()); values.add(rs.getString("name"));
            for (String field : NUMERIC_FIELDS) {
                Object value = rs.getObject(field);
                if (value == null) values.add(null);
                else if (value instanceof Number number && Double.isFinite(number.doubleValue())) values.add(number.doubleValue());
                else throw new SQLException("Invalid D025 QuestDB numeric value in " + field);
            }
            return new Actual(new Key(date, code), time.longValue(), java.util.Collections.unmodifiableList(values));
        };
    }

    private static Double decimalAsDouble(JsonNode value, String field) {
        if (value == null || value.isNull() || value.isTextual() && value.textValue().isBlank()) return null;
        if (!(value.isNumber() || value.isTextual())) throw new IllegalStateException("Invalid D025 raw numeric field " + field);
        try {
            double number = new BigDecimal(value.asText()).doubleValue();
            if (!Double.isFinite(number)) throw new NumberFormatException("non-finite");
            return number;
        } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid D025 raw numeric field " + field, invalid); }
    }

    private static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        return a.equals(b);
    }

    private static void verifyPhysicalTarget(JdbcTemplate jdbc, String table, String targetId) {
        var tables = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (tables.size() != 1 || !(tables.getFirst().get("id") instanceof Number id)
                || !(tables.getFirst().get("directoryName") instanceof String directory)
                || !targetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D025 isolated physical target generation differs from frozen targetId");
    }

    private static Path containedDirectory(Path root, Path child) throws Exception {
        Path realRoot = root.toRealPath(); Path realChild = child.toRealPath();
        if (!realChild.startsWith(realRoot) || !Files.isDirectory(realChild))
            throw new IllegalStateException("D025 source evidence directory escapes the run evidence root");
        return realChild;
    }

    private static Path safeEvidence(Path root, Path evidence, long maximumBytes) throws Exception {
        if (!evidence.isAbsolute()) throw new IllegalStateException("D025 evidence reference must be absolute");
        Path realRoot = root.toRealPath(); Path normalized = evidence.toAbsolutePath().normalize();
        if (!normalized.startsWith(realRoot) || !Files.isRegularFile(normalized)
                || !normalized.toRealPath().startsWith(realRoot) || Files.size(normalized) > maximumBytes)
            throw new IllegalStateException("D025 evidence absent, outside its root, or over its byte cap");
        return normalized.toRealPath();
    }

    private static String combinedFingerprint(List<Receipt> receipts) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Receipt receipt : receipts) {
            digest.update(receipt.fingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String cursor(JsonNode fetched) { return fetched.path("cursor").asText(""); }
    private static String required(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalStateException("D025 evidence missing " + field);
        return value.textValue();
    }
    private static long micros(LocalDate date) { return date.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1_000_000L; }
    private static LocalDate dateFromMicros(long value) {
        long seconds = Math.floorDiv(value, 1_000_000); long remainder = Math.floorMod(value, 1_000_000);
        Instant instant = Instant.ofEpochSecond(seconds, remainder * 1000);
        if (!instant.atOffset(ZoneOffset.UTC).toLocalTime().equals(LocalTime.MIDNIGHT))
            throw new IllegalArgumentException("D025 timestamp is not a UTC-midnight business-date carrier");
        return instant.atOffset(ZoneOffset.UTC).toLocalDate();
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void sample(List<String> values, String value) { if (values.size() < 30) values.add(value); }
}
