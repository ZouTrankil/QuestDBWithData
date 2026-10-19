package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Independent test-side raw Tushare receipt to isolated QuestDB comparison for D007-D009. */
public final class MarketSourceReadbackVerifier {
    private static final Profile D015 = profile("D015", "data.etf_adj", "fund_adj", "rawRows",
            List.of("ts_code", "trade_date", "adj_factor"), Map.of("trade_date", "timestamp"),
            10000, false, false, true);
    private static final Profile D014 = profile("D014", "data.etf_daily", "fund_daily", "rawRows",
            List.of("ts_code", "trade_date", "pre_close", "open", "high", "low", "close", "change", "pct_chg", "vol", "amount"),
            Map.of("trade_date", "timestamp"), 5000, false, false, true);
    private static final Profile D010 = profile("D010", "data.stk_limit", "stk_limit", "rawRows",
            List.of("ts_code", "trade_date", "up_limit", "down_limit"), null, 5800, true, false, true);
    private static final int LEDGER_PAGE = 1000;
    private static final int MAX_SLICES = 1000;
    private static final int MAX_SOURCE_ROWS = 3_660_000;
    private static final int MAX_RECEIPT_BYTES = 32 * 1024 * 1024;
    private static final long MAX_TOTAL_RECEIPT_BYTES = 512L * 1024 * 1024;
    private static final int QUERY_KEYS = 100;
    private static final int MAX_TARGET_ROWS_PER_QUERY = 10_000;
    private static final int SAMPLE_LIMIT = 100;
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TRADE_DATE = "trade_date";

    private record Column(String source, String storage, boolean numeric) {}
    private record Profile(String task, String jobId, String endpoint, String rowField,
                           List<String> sourceFields, List<Column> columns, int rawRowCap,
                           boolean numericTextAllowed, boolean blankNumericMeansNull,
                           boolean wholeFileFingerprint) {}
    private record BusinessKey(String tsCode, LocalDate tradeDate) {}
    private record ReceiptRow(BusinessKey key, Map<String, Object> values) {}
    private record Receipt(Path path, List<ReceiptRow> rows, String fingerprint) {}

    private static final Profile D007 = profile("D007", "data.daily", "daily", "rawRows",
            List.of("ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change",
                    "pct_chg", "vol", "amount", "ah_vol", "ah_amount"), null,
            10_000, false, false, true);
    private static final Profile D008 = profile("D008", "data.daily_basic", "daily_basic", "rows",
            List.of("ts_code", "trade_date", "close", "turnover_rate", "turnover_rate_f", "volume_ratio",
                    "pe", "pe_ttm", "pb", "ps", "ps_ttm", "dv_ratio", "dv_ttm", "total_share",
                    "float_share", "free_share", "total_mv", "circ_mv"), null,
            6000, true, true, false);
    private static final Profile D009 = profile("D009", "data.stk_factor", "stk_factor", "rows",
            List.of("ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change",
                    "pct_change", "vol", "amount", "adj_factor", "open_hfq", "open_qfq", "close_hfq",
                    "close_qfq", "high_hfq", "high_qfq", "low_hfq", "low_qfq", "pre_close_hfq",
                    "pre_close_qfq", "macd_dif", "macd_dea", "macd", "kdj_k", "kdj_d", "kdj_j",
                    "rsi_6", "rsi_12", "rsi_24", "boll_upper", "boll_mid", "boll_lower", "cci"),
            Map.of(), 10_000, true, false, true);

    private MarketSourceReadbackVerifier() {}

