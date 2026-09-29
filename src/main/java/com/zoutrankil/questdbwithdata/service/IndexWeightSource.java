package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.questdbwithdata.domain.IndexWeight;
import com.zoutrankil.questdbwithdata.domain.IndexWeightKey;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.mapper.IndexWeightMapper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** D021's two observed provider routes, with immutable raw/normalized receipts and strict caps. */
public final class IndexWeightSource {
    public static final String TUSHARE_ENDPOINT = "index_weight";
    public static final int TUSHARE_ROW_CAP = 4_000;
    public static final int MAX_BACKFILL_DAYS = 92;
    public static final int MAX_MONTH_CALLS = 32;
    public static final int MAX_RUN_ROWS = MAX_MONTH_CALLS * TUSHARE_ROW_CAP;
    public static final int MAX_XLS_BYTES = 16 * 1024 * 1024;
    public static final int MAX_RECEIPT_BYTES = 32 * 1024 * 1024;
    public static final int MAX_ROWS_PER_CSI_FILE = 2_000;
    public static final int REFRESH_LOOKBACK_DAYS = 75;
    public static final Duration SNAPSHOT_TOTAL_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration PER_CSI_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration BACKFILL_TOTAL_TIMEOUT = Duration.ofMinutes(20);
    private static final String CSI_URL = "https://oss-ch.csindex.com.cn/static/html/csindex/public/uploads/file/autofile/closeweight/";
    private static final int CSI_COLUMNS = 10;
    private static final List<String> CSI_HEADERS = List.of("日期", "指数代码", "指数名称", "指数英文名称",
            "成分券代码", "成分券名称", "成分券英文名称", "交易所", "交易所英文名称", "权重");
    private static final List<String> CSI_HEADERS_BILINGUAL = List.of("日期Date", "指数代码 Index Code", "指数名称 Index Name",
            "指数英文名称Index Name(Eng)", "成份券代码Constituent Code", "成份券名称Constituent Name",
            "成份券英文名称Constituent Name(Eng)", "交易所Exchange", "交易所英文名称Exchange(Eng)", "权重(%)weight");
    private static final List<String> TUSHARE_FIELDS = List.of("index_code", "con_code", "trade_date", "weight");
    private static final PageContract TUSHARE_CONTRACT = new PageContract(TUSHARE_ENDPOINT, TUSHARE_FIELDS,
            List.of("index_code", "con_code", "trade_date"), Set.of("index_code", "start_date", "end_date"),
            PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE, null, null,
            TUSHARE_ROW_CAP, TUSHARE_ROW_CAP, 1, TUSHARE_ROW_CAP,
            "Python calls pro.index_weight once per index/month for explicit history and once for the 399673 75-day snapshot. "
                    + "Java uses a one-response 4000-row guard; exact-cap responses fail closed because this endpoint route has no observed offset paging here.");

