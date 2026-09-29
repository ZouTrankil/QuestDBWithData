package com.zoutrankil.questdbwithdata.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.service.IndexWeightSyncJobOwner;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.ByteArrayInputStream;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * D021 operational verifier. It derives expected values from raw XLS/Tushare receipt data and
 * retained D002 name evidence, without calling the production mapper, source, or write port.
 */
public final class IndexWeightIndependentReadback {
    // Restate the D021 source bounds here so the readback does not inherit production mapping logic.
    private static final int MAX_RECEIPT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ROWS = 128_000;
    private static final int MAX_XLS_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TUSHARE_ROWS_PER_CALL = 4_000;
    private static final int MAX_BACKFILL_DAYS = 92;
    private static final int SNAPSHOT_LOOKBACK_DAYS = 75;
    private static final String CSI_URL_PREFIX = "https://oss-ch.csindex.com.cn/static/html/csindex/public/uploads/file/autofile/closeweight/";
    private static final List<String> TUSHARE_FIELDS = List.of("index_code", "con_code", "trade_date", "weight");
    private enum Route { CSINDEX_OSS_XLS, TUSHARE_INDEX_WEIGHT }
    private record IndexSpec(String code, String providerCode, Route route, int expectedMembers) {}
    private static final List<IndexSpec> INDEXES = List.of(
            new IndexSpec("000300", "000300.SH", Route.CSINDEX_OSS_XLS, 300),
            new IndexSpec("000016", "000016.SH", Route.CSINDEX_OSS_XLS, 50),
            new IndexSpec("000688", "000688.SH", Route.CSINDEX_OSS_XLS, 50),
            new IndexSpec("000905", "000905.SH", Route.CSINDEX_OSS_XLS, 500),
            new IndexSpec("000510", "000510.SH", Route.CSINDEX_OSS_XLS, 500),
            new IndexSpec("000852", "000852.SH", Route.CSINDEX_OSS_XLS, 1_000),
            new IndexSpec("932000", "932000.CSI", Route.CSINDEX_OSS_XLS, 2_000),
            new IndexSpec("399673", "399673.SZ", Route.TUSHARE_INDEX_WEIGHT, 50));
    private static final Map<String,IndexSpec> INDEX_BY_CODE = INDEXES.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(IndexSpec::code, value -> value));
    private static final List<String> PHYSICAL_FIELDS = List.of("index_code", "con_code", "trade_date", "index_name",
            "index_name_en", "con_name", "con_name_en", "exchange", "exchange_en", "weight", "update_time");
    private static final List<String> FIELDS = List.of("index_code", "con_code", "trade_date", "index_name",
            "index_name_en", "con_name", "con_name_en", "exchange", "exchange_en", "weight", "update_time");
    private static final List<String> HEADERS_ZH = List.of("日期", "指数代码", "指数名称", "指数英文名称",
            "成分券代码", "成分券名称", "成分券英文名称", "交易所", "交易所英文名称", "权重");
    private static final List<String> HEADERS_BILINGUAL = List.of("日期Date", "指数代码 Index Code", "指数名称 Index Name",
            "指数英文名称Index Name(Eng)", "成份券代码Constituent Code", "成份券名称Constituent Name",
            "成份券英文名称Constituent Name(Eng)", "交易所Exchange", "交易所英文名称Exchange(Eng)", "权重(%)weight");
    private record Key(String indexCode, String conCode, LocalDate tradeDate) implements Comparable<Key> {
        @Override public int compareTo(Key other) {
            int result = indexCode.compareTo(other.indexCode);
            if (result == 0) result = tradeDate.compareTo(other.tradeDate);
            return result == 0 ? conCode.compareTo(other.conCode) : result;
        }
        String text() { return indexCode + "|" + conCode + "|" + tradeDate; }
    }
    private record SourceSlice(String receipt, String fingerprint, String cursor, int rows) {}
    private record Expected(Map<String,Object> values, String sourceRoute) {}

    private IndexWeightIndependentReadback() {}

    public static Map<String,Object> verify(JdbcTemplate jdbc, Path ledgerPath, String table, String runId) throws Exception {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(ledgerPath); Objects.requireNonNull(table); Objects.requireNonNull(runId);
        if (!table.matches("java_d021_index_weight_[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Dedicated D021 isolated table required");
        Path ledgerFile = ledgerPath.toAbsolutePath().normalize();
        var ledger = SyncRunLedger.openReadOnly(ledgerFile);
        var run = ledger.getRun(runId); var runEntry = ledger.get(runId);
        if (!IndexWeightSyncJobOwner.DEFINITION.jobId().equals(run.jobId())
                || run.jobVersion() != IndexWeightSyncJobOwner.DEFINITION.version()
                || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(runEntry.state()))
            throw new IllegalStateException("D021 run must be terminal verified or verified-empty");
        JsonNode frozen = JobDefinitionJson.mapper().readTree(run.frozenJson());
        JsonNode params = frozen.path("parameters");
        String targetId = run.targetId();
        String frozenTarget = requiredText(params, "targetId");
        String stockTargetId = requiredText(params, "stockDetailTargetId");
        if (!targetId.equals(frozenTarget) || !targetId.matches("static-v2-[0-9a-f]{64}")
                || !stockTargetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalStateException("D021 frozen physical or D002 reference target differs");
        if (!"SNAPSHOT".equals(frozen.path("mode").asText()) && !"BACKFILL".equals(frozen.path("mode").asText()))
            throw new IllegalStateException("D021 frozen mode is unsupported by independent verifier");
        Instant observedAt = Instant.parse(requiredText(params, "observedAt"));
        if (!observedAt.equals(observedAt.truncatedTo(ChronoUnit.MICROS)))
            throw new IllegalStateException("D021 observation instant is not microsecond precise");
        LocalDate from = frozen.path("from").isNull() ? null : LocalDate.parse(frozen.path("from").asText());
        LocalDate to = frozen.path("to").isNull() ? null : LocalDate.parse(frozen.path("to").asText());
        LocalDate logicalDate = LocalDate.parse(frozen.path("logicalDate").asText());
        boolean snapshot = "SNAPSHOT".equals(frozen.path("mode").asText());
        if (snapshot && (from != null || to != null) || !snapshot && (from == null || to == null
                || from.isAfter(to) || to.isAfter(logicalDate)
                || ChronoUnit.DAYS.between(from, to) + 1 > MAX_BACKFILL_DAYS))
            throw new IllegalStateException("D021 frozen source bounds are invalid");

        var targetRows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (targetRows.size() != 1 || !(targetRows.getFirst().get("id") instanceof Number tableId)
                || !(targetRows.getFirst().get("directoryName") instanceof String directory)
                || !targetId.equals(StaticTargetIdentity.identify(jdbc, table, tableId.longValue(), directory)))
            throw new IllegalStateException("D021 current QuestDB target identity differs from frozen run");

        // A resumed slice retains the original ledger-bound receipt path and SHA-256.
        Path sourceRoot = ledgerFile.getParent().resolve("sync-evidence").toRealPath();
        var slices = loadSlices(ledger, runId);
        var expected = new TreeMap<Key,Expected>(); var calls = new ArrayList<Map<String,Object>>();
        var observedCalls = new HashSet<String>(); int sourceRows = 0;
        for (SourceSlice slice : slices) {
            Path receiptPath = evidencePath(slice.receipt(), sourceRoot);
            byte[] receiptBytes = readBounded(receiptPath, MAX_RECEIPT_BYTES);
            if (!slice.fingerprint().equals(sha256(receiptBytes)))
                throw new IllegalStateException("D021 ledger source fingerprint differs from receipt bytes");
            JsonNode receipt = JobDefinitionJson.mapper().readTree(receiptBytes);
            if (!receipt.path("sourceComplete").asBoolean(false)
                    || !observedAt.toString().equals(receipt.path("observedAt").asText())
                    || receipt.path("returnedRows").asInt(-1) != slice.rows()
                    || !"D021".equals(receipt.path("task").asText("D021"))
                    || !matchesFrozenCall(receipt, frozen, params, snapshot))
                throw new IllegalStateException("D021 source receipt is incomplete or differs from frozen scope");
            String indexCode = requiredText(receipt, "indexCode");
            IndexSpec index = resolveIndex(indexCode);
            if (receipt.path("sourceContractVersion").asInt(-1) != 1
                    || receipt.path("expectedMemberCount").asInt(-1) != index.expectedMembers())
                throw new IllegalStateException("D021 receipt source contract or frozen member count differs");
            String callIdentity = callIdentity(receipt);
            if (!observedCalls.add(callIdentity)) throw new IllegalStateException("Duplicate D021 index/month source call");
            Map<String,String> nameMap = Map.of();
            JsonNode rawRows;
            String route = receipt.path("route").asText("");
            if ("csindex_oss_xls".equals(receipt.path("sourceKind").asText())) {
                if (index.route() != Route.CSINDEX_OSS_XLS || !index.route().name().equals(route)
                        || !"index_stock_cons_weight_csindex".equals(receipt.path("endpoint").asText())
                        || !receipt.path("parameters").path("url").asText()
                                .equals(CSI_URL_PREFIX + index.code() + "closeweight.xls"))
                    throw new IllegalStateException("D021 CSIndex route differs from frozen universe");
                Path rawFile = evidencePath(receiptPath.getParent().resolve(requiredText(receipt, "rawFile")).toString(), sourceRoot);
                byte[] workbook = readBounded(rawFile, MAX_XLS_BYTES);
                if (workbook.length != receipt.path("rawBytes").asInt(-1)
                        || !sha256(workbook).equals(receipt.path("rawSha256").asText()))
                    throw new IllegalStateException("D021 raw CSIndex workbook hash or size differs from receipt");
                var parsed = parseWorkbook(workbook);
                if (!JobDefinitionJson.mapper().valueToTree(parsed.headers()).equals(receipt.path("sourceHeaders")))
                    throw new IllegalStateException("D021 raw XLS headers differ from retained source schema");
                int rowsInCall = 0;
                for (var raw : parsed.rows()) {
                    Map<String,Object> values = csindexValues(raw, observedAt);
                    if (((LocalDate) values.get("trade_date")).isAfter(logicalDate))
                        throw new IllegalStateException("D021 CSIndex snapshot row exceeds frozen logical date");
                    addExpected(expected, values, "csindex_oss_xls"); rowsInCall++;
                }
                if (rowsInCall != slice.rows() || rowsInCall != index.expectedMembers())
                    throw new IllegalStateException("D021 raw XLS member count differs from frozen expected membership");
            } else if ("tushare".equals(receipt.path("sourceKind").asText())) {
                if ((snapshot && index.route() != Route.TUSHARE_INDEX_WEIGHT) || !route.equals(index.route().name()))
                    throw new IllegalStateException("D021 Tushare route differs from frozen universe");
                if (!"index_weight".equals(receipt.path("endpoint").asText())
                        || !index.providerCode().equals(receipt.path("providerIndexCode").asText())
                        || !JobDefinitionJson.mapper().valueToTree(TUSHARE_FIELDS).equals(receipt.path("fields")))
                    throw new IllegalStateException("D021 Tushare endpoint, provider code, or fields differ from source contract");
                nameMap = retainedNames(receipt.path("nameEnrichment"), stockTargetId);
                rawRows = receipt.path("rawRows");
                if (!rawRows.isArray()) throw new IllegalStateException("D021 Tushare receipt lacks raw response rows");
                var selected = new ArrayList<JsonNode>();
                LocalDate latest = null;
                for (JsonNode raw : rawRows) {
                    LocalDate date = date(raw.path("trade_date"));
                    if ("latest_snapshot".equals(receipt.path("purpose").asText())) {
                        if (latest == null || date.isAfter(latest)) latest = date;
                    }
                }
                if ("latest_snapshot".equals(receipt.path("purpose").asText())) {
                    LocalDate latestDate = latest;
                    rawRows.forEach(raw -> { if (date(raw.path("trade_date")).equals(latestDate)) selected.add(raw); });
                } else rawRows.forEach(selected::add);
                if (selected.size() != slice.rows())
                    throw new IllegalStateException("D021 independently selected Tushare rows differ from runner slice");
                for (JsonNode raw : selected) {
                    Map<String,Object> values = tushareValues(raw, index, from, to, logicalDate, snapshot, nameMap, observedAt);
                    addExpected(expected, values, "tushare");
                }
                if ("latest_snapshot".equals(receipt.path("purpose").asText())
                        && selected.size() != index.expectedMembers())
                    throw new IllegalStateException("D021 Tushare latest snapshot member count is incomplete");
            } else throw new IllegalStateException("Unsupported D021 source receipt kind");
            sourceRows = Math.addExact(sourceRows, slice.rows());
            calls.add(Map.of("indexCode", indexCode, "sourceKind", receipt.path("sourceKind").asText(),
                    "route", route, "rows", slice.rows(), "fingerprint", slice.fingerprint(), "receipt", slice.receipt()));
        }
        validateCallInventory(snapshot, from, to, logicalDate, observedCalls, slices.size());
        if (sourceRows != expected.size() || sourceRows > MAX_ROWS)
            throw new IllegalStateException("D021 independently derived source-row count is inconsistent or over budget");
        if ((runEntry.state() == SyncRunState.VERIFIED_EMPTY) != (sourceRows == 0))
            throw new IllegalStateException("D021 ledger completion state differs from raw source rows");

        var actualRows = readTarget(jdbc, table, snapshot, expected.keySet(), from, to);
        var actual = new TreeMap<Key,Map<String,Object>>(); int duplicateKeys = 0;
        for (var row : actualRows) if (actual.putIfAbsent(key(row), row) != null) duplicateKeys++;
        var allKeys = new TreeSet<Key>(); allKeys.addAll(expected.keySet()); allKeys.addAll(actual.keySet());
        var mismatches = new ArrayList<String>(); int matched = 0;
        for (Key key : allKeys) {
            Expected wanted = expected.get(key); Map<String,Object> found = actual.get(key);
            if (wanted != null && found != null && same(wanted.values(), found)) matched++;
            else if (mismatches.size() < 100) mismatches.add(key.text());
        }
        int mismatchCount = allKeys.size() - matched;
        var result = new LinkedHashMap<String,Object>(); result.put("task", "D021"); result.put("runId", runId);
        result.put("targetId", targetId); result.put("table", table); result.put("mode", snapshot ? "SNAPSHOT" : "BACKFILL");
        result.put("from", from); result.put("to", to); result.put("observedAt", observedAt.toString());
        result.put("sourceRows", sourceRows); result.put("expectedRows", expected.size());
        result.put("actualRows", actual.size()); result.put("matches", matched); result.put("mismatches", mismatchCount);
        result.put("duplicateKeys", duplicateKeys); result.put("missingKeys", expected.keySet().stream().filter(k -> !actual.containsKey(k)).count());
        result.put("extraKeys", actual.keySet().stream().filter(k -> !expected.containsKey(k)).count());
        result.put("mismatchKeySample", mismatches); result.put("calls", calls); result.put("fieldsCompared", PHYSICAL_FIELDS);
        result.put("status", mismatchCount == 0 && duplicateKeys == 0 && sourceRows == actual.size() ? "MATCHED" : "MISMATCHED");
        return Collections.unmodifiableMap(result);
    }

    private static List<SourceSlice> loadSlices(SyncRunLedger ledger, String runId) throws Exception {
        var result = new ArrayList<SourceSlice>(); String after = null;
        while (true) {
            var page = ledger.entries(runId, after, 100);
            for (var entry : page) {
                if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(entry.state()))
                    throw new IllegalStateException("D021 run contains an unverified slice");
                JsonNode fetched = null;
                for (var event : ledger.events(entry.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
                    if (fetched != null) throw new IllegalStateException("Duplicate D021 FETCHED event");
                    fetched = JobDefinitionJson.mapper().readTree(event.payloadJson());
                }
                if (fetched == null) throw new IllegalStateException("D021 verified slice lacks FETCHED event");
                int rows = fetched.path("returnedRows").asInt(-1);
                if (rows < 0 || rows > MAX_TUSHARE_ROWS_PER_CALL)
                    throw new IllegalStateException("D021 source slice count is outside the route cap");
                result.add(new SourceSlice(requiredText(fetched, "responseEvidence"),
                        requiredText(fetched, "sourceFingerprint"), fetched.path("cursor").asText(""), rows));
            }
            if (page.size() < 100) break;
            after = page.getLast().id();
        }
        return List.copyOf(result);
    }

    private record ParsedWorkbook(List<String> headers, List<Map<String,String>> rows) {}
    private static ParsedWorkbook parseWorkbook(byte[] bytes) throws Exception {
        try (var workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 1) throw new IllegalStateException("D021 raw workbook must have exactly one sheet");
            var sheet = workbook.getSheetAt(0); var formatter = new DataFormatter(Locale.ROOT);
            var header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null || header.getLastCellNum() != 10 || sheet.getLastRowNum() > 2001)
                throw new IllegalStateException("D021 raw XLS header/row bound invalid");
            var headers = new ArrayList<String>(10);
            for (int i = 0; i < 10; i++) headers.add(cellText(header.getCell(i), formatter));
            if (!headers.equals(HEADERS_ZH) && !headers.equals(HEADERS_BILINGUAL))
                throw new IllegalStateException("D021 raw XLS has an unsupported exact header schema");
            var rows = new ArrayList<Map<String,String>>();
            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r); if (row == null || blank(row, formatter)) continue;
                if (row.getLastCellNum() > 10) throw new IllegalStateException("D021 raw XLS has an extra field");
                var values = new LinkedHashMap<String,String>();
                values.put("trade_date", dateCell(row.getCell(0), formatter));
                values.put("index_code", numericText(row.getCell(1), formatter));
                values.put("index_name", cellText(row.getCell(2), formatter));
                values.put("index_name_en", cellText(row.getCell(3), formatter));
                values.put("con_code", numericText(row.getCell(4), formatter));
                values.put("con_name", cellText(row.getCell(5), formatter));
                values.put("con_name_en", cellText(row.getCell(6), formatter));
                values.put("exchange", cellText(row.getCell(7), formatter));
                values.put("exchange_en", cellText(row.getCell(8), formatter));
                values.put("weight", cellText(row.getCell(9), formatter));
                if (values.get("trade_date") == null || values.get("index_code") == null
                        || values.get("con_code") == null || values.get("weight") == null)
                    throw new IllegalStateException("D021 raw XLS omits a required business field");
                rows.add(values);
            }
            if (rows.isEmpty() || rows.size() > 2000) throw new IllegalStateException("D021 raw XLS member count outside cap");
            return new ParsedWorkbook(List.copyOf(headers), List.copyOf(rows));
        }
    }

    private static Map<String,Object> csindexValues(Map<String,String> raw, Instant observedAt) {
        String indexCode = normalizeIndex(raw.get("index_code"));
        LocalDate date = LocalDate.parse(basicDate(raw.get("trade_date")), DateTimeFormatter.BASIC_ISO_DATE);
        String conCode = normalizeCon(raw.get("con_code"), raw.get("exchange"), raw.get("exchange_en"));
        Double weight = parseNumber(raw.get("weight"));
        var result = baseValues(indexCode, conCode, date, observedAt);
        result.put("index_name", blankToNull(raw.get("index_name"))); result.put("index_name_en", blankToNull(raw.get("index_name_en")));
        result.put("con_name", blankToNull(raw.get("con_name"))); result.put("con_name_en", blankToNull(raw.get("con_name_en")));
        result.put("exchange", blankToNull(raw.get("exchange"))); result.put("exchange_en", blankToNull(raw.get("exchange_en")));
        if (weight == null || weight < 0 || weight > 100) throw new IllegalStateException("D021 raw XLS weight is invalid");
        result.put("weight", weight); return result;
    }

    private static Map<String,Object> tushareValues(JsonNode raw, IndexSpec index,
            LocalDate from, LocalDate to, LocalDate logicalDate, boolean snapshot,
            Map<String,String> names, Instant observedAt) {
        String indexCode = normalizeIndex(raw.path("index_code").asText(index.code()));
        if (!index.code().equals(indexCode)) throw new IllegalStateException("D021 raw Tushare index_code differs from call");
        String conRaw = requiredText(raw, "con_code");
        String exchange = textOrNull(raw.get("exchange")); String exchangeEn = textOrNull(raw.get("exchange_en"));
        String conCode = normalizeCon(conRaw, firstNonblank(exchange, exchangeEn, textOrNull(raw.get("market"))), null);
        LocalDate date = date(raw.get("trade_date"));
        if (snapshot) {
            if (date.isAfter(logicalDate) || date.isBefore(logicalDate.minusDays(SNAPSHOT_LOOKBACK_DAYS)))
                throw new IllegalStateException("D021 raw Tushare snapshot row outside 75-day frozen request");
        } else if (date.isBefore(from) || date.isAfter(to))
            throw new IllegalStateException("D021 raw Tushare historical row outside frozen bounds");
        Double weight = number(raw.get("weight"));
        if (weight == null || weight < 0 || weight > 100) throw new IllegalStateException("D021 raw Tushare weight invalid");
        var result = baseValues(indexCode, conCode, date, observedAt);
        result.put("index_name", textOrNull(raw.get("index_name")));
        result.put("index_name_en", textOrNull(raw.get("index_name_en")));
        String conName = textOrNull(raw.get("con_name"));
        if (conName == null || conName.isBlank()) conName = names.get(conCode);
        result.put("con_name", blankToNull(conName)); result.put("con_name_en", textOrNull(raw.get("con_name_en")));
        result.put("exchange", exchange); result.put("exchange_en", exchangeEn); result.put("weight", weight);
        return result;
    }

    private static Map<String,String> retainedNames(JsonNode enrichment, String expectedTarget) throws Exception {
        if (!"stock_detail_info".equals(enrichment.path("sourceDataset").asText())
                || !expectedTarget.equals(enrichment.path("targetId").asText())
                || !enrichment.path("referenceRows").isArray())
            throw new IllegalStateException("D021 Tushare name enrichment source or target differs");
        JsonNode rows = enrichment.path("referenceRows");
        if (!enrichment.path("referenceFingerprint").asText().equals(sha256(JobDefinitionJson.mapper().writeValueAsBytes(rows))))
            throw new IllegalStateException("D021 retained D002 name rows fingerprint mismatch");
        var names = new LinkedHashMap<String,String>(); var seen = new HashSet<String>();
        for (JsonNode row : rows) {
            String code = requiredText(row, "ts_code");
            String status = textOrNull(row.get("list_status"));
            if (!code.matches("[0-9]{6}\\.(?:SH|SZ|BJ)|T[0-9]{6}\\.SH")
                    || status != null && !Set.of("L", "D", "P").contains(status) || !seen.add(code))
                throw new IllegalStateException("D021 invalid or duplicate retained D002 name row");
            names.put(code, textOrNull(row.get("name")));
        }
        return Collections.unmodifiableMap(names);
    }

    private static Map<String,Object> baseValues(String indexCode, String conCode, LocalDate date, Instant observedAt) {
        var values = new LinkedHashMap<String,Object>(); values.put("index_code", indexCode); values.put("con_code", conCode);
        values.put("trade_date", date); values.put("index_name", null); values.put("index_name_en", null);
        values.put("con_name", null); values.put("con_name_en", null); values.put("exchange", null); values.put("exchange_en", null);
        values.put("weight", null); values.put("update_time", micros(observedAt)); return values;
    }

    private static List<Map<String,Object>> readTarget(JdbcTemplate jdbc, String table, boolean snapshot,
            Set<Key> keys, LocalDate from, LocalDate to) {
        var args = new ArrayList<Object>(); String where;
        if (snapshot) {
            if (keys.isEmpty()) where = "1=0";
            else {
                var groups = new TreeSet<String>();
                for (Key key : keys) groups.add(key.indexCode() + "|" + key.tradeDate());
                var clauses = new ArrayList<String>();
                for (String group : groups) {
                    String[] parts = group.split("\\|", -1); clauses.add("(index_code=? AND trade_date=cast(? AS TIMESTAMP))");
                    args.add(parts[0]); args.add(dateMicros(LocalDate.parse(parts[1])));
                }
                where = String.join(" OR ", clauses);
            }
        } else {
            where = "trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP)";
            args.add(dateMicros(from)); args.add(dateMicros(to.plusDays(1)));
        }
        String sql = "SELECT index_code,con_code,cast(trade_date AS long) AS trade_micros,index_name,index_name_en,"
                + "con_name,con_name_en,exchange,exchange_en,weight,cast(update_time AS long) AS update_micros FROM \""
                + table + "\" WHERE " + where + " ORDER BY trade_date,index_code,con_code LIMIT " + (MAX_ROWS + 1);
        var rows = jdbc.query(sql, (rs, n) -> physical(rs), args.toArray());
        if (rows.size() > MAX_ROWS) throw new IllegalStateException("D021 isolated target range exceeds independent readback cap");
        return List.copyOf(rows);
    }

    private static Map<String,Object> physical(ResultSet rs) throws SQLException {
        Object t = rs.getObject("trade_micros"), u = rs.getObject("update_micros");
        if (!(t instanceof Number trade) || !(u instanceof Number update)) throw new SQLException("D021 physical timestamps required");
        long day = trade.longValue();
        if (Math.floorMod(day, 86_400_000_000L) != 0) throw new SQLException("D021 trade_date must be UTC midnight");
        var values = new LinkedHashMap<String,Object>(); values.put("index_code", rs.getString("index_code"));
        values.put("con_code", rs.getString("con_code")); values.put("trade_date", LocalDate.ofEpochDay(Math.floorDiv(day, 86_400_000_000L)));
        values.put("index_name", rs.getString("index_name")); values.put("index_name_en", rs.getString("index_name_en"));
        values.put("con_name", rs.getString("con_name")); values.put("con_name_en", rs.getString("con_name_en"));
        values.put("exchange", rs.getString("exchange")); values.put("exchange_en", rs.getString("exchange_en"));
        Object weight = rs.getObject("weight");
        if (weight != null && (!(weight instanceof Number number) || !Double.isFinite(number.doubleValue())))
            throw new SQLException("D021 physical weight is not finite");
        values.put("weight", weight == null ? null : ((Number) weight).doubleValue()); values.put("update_time", update.longValue());
        return values;
    }

    private static Key key(Map<String,Object> values) {
        return new Key((String) values.get("index_code"), (String) values.get("con_code"), (LocalDate) values.get("trade_date"));
    }
    private static boolean same(Map<String,Object> expected, Map<String,Object> actual) {
        for (String field : PHYSICAL_FIELDS) {
            Object left = expected.get(field), right = actual.get(field);
            if (left instanceof Number l && right instanceof Number r) {
                if ("weight".equals(field)) { if (Double.compare(l.doubleValue(), r.doubleValue()) != 0) return false; }
                else if (l.longValue() != r.longValue()) return false;
            } else if (!Objects.equals(left, right)) return false;
        }
        return true;
    }
    private static void addExpected(Map<Key,Expected> expected, Map<String,Object> values, String route) {
        Key key = key(values);
        if (expected.putIfAbsent(key, new Expected(Collections.unmodifiableMap(new LinkedHashMap<>(values)), route)) != null)
            throw new IllegalStateException("D021 raw receipts contain a duplicate full business key: " + key.text());
    }

    private static boolean matchesFrozenCall(JsonNode receipt, JsonNode frozen, JsonNode params, boolean snapshot) {
        String kind = receipt.path("sourceKind").asText(); String purpose = receipt.path("purpose").asText();
        JsonNode call = receipt.path("parameters"); String code = receipt.path("indexCode").asText();
        if (snapshot) {
            if ("csindex_oss_xls".equals(kind))
                return call.path("index_code").asText().equals(code)
                        && call.path("url").asText().endsWith("/" + code + "closeweight.xls");
            LocalDate logical = LocalDate.parse(frozen.path("logicalDate").asText());
            return "tushare".equals(kind) && "latest_snapshot".equals(purpose)
                    && call.path("index_code").asText().equals(resolveIndex(code).providerCode())
                    && call.path("start_date").asText().equals(logical.minusDays(SNAPSHOT_LOOKBACK_DAYS).format(DateTimeFormatter.BASIC_ISO_DATE))
                    && call.path("end_date").asText().equals(logical.format(DateTimeFormatter.BASIC_ISO_DATE));
        }
        if (!"tushare".equals(kind) || !"history_month".equals(purpose)
                || !call.path("index_code").asText().equals(resolveIndex(code).providerCode())) return false;
        LocalDate from = LocalDate.parse(frozen.path("from").asText()), to = LocalDate.parse(frozen.path("to").asText());
        String start = call.path("start_date").asText(), end = call.path("end_date").asText();
        if (!start.matches("[0-9]{8}") || !end.matches("[0-9]{8}")) return false;
        LocalDate lo = LocalDate.parse(start, DateTimeFormatter.BASIC_ISO_DATE), hi = LocalDate.parse(end, DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate monthLo = lo.withDayOfMonth(1).isBefore(from) ? from : lo.withDayOfMonth(1);
        LocalDate monthHi = lo.withDayOfMonth(1).withDayOfMonth(lo.withDayOfMonth(1).lengthOfMonth());
        if (monthHi.isAfter(to)) monthHi = to;
        return lo.equals(monthLo) && hi.equals(monthHi) && !hi.isBefore(lo);
    }

    private static String callIdentity(JsonNode receipt) {
        JsonNode parameters = receipt.path("parameters");
        return receipt.path("indexCode").asText() + "|" + receipt.path("purpose").asText("snapshot")
                + "|" + parameters.path("start_date").asText() + "|" + parameters.path("end_date").asText();
    }
    private static void validateCallInventory(boolean snapshot, LocalDate from, LocalDate to, LocalDate logicalDate,
            Set<String> actual, int slices) {
        if (snapshot) {
            String start = logicalDate.minusDays(SNAPSHOT_LOOKBACK_DAYS).format(DateTimeFormatter.BASIC_ISO_DATE);
            String end = logicalDate.format(DateTimeFormatter.BASIC_ISO_DATE);
            Set<String> expected = INDEXES.stream().map(index -> index.route() == Route.CSINDEX_OSS_XLS
                    ? index.code() + "|snapshot||"
                    : index.code() + "|latest_snapshot|" + start + "|" + end)
                    .collect(java.util.stream.Collectors.toSet());
            if (!actual.equals(expected) || slices != expected.size()) throw new IllegalStateException("D021 snapshot lacks one of eight source receipts");
            return;
        }
        var expected = new HashSet<String>();
        for (var index : INDEXES) {
            LocalDate month = from.withDayOfMonth(1);
            while (!month.isAfter(to)) {
                LocalDate hi = month.withDayOfMonth(month.lengthOfMonth()); if (hi.isAfter(to)) hi = to;
                LocalDate lo = month.isBefore(from) ? from : month;
                expected.add(index.code() + "|history_month|" + lo.format(DateTimeFormatter.BASIC_ISO_DATE)
                        + "|" + hi.format(DateTimeFormatter.BASIC_ISO_DATE));
                month = month.plusMonths(1);
            }
        }
        if (!actual.equals(expected) || slices != expected.size())
            throw new IllegalStateException("D021 historical receipts do not cover every frozen index/month call");
    }

    private static LocalDate date(JsonNode value) {
        if (value == null || value.isNull()) throw new IllegalStateException("D021 raw trade_date missing");
        String text = value.isTextual() ? value.asText().strip() : value.isNumber() ? value.asText() : "";
        if (text.matches("[0-9]{8}")) return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
        try { return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid D021 raw trade_date", invalid); }
    }
    private static LocalDate date(String text) { return LocalDate.parse(basicDate(text), DateTimeFormatter.BASIC_ISO_DATE); }
    private static String basicDate(String text) {
        if (text == null) throw new IllegalStateException("D021 raw date missing");
        String value = text.strip();
        LocalDate parsed;
        try { parsed = value.matches("[0-9]{8}") ? LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                : LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid D021 raw date", invalid); }
        return parsed.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
    private static String normalizeIndex(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("D021 raw index code missing");
        String code = raw.strip().toUpperCase(Locale.ROOT); int dot = code.indexOf('.'); if (dot >= 0) code = code.substring(0, dot);
        if (!code.matches("[0-9]{1,6}")) throw new IllegalStateException("D021 raw index code invalid");
        return "0".repeat(6 - code.length()) + code;
    }
    private static IndexSpec resolveIndex(String raw) {
        IndexSpec index = INDEX_BY_CODE.get(normalizeIndex(raw));
        if (index == null) throw new IllegalStateException("Unknown D021 index code in raw receipt");
        return index;
    }
    private static String normalizeCon(String raw, String exchange, String exchangeEn) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("D021 raw constituent code missing");
        String code = raw.strip().toUpperCase(Locale.ROOT);
        if (code.contains(".")) {
            if (!code.matches("[0-9]{6}\\.[A-Z]{2,4}")) throw new IllegalStateException("Invalid D021 dotted constituent code");
            return code;
        }
        if (!code.matches("[0-9]{1,6}")) throw new IllegalStateException("Invalid D021 constituent code");
        code = "0".repeat(6 - code.length()) + code;
        String market = (exchange == null || exchange.isBlank() ? exchangeEn : exchange);
        String text = market == null ? "" : market.toUpperCase(Locale.ROOT);
        String suffix;
        if (text.contains("SH") || text.contains("上海")) suffix = "SH";
        else if (text.contains("SZ") || text.contains("深圳")) suffix = "SZ";
        else if (text.contains("BJ") || text.contains("北京") || code.startsWith("8") || code.startsWith("4") || code.startsWith("9")) suffix = "BJ";
        else suffix = code.startsWith("6") ? "SH" : "SZ";
        return code + "." + suffix;
    }
    private static String numericText(Cell cell, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.FORMULA) throw new IllegalStateException("D021 raw formula is rejected");
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (!Double.isFinite(value) || value != Math.rint(value) || value < 0) throw new IllegalStateException("D021 raw code cell invalid");
            return BigDecimal.valueOf(value).toBigIntegerExact().toString();
        }
        return formatter.formatCellValue(cell).strip();
    }
    private static String dateCell(Cell cell, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.FORMULA) throw new IllegalStateException("D021 raw formula is rejected");
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
            return cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        return basicDate(formatter.formatCellValue(cell));
    }
    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.FORMULA) throw new IllegalStateException("D021 raw formula is rejected");
        String value = formatter.formatCellValue(cell).strip(); return value.isEmpty() ? null : value;
    }
    private static Double parseNumber(String text) {
        if (text == null || text.isBlank()) return null;
        try { return new BigDecimal(text.replace(",", "")).doubleValue(); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("D021 raw numeric value invalid", invalid); }
    }
    private static Double number(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isNumber() || !Double.isFinite(node.doubleValue())) throw new IllegalStateException("D021 raw weight invalid");
        return node.doubleValue();
    }
    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalStateException("D021 optional text value is not text");
        return node.asText();
    }
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank())
            throw new IllegalStateException("D021 required receipt field missing: " + field);
        return value.asText();
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static String firstNonblank(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return null; }
    private static boolean blank(org.apache.poi.ss.usermodel.Row row, DataFormatter formatter) {
        for (int i = 0; i < 10; i++) { String text = cellText(row.getCell(i), formatter); if (text != null && !text.isBlank()) return false; }
        return true;
    }
    private static byte[] readBounded(Path path, int max) throws Exception {
        if (!Files.isRegularFile(path) || Files.size(path) > max) throw new IllegalStateException("D021 evidence missing or oversized");
        return Files.readAllBytes(path);
    }
    private static Path evidencePath(String text, Path root) throws Exception {
        if (text == null || text.isBlank()) throw new IllegalStateException("D021 evidence path missing");
        Path path = Path.of(text).toAbsolutePath().normalize().toRealPath();
        if (!path.startsWith(root)) throw new IllegalStateException("D021 evidence path escapes source root");
        return path;
    }
    private static long micros(Instant value) {
        return Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000L), value.getNano() / 1000L);
    }
    private static long dateMicros(LocalDate date) { return Math.multiplyExact(date.toEpochDay(), 86_400_000_000L); }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