    /**
     * Reopens every verified slice receipt for {@code runId}, then selects and compares every physical
     * key and column in bounded batches. The returned map is JSON-serializable and reports mismatches
     * rather than treating typed-runner verification as independent source acceptance.
     */
    public static Map<String, Object> verify(JdbcTemplate jdbc, String task, String table,
            Path ledgerPath, String runId) throws Exception {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(ledgerPath);
        requireIdentifier(table);
        Profile profile = profile(task);
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var run = ledger.getRun(runId);
        if (!profile.jobId().equals(run.jobId())) throw new IllegalArgumentException("Run job does not match task " + task);
        SyncRunState runState = ledger.get(runId).state();
        if (runState != SyncRunState.VERIFIED && runState != SyncRunState.VERIFIED_EMPTY)
            throw new IllegalStateException("Independent comparison requires a terminal verified run");

        Path evidenceRoot = ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").normalize();
        Path realEvidenceRoot = evidenceRoot.toRealPath();
        var sourceRows = new LinkedHashMap<BusinessKey, ReceiptRow>();
        var receipts = new ArrayList<Map<String, Object>>();
        var batchRows = new ArrayList<Map.Entry<BusinessKey, ReceiptRow>>();
        int sourceRowCount = 0, sourceDuplicateKeys = 0, sliceCount = 0;
        long evidenceBytes = 0;
        String after = null;
        while (true) {
            var entries = ledger.entries(runId, after, LEDGER_PAGE);
            for (var entry : entries) {
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (++sliceCount > MAX_SLICES) throw new IllegalStateException("Run exceeds bounded receipt slice count");
                if (entry.state() != SyncRunState.VERIFIED && entry.state() != SyncRunState.VERIFIED_EMPTY)
                    throw new IllegalStateException("Run contains a non-verified source slice: " + entry.id());
                JsonNode fetched = fetchedEvent(ledger, entry.id());
                String receiptText = requiredText(fetched, "responseEvidence");
                String fingerprint = requiredText(fetched, "sourceFingerprint");
                int recordedRows = requiredInt(fetched, "returnedRows");
                Path candidate = Path.of(receiptText).toAbsolutePath().normalize();
                if (!candidate.startsWith(evidenceRoot) || !Files.isRegularFile(candidate))
                    throw new IllegalStateException("Source receipt is outside the ledger evidence root or absent: " + entry.id());
                Path receiptPath = candidate.toRealPath();
                if (!receiptPath.startsWith(realEvidenceRoot))
                    throw new IllegalStateException("Source receipt resolves outside the ledger evidence root: " + entry.id());
                long size = Files.size(receiptPath);
                evidenceBytes = Math.addExact(evidenceBytes, size);
                if (size > MAX_RECEIPT_BYTES || evidenceBytes > MAX_TOTAL_RECEIPT_BYTES)
                    throw new IllegalStateException("Run exceeds the bounded raw-evidence byte budget");
                byte[] bytes = Files.readAllBytes(receiptPath);
                Receipt receipt = reopen(profile, receiptPath, bytes, fingerprint);
                if (receipt.rows().size() != recordedRows)
                    throw new IllegalStateException("Ledger row count differs from raw source receipt: " + entry.id());
                if ((recordedRows == 0) != (entry.state() == SyncRunState.VERIFIED_EMPTY))
                    throw new IllegalStateException("Ledger slice state differs from its raw source row count: " + entry.id());
                receipts.add(Map.of("sliceId", entry.id(), "path", receiptPath.toString(),
                        "fingerprint", receipt.fingerprint(), "sourceRows", receipt.rows().size()));
                for (ReceiptRow row : receipt.rows()) {
                    sourceRowCount = Math.addExact(sourceRowCount, 1);
                    if (sourceRowCount > MAX_SOURCE_ROWS) throw new IllegalStateException("Run exceeds independent source-row bound");
                    if (sourceRows.putIfAbsent(row.key(), row) != null) sourceDuplicateKeys++;
                }
            }
            if (entries.size() < LEDGER_PAGE) break;
            after = entries.getLast().id();
        }

        // The full source key list is held under MAX_SOURCE_ROWS; only the database readback is paged.
        for (var entry : sourceRows.entrySet()) batchRows.add(entry);
        var report = new LinkedHashMap<String, Object>();
        report.put("task", profile.task()); report.put("jobId", profile.jobId());
        report.put("runId", runId); report.put("table", table); report.put("status", "PENDING");
        report.put("sourceReceiptCount", sliceCount); report.put("sourceRows", sourceRowCount);
        report.put("uniqueSourceKeys", sourceRows.size()); report.put("duplicateSourceKeys", sourceDuplicateKeys);
        report.put("receipts", List.copyOf(receipts));
        report.put("comparedColumns", profile.columns().stream().map(Column::storage).toList());
        report.put("queryKeyLimit", QUERY_KEYS);
        report.put("readbackScope", "exact source keys; no full-table extra-row census");

        if (sourceRows.isEmpty()) {
            report.put("status", "NO_SOURCE_ROWS"); report.put("readbackQueries", 0);
            report.put("matches", 0); report.put("fieldMismatches", 0); report.put("missingRows", 0);
            report.put("duplicateTargetKeys", 0); report.put("unexpectedTargetRows", 0);
            report.put("mismatches", sourceDuplicateKeys); report.put("mismatchSamples", List.of());
            return report;
        }

        var mismatchSamples = new ArrayList<Map<String, String>>();
        long[] counters = new long[5]; // matches, field mismatches, missing rows, duplicate target rows, unexpected rows
        var queryBatchSizes = new ArrayList<Integer>();
        int queryCount = 0;
        String sampleSql = null;
        for (int offset = 0; offset < batchRows.size(); offset += QUERY_KEYS) {
            var part = batchRows.subList(offset, Math.min(batchRows.size(), offset + QUERY_KEYS));
            String sql = querySql(profile, table, part.size());
            if (sampleSql == null) sampleSql = sql;
            queryCount++;
            queryBatchSizes.add(part.size());
            var params = new ArrayList<Object>(part.size() * 2);
            for (var expected : part) {
                params.add(expected.getKey().tsCode());
                params.add(new TemporalValues.CalendarTimestamp(expected.getKey().tradeDate())
                        .storageEpoch(epochUnit(profile)));
            }
            List<Map<String, Object>> actualRows = jdbc.query(sql,
                    (rs, rowNum) -> readPhysicalRow(rs, profile), params.toArray());
            if (actualRows.size() > MAX_TARGET_ROWS_PER_QUERY)
                throw new IllegalStateException("One source-key batch exceeds the bounded target-row readback limit");
            var actualByKey = new HashMap<BusinessKey, List<Map<String, Object>>>();
            for (var actual : actualRows) {
                var key = new BusinessKey((String) actual.get("ts_code"), (LocalDate) actual.get(dateStorage(profile)));
                actualByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(actual);
            }
            for (var expected : part) {
                List<Map<String, Object>> found = actualByKey.remove(expected.getKey());
                if (found == null || found.isEmpty()) {
                    counters[2]++;
                    sample(mismatchSamples, "missing_row", expected.getKey(), null, null, null);
                    continue;
                }
                if (found.size() > 1) {
                    counters[3] += found.size() - 1L;
                    sample(mismatchSamples, "duplicate_target_key", expected.getKey(), null,
                            found.size(), 1);
                }
                int beforeFields = (int) counters[1];
                for (Column column : profile.columns()) {
                    Object want = expected.getValue().values().get(column.storage());
                    Object got = found.getFirst().get(column.storage());
                    if (!sameValue(want, got, column.numeric())) {
                        counters[1]++;
                        sample(mismatchSamples, "field_mismatch", expected.getKey(), column.storage(), want, got);
                    }
                }
                if (beforeFields == counters[1] && found.size() == 1) counters[0]++;
            }
            for (var unexpected : actualByKey.entrySet()) {
                counters[4] += unexpected.getValue().size();
                sample(mismatchSamples, "unexpected_target_row", unexpected.getKey(), null,
                        unexpected.getValue().size(), 0);
            }
        }

        long mismatches = sourceDuplicateKeys + counters[1] + counters[2] + counters[3] + counters[4];
        report.put("status", mismatches == 0 ? "MATCHED" : "MISMATCH");
        report.put("matches", counters[0]); report.put("fieldMismatches", counters[1]);
        report.put("missingRows", counters[2]); report.put("duplicateTargetKeys", counters[3]);
        report.put("unexpectedTargetRows", counters[4]); report.put("mismatches", mismatches);
        report.put("readbackQueries", queryCount); report.put("queryBatchSizes", List.copyOf(queryBatchSizes));
        report.put("query", sampleSql); report.put("mismatchSamples", List.copyOf(mismatchSamples));
        return report;
    }

