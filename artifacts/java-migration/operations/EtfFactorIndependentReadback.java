import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Operational D017 receipt-to-QuestDB verifier. Expected values are decoded from immutable raw
 * receipts here; this class deliberately does not call EtfFactorSource, EtfFactorMapper or a write port.
 */
public final class EtfFactorIndependentReadback {
    private static final String JOB_ID = "data.etf_factor";
    private static final int JOB_VERSION = 1;
    private static final int ROW_CAP = 8_000;
    private static final int MAX_WINDOW_DAYS = 366;
    private static final long MAX_TOTAL_EVIDENCE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    // Duplicated intentionally: this verifier is an independent oracle for all 89 source/physical columns.
    private static final List<String> DOUBLE_FIELDS = List.of(
            "open", "high", "low", "close", "pre_close", "change", "pct_change", "vol", "amount",
            "asi_bfq", "asit_bfq", "bbi_bfq", "bias1_bfq", "bias2_bfq", "bias3_bfq",
            "brar_ar_bfq", "brar_br_bfq", "cr_bfq", "dfma_dif_bfq", "dfma_difma_bfq",
            "dpo_bfq", "madpo_bfq", "ema_bfq_5", "ema_bfq_10", "ema_bfq_20", "ema_bfq_30",
            "ema_bfq_60", "ema_bfq_90", "ema_bfq_250", "emv_bfq", "maemv_bfq", "expma_12_bfq",
            "expma_50_bfq", "ktn_down_bfq", "ktn_mid_bfq", "ktn_upper_bfq", "ma_bfq_5", "ma_bfq_10",
            "ma_bfq_20", "ma_bfq_30", "ma_bfq_60", "ma_bfq_90", "ma_bfq_250", "macd_bfq",
            "macd_dif_bfq", "macd_dea_bfq", "kdj_bfq", "kdj_k_bfq", "kdj_d_bfq", "rsi_bfq_6",
            "rsi_bfq_12", "rsi_bfq_24", "boll_upper_bfq", "boll_mid_bfq", "boll_lower_bfq", "atr_bfq",
            "cci_bfq", "dmi_pdi_bfq", "dmi_mdi_bfq", "dmi_adx_bfq", "dmi_adxr_bfq", "mass_bfq",
            "ma_mass_bfq", "mfi_bfq", "mtm_bfq", "mtmma_bfq", "obv_bfq", "psy_bfq", "psyma_bfq",
            "roc_bfq", "maroc_bfq", "taq_down_bfq", "taq_mid_bfq", "taq_up_bfq", "trix_bfq",
            "trma_bfq", "vr_bfq", "wr_bfq", "wr1_bfq", "xsii_td1_bfq", "xsii_td2_bfq",
            "xsii_td3_bfq", "xsii_td4_bfq", "updays", "downdays", "lowdays", "topdays");
    private static final List<String> FIELDS;
    private static final Set<String> FIELD_SET;
    private static final String READ_SQL;

    static {
        var fields = new ArrayList<String>(List.of("ts_code", "trade_date"));
        fields.addAll(DOUBLE_FIELDS);
        FIELDS = List.copyOf(fields);
        FIELD_SET = Set.copyOf(FIELDS);
        if (FIELDS.size() != 89 || FIELD_SET.size() != 89)
            throw new ExceptionInInitializerError("D017 independent verifier must cover exactly 89 unique fields");
        var selected = new ArrayList<String>();
        selected.add("ts_code");
        selected.add("cast(trade_date AS long) AS trade_date_micros");
        selected.addAll(DOUBLE_FIELDS);
        READ_SQL = "SELECT " + String.join(",", selected) + " FROM \"%s\" "
                + "WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT " + (ROW_CAP + 1);
    }

    private record Key(String code, LocalDate date) {}
    private record Expected(Key key, Map<String, Double> values) {}
    private record Actual(Key key, long dateMicros, Map<String, Double> values) {}
    private EtfFactorIndependentReadback() {}