    public record Result(List<SyncJobRunner.Page<IndexWeight>> pages,
            List<Map<String,Object>> calls, int sourceRows, boolean complete) {
        public Result { pages = List.copyOf(pages); calls = List.copyOf(calls); }
    }
    public record NameEnrichment(String targetId, String fingerprint, Map<String,String> names,
            List<Map<String,Object>> referenceRows) {
        public NameEnrichment {
            if (targetId == null || !targetId.matches("static-v2-[0-9a-f]{64}")
                    || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Frozen D002 target and reference fingerprint required");
            names = Collections.unmodifiableMap(new LinkedHashMap<>(names));
            referenceRows = referenceRows.stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        }
    }
    @FunctionalInterface public interface NameLookup {
        NameEnrichment resolve(String expectedTargetId, Set<String> constituentCodes) throws Exception;
    }
    private record Call(String indexCode, String route, Map<String,Object> parameters,
            String receipt, String fingerprint, int responseRows, List<IndexWeight> selectedRows) {
        private Call { selectedRows = List.copyOf(selectedRows); }
    }
    private record Captured(PageExecutor.Page page) {}

    private final TusharePageService tushare;
    private final IndexWeightMapper mapper;
    private final Path evidenceRoot;
    private final HttpClient http;
    private final NameLookup nameLookup;

    public IndexWeightSource(TusharePageService tushare, IndexWeightMapper mapper, Path evidenceRoot, NameLookup nameLookup) {
        this.tushare = Objects.requireNonNull(tushare); this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
        this.nameLookup = Objects.requireNonNull(nameLookup);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Fetch all source scopes before returning any runner pages, so a later failed code never starts writes. */
    public Result fetch(SyncJobDefinition.FrozenRequest request, BooleanSupplier cancelled) throws Exception {
        validateRequest(request);
        Files.createDirectories(evidenceRoot);
        Instant observedAt = Instant.parse((String) request.parameters().get("observedAt"));
        long budgetNanos = request.mode() == SyncJobDefinition.Mode.SNAPSHOT
                ? SNAPSHOT_TOTAL_TIMEOUT.toNanos() : BACKFILL_TOTAL_TIMEOUT.toNanos();
        long deadline = Math.addExact(System.nanoTime(), budgetNanos);
        BooleanSupplier stopped = () -> cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()
                || System.nanoTime() >= deadline;
        var calls = new ArrayList<Call>();
        if (request.mode() == SyncJobDefinition.Mode.SNAPSHOT) {
            for (var index : IndexWeightUniverse.INDEXES) {
                check(stopped, "D021 snapshot source cancelled or reached its 90-second total deadline");
                if (index.route() == IndexWeightUniverse.Route.CSINDEX_OSS_XLS)
                    calls.add(fetchCsindex(index, observedAt, stopped, deadline));
                else {
                    LocalDate start = request.logicalDate().minusDays(REFRESH_LOOKBACK_DAYS);
                    calls.add(fetchTushare(index, start, request.logicalDate(), observedAt,
                            "latest_snapshot", (String) request.parameters().get("stockDetailTargetId"), stopped, deadline));
                }
            }
        } else if (request.mode() == SyncJobDefinition.Mode.BACKFILL) {
            for (var index : IndexWeightUniverse.INDEXES) {
                for (var month : months(request.from(), request.to())) {
                    check(stopped, "D021 history fetch cancelled or reached its 20-minute total deadline");
                    LocalDate from = month.atDay(1).isBefore(request.from()) ? request.from() : month.atDay(1);
                    LocalDate to = month.atEndOfMonth().isAfter(request.to()) ? request.to() : month.atEndOfMonth();
                    calls.add(fetchTushare(index, from, to, observedAt, "history_month",
                            (String) request.parameters().get("stockDetailTargetId"), stopped, deadline));
                }
            }
        } else throw new IllegalArgumentException("D021 only supports snapshot and bounded history backfill");

        var pages = new ArrayList<SyncJobRunner.Page<IndexWeight>>();
        int rows = 0;
        var callEvidence = new ArrayList<Map<String,Object>>(calls.size());
        for (var call : calls) {
            if (call.selectedRows().stream().anyMatch(row -> row.tradeDate().isAfter(request.logicalDate())))
                throw new IllegalStateException("D021 provider row exceeds frozen logical date");
            callEvidence.add(Map.of("indexCode", call.indexCode(), "route", call.route(),
                    "parameters", call.parameters(), "responseRows", call.responseRows(),
                    "selectedRows", call.selectedRows().size(), "fingerprint", call.fingerprint(),
                    "responseEvidence", call.receipt()));
            if (call.selectedRows().size() > 10_000) throw new IllegalStateException("D021 runner page exceeds 10000 rows");
            var ordered = call.selectedRows().stream().sorted(Comparator.comparing(IndexWeight::indexCode)
                    .thenComparing(IndexWeight::tradeDate).thenComparing(IndexWeight::conCode)).toList();
            pages.add(new SyncJobRunner.Page<>(ordered, call.fingerprint(), call.receipt(),
                    call.route() + ":" + call.indexCode() + ":" + call.parameters().get("start_date") + ":" + call.parameters().get("end_date")));
            rows = Math.addExact(rows, ordered.size());
        }
        if (pages.size() > MAX_MONTH_CALLS || rows > MAX_RUN_ROWS)
            throw new IllegalArgumentException("D021 request exceeds local source page/row budget");
        return new Result(pages, callEvidence, rows, true);
    }

    private Call fetchCsindex(IndexWeightUniverse.Index index, Instant observedAt,
            BooleanSupplier stopped, long deadline) throws Exception {
        String url = CSI_URL + index.code() + "closeweight.xls";
        byte[] bytes = null;
        Map<String,Object> parameters = Map.of("url", url, "index_code", index.code());
        try {
            check(stopped, "D021 CSIndex request cancelled before send");
            Duration timeout = boundedTimeout(PER_CSI_TIMEOUT, deadline);
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Accept", "application/vnd.ms-excel,application/octet-stream")
                    .header("User-Agent", "QuestDBWithData-D021/1")
                    .GET().build();
            var pending = http.sendAsync(request,
                    ignored -> new LimitedBodySubscriber(MAX_XLS_BYTES, stopped));
            HttpResponse<byte[]> response = awaitBody(pending, deadline, stopped);
            bytes = response.body();
            if (response.statusCode() != 200)
                throw new IllegalStateException("D021 CSIndex HTTP status " + response.statusCode());
            if (bytes.length == 0) throw new IllegalStateException("D021 CSIndex returned an empty workbook");
            String rawSha = sha256(bytes);
            String rawFile = "csindex-" + index.code() + "-" + rawSha + ".xls";
            writeImmutable(evidenceRoot.resolve(rawFile), bytes);
            List<Map<String,JsonNode>> rawRows = parseWorkbook(bytes);
            var selected = new ArrayList<IndexWeight>(rawRows.size());
            LocalDate snapshotDate = null;
            for (var raw : rawRows) {
                var row = mapper.fromSource(mapper.dto(raw), observedAt);
                if (!row.indexCode().equals(index.code())) throw new IllegalArgumentException("CSIndex workbook index_code differs from requested code");
                if (snapshotDate == null) snapshotDate = row.tradeDate();
                else if (!snapshotDate.equals(row.tradeDate())) throw new IllegalArgumentException("CSIndex workbook contains multiple trade_date snapshots");
                selected.add(row);
            }
            validateSnapshot(index, selected);
            var sorted = sortUnique(selected);
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("sourceKind", "csindex_oss_xls"); body.put("endpoint", "index_stock_cons_weight_csindex");
            body.put("sourceContractVersion", 1); body.put("indexCode", index.code()); body.put("route", index.route().name());
            body.put("parameters", parameters); body.put("fields", List.of("trade_date", "index_code", "index_name", "index_name_en",
                    "con_code", "con_name", "con_name_en", "exchange", "exchange_en", "weight"));
            body.put("sourceHeaders", readHeaders(bytes));
            body.put("rawFile", rawFile); body.put("rawSha256", rawSha); body.put("rawBytes", bytes.length);
            body.put("rawRows", rawRows); body.put("normalizedRows", normalized(sorted));
            body.put("returnedRows", sorted.size()); body.put("sourceComplete", true);
            body.put("snapshotTradeDate", snapshotDate); body.put("expectedMemberCount", index.expectedMembers());
            body.put("observedAt", observedAt);
            return persistCall(index.code(), index.route().name(), parameters, body, bytes.length, rawRows.size(), sorted);
        } catch (Exception failure) {
            persistIncomplete("csindex_oss_xls", index.code(), parameters, bytes, failure);
            throw failure;
        }
    }

    private Call fetchTushare(IndexWeightUniverse.Index index, LocalDate from, LocalDate to, Instant observedAt,
            String purpose, String stockDetailTargetId, BooleanSupplier stopped, long deadline) throws Exception {
        String start = from.format(DateTimeFormatter.BASIC_ISO_DATE), end = to.format(DateTimeFormatter.BASIC_ISO_DATE);
        var parameters = new LinkedHashMap<String,Object>();
        parameters.put("index_code", index.tushareCode()); parameters.put("start_date", start); parameters.put("end_date", end);
        var captured = new Captured[1];
        try {
            Duration remain = Duration.ofNanos(Math.max(1L, deadline - System.nanoTime()));
            BooleanSupplier boundedCancellation = () -> stopped.getAsBoolean() || Thread.currentThread().isInterrupted();
            var complete = new PageExecutor().execute(TUSHARE_CONTRACT, parameters,
                    params -> {
                        if (Duration.ofNanos(Math.max(0L, deadline - System.nanoTime())).isZero())
                            throw new java.util.concurrent.TimeoutException("D021 source deadline elapsed");
                        var page = tushare.fetcher(TUSHARE_CONTRACT, boundedCancellation).fetch(params);
                        captured[0] = new Captured(page); return page;
                    }, (page, receipt) -> {}, raw -> validateRawTushare(raw, index, from, to), boundedCancellation);
            if (complete.pages() != 1 || captured[0] == null)
                throw new IllegalStateException("D021 Tushare history call must be exactly one complete response");
            var rawRows = captured[0].page().rows();
            var mapped = new ArrayList<IndexWeight>(rawRows.size());
            for (var raw : rawRows) mapped.add(mapper.fromSource(mapper.dto(raw), observedAt));
            var codes = mapped.stream().map(IndexWeight::conCode).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            NameEnrichment enrichment = nameLookup.resolve(stockDetailTargetId, Set.copyOf(codes));
            if (!stockDetailTargetId.equals(enrichment.targetId()))
                throw new IllegalStateException("D021 name-enrichment target identity changed during source fetch");
            mapped.replaceAll(row -> row.conName() != null && !row.conName().isBlank() ? row
                    : withConName(row, enrichment.names().get(row.conCode())));
            List<IndexWeight> selected;
            LocalDate latest = null;
            if (purpose.equals("latest_snapshot")) {
                latest = mapped.stream().map(IndexWeight::tradeDate).max(LocalDate::compareTo)
                        .orElseThrow(() -> new IllegalStateException("D021 Tushare latest snapshot is empty"));
                LocalDate selectedDate = latest;
                selected = mapped.stream().filter(row -> row.tradeDate().equals(selectedDate)).toList();
                validateSnapshot(index, selected);
            } else {
                selected = mapped;
                validateHistoryGroups(index, selected);
            }
            var sorted = sortUnique(selected);
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("sourceKind", "tushare"); body.put("endpoint", TUSHARE_ENDPOINT);
            body.put("sourceContractVersion", 1); body.put("purpose", purpose); body.put("indexCode", index.code());
            body.put("providerIndexCode", index.tushareCode()); body.put("route", index.route().name());
            body.put("parameters", parameters); body.put("fields", TUSHARE_FIELDS);
            body.put("rawRows", rawRows); body.put("normalizedRows", normalized(sorted));
            body.put("responseRows", complete.rows()); body.put("returnedRows", sorted.size());
            body.put("nameEnrichment", Map.of("sourceDataset", "stock_detail_info", "targetId", enrichment.targetId(),
                    "referenceFingerprint", enrichment.fingerprint(), "referenceRows", enrichment.referenceRows()));
            body.put("sourceComplete", true); body.put("sourceVersion", complete.sourceVersion());
            body.put("latestTradeDate", latest); body.put("expectedMemberCount", index.expectedMembers());
            body.put("observedAt", observedAt);
            return persistCall(index.code(), "tushare_" + purpose, parameters, body, 0, complete.rows(), sorted);
        } catch (Exception failure) {
            persistIncomplete("tushare_" + purpose, index.code(), parameters,
                    captured[0] == null ? null : JobDefinitionJson.mapper().writeValueAsBytes(captured[0].page().rows()), failure);
            throw failure;
        }
    }

    private Call persistCall(String indexCode, String route, Map<String,Object> parameters,
            Map<String,Object> body, int rawBytes, int responseRows, List<IndexWeight> rows) throws Exception {
        var json = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        byte[] bytes = json.writeValueAsBytes(body);
        requireReceiptSize(bytes);
        String fingerprint = sha256(bytes);
        Path receipt = evidenceRoot.resolve("complete-" + route.toLowerCase(java.util.Locale.ROOT) + "-"
                + indexCode + "-" + fingerprint + ".json");
        writeImmutable(receipt, bytes);
        return new Call(indexCode, route, Map.copyOf(parameters), receipt.toAbsolutePath().normalize().toString(),
                fingerprint, responseRows, rows);
    }

    private void validateRawTushare(Map<String,JsonNode> row, IndexWeightUniverse.Index index,
            LocalDate from, LocalDate to) {
        var mapped = mapper.fromSource(mapper.dto(row), Instant.EPOCH);
        if (!mapped.indexCode().equals(index.code()) || mapped.tradeDate().isBefore(from) || mapped.tradeDate().isAfter(to))
            throw new IllegalArgumentException("D021 Tushare row differs from frozen index/month scope");
    }

    private List<Map<String,JsonNode>> parseWorkbook(byte[] bytes) throws Exception {
        var json = JobDefinitionJson.mapper();
        var rows = new ArrayList<Map<String,JsonNode>>();
        try (var workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 1) throw new IllegalArgumentException("D021 CSIndex workbook must contain exactly one sheet");
            var sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() < 2 || sheet.getLastRowNum() > MAX_ROWS_PER_CSI_FILE + 1)
                throw new IllegalArgumentException("D021 CSIndex workbook rows outside the 1..2000 data cap");
            var formatter = new DataFormatter(java.util.Locale.ROOT);
            var header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null || header.getLastCellNum() != CSI_COLUMNS)
                throw new IllegalArgumentException("D021 CSIndex workbook requires exactly ten observed columns");
            var actualHeaders = new ArrayList<String>(CSI_COLUMNS);
            for (int column = 0; column < CSI_COLUMNS; column++) {
                actualHeaders.add(cellText(header.getCell(column), formatter, false));
            }
            if (!actualHeaders.equals(CSI_HEADERS) && !actualHeaders.equals(CSI_HEADERS_BILINGUAL))
                throw new IllegalArgumentException("D021 CSIndex XLS header/order is not a supported observed schema");
            for (int number = header.getRowNum() + 1; number <= sheet.getLastRowNum(); number++) {
                var row = sheet.getRow(number);
                if (row == null || blankRow(row, formatter)) continue;
                if (row.getLastCellNum() > CSI_COLUMNS) throw new IllegalArgumentException("Unexpected D021 CSIndex XLS extra column");
                String date = dateCell(row.getCell(0), formatter);
                String indexCode = numericText(row.getCell(1), formatter);
                String indexName = cellText(row.getCell(2), formatter, true);
                String indexNameEn = cellText(row.getCell(3), formatter, true);
                String conCode = numericText(row.getCell(4), formatter);
                String conName = cellText(row.getCell(5), formatter, true);
                String conNameEn = cellText(row.getCell(6), formatter, true);
                String exchange = cellText(row.getCell(7), formatter, true);
                String exchangeEn = cellText(row.getCell(8), formatter, true);
                Double weight = decimalCell(row.getCell(9), formatter);
                if (date == null || indexCode == null || conCode == null || weight == null)
                    throw new IllegalArgumentException("D021 CSIndex XLS row omits a required key or weight");
                var value = new LinkedHashMap<String,JsonNode>();
                value.put("trade_date", json.getNodeFactory().textNode(date));
                value.put("index_code", json.getNodeFactory().textNode(indexCode));
                value.put("index_name", nullableText(indexName)); value.put("index_name_en", nullableText(indexNameEn));
                value.put("con_code", json.getNodeFactory().textNode(conCode));
                value.put("con_name", nullableText(conName)); value.put("con_name_en", nullableText(conNameEn));
                value.put("exchange", nullableText(exchange)); value.put("exchange_en", nullableText(exchangeEn));
                value.put("weight", json.getNodeFactory().numberNode(weight));
                rows.add(Map.copyOf(value));
            }
        }
        if (rows.isEmpty() || rows.size() > MAX_ROWS_PER_CSI_FILE)
            throw new IllegalArgumentException("D021 CSIndex response empty or exceeds 2000-row finite cap");
        return List.copyOf(rows);
    }

    private static List<String> readHeaders(byte[] bytes) throws Exception {
        try (var workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 1) throw new IllegalArgumentException("D021 CSIndex workbook must contain exactly one sheet");
            var header = workbook.getSheetAt(0).getRow(workbook.getSheetAt(0).getFirstRowNum());
            if (header == null || header.getLastCellNum() != CSI_COLUMNS)
                throw new IllegalArgumentException("D021 CSIndex workbook requires exactly ten observed columns");
            var formatter = new DataFormatter(java.util.Locale.ROOT);
            var headers = new ArrayList<String>(CSI_COLUMNS);
            for (int column = 0; column < CSI_COLUMNS; column++)
                headers.add(cellText(header.getCell(column), formatter, false));
            return List.copyOf(headers);
        }
    }

    private static String dateCell(Cell cell, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.FORMULA) throw new IllegalArgumentException("D021 source workbook formulas are not accepted");
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
            return cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        return IndexWeightMapper.normalizeDateText(formatter.formatCellValue(cell));
    }
    private static String numericText(Cell cell, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.FORMULA) throw new IllegalArgumentException("D021 source workbook formulas are not accepted");
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (!Double.isFinite(value) || value < 0 || value != Math.rint(value))
                throw new IllegalArgumentException("D021 code cell must be a nonnegative integer");
            return BigDecimal.valueOf(value).toBigIntegerExact().toString();
        }
        return formatter.formatCellValue(cell).strip();
    }
    private static String cellText(Cell cell, DataFormatter formatter, boolean optional) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return optional ? null : "";
        if (cell.getCellType() == CellType.FORMULA) throw new IllegalArgumentException("D021 source workbook formulas are not accepted");
        String text = formatter.formatCellValue(cell).strip();
        return text.isEmpty() && optional ? null : text;
    }
    private static Double decimalCell(Cell cell, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.FORMULA) throw new IllegalArgumentException("D021 source workbook formulas are not accepted");
        String text = formatter.formatCellValue(cell).strip();
        if (text.isEmpty()) return null;
        try {
            double value = cell.getCellType() == CellType.NUMERIC ? cell.getNumericCellValue()
                    : new BigDecimal(text.replace(",", "")).doubleValue();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("D021 weight must be finite");
            return value;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid D021 weight cell", invalid); }
    }
    private static boolean blankRow(org.apache.poi.ss.usermodel.Row row, DataFormatter formatter) {
        for (int column = 0; column < CSI_COLUMNS; column++) {
            String value = cellText(row.getCell(column), formatter, true);
            if (value != null && !value.isBlank()) return false;
        }
        return true;
    }
    private static JsonNode nullableText(String value) {
        return value == null ? JsonNodeFactory.instance.nullNode() : JsonNodeFactory.instance.textNode(value);
    }

    private List<IndexWeight> sortUnique(List<IndexWeight> rows) {
        var sorted = rows.stream().sorted(Comparator.comparing(IndexWeight::indexCode)
                .thenComparing(IndexWeight::tradeDate).thenComparing(IndexWeight::conCode)).toList();
        var keys = new HashSet<IndexWeightKey>();
        for (var row : sorted) if (!keys.add(row.key()))
            throw new IllegalArgumentException("D021 source contains a duplicate complete business key");
        return List.copyOf(sorted);
    }
    private static void validateSnapshot(IndexWeightUniverse.Index index, List<IndexWeight> rows) {
        if (rows.size() != index.expectedMembers())
            throw new IllegalArgumentException("D021 snapshot member count differs from source contract for " + index.code()
                    + ": " + rows.size() + " != " + index.expectedMembers());
        if (rows.stream().map(IndexWeight::tradeDate).distinct().count() != 1
                || rows.stream().anyMatch(row -> !row.indexCode().equals(index.code())))
            throw new IllegalArgumentException("D021 source does not contain one complete index/date snapshot");
    }
    private static void validateHistoryGroups(IndexWeightUniverse.Index index, List<IndexWeight> rows) {
        var counts = new HashMap<LocalDate,Integer>();
        for (var row : rows) {
            if (!row.indexCode().equals(index.code())) throw new IllegalArgumentException("D021 history source index mismatch");
            counts.merge(row.tradeDate(), 1, Integer::sum);
        }
        for (var entry : counts.entrySet()) if (entry.getValue() != index.expectedMembers())
            throw new IllegalArgumentException("D021 history trade_date member count differs for " + index.code()
                    + "@" + entry.getKey() + ": " + entry.getValue() + " != " + index.expectedMembers());
    }
    private List<Map<String,Object>> normalized(List<IndexWeight> rows) {
        return rows.stream().map(row -> mapper.values(row).asMap()).toList();
    }
    private static List<YearMonth> months(LocalDate from, LocalDate to) {
        var months = new ArrayList<YearMonth>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) months.add(month);
        if (months.isEmpty() || months.size() > MAX_MONTH_CALLS / IndexWeightUniverse.INDEXES.size())
            throw new IllegalArgumentException("D021 backfill expands beyond 4 calendar months / 32 API calls");
        return List.copyOf(months);
    }
    private static void validateRequest(SyncJobDefinition.FrozenRequest request) {
        if (request == null || !request.definition().equals(IndexWeightSyncJobOwner.DEFINITION)
                || request.definition().datasetVersion() != 1
                || !Set.of(SyncJobDefinition.Mode.SNAPSHOT, SyncJobDefinition.Mode.BACKFILL).contains(request.mode()))
            throw new IllegalArgumentException("Frozen D021 snapshot or backfill request required");
        String text = (String) request.parameters().get("observedAt");
        Instant observed;
        try { observed = Instant.parse(text); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Canonical D021 observedAt required", invalid); }
        com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.requirePrecision(observed,
                com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.Precision.MICROS);
        if (!observed.toString().equals(text)) throw new IllegalArgumentException("Canonical D021 observedAt required");
        if (request.mode() == SyncJobDefinition.Mode.SNAPSHOT
                && (request.from() != null || request.to() != null))
            throw new IllegalArgumentException("D021 snapshot uses provider latest-date scope, not fake trade_date bounds");
        if (request.mode() == SyncJobDefinition.Mode.BACKFILL) {
            long days = ChronoUnit.DAYS.between(request.from(), request.to()) + 1;
            if (days < 1 || days > MAX_BACKFILL_DAYS || request.to().isAfter(request.logicalDate()))
                throw new IllegalArgumentException("D021 backfill must be bounded to 1..92 days and not exceed logicalDate");
        }
        IndexWeightJobService.requireIsolatedTargetParameter(request.parameters());
        Object stockTarget = request.parameters().get("stockDetailTargetId");
        if (!(stockTarget instanceof String stockTargetText) || !stockTargetText.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D021 D002 name-reference target identity required");
    }
    private static IndexWeight withConName(IndexWeight row, String conName) {
        return new IndexWeight(row.key(), row.indexName(), row.indexNameEn(), conName, row.conNameEn(),
                row.exchange(), row.exchangeEn(), row.weight(), row.updateTime());
    }
    private static HttpResponse<byte[]> awaitBody(CompletableFuture<HttpResponse<byte[]>> pending,
            long deadline, BooleanSupplier stopped) throws Exception {
        try {
            while (true) {
                check(stopped, "D021 CSIndex response cancelled or reached its source deadline");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("D021 total source deadline elapsed during XLS body read");
                try { return pending.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); }
                catch (TimeoutException pollExpired) {
                    if (System.nanoTime() >= deadline) throw new TimeoutException("D021 total source deadline elapsed during XLS body read");
                }
            }
        } catch (InterruptedException interrupted) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw interrupted;
        } catch (Exception failure) {
            pending.cancel(true);
            if (failure instanceof ExecutionException wrapped && wrapped.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    /** Bounds bytes while the asynchronous response body is arriving, not after it has been buffered. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final BooleanSupplier cancelled;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int received;

        private LimitedBodySubscriber(int maxBytes, BooleanSupplier cancelled) {
            this.maxBytes = maxBytes; this.cancelled = cancelled;
            this.output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        }
        @Override public CompletableFuture<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) { subscription.cancel(); return; }
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            try {
                check(cancelled, "D021 CSIndex response cancelled or reached its source deadline");
                for (ByteBuffer buffer : buffers) {
                    int count = buffer.remaining();
                    if (count > maxBytes - received)
                        throw new IllegalArgumentException("D021 CSIndex XLS exceeds 16 MiB streaming cap");
                    byte[] chunk = new byte[count]; buffer.get(chunk); output.write(chunk); received += count;
                }
                subscription.request(1);
            } catch (Exception failure) {
                subscription.cancel(); body.completeExceptionally(failure);
            }
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() { body.complete(output.toByteArray()); }
    }
    private static Duration boundedTimeout(Duration preferred, long deadline) throws java.util.concurrent.TimeoutException {
        long remain = deadline - System.nanoTime();
        if (remain <= 0) throw new java.util.concurrent.TimeoutException("D021 total source deadline elapsed");
        return Duration.ofNanos(Math.min(preferred.toNanos(), remain));
    }
    private static void check(BooleanSupplier stopped, String message) {
        if (stopped.getAsBoolean()) throw new CancellationException(message);
    }
    private void persistIncomplete(String route, String indexCode, Map<String,Object> parameters,
            byte[] raw, Exception failure) throws Exception {
        var body = new LinkedHashMap<String,Object>(); body.put("sourceKind", route); body.put("indexCode", indexCode);
        body.put("parameters", parameters); body.put("sourceComplete", false);
        body.put("failureCategory", failure.getClass().getSimpleName()); body.put("failure", failure.getMessage());
        if (raw != null && raw.length > 0) {
            String name = "incomplete-" + route + "-" + indexCode + "-" + sha256(raw) + ".bin";
            writeImmutable(evidenceRoot.resolve(name), raw); body.put("rawFile", name);
            body.put("rawSha256", sha256(raw)); body.put("rawBytes", raw.length);
        }
        byte[] bytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(body);
        requireReceiptSize(bytes); writeImmutable(evidenceRoot.resolve("incomplete-" + UUID.randomUUID() + ".json"), bytes);
    }
    private static void requireReceiptSize(byte[] bytes) {
        if (bytes.length > MAX_RECEIPT_BYTES) throw new IllegalArgumentException("D021 receipt exceeds 32 MiB cap");
    }
    private static void writeImmutable(Path path, byte[] bytes) throws Exception {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        try { Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
        catch (java.nio.file.FileAlreadyExistsException exists) {
            if (!MessageDigest.isEqual(Files.readAllBytes(path), bytes))
                throw new IllegalStateException("D021 immutable evidence path conflicts with existing bytes", exists);
        }
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
