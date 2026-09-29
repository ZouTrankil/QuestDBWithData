package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.IndexDailyMarketMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One code × bounded date-range source request, matching the Python connector's two routes. */
public final class IndexDailyMarketSource {
    public static final int MAX_WINDOW_DAYS = 366;
    public static final int API_ROW_CAP = 4000;
    public static final int MAX_EVIDENCE_BYTES = 32 * 1024 * 1024;
    public static final List<String> INDEX_DAILY_FIELDS = IndexDailyMarketMapper.INDEX_DAILY_FIELDS;
    public static final List<String> SW_DAILY_FIELDS = IndexDailyMarketMapper.SW_DAILY_FIELDS;
    public static final PageContract INDEX_DAILY = new PageContract("index_daily", INDEX_DAILY_FIELDS,
            List.of("ts_code", "trade_date"), Set.of("ts_code", "start_date", "end_date"),
            PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE, null, null,
            API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Tushare index_daily supports ts_code/start_date/end_date. No server maximum is published on doc95; a single-code 366-calendar-day bound admits at most 366 daily identities, below the 4000-row client guard.");
    public static final PageContract SW_DAILY = new PageContract("sw_daily", SW_DAILY_FIELDS,
            List.of("ts_code", "trade_date"), Set.of("ts_code", "start_date", "end_date"),
            PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE, null, null,
            API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Tushare sw_daily doc327 declares a 4000-row request maximum; one-code windows are limited to 366 calendar days and exact-cap responses fail closed.");

    private final TusharePageService pages;
    private final IndexDailyMarketMapper mapper;
    private final Path evidenceRoot;
    public IndexDailyMarketSource(TusharePageService pages, IndexDailyMarketMapper mapper, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages); this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<IndexDailyMarket> fetch(String tsCode, LocalDate from, LocalDate to,
            Instant observedAt, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(observedAt);
        var index = requireIndex(tsCode);
        requireWindow(from, to); com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.requirePrecision(
                observedAt, com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.Precision.MICROS);
        String start = from.format(DateTimeFormatter.BASIC_ISO_DATE), end = to.format(DateTimeFormatter.BASIC_ISO_DATE);
        var parameters = new LinkedHashMap<String,Object>();
        parameters.put("ts_code", index.tsCode()); parameters.put("start_date", start); parameters.put("end_date", end);
        var contract = contract(index.route()); var raw = new ArrayList<Map<String,JsonNode>>();
        var captured = new PageExecutor.Page[1]; var fetcher = pages.fetcher(contract, cancelled);
        PageExecutor.Completed complete;
        try {
            complete = new PageExecutor().execute(contract, parameters, request -> {
                var response = fetcher.fetch(request); captured[0] = response; return response;
            }, (page, receipt) -> raw.addAll(page.rows()),
                    row -> validate(row, index, from, to, observedAt), cancelled);
        } catch (Exception failure) {
            if (captured[0] != null) try { persistIncomplete(index, from, to, observedAt, parameters, captured[0], failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
        if (complete.pages() != 1 || complete.rows() != raw.size() || captured[0] == null)
            throw new IllegalStateException("index_daily_market requires exactly one complete source response per code/range");
        raw.sort(Comparator.comparing((Map<String,JsonNode> row) -> row.get("ts_code").asText())
                .thenComparing(row -> row.get("trade_date").asText()));
        var typed = raw.stream().map(row -> mapper.dto(row, index)).map(dto -> mapper.fromSource(dto, index, observedAt)).toList();
        var keys = new HashSet<IndexDailyMarketKey>();
        for (var row : typed) if (!row.tsCode().equals(index.tsCode()) || row.tradeDate().isBefore(from)
                || row.tradeDate().isAfter(to) || !keys.add(row.key()))
            throw new IllegalStateException("index_daily_market source contains duplicate or out-of-range full key");

        var body = new LinkedHashMap<String,Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", IndexDailyMarketUniverse.endpoint(index.route()));
        body.put("route", index.route()); body.put("tsCode", index.tsCode()); body.put("from", from); body.put("to", to);
        body.put("observedAt", observedAt); body.put("parameters", parameters);
        body.put("fields", fields(index.route())); body.put("normalization", index.route() == IndexDailyMarketUniverse.Route.SW_DAILY
                ? Map.of("pct_change", "pct_chg") : Map.of());
        body.put("rawRows", raw); body.put("returnedRows", typed.size()); body.put("sourceComplete", true);
        body.put("sourceRowCap", API_ROW_CAP); body.put("sourceVersion", complete.sourceVersion());
        byte[] bytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(body); requireEvidenceSize(bytes);
        Files.createDirectories(evidenceRoot);
        Path receipt = evidenceRoot.resolve("index-daily-" + index.tsCode().replace('.', '-') + "-"
                + start + "-" + end + "-" + UUID.randomUUID() + ".json");
        Files.write(receipt, bytes, StandardOpenOption.CREATE_NEW);
        return new SyncJobRunner.Page<>(typed, sha256(bytes), receipt.toString(), index.tsCode());
    }

    /** Reopens a bounded immutable source receipt against the frozen job identity. */
    public static SyncJobRunner.Page<IndexDailyMarket> reopen(Path receipt, String expectedFingerprint,
            String expectedCode, LocalDate expectedFrom, LocalDate expectedTo, Instant expectedObservedAt) throws Exception {
        Objects.requireNonNull(receipt); var index = requireIndex(expectedCode); requireWindow(expectedFrom, expectedTo);
        if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(receipt) || Files.size(receipt) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("Bounded index_daily_market receipt and SHA-256 required");
        byte[] bytes = Files.readAllBytes(receipt);
        if (!sha256(bytes).equals(expectedFingerprint)) throw new IllegalStateException("index_daily_market source receipt fingerprint changed");
        var json = JobDefinitionJson.mapper(); var proof = json.readTree(bytes);
        String endpoint = IndexDailyMarketUniverse.endpoint(index.route());
        if (!"tushare".equals(proof.path("sourceKind").asText()) || !endpoint.equals(proof.path("endpoint").asText())
                || !index.route().name().equals(proof.path("route").asText()) || !expectedCode.equals(proof.path("tsCode").asText())
                || !expectedFrom.toString().equals(proof.path("from").asText()) || !expectedTo.toString().equals(proof.path("to").asText())
                || !expectedObservedAt.toString().equals(proof.path("observedAt").asText())
                || !json.valueToTree(fields(index.route())).equals(proof.path("fields"))
                || !proof.path("sourceComplete").asBoolean(false) || !proof.path("rawRows").isArray()
                || proof.path("returnedRows").asInt(-1) != proof.path("rawRows").size()
                || proof.path("sourceRowCap").asInt(-1) != API_ROW_CAP)
            throw new IllegalStateException("index_daily_market source receipt scope/completion differs");
        var start = expectedFrom.format(DateTimeFormatter.BASIC_ISO_DATE);
        var end = expectedTo.format(DateTimeFormatter.BASIC_ISO_DATE);
        var parameters = proof.path("parameters");
        if (!expectedCode.equals(parameters.path("ts_code").asText()) || !start.equals(parameters.path("start_date").asText())
                || !end.equals(parameters.path("end_date").asText()))
            throw new IllegalStateException("index_daily_market receipt request parameters differ from frozen range");
        var raw = json.convertValue(proof.path("rawRows"), new TypeReference<List<Map<String,JsonNode>>>() {});
        var mapper = new IndexDailyMarketMapper(); var rows = new ArrayList<IndexDailyMarket>();
        var keys = new HashSet<IndexDailyMarketKey>();
        for (var sourceRow : raw) {
            validate(sourceRow, index, expectedFrom, expectedTo, expectedObservedAt);
            var value = mapper.fromSource(mapper.dto(sourceRow, index), index, expectedObservedAt);
            if (!keys.add(value.key())) throw new IllegalStateException("Duplicate complete key in index_daily_market receipt");
            rows.add(value);
        }
        return new SyncJobRunner.Page<>(rows, expectedFingerprint, receipt.toAbsolutePath().normalize().toString(), expectedCode);
    }

    private static void validate(Map<String,JsonNode> row, IndexDailyMarketUniverse.Index index,
            LocalDate from, LocalDate to, Instant observedAt) {
        var mapper = new IndexDailyMarketMapper();
        var value = mapper.fromSource(mapper.dto(row, index), index, observedAt);
        if (value.tradeDate().isBefore(from) || value.tradeDate().isAfter(to))
            throw new IllegalArgumentException("index_daily_market row lies outside frozen date range");
    }
    private void persistIncomplete(IndexDailyMarketUniverse.Index index, LocalDate from, LocalDate to,
            Instant observedAt, Map<String,Object> parameters, PageExecutor.Page response, Exception failure) throws Exception {
        var body = new LinkedHashMap<String,Object>(); body.put("sourceKind", "tushare");
        body.put("endpoint", IndexDailyMarketUniverse.endpoint(index.route())); body.put("route", index.route());
        body.put("tsCode", index.tsCode()); body.put("from", from); body.put("to", to); body.put("observedAt", observedAt);
        body.put("parameters", parameters); body.put("fields", fields(index.route())); body.put("responseRows", response.rows().size());
        body.put("rawRows", response.rows()); body.put("sourceComplete", false); body.put("sourceRowCap", API_ROW_CAP);
        body.put("evidenceStatus", "unverified_raw_response"); body.put("explicitEnd", response.explicitEnd());
        body.put("failureCategory", failure.getClass().getSimpleName()); body.put("failure", failure.getMessage());
        if (response.sourceVersion() != null) body.put("sourceVersion", response.sourceVersion());
        byte[] bytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValueAsBytes(body);
        requireEvidenceSize(bytes); Files.createDirectories(evidenceRoot);
        Files.write(evidenceRoot.resolve("incomplete-" + UUID.randomUUID() + ".json"), bytes, StandardOpenOption.CREATE_NEW);
    }
    private static IndexDailyMarketUniverse.Index requireIndex(String code) {
        var index = IndexDailyMarketUniverse.resolve(code);
        if (index == null) throw new IllegalArgumentException("D019 code must be a member of the frozen CORE57 or SW2021_L1_31 universe");
        return index;
    }
    private static void requireWindow(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > MAX_WINDOW_DAYS) throw new IllegalArgumentException("D019 source range must contain 1..366 calendar days");
    }
    private static PageContract contract(IndexDailyMarketUniverse.Route route) {
        return route == IndexDailyMarketUniverse.Route.SW_DAILY ? SW_DAILY : INDEX_DAILY;
    }
    public static List<String> fields(IndexDailyMarketUniverse.Route route) {
        return route == IndexDailyMarketUniverse.Route.SW_DAILY ? SW_DAILY_FIELDS : INDEX_DAILY_FIELDS;
    }
    private static void requireEvidenceSize(byte[] bytes) {
        if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("index_daily_market evidence exceeds 32 MiB");
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