    public static Map<String, Object> verify(JdbcTemplate jdbc, Path ledger, String table, String runId)
            throws Exception {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(ledger); Objects.requireNonNull(table);
        Objects.requireNonNull(runId);
        if (!table.matches("java_d017_etf_factor_[A-Za-z0-9_]{1,80}")
                || !runId.matches("[A-Za-z0-9_.-]{1,128}") || runId.contains(".."))
            throw new IllegalArgumentException("An isolated D017 target and bounded run ID are required");

        Path ledgerPath = ledger.toAbsolutePath().normalize();
        var json = JobDefinitionJson.mapper();
        var history = SyncRunLedger.openReadOnly(ledgerPath);
        var run = history.getRun(runId);
        var runEntry = history.get(runId);
        if (!JOB_ID.equals(run.jobId()) || run.jobVersion() != JOB_VERSION
                || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(runEntry.state()))
            throw new IllegalStateException("A terminal verified D017 run is required");
        JsonNode frozen = json.readTree(run.frozenJson());
        JsonNode parameters = frozen.path("parameters");
        if (!JOB_ID.equals(frozen.path("definition").path("jobId").asText())
                || frozen.path("definition").path("version").asInt(-1) != JOB_VERSION)
            throw new IllegalStateException("Frozen D017 definition differs from the registered source contract");
        String targetId = requiredText(parameters, "targetId");
        if (!targetId.equals(run.targetId()) || !targetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalStateException("Frozen D017 physical identity differs from the ledger target");
        LocalDate from = LocalDate.parse(requiredText(frozen, "from"));
        LocalDate to = LocalDate.parse(requiredText(frozen, "to"));
        LocalDate logicalDate = LocalDate.parse(requiredText(frozen, "logicalDate"));
        long span = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (from.isAfter(to) || span < 1 || span > MAX_WINDOW_DAYS || to.isAfter(logicalDate))
            throw new IllegalStateException("Frozen D017 range is invalid or exceeds the bounded window");
        List<LocalDate> dates = decodeDates(requiredText(parameters, "trade_dates"), from, to);
        assertTarget(jdbc, table, targetId);
        String query = READ_SQL.formatted(table);

        // Recovery exercises can reuse an immutable receipt from an earlier run. Keep the allowed
        // evidence namespace fixed at this ledger's sibling tree, never at an arbitrary filesystem path.
        Path evidenceRoot = ledgerPath.getParent().resolve("sync-evidence").toRealPath();
        List<SyncRunLedger.Entry> slices = history.entries(runId, null, 1000).stream()
                .filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).toList();
        if (slices.size() != dates.size())
            throw new IllegalStateException("D017 ledger slice count differs from frozen trade-date list");
        var sliceByDate = new HashMap<LocalDate, SyncRunLedger.Entry>();
        for (var slice : slices) {
            if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(slice.state()))
                throw new IllegalStateException("D017 contains an unverified daily slice");
            var fetched = history.events(slice.id(), -1, 100).stream()
                    .filter(event -> event.state() == SyncRunState.FETCHED).toList();
            if (fetched.size() != 1) throw new IllegalStateException("Each D017 slice must have one immutable FETCHED event");
            JsonNode event = json.readTree(fetched.getFirst().payloadJson());
            String cursor = requiredText(event, "cursor");
            if (!cursor.matches("[0-9]{8}")) throw new IllegalStateException("D017 daily cursor is not BASIC_ISO_DATE");
            LocalDate date = LocalDate.parse(cursor, BASIC);
            if (!dates.contains(date) || sliceByDate.putIfAbsent(date, slice) != null)
                throw new IllegalStateException("D017 ledger has a duplicate or out-of-scope daily slice");
        }

