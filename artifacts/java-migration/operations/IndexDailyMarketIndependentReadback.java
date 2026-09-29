import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Independent D019 source-receipt to physical-row acceptance checker; does not call the business mapper/source. */
public final class IndexDailyMarketIndependentReadback {
    private static final int MAX_RECEIPT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ACTUAL_ROWS = 5000;
    private static final List<String> METRICS = List.of("close", "open", "high", "low", "pre_close", "change", "pct_chg", "vol", "amount");
    private IndexDailyMarketIndependentReadback() {}

    public static Map<String,Object> verify(JdbcTemplate jdbc, Path ledgerPath, String table, String runId) throws Exception {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(ledgerPath);
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]*") || !table.startsWith("java_d019_index_daily_market_"))
            throw new IllegalArgumentException("Explicit D019 isolated table required");
        var ledger = SyncRunLedger.openReadOnly(ledgerPath); var run = ledger.getRun(runId); var runEntry = ledger.get(runId);
        if (!"data.index_daily_market".equals(run.jobId()) || run.jobVersion() != 1
                || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(runEntry.state()))
            throw new IllegalStateException("D019 run must be a completed verified ledger run");
        var json = JobDefinitionJson.mapper(); JsonNode frozen = json.readTree(run.frozenJson());
        JsonNode params = frozen.path("parameters");
        String targetId = run.targetId(), code = params.path("tsCode").asText(""), route = params.path("route").asText("");
        if (!code.matches("[0-9]{6}\\.(?:SH|SZ|CSI|SI)")) throw new IllegalStateException("Invalid D019 canonical ts_code");
        String expectedRoute = code.endsWith(".SI") ? "SW_DAILY" : "INDEX_DAILY";
        if (!targetId.equals(params.path("targetId").asText()) || !expectedRoute.equals(route)
                || !Set.of("INCREMENTAL", "BACKFILL", "RECONCILE").contains(frozen.path("mode").asText()))
            throw new IllegalStateException("D019 frozen target/code/route/mode identity differs");
        LocalDate from = LocalDate.parse(frozen.path("from").asText()), to = LocalDate.parse(frozen.path("to").asText());
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > 366) throw new IllegalStateException("D019 frozen window exceeds 366 days");
        Instant observedAt = Instant.parse(params.path("observedAt").asText());
        if (!observedAt.equals(observedAt.truncatedTo(ChronoUnit.MICROS))) throw new IllegalStateException("D019 frozen observation is not microsecond precise");
        var tableRows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (tableRows.size() != 1 || !(tableRows.getFirst().get("id") instanceof Number tableId)
                || !(tableRows.getFirst().get("directoryName") instanceof String directory)
                || !targetId.equals(StaticTargetIdentity.identify(jdbc, table, tableId.longValue(), directory)))
            throw new IllegalStateException("D019 isolated target identity differs from frozen run");

        var sliceEntries = new ArrayList<SyncRunLedger.Entry>(); String after = null;
        while (true) {
            var entries = ledger.entries(runId, after, 100);
            for (var entry : entries) if (entry.kind() == SyncRunLedger.Kind.SLICE) sliceEntries.add(entry);
            if (entries.size() < 100) break;
            after = entries.getLast().id();
        }
        if (sliceEntries.size() != 1) throw new IllegalStateException("D019 run must contain one source slice");
        var slice = sliceEntries.getFirst();
        if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(slice.state()))
            throw new IllegalStateException("D019 slice is not verified");
        JsonNode fetched = null;
        for (var event : ledger.events(slice.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
            if (fetched != null) throw new IllegalStateException("D019 slice has duplicate FETCHED evidence");
            fetched = json.readTree(event.payloadJson());
        }
        if (fetched == null || !code.equals(fetched.path("cursor").asText())) throw new IllegalStateException("D019 source cursor differs from frozen code");
        String receiptPath = fetched.path("responseEvidence").asText(""), fingerprint = fetched.path("sourceFingerprint").asText("");
        if (receiptPath.isBlank() || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalStateException("D019 receipt reference is invalid");
        Path receiptFile = Path.of(receiptPath);
        if (!Files.isRegularFile(receiptFile) || Files.size(receiptFile) > MAX_RECEIPT_BYTES) throw new IllegalStateException("D019 source receipt is absent or oversized");
        byte[] receiptBytes = Files.readAllBytes(receiptFile);
        if (!fingerprint.equals(sha256(receiptBytes))) throw new IllegalStateException("D019 source receipt SHA-256 differs from ledger");
        JsonNode receipt = json.readTree(receiptBytes);
        String endpoint = expectedRoute.equals("SW_DAILY") ? "sw_daily" : "index_daily";
        String basicFrom = from.format(DateTimeFormatter.BASIC_ISO_DATE), basicTo = to.format(DateTimeFormatter.BASIC_ISO_DATE);
        JsonNode sourceParams = receipt.path("parameters");
        if (!"tushare".equals(receipt.path("sourceKind").asText()) || !endpoint.equals(receipt.path("endpoint").asText())
                || !route.equals(receipt.path("route").asText()) || !code.equals(receipt.path("tsCode").asText())
                || !from.toString().equals(receipt.path("from").asText()) || !to.toString().equals(receipt.path("to").asText())
                || !observedAt.toString().equals(receipt.path("observedAt").asText())
                || !code.equals(sourceParams.path("ts_code").asText()) || !basicFrom.equals(sourceParams.path("start_date").asText())
                || !basicTo.equals(sourceParams.path("end_date").asText())
                || !json.valueToTree(expectedFields(expectedRoute)).equals(receipt.path("fields"))
                || !receipt.path("sourceComplete").asBoolean(false) || !receipt.path("rawRows").isArray()
                || receipt.path("returnedRows").asInt(-1) != receipt.path("rawRows").size()
                || receipt.path("sourceRowCap").asInt(-1) != 4000)
            throw new IllegalStateException("D019 receipt scope/completion evidence differs from frozen run");
        JsonNode rawRows = receipt.path("rawRows");
        if ((rawRows.size() == 0) != (slice.state() == SyncRunState.VERIFIED_EMPTY)
                || (rawRows.size() == 0) != (runEntry.state() == SyncRunState.VERIFIED_EMPTY))
            throw new IllegalStateException("D019 ledger verified-empty state differs from raw source rows");
        String pctField = expectedRoute.equals("SW_DAILY") ? "pct_change" : "pct_chg";
        long expectedObservedMicros = Math.addExact(Math.multiplyExact(observedAt.getEpochSecond(), 1_000_000L), observedAt.getNano() / 1000L);
        var expected = new TreeMap<String,Map<String,Object>>();
        for (JsonNode row : rawRows) {
            if (!row.path("ts_code").isTextual() || !code.equals(row.path("ts_code").asText())
                    || !row.path("trade_date").isTextual() || !row.path(pctField).isNumber() && !row.path(pctField).isNull())
                throw new IllegalStateException("D019 raw source row has invalid code/date/pct field");
            String basicDate = row.path("trade_date").asText();
            if (!basicDate.matches("[0-9]{8}")) throw new IllegalStateException("D019 raw trade_date must be BASIC_ISO_DATE");
            LocalDate date = LocalDate.parse(basicDate, DateTimeFormatter.BASIC_ISO_DATE);
            if (date.isBefore(from) || date.isAfter(to)) throw new IllegalStateException("D019 raw source row lies outside frozen interval");
            var values = new LinkedHashMap<String,Object>(); values.put("ts_code", code); values.put("tradeDate", date);
            for (String metric : METRICS) {
                if (expectedRoute.equals("SW_DAILY") && metric.equals("pre_close")) {
                    values.put(metric, null); // sw_daily doc327 does not return pre_close; do not derive it.
                    continue;
                }
                String wire = metric.equals("pct_chg") ? pctField : metric;
                JsonNode cell = row.get(wire);
                if (cell == null || (!cell.isNull() && (!cell.isNumber() || !Double.isFinite(cell.doubleValue()))))
                    throw new IllegalStateException("D019 raw metric invalid: " + wire);
                values.put(metric, cell == null || cell.isNull() ? null : cell.doubleValue());
            }
            values.put("updateTimeMicros", expectedObservedMicros);
            if (expected.putIfAbsent(code + "|" + date, values) != null) throw new IllegalStateException("Duplicate D019 full source business key");
        }

        long fromMicros = Math.multiplyExact(from.toEpochDay(), 86_400_000_000L);
        long toMicros = Math.multiplyExact(to.plusDays(1).toEpochDay(), 86_400_000_000L);
        String sql = "SELECT ts_code, cast(timestamp AS long) AS trade_date_micros, close, open, high, low, pre_close, change, pct_chg, vol, amount, "
                + "cast(update_time AS long) AS update_time_micros FROM \"" + table + "\" WHERE ts_code = ? "
                + "AND timestamp >= cast(? AS TIMESTAMP) AND timestamp < cast(? AS TIMESTAMP) ORDER BY timestamp LIMIT " + (MAX_ACTUAL_ROWS + 1);
        var rows = jdbc.query(sql, (rs, index) -> {
            Object rawDate = rs.getObject("trade_date_micros"), rawTime = rs.getObject("update_time_micros");
            if (!(rawDate instanceof Number date) || !(rawTime instanceof Number time)) throw new java.sql.SQLException("D019 physical timestamps required");
            long dateMicros = date.longValue(); long dayEpoch = Math.floorDiv(dateMicros, 86_400_000_000L);
            if (Math.floorMod(dateMicros, 86_400_000_000L) != 0) throw new java.sql.SQLException("D019 date carrier must be UTC midnight");
            var actual = new LinkedHashMap<String,Object>(); actual.put("ts_code", rs.getString("ts_code"));
            actual.put("tradeDate", LocalDate.ofEpochDay(dayEpoch));
            for (String metric : METRICS) {
                Object value = rs.getObject(metric);
                if (value != null && (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())))
                    throw new java.sql.SQLException("Invalid D019 physical metric: " + metric);
                actual.put(metric, value == null ? null : ((Number)value).doubleValue());
            }
            actual.put("updateTimeMicros", time.longValue()); return actual;
        }, code, fromMicros, toMicros);
        if (rows.size() > MAX_ACTUAL_ROWS) throw new IllegalStateException("D019 actual range exceeds independent readback bound");
        var actual = new TreeMap<String,Map<String,Object>>();
        for (var row : rows) {
            String key = row.get("ts_code") + "|" + row.get("tradeDate");
            if (actual.putIfAbsent(key, row) != null) throw new IllegalStateException("Duplicate physical D019 full key");
        }
        int mismatches = 0; var mismatchKeys = new ArrayList<String>();
        var allKeys = new TreeSet<String>(); allKeys.addAll(expected.keySet()); allKeys.addAll(actual.keySet());
        for (String key : allKeys) {
            Map<String,Object> source = expected.get(key), physical = actual.get(key);
            if (source == null || physical == null || !same(source, physical)) {
                mismatches++; if (mismatchKeys.size() < 50) mismatchKeys.add(key);
            }
        }
        var result = new LinkedHashMap<String,Object>(); result.put("runId", runId); result.put("targetId", targetId);
        result.put("table", table); result.put("tsCode", code); result.put("route", route); result.put("from", from);
        result.put("to", to); result.put("observedAt", observedAt.toString()); result.put("sourceRows", expected.size());
        result.put("actualRows", actual.size()); result.put("matchedRows", expected.size() == actual.size()
                ? expected.size() - mismatches : Math.max(0, Math.min(expected.size(), actual.size()) - mismatches));
        result.put("mismatchedKeys", mismatches); result.put("mismatchKeySample", mismatchKeys);
        result.put("status", mismatches == 0 ? "MATCHED" : "MISMATCHED");
        result.put("receipt", receiptPath); result.put("sourceFingerprint", fingerprint); return Map.copyOf(result);
    }

    private static boolean same(Map<String,Object> expected, Map<String,Object> actual) {
        if (!Objects.equals(expected.get("ts_code"), actual.get("ts_code"))
                || !Objects.equals(expected.get("tradeDate"), actual.get("tradeDate"))
                || !Objects.equals(expected.get("updateTimeMicros"), actual.get("updateTimeMicros"))) return false;
        for (String field : METRICS) {
            Object left = expected.get(field), right = actual.get(field);
            if (left == null || right == null) { if (left != right) return false; }
            else if (Double.compare(((Number)left).doubleValue(), ((Number)right).doubleValue()) != 0) return false;
        }
        return true;
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static List<String> expectedFields(String route) {
        var fields = new ArrayList<>(List.of("ts_code", "trade_date", "close", "open", "high", "low"));
        if (!route.equals("SW_DAILY")) fields.add("pre_close");
        fields.add("change");
        fields.add(route.equals("SW_DAILY") ? "pct_change" : "pct_chg"); fields.add("vol"); fields.add("amount");
        return List.copyOf(fields);
    }
}
