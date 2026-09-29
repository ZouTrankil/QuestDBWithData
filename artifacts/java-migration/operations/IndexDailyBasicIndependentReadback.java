package com.zoutrankil.questdbwithdata.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Independent D020 raw-receipt → QuestDB full-row comparison; does not call source, mapper, or write-port code. */
public final class IndexDailyBasicIndependentReadback {
    private static final List<String> FIELDS = List.of("ts_code", "trade_date", "total_mv", "float_mv",
            "total_share", "float_share", "free_share", "turnover_rate", "turnover_rate_f", "pe", "pe_ttm", "pb");
    private static final int MAX_ROWS = 20_000;
    private IndexDailyBasicIndependentReadback() {}

    public static Map<String,Object> verify(JdbcTemplate jdbc, Path ledgerPath, String table, String runId) throws Exception {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(ledgerPath);
        if (table == null || !table.matches("java_d020_index_daily_basic_[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("D020 isolated target table required");
        var ledger = SyncRunLedger.openReadOnly(ledgerPath); var run = ledger.getRun(runId);
        var frozen = JobDefinitionJson.mapper().readTree(run.frozenJson()); var params = frozen.path("parameters");
        String targetId = run.targetId(), code = params.path("tsCode").asText();
        LocalDate from = LocalDate.parse(frozen.path("from").asText()), to = LocalDate.parse(frozen.path("to").asText());
        if (!"data.index_daily_basic".equals(run.jobId()) || run.jobVersion() != 1
                || !targetId.equals(params.path("targetId").asText())
                || !Set.of("000300.SH", "000016.SH", "399006.SZ", "000905.SH", "000852.SH").contains(code)
                || to.isBefore(from) || to.toEpochDay() - from.toEpochDay() > 365)
            throw new IllegalStateException("D020 run/target/frozen scope does not match requested verifier inputs");
        var identityRows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (identityRows.size() != 1 || !(identityRows.getFirst().get("id") instanceof Number id)
                || !(identityRows.getFirst().get("directoryName") instanceof String directory)
                || !targetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D020 target physical identity differs from frozen run");

        var slices = ledger.entries(runId, null, 100).stream().filter(entry -> entry.kind() == SyncRunLedger.Kind.SLICE).toList();
        if (slices.size() != 1) throw new IllegalStateException("D020 run must have one source slice");
        JsonNode fetched = null;
        for (var event : ledger.events(slices.getFirst().id(), -1, 100)) if (event.state() == com.zoutrankil.questdbwithdata.domain.SyncRunState.FETCHED) {
            if (fetched != null) throw new IllegalStateException("Multiple D020 fetched evidence events");
            fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
        }
        if (fetched == null || !code.equals(fetched.path("cursor").asText())) throw new IllegalStateException("D020 fetched code differs");
        String receiptPath = fetched.path("responseEvidence").asText(""), fingerprint = fetched.path("sourceFingerprint").asText("");
        Path receipt = Path.of(receiptPath);
        Path evidenceRoot = ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").normalize();
        if (receiptPath.isBlank() || !fingerprint.matches("[0-9a-f]{64}") || !Files.isRegularFile(receipt)
                || !receipt.toAbsolutePath().normalize().startsWith(evidenceRoot)
                || Files.size(receipt) > 16 * 1024 * 1024) throw new IllegalStateException("Bounded ledger-scoped D020 raw receipt required");
        byte[] bytes = Files.readAllBytes(receipt);
        if (!sha256(bytes).equals(fingerprint)) throw new IllegalStateException("D020 source receipt fingerprint changed");
        var proof = JobDefinitionJson.mapper().readTree(bytes);
        String start = from.format(DateTimeFormatter.BASIC_ISO_DATE), end = to.format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!"tushare".equals(proof.path("sourceKind").asText()) || !"index_dailybasic".equals(proof.path("endpoint").asText())
                || !code.equals(proof.path("tsCode").asText()) || !from.toString().equals(proof.path("from").asText())
                || !to.toString().equals(proof.path("to").asText()) || !JobDefinitionJson.mapper().valueToTree(FIELDS).equals(proof.path("fields"))
                || !proof.path("sourceComplete").asBoolean(false) || proof.path("sourceRowCap").asInt(-1) != 3000
                || !proof.path("rawRows").isArray() || proof.path("returnedRows").asInt(-1) != proof.path("rawRows").size()
                || !code.equals(proof.path("parameters").path("ts_code").asText())
                || !start.equals(proof.path("parameters").path("start_date").asText())
                || !end.equals(proof.path("parameters").path("end_date").asText()))
            throw new IllegalStateException("D020 raw receipt does not prove the frozen complete request");
        if (proof.path("rawRows").size() >= 3000) throw new IllegalStateException("D020 response reached unpaged API cap");

        Map<Key,Values> expected = new HashMap<>(); int duplicateSourceKeys = 0;
        for (JsonNode row : proof.path("rawRows")) {
            if (!row.isObject() || !row.fieldNames().hasNext()) throw new IllegalStateException("Invalid D020 raw row");
            String rowCode = text(row, "ts_code"); LocalDate date = basicDate(text(row, "trade_date"));
            if (!code.equals(rowCode) || date.isBefore(from) || date.isAfter(to)) throw new IllegalStateException("D020 raw row outside frozen scope");
            Values value = values(row); if (expected.putIfAbsent(new Key(rowCode, date), value) != null) duplicateSourceKeys++;
        }
        long fromMicros = Math.multiplyExact(from.toEpochDay(), 86_400_000_000L);
        long toMicros = Math.multiplyExact(to.plusDays(1).toEpochDay(), 86_400_000_000L);
        String sql = "SELECT ts_code, cast(trade_date AS long) AS trade_date_micros, total_mv, float_mv, total_share, float_share, free_share, turnover_rate, turnover_rate_f, pe, pe_ttm, pb FROM \""
                + table + "\" WHERE ts_code = ? AND trade_date >= cast(? AS TIMESTAMP) AND trade_date < cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT " + (MAX_ROWS + 1);
        var actualRows = jdbc.query(sql, rs -> {
            var out = new ArrayList<Actual>();
            while (rs.next()) {
                if (out.size() >= MAX_ROWS) throw new IllegalStateException("D020 independent readback exceeds row bound");
                long micros = rs.getLong("trade_date_micros");
                LocalDate date = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)
                        .atZone(ZoneOffset.UTC).toLocalDate();
                out.add(new Actual(new Key(rs.getString("ts_code"), date), new Values(
                        number(rs, "total_mv"), number(rs, "float_mv"), number(rs, "total_share"), number(rs, "float_share"),
                        number(rs, "free_share"), number(rs, "turnover_rate"), number(rs, "turnover_rate_f"),
                        number(rs, "pe"), number(rs, "pe_ttm"), number(rs, "pb"))));
            }
            return out;
        }, code, fromMicros, toMicros);
        var actual = new HashMap<Key,Values>(); int duplicateTargetKeys = 0;
        for (Actual row : actualRows) if (actual.putIfAbsent(row.key(), row.values()) != null) duplicateTargetKeys++;
        int missing = 0, unexpected = 0, mismatched = 0;
        for (var entry : expected.entrySet()) {
            Values found = actual.get(entry.getKey());
            if (found == null) missing++;
            else if (!entry.getValue().equals(found)) mismatched++;
        }
        for (Key key : actual.keySet()) if (!expected.containsKey(key)) unexpected++;
        String status = duplicateSourceKeys == 0 && duplicateTargetKeys == 0 && missing == 0 && unexpected == 0 && mismatched == 0
                ? "MATCHED" : "MISMATCH";
        var result = new LinkedHashMap<String,Object>(); result.put("status", status); result.put("jobId", run.jobId());
        result.put("runId", runId); result.put("targetTable", table); result.put("targetId", targetId);
        result.put("endpoint", "index_dailybasic"); result.put("tsCode", code); result.put("from", from); result.put("to", to);
        result.put("fieldsCompared", FIELDS); result.put("sourceFingerprint", fingerprint);
        result.put("expectedRows", expected.size()); result.put("actualRows", actualRows.size());
        result.put("matchedRows", expected.size() - missing - mismatched); result.put("missingRows", missing);
        result.put("unexpectedRows", unexpected); result.put("mismatchedRows", mismatched);
        result.put("duplicateSourceKeys", duplicateSourceKeys); result.put("duplicateTargetKeys", duplicateTargetKeys);
        return Collections.unmodifiableMap(result);
    }
    private static Values values(JsonNode row) {
        var values = new ArrayList<Double>(); for (String field : FIELDS.subList(2, FIELDS.size())) {
            JsonNode value = row.get(field);
            if (value == null || value.isNull()) values.add(null);
            else if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw new IllegalStateException("Invalid D020 numeric raw field: " + field);
            else values.add(value.doubleValue());
        }
        return new Values(values.get(0), values.get(1), values.get(2), values.get(3), values.get(4),
                values.get(5), values.get(6), values.get(7), values.get(8), values.get(9));
    }
    private static String text(JsonNode row, String field) {
        JsonNode node = row.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) throw new IllegalStateException("Invalid D020 raw key: " + field);
        return node.asText();
    }
    private static LocalDate basicDate(String value) {
        if (!value.matches("[0-9]{8}")) throw new IllegalStateException("D020 trade_date must be YYYYMMDD");
        return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
    }
    private static Double number(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column); if (value == null) return null;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new java.sql.SQLException("Invalid D020 physical number: " + column);
        return number.doubleValue();
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private record Key(String tsCode, LocalDate tradeDate) {}
    private record Values(Double totalMv, Double floatMv, Double totalShare, Double floatShare, Double freeShare,
            Double turnoverRate, Double turnoverRateF, Double pe, Double peTtm, Double pb) {}
    private record Actual(Key key, Values values) {}
}