        long sourceRows = 0, actualRows = 0, matchedRows = 0, mismatchedRows = 0;
        long sourceDuplicateKeys = 0, duplicateKeys = 0, missingKeys = 0, extraKeys = 0, evidenceBytes = 0;
        var receipts = new ArrayList<Map<String, Object>>();
        var emptyDates = new ArrayList<String>();
        var mismatchSamples = new ArrayList<String>();
        for (LocalDate date : dates) {
            var slice = sliceByDate.get(date);
            var fetchEvent = history.events(slice.id(), -1, 100).stream()
                    .filter(event -> event.state() == SyncRunState.FETCHED).findFirst().orElseThrow();
            JsonNode fetch = json.readTree(fetchEvent.payloadJson());
            int declaredRows = fetch.path("returnedRows").asInt(-1);
            String fingerprint = requiredText(fetch, "sourceFingerprint");
            if (declaredRows < 0 || declaredRows >= ROW_CAP || !fingerprint.matches("[0-9a-f]{64}")
                    || !date.format(BASIC).equals(requiredText(fetch, "cursor")))
                throw new IllegalStateException("D017 FETCHED event violates the per-date cap or cursor contract");
            Path receiptPath = evidencePath(evidenceRoot, requiredText(fetch, "responseEvidence"));
            byte[] bytes = Files.readAllBytes(receiptPath);
            evidenceBytes = Math.addExact(evidenceBytes, bytes.length);
            if (evidenceBytes > MAX_TOTAL_EVIDENCE_BYTES || !sha256(bytes).equals(fingerprint))
                throw new IllegalStateException("D017 raw receipt SHA-256 or total evidence bound failed");
            JsonNode receipt = json.readTree(bytes);
            validateReceipt(receipt, date, declaredRows, fingerprint, receiptPath);
            boolean receiptEmpty = declaredRows == 0;
            if (receiptEmpty != (slice.state() == SyncRunState.VERIFIED_EMPTY))
                throw new IllegalStateException("D017 slice terminal state disagrees with receipt row count");
            var expected = new TreeMap<Key, Expected>(Comparator.comparing(Key::date).thenComparing(Key::code));
            int duplicatesBeforeDate = Math.toIntExact(sourceDuplicateKeys);
            String basic = date.format(BASIC);
            for (JsonNode raw : receipt.path("rawRows")) {
                Expected wanted = normalize(raw, date, basic);
                if (expected.putIfAbsent(wanted.key(), wanted) != null) {
                    sourceDuplicateKeys++;
                    addSample(mismatchSamples, "duplicate-source:" + wanted.key());
                }
            }
            if (expected.size() + (sourceDuplicateKeys - duplicatesBeforeDate) != declaredRows)
                throw new IllegalStateException("D017 source key accounting is inconsistent");
            if (declaredRows == 0) emptyDates.add(date.toString());
            sourceRows = Math.addExact(sourceRows, declaredRows);

            List<Actual> actual = jdbc.query(query, actualMapper(), date + "T00:00:00.000000Z");
            if (actual.size() > ROW_CAP) throw new IllegalStateException("D017 target readback exceeds the daily row bound");
            actualRows = Math.addExact(actualRows, actual.size());
            var actualByKey = new HashMap<Key, Actual>();
            for (Actual row : actual) {
                if (actualByKey.putIfAbsent(row.key(), row) != null) {
                    duplicateKeys++;
                    addSample(mismatchSamples, "duplicate-target:" + row.key());
                }
                if (!expected.containsKey(row.key())) {
                    extraKeys++;
                    addSample(mismatchSamples, "unexpected-target:" + row.key());
                }
            }
            for (var entry : expected.entrySet()) {
                Actual actualRow = actualByKey.get(entry.getKey());
                if (actualRow == null) { missingKeys++; addSample(mismatchSamples, "missing-target:" + entry.getKey()); continue; }
                boolean rowMismatch = actualRow.dateMicros() != epochMicros(date);
                for (String field : DOUBLE_FIELDS)
                    if (!sameDouble(entry.getValue().values().get(field), actualRow.values().get(field))) {
                        mismatchedRows++;
                        rowMismatch = true;
                        addSample(mismatchSamples, "value:" + entry.getKey() + ":" + field);
                    }
                if (!rowMismatch) matchedRows++;
            }
            receipts.add(Map.of("tradeDate", date.toString(), "rows", declaredRows,
                    "path", receiptPath.toString(), "sha256", fingerprint));
        }

        String manifestEvidence = "";
        String rangeSql = "SELECT count() AS row_count FROM \"" + table
                + "\" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP)";
        Number rangeCountValue = jdbc.queryForObject(rangeSql, Number.class, from + "T00:00:00.000000Z",
                to.plusDays(1) + "T00:00:00.000000Z");
        if (rangeCountValue == null || rangeCountValue.longValue() < 0)
            throw new IllegalStateException("D017 range-wide target row count is unavailable");
        long rangeRows = rangeCountValue.longValue();
        long outOfPlanDateRows = rangeRows - actualRows;
        if (outOfPlanDateRows < 0) throw new IllegalStateException("D017 target changed during range-wide readback");
        if (dates.isEmpty()) {
            JsonNode terminal = json.readTree(runEntry.payloadJson());
            manifestEvidence = requiredText(terminal, "responseEvidence");
            Path manifestPath = evidencePath(evidenceRoot, manifestEvidence);
            JsonNode manifest = json.readTree(Files.readAllBytes(manifestPath));
            if (!"fund_factor_pro".equals(manifest.path("endpoint").asText())
                    || !manifest.path("complete").asBoolean(false)
                    || manifest.path("slices").asInt(-1) != 0 || manifest.path("sourceRows").asInt(-1) != 0
                    || !manifest.path("tradeDates").isArray() || manifest.path("tradeDates").size() != 0
                    || !from.toString().equals(manifest.path("from").asText())
                    || !to.toString().equals(manifest.path("to").asText())
                    || !manifest.path("sourceEvidence").isArray() || manifest.path("sourceEvidence").size() != 0)
                throw new IllegalStateException("D017 zero-trading-date completion receipt is incomplete or inconsistent");
            if (rangeRows != 0)
                throw new IllegalStateException("D017 target contains rows in a frozen range with no trade dates");
        }