    /** Package-visible for offline contract tests; this code intentionally does not call a business mapper. */
    static Map<String, Object> normalizeRawRow(String task, JsonNode raw) {
        Profile profile = profile(task);
        if (raw == null || !raw.isObject()) throw new IllegalArgumentException("Raw source row must be a JSON object");
        var actualFields = new TreeSet<String>(); raw.fieldNames().forEachRemaining(actualFields::add);
        var expectedFields = new TreeSet<>(profile.sourceFields());
        if (!actualFields.equals(expectedFields)) throw new IllegalArgumentException("Raw source row columns differ from the frozen task contract");
        var values = new LinkedHashMap<String, Object>();
        JsonNode code = raw.get("ts_code"), date = raw.get(TRADE_DATE);
        if (code == null || !code.isTextual() || !code.asText().matches(Set.of("D014","D015").contains(profile.task())
                ? "[0-9]{6}\\.(SZ|SH|OF)" : "[0-9]{6}\\.(SZ|SH|BJ)"))
            throw new IllegalArgumentException("Raw Tushare ts_code is invalid");
        if (date == null || !date.isTextual()) throw new IllegalArgumentException("Raw Tushare trade_date is required text");
        LocalDate tradeDate;
        try { tradeDate = LocalDate.parse(date.asText(), BASIC_DATE); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Raw Tushare trade_date must be YYYYMMDD", invalid); }
        for (Column column : profile.columns()) {
            JsonNode value = raw.get(column.source());
            Object normalized;
            if (column.source().equals("ts_code")) normalized = code.asText();
            else if (column.source().equals(TRADE_DATE)) normalized = tradeDate;
            else if (value == null || value.isNull()) normalized = null;
            else if (column.numeric()) normalized = numericValue(profile, value, column.source());
            else throw new IllegalArgumentException("Unexpected nonnumeric raw source column: " + column.source());
            values.put(column.storage(), normalized);
        }
        return Collections.unmodifiableMap(values);
    }

    private static Receipt reopen(Profile profile, Path path, byte[] bytes, String expectedFingerprint) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_RECEIPT_BYTES)
            throw new IllegalArgumentException("Source receipt byte size is outside its bound");
        JsonNode json = JobDefinitionJson.mapper().readTree(bytes);
        String actualFingerprint = profile.wholeFileFingerprint()
                ? sha256(bytes) : dailyBasicFingerprint(json);
        if (!actualFingerprint.equals(expectedFingerprint))
            throw new IllegalStateException("Raw receipt fingerprint differs from the FETCHED ledger event: " + path.getFileName());
        if (!profile.endpoint().equals(json.path("endpoint").asText()))
            throw new IllegalArgumentException("Raw receipt endpoint differs from the selected task");
        if (!JobDefinitionJson.mapper().valueToTree(profile.sourceFields()).equals(json.path("fields")))
            throw new IllegalArgumentException("Raw receipt fields differ from the frozen task contract");
        JsonNode rawRows = json.path(profile.rowField());
        if (!rawRows.isArray() || rawRows.size() > profile.rawRowCap())
            throw new IllegalArgumentException("Raw receipt rows are absent or exceed the endpoint cap");
        validateReceiptScope(profile, json, rawRows);
        var rows = new ArrayList<ReceiptRow>(rawRows.size());
        for (JsonNode raw : rawRows) {
            Map<String, Object> values = normalizeRawRow(profile.task(), raw);
            var key = new BusinessKey((String) values.get("ts_code"), (LocalDate) values.get(dateStorage(profile)));
            rows.add(new ReceiptRow(key, values));
        }
        return new Receipt(path, List.copyOf(rows), actualFingerprint);
    }

    private static void validateReceiptScope(Profile profile, JsonNode receipt, JsonNode rows) throws Exception {
        if (profile.task().equals("D007")) {
            String tradeDate = requiredText(receipt, "tradeDate");
            LocalDate expected = parseBasic(tradeDate);
            if (!receipt.path("returnedRows").canConvertToInt() || receipt.path("returnedRows").asInt() != rows.size()
                    || !receipt.path("completionPages").canConvertToInt() || receipt.path("completionPages").asInt() < 1)
                throw new IllegalArgumentException("D007 raw receipt completion count is invalid");
            for (JsonNode row : rows) if (!expected.equals((LocalDate) normalizeRawRow("D007", row).get(TRADE_DATE)))
                throw new IllegalArgumentException("D007 receipt contains another trade date");
            validateDailyFallback(receipt, rows);
        } else if (profile.task().equals("D008")) {
            if (!receipt.path("sourceComplete").asBoolean(false)
                    || !receipt.path("tradeDate").isTextual()
                    || !receipt.path("parameters").path("trade_date").asText().equals(
                            receipt.path("tradeDate").asText().replace("-", ""))
                    || !receipt.path("receipt").path("page").canConvertToInt()
                    || receipt.path("receipt").path("page").asInt() != 1
                    || receipt.path("receipt").path("offset").asInt(-1) != 0
                    || !receipt.path("receipt").path("cursor").isNull()
                    || !receipt.path("receipt").path("rows").canConvertToInt()
                    || receipt.path("receipt").path("rows").asInt() != rows.size())
                throw new IllegalArgumentException("D008 raw receipt scope or completion is invalid");
            LocalDate expected;
            try { expected = LocalDate.parse(receipt.path("tradeDate").asText()); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("D008 receipt tradeDate is invalid", invalid); }
            for (JsonNode row : rows) if (!expected.equals((LocalDate) normalizeRawRow("D008", row).get(TRADE_DATE)))
                throw new IllegalArgumentException("D008 receipt contains another trade date");
        } else if (profile.task().equals("D015")) {
            LocalDate expected = parseIso(requiredText(receipt, "tradeDate"));
            JsonNode pages=receipt.path("pageEvidence");
            if(!receipt.path("sourceComplete").asBoolean(false) || !"tushare".equals(receipt.path("sourceKind").asText())
                    ||receipt.path("returnedRows").asInt(-1)!=rows.size()||receipt.path("pageSize").asInt(-1)!=2000
                    ||receipt.path("dateRowCap").asInt(-1)!=10000||!pages.isArray()||pages.isEmpty()||pages.size()>6
                    ||!expected.format(BASIC_DATE).equals(receipt.path("parameters").path("trade_date").asText()))
                throw new IllegalArgumentException("D015 raw page sequence is incomplete or outside scope");
            int count=0,index=0;
            for(var page:pages){int size=page.path("rows").asInt(-1);
                if(page.path("offset").asInt(-1)!=index*2000||page.path("limit").asInt(-1)!=2000||size<0||size>2000
                        ||(index<pages.size()-1?size!=2000:size>=2000))throw new IllegalArgumentException("D015 page offset/count/end invalid");
                count+=size;index++;
            }
            if(count!=rows.size())throw new IllegalArgumentException("D015 complete page count differs from raw rows");
            for(var row:rows)if(!expected.equals(normalizeRawRow("D015",row).get(dateStorage(profile))))
                throw new IllegalArgumentException("D015 source row lies outside requested date");
        } else if (Set.of("D010", "D014").contains(profile.task())) {
            LocalDate expected = parseIso(requiredText(receipt, "tradeDate"));
            if (!receipt.path("sourceComplete").asBoolean(false)
                    || !"tushare".equals(receipt.path("sourceKind").asText())
                    || receipt.path("returnedRows").asInt(-1) != rows.size()
                    || !expected.format(BASIC_DATE).equals(receipt.path("parameters").path("trade_date").asText())
                    || rows.size() >= profile.rawRowCap())
                throw new IllegalArgumentException("D010 raw receipt scope or completion is invalid");
            for (JsonNode row : rows)
                if (!expected.equals(normalizeRawRow(profile.task(), row).get(dateStorage(profile))))
                    throw new IllegalArgumentException("D010 source row is outside its exact trade date");
        } else {
            if (receipt.path("sourceContractVersion").asInt(-1) != 2
                    || !"tushare".equals(receipt.path("sourceKind").asText())
                    || !receipt.path("returnedRows").canConvertToInt()
                    || receipt.path("returnedRows").asInt() != rows.size()
                    || receipt.path("completion").path("pages").asInt(-1) != 1
                    || receipt.path("completion").path("rows").asInt(-1) != rows.size())
                throw new IllegalArgumentException("D009 raw receipt completion is invalid");
            JsonNode query = receipt.path("query"), parameters = receipt.path("parameters");
            LocalDate from = parseIso(requiredText(query, "from")), to = parseIso(requiredText(query, "to"));
            if (to.isBefore(from)) throw new IllegalArgumentException("D009 raw receipt has a reversed date range");
            JsonNode codeNode = query.get("tsCode");
            String code = codeNode == null || codeNode.isNull() ? null : codeNode.asText();
            if (code == null) {
                if (!from.equals(to) || !from.format(BASIC_DATE).equals(parameters.path("trade_date").asText()))
                    throw new IllegalArgumentException("D009 all-market receipt must be scoped to one trade_date");
            } else if (!code.matches("[0-9]{6}\\.(SZ|SH|BJ)")
                    || !code.equals(parameters.path("ts_code").asText())
                    || !from.format(BASIC_DATE).equals(parameters.path("start_date").asText())
                    || !to.format(BASIC_DATE).equals(parameters.path("end_date").asText()))
                throw new IllegalArgumentException("D009 exact-code receipt parameters do not match its frozen query");
            for (JsonNode row : rows) {
                var values = normalizeRawRow("D009", row);
                LocalDate date = (LocalDate) values.get(TRADE_DATE);
                if (date.isBefore(from) || date.isAfter(to)
                        || code != null && !code.equals(values.get("ts_code")))
                    throw new IllegalArgumentException("D009 source row is outside its exact receipt query scope");
            }
        }
    }

    private static void validateDailyFallback(JsonNode receipt, JsonNode rows) throws Exception {
        JsonNode split = receipt.path("splitByCode");
        JsonNode groups = receipt.path("fallbackGroups"), cappedRows = receipt.path("initialCappedRows");
        if (!split.isBoolean() || !groups.isArray() || !cappedRows.isArray())
            throw new IllegalArgumentException("D007 split evidence is incomplete");
        if (!split.asBoolean()) {
            if (!groups.isEmpty() || !cappedRows.isEmpty() || receipt.path("initialCappedRowCount").asInt(-1) != 0
                    || !receipt.path("inventoryFingerprint").isNull())
                throw new IllegalArgumentException("D007 non-split receipt unexpectedly contains fallback metadata");
            return;
        }
        if (groups.isEmpty() || receipt.path("initialCappedRowCount").asInt(-1) != cappedRows.size())
            throw new IllegalArgumentException("D007 capped receipt lacks code-group coverage evidence");
        var codes = new ArrayList<String>();
        for (JsonNode group : groups) {
            if (!group.isArray() || group.isEmpty() || group.size() > 1000)
                throw new IllegalArgumentException("D007 fallback group is outside its row bound");
            for (JsonNode code : group) {
                if (!code.isTextual() || !code.asText().matches("[0-9]{6}\\.(SZ|SH|BJ)"))
                    throw new IllegalArgumentException("D007 fallback inventory contains an invalid code");
                codes.add(code.asText());
            }
        }
        String inventoryFingerprint = requiredText(receipt, "inventoryFingerprint");
        if (new HashSet<>(codes).size() != codes.size()
                || !sha256(String.join("\n", codes).getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(inventoryFingerprint))
            throw new IllegalArgumentException("D007 fallback inventory fingerprint differs");
        var allowed = Set.copyOf(codes);
        for (JsonNode row : rows) if (!allowed.contains(row.path("ts_code").asText()))
            throw new IllegalArgumentException("D007 returned row falls outside fallback inventory");
        for (JsonNode row : cappedRows) if (!allowed.contains(row.path("ts_code").asText()))
            throw new IllegalArgumentException("D007 capped response row falls outside fallback inventory");
    }

    private static String dailyBasicFingerprint(JsonNode receipt) throws Exception {
        String dateText = requiredText(receipt, "tradeDate");
        LocalDate date = parseIso(dateText);
        JsonNode rowsNode = receipt.path("rows");
        var rows = new ArrayList<Map<String, JsonNode>>(rowsNode.size());
        for (JsonNode row : rowsNode) {
            if (!row.isObject()) throw new IllegalArgumentException("D008 source row is not a JSON object");
            var values = new LinkedHashMap<String, JsonNode>();
            row.fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue()));
            rows.add(values);
        }
        var receiptMeta = receipt.path("receipt");
        var body = new LinkedHashMap<String, Object>();
        body.put("endpoint", "daily_basic");
        body.put("parameters", Map.of("trade_date", date.format(BASIC_DATE)));
        body.put("fields", D008.sourceFields());
        body.put("tradeDate", date);
        body.put("rows", rows);
        if (receiptMeta.path("sourceVersion").isTextual())
            body.put("sourceVersion", receiptMeta.path("sourceVersion").textValue());
        byte[] canonical = JobDefinitionJson.canonicalMapper()
                .writeValueAsBytes(body);
        return sha256(canonical);
    }

    private static JsonNode fetchedEvent(SyncRunLedger ledger, String sliceId) throws Exception {
        JsonNode fetched = null;
        for (var event : ledger.events(sliceId, -1, 100)) {
            if (event.state() != SyncRunState.FETCHED) continue;
            if (fetched != null) throw new IllegalStateException("Slice contains multiple FETCHED events: " + sliceId);
            fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
        }
        if (fetched == null) throw new IllegalStateException("Verified slice has no FETCHED event: " + sliceId);
        return fetched;
    }

    private static Map<String, Object> readPhysicalRow(ResultSet rs, Profile profile) throws SQLException {
        Object rawMicros = rs.getObject("__trade_date_micros");
        if (!(rawMicros instanceof Number micros)) throw new SQLException("QuestDB row has no timestamp epoch");
        LocalDate date = TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(),
                epochUnit(profile)).date();
        var values = new LinkedHashMap<String, Object>();
        values.put("ts_code", rs.getString("ts_code")); values.put(dateStorage(profile), date);
        for (Column column : profile.columns()) {
            if (column.source().equals("ts_code") || column.source().equals(TRADE_DATE)) continue;
            Object raw = rs.getObject(column.storage());
            if (raw != null && !(raw instanceof Number)) throw new SQLException("Non-numeric QuestDB value: " + column.storage());
            Double value = raw == null ? null : ((Number) raw).doubleValue();
            values.put(column.storage(), value == null || !Double.isFinite(value) ? null : value);
        }
        return values;
    }

    private static boolean sameValue(Object expected, Object actual, boolean numeric) {
        if (expected == null || actual == null) return expected == actual;
        if (numeric) return expected instanceof Double left && actual instanceof Double right
                && Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
        return expected.equals(actual);
    }

    private static Double numericValue(Profile profile, JsonNode raw, String name) {
        if (!profile.numericTextAllowed() && !raw.isNumber())
            throw new IllegalArgumentException("Numeric JSON value required for " + profile.task() + " " + name);
        if (raw.isTextual() && raw.asText().isBlank() && profile.blankNumericMeansNull()) return null;
        if (!raw.isNumber() && !raw.isTextual()) throw new IllegalArgumentException("Numeric source value required for " + name);
        String text = raw.asText();
        if (text.isBlank()) throw new IllegalArgumentException("Blank non-null numeric source value: " + name);
        double parsed;
        try { parsed = new BigDecimal(text).doubleValue(); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid raw numeric source value: " + name, invalid); }
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException("Non-finite raw numeric source value: " + name);
        return parsed;
    }

    private static String querySql(Profile profile, String table, int keys) {
        String dateColumn = dateStorage(profile);
        String dateType = profile.task().equals("D014") ? "TIMESTAMP_NS" : "TIMESTAMP";
        String projection = String.join(",", profile.columns().stream().map(column -> column.source().equals(TRADE_DATE)
                ? "cast(\"" + dateColumn + "\" as long) AS \"__trade_date_micros\""
                : "\"" + column.storage() + "\"").toList());
        var clauses = new ArrayList<String>(keys);
        for (int i = 0; i < keys; i++) clauses.add("(\"ts_code\"=? AND \"" + dateColumn + "\"=cast(? AS " + dateType + "))");
        return "SELECT " + projection + " FROM \"" + table + "\" WHERE " + String.join(" OR ", clauses)
                + " ORDER BY \"ts_code\",\"" + dateColumn + "\" LIMIT " + (MAX_TARGET_ROWS_PER_QUERY + 1);
    }

    private static String dateStorage(Profile profile) {
        return profile.columns().stream().filter(c -> c.source().equals(TRADE_DATE)).findFirst().orElseThrow().storage();
    }
    private static TemporalValues.EpochUnit epochUnit(Profile profile) {
        return profile.task().equals("D014") ? TemporalValues.EpochUnit.NANOS : TemporalValues.EpochUnit.MICROS;
    }

    private static Profile profile(String task, String jobId, String endpoint, String rowField,
            List<String> sourceFields, Map<String, String> renames, int cap,
            boolean textAllowed, boolean blankMeansNull, boolean fileHash) {
        var columns = new ArrayList<Column>(sourceFields.size());
        for (String source : sourceFields) {
            boolean key = source.equals("ts_code") || source.equals(TRADE_DATE);
            String storage = renames == null ? source : renames.getOrDefault(source, source);
            columns.add(new Column(source, storage, !key));
        }
        return new Profile(task, jobId, endpoint, rowField, List.copyOf(sourceFields), List.copyOf(columns),
                cap, textAllowed, blankMeansNull, fileHash);
    }

    private static Profile profile(String task) {
        return switch (task) {
            case "D007" -> D007;
            case "D008" -> D008;
            case "D009" -> D009;
            case "D010" -> D010;
            case "D014" -> D014;
            case "D015" -> D015;
            default -> throw new IllegalArgumentException("Only D007, D008 and D009 are supported");
        };
    }

    private static LocalDate parseBasic(String value) {
        try { return LocalDate.parse(value, BASIC_DATE); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Expected Tushare YYYYMMDD date", invalid); }
    }
    private static LocalDate parseIso(String value) {
        try { return LocalDate.parse(value); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Expected ISO calendar date", invalid); }
    }
    private static int requiredInt(JsonNode node, String field) {
        if (!node.path(field).canConvertToInt()) throw new IllegalArgumentException("Receipt integer is required: " + field);
        return node.path(field).asInt();
    }
    private static String requiredText(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
            throw new IllegalArgumentException("Receipt text is required: " + field);
        return node.path(field).asText();
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void requireIdentifier(String table) {
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("QuestDB table identifier required");
    }
    private static void sample(List<Map<String, String>> samples, String kind, BusinessKey key,
            String column, Object expected, Object actual) {
        if (samples.size() >= SAMPLE_LIMIT) return;
        var item = new LinkedHashMap<String, String>();
        item.put("kind", kind); item.put("ts_code", key.tsCode()); item.put("trade_date", key.tradeDate().toString());
        if (column != null) item.put("column", column);
        if (expected != null) item.put("expected", String.valueOf(expected));
        if (actual != null) item.put("actual", String.valueOf(actual));
        samples.add(Collections.unmodifiableMap(item));
    }
}