        if ((runEntry.state() == SyncRunState.VERIFIED_EMPTY) != (sourceRows == 0))
            throw new IllegalStateException("D017 run terminal state disagrees with raw receipt totals");
        String status = sourceDuplicateKeys == 0 && duplicateKeys == 0 && missingKeys == 0 && extraKeys == 0
                && outOfPlanDateRows == 0
                && mismatchedRows == 0 && sourceRows == actualRows && matchedRows == sourceRows
                ? "MATCHED" : "MISMATCH";
        var report = new LinkedHashMap<String, Object>();
        report.put("task", "D017"); report.put("runId", runId); report.put("status", status);
        report.put("table", table); report.put("targetId", targetId);
        report.put("mode", requiredText(frozen, "mode")); report.put("from", from.toString());
        report.put("to", to.toString()); report.put("logicalDate", logicalDate.toString());
        report.put("tradeDates", dates.stream().map(LocalDate::toString).toList());
        report.put("sourceRows", sourceRows); report.put("actualRows", actualRows); report.put("matchedRows", matchedRows);
        report.put("mismatchedValues", mismatchedRows); report.put("sourceDuplicateKeys", sourceDuplicateKeys);
        report.put("duplicateKeys", duplicateKeys); report.put("missingKeys", missingKeys); report.put("extraKeys", extraKeys);
        report.put("rangeRows", rangeRows); report.put("outOfPlanDateRows", outOfPlanDateRows);
        report.put("emptyTradeDates", emptyDates); report.put("sourceEvidenceBytes", evidenceBytes);
        report.put("sourceReceipts", receipts); report.put("comparedColumns", FIELDS);
        report.put("readbackQuery", query); report.put("rangeCountQuery", rangeSql);
        report.put("mismatchSamples", mismatchSamples);
        if (!manifestEvidence.isEmpty()) report.put("completionEvidence", manifestEvidence);
        report.put("passed", "MATCHED".equals(status));
        return Map.copyOf(report);
    }

    private static Expected normalize(JsonNode raw, LocalDate date, String basic) {
        var names = new TreeSet<String>(); raw.fieldNames().forEachRemaining(names::add);
        if (!names.equals(new TreeSet<>(FIELDS))) throw new IllegalStateException("D017 raw row does not contain the exact 89-field contract");
        String code = requiredText(raw, "ts_code");
        if (!code.matches("[0-9]{6}\\.(?:SH|SZ|BJ|OF)") || !basic.equals(requiredText(raw, "trade_date")))
            throw new IllegalStateException("D017 source row has an invalid fund code or escaped its requested trade date");
        var values = new LinkedHashMap<String, Double>();
        for (String field : DOUBLE_FIELDS) values.put(field, number(raw.get(field), field));
        return new Expected(new Key(code, date), Collections.unmodifiableMap(values));
    }

    private static void validateReceipt(JsonNode receipt, LocalDate date, int rows, String fingerprint, Path path) {
        String basic = date.format(BASIC);
        if (!"tushare".equals(receipt.path("sourceKind").asText())
                || !"fund_factor_pro".equals(receipt.path("endpoint").asText())
                || !receipt.path("sourceComplete").asBoolean(false)
                || !date.toString().equals(receipt.path("tradeDate").asText())
                || receipt.path("sourceRowCap").asInt(-1) != ROW_CAP
                || receipt.path("returnedRows").asInt(-1) != rows
                || !receipt.path("rawRows").isArray() || receipt.path("rawRows").size() != rows
                || rows >= ROW_CAP || !receipt.path("fields").equals(JobDefinitionJson.mapper().valueToTree(FIELDS))
                || !basic.equals(receipt.path("parameters").path("trade_date").asText())
                || !path.getFileName().toString().equals("fund-factor-pro-" + basic + "-" + fingerprint + ".json"))
            throw new IllegalStateException("D017 raw receipt endpoint, field set, request, cap or deterministic name differs");
    }

    private static List<LocalDate> decodeDates(String encoded, LocalDate from, LocalDate to) {
        if ("NONE".equals(encoded)) return List.of();
        if (encoded.isBlank()) throw new IllegalStateException("D017 frozen trade-date list is blank");
        var dates = new ArrayList<LocalDate>();
        for (String value : encoded.split(",", -1)) {
            if (!value.matches("[0-9]{8}")) throw new IllegalStateException("D017 frozen date is not BASIC_ISO_DATE");
            LocalDate date = LocalDate.parse(value, BASIC);
            if (date.isBefore(from) || date.isAfter(to) || (!dates.isEmpty() && !dates.getLast().isBefore(date)))
                throw new IllegalStateException("D017 frozen trade dates are duplicated, unordered or outside the request");
            dates.add(date);
        }
        if (dates.size() > MAX_WINDOW_DAYS) throw new IllegalStateException("D017 trade-date count exceeds bounded plan");
        return List.copyOf(dates);
    }

    private static RowMapper<Actual> actualMapper() {
        return (ResultSet row, int index) -> {
            Object dateValue = row.getObject("trade_date_micros");
            if (!(dateValue instanceof Number micros)) throw new SQLException("D017 QuestDB timestamp must be numeric");
            String code = row.getString("ts_code");
            if (code == null) throw new SQLException("D017 QuestDB ts_code must be nonnull");
            var values = new LinkedHashMap<String, Double>();
            for (String field : DOUBLE_FIELDS) values.put(field, sqlNumber(row.getObject(field), field));
            LocalDate date = java.time.Instant.ofEpochSecond(Math.floorDiv(micros.longValue(), 1_000_000L),
                    Math.multiplyExact(Math.floorMod(micros.longValue(), 1_000_000L), 1_000L))
                    .atZone(ZoneOffset.UTC).toLocalDate();
            return new Actual(new Key(code, date), micros.longValue(), Collections.unmodifiableMap(values));
        };
    }

    private static Double sqlNumber(Object raw, String field) throws SQLException {
        if (raw == null) return null;
        if (!(raw instanceof Number number)) throw new SQLException("D017 physical " + field + " must be numeric or NULL");
        double value = number.doubleValue();
        if (!Double.isFinite(value)) throw new SQLException("D017 physical " + field + " must be finite");
        return value;
    }

    private static Double number(JsonNode raw, String field) {
        if (raw == null || raw.isNull()) return null;
        if (!raw.isNumber() && !raw.isTextual()) throw new IllegalStateException("D017 " + field + " must be numeric or NULL");
        try {
            String value = raw.asText();
            if (value.isBlank()) throw new NumberFormatException("blank");
            double parsed = new BigDecimal(value).doubleValue();
            if (!Double.isFinite(parsed)) throw new NumberFormatException("non-finite");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("D017 " + field + " is not a finite decimal", invalid);
        }
    }

    private static boolean sameDouble(Double left, Double right) {
        return left == null ? right == null : right != null
                && Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
    }

    private static Path evidencePath(Path root, String value) throws Exception {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path))
            throw new IllegalStateException("D017 raw evidence path escapes its run directory");
        Path realRoot = root.toRealPath(), real = path.toRealPath();
        if (!real.startsWith(realRoot) || Files.size(real) > EtfFactorEvidenceBound.MAX_BYTES)
            throw new IllegalStateException("D017 raw evidence is oversized or escapes its run directory");
        return real;
    }

    private static void assertTarget(JdbcTemplate jdbc, String table, String targetId) throws Exception {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory)
                || !targetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D017 live table generation differs from frozen physical identity");
    }

    private static long epochMicros(LocalDate date) {
        var instant = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("Required D017 text missing: " + name);
        return value.asText();
    }
    private static void addSample(List<String> values, String value) { if (values.size() < 40) values.add(value); }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Small local bound to avoid depending on the source implementation under verification. */
    private static final class EtfFactorEvidenceBound { private static final long MAX_BYTES = 32L * 1024 * 1024; }
}
