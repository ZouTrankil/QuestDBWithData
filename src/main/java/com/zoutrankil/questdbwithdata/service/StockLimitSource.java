package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.StockLimitMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One all-market trade_date request. A 5800-row response fails closed because stk_limit has no documented cursor. */
public final class StockLimitSource {
    public static final int API_ROW_CAP = 5800;
    public static final int MAX_EVIDENCE_BYTES = 32 * 1024 * 1024;
    public static final List<String> FIELDS = StockLimitMapper.SOURCE_FIELDS;
    public static final PageContract CONTRACT = new PageContract("stk_limit", FIELDS,
            List.of("ts_code", "trade_date"), Set.of("trade_date"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Official Tushare stk_limit documentation: one trade_date request, maximum 5800 records, no offset/cursor route declared.");

    private final TusharePageService pages;
    private final StockLimitMapper mapper;
    private final Path evidenceRoot;
    public StockLimitSource(TusharePageService pages, StockLimitMapper mapper, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages); this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<StockLimit> fetch(LocalDate date, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(date); Objects.requireNonNull(cancelled);
        String basic = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        var parameters = Map.<String,Object>of("trade_date", basic);
        var raw = new ArrayList<Map<String,JsonNode>>();
        var captured = new PageExecutor.Page[1];
        var fetcher = pages.fetcher(CONTRACT, cancelled);
        PageExecutor.Completed complete;
        try {
            complete = new PageExecutor().execute(CONTRACT, parameters, request -> {
                PageExecutor.Page response = fetcher.fetch(request);
                captured[0] = response; // Retain untouched source data before cap/validation can reject it.
                return response;
            }, (page, receipt) -> raw.addAll(page.rows()), row -> validate(row, basic), cancelled);
        } catch (Exception failure) {
            if (captured[0] != null) {
                try { persistIncomplete(date, parameters, captured[0], failure); }
                catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            }
            throw failure;
        }
        if (complete.pages() != 1 || complete.rows() != raw.size() || captured[0] == null)
            throw new IllegalStateException("stk_limit must produce one complete response for each exact trade_date");
        // Stable receipt identity must not depend on the provider's response ordering.
        raw.sort(Comparator.comparing((Map<String,JsonNode> row) -> row.get("ts_code").asText())
                .thenComparing(row -> row.get("trade_date").asText()));
        var typed = raw.stream().map(mapper::dto).map(mapper::fromSource).toList();
        var keys = new HashSet<StockLimitKey>();
        for (var row : typed) if (!row.tradeDate().equals(date) || !keys.add(row.key()))
            throw new IllegalStateException("stk_limit source contains duplicate or out-of-date business keys");
        var json = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        var body = new LinkedHashMap<String,Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "stk_limit"); body.put("parameters", parameters);
        body.put("fields", FIELDS); body.put("tradeDate", date); body.put("rawRows", raw);
        body.put("returnedRows", typed.size()); body.put("sourceComplete", true);
        body.put("apiMaximumRows", API_ROW_CAP); body.put("sourceVersion", complete.sourceVersion());
        byte[] bytes = json.writeValueAsBytes(body);
        requireEvidenceSize(bytes);
        String fingerprint = sha256(bytes);
        Files.createDirectories(evidenceRoot);
        Path receipt = evidenceRoot.resolve("stk-limit-" + basic + "-" + UUID.randomUUID() + ".json");
        Files.write(receipt, bytes, StandardOpenOption.CREATE_NEW);
        return new SyncJobRunner.Page<>(typed, fingerprint, receipt.toString(), basic);
    }

    /** Validates immutable response evidence when rebuilding checkpoint/readback proofs. */
    public static SyncJobRunner.Page<StockLimit> reopen(Path receipt, String expectedFingerprint,
                                                          LocalDate expectedDate) throws Exception {
        Objects.requireNonNull(receipt); Objects.requireNonNull(expectedDate);
        if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(receipt) || Files.size(receipt) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("Bounded stk_limit receipt and SHA-256 required");
        byte[] bytes = Files.readAllBytes(receipt);
        if (!sha256(bytes).equals(expectedFingerprint)) throw new IllegalStateException("stk_limit source receipt fingerprint changed");
        var json = JobDefinitionJson.mapper(); var proof = json.readTree(bytes);
        String basic = expectedDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!proof.path("endpoint").asText().equals("stk_limit") || !proof.path("sourceComplete").asBoolean(false)
                || !proof.path("tradeDate").asText().equals(expectedDate.toString())
                || !json.valueToTree(FIELDS).equals(proof.path("fields")) || !proof.path("rawRows").isArray()
                || proof.path("returnedRows").asInt(-1) != proof.path("rawRows").size()
                || !proof.path("parameters").path("trade_date").asText().equals(basic))
            throw new IllegalStateException("stk_limit receipt scope or completion evidence differs");
        var raw = json.convertValue(proof.path("rawRows"), new TypeReference<List<Map<String,JsonNode>>>() {});
        var mapper = new StockLimitMapper(); var rows = new ArrayList<StockLimit>();
        var keys = new HashSet<StockLimitKey>();
        for (var value : raw) {
            validate(value, basic); var row = mapper.fromSource(mapper.dto(value));
            if (!keys.add(row.key())) throw new IllegalStateException("Duplicate full key in stk_limit receipt");
            rows.add(row);
        }
        return new SyncJobRunner.Page<>(rows, expectedFingerprint, receipt.toAbsolutePath().normalize().toString(), basic);
    }

    private static void validate(Map<String,JsonNode> row, String date) {
        var mapper = new StockLimitMapper();
        StockLimit value = mapper.fromSource(mapper.dto(row));
        if (!date.equals(value.tradeDate().format(DateTimeFormatter.BASIC_ISO_DATE)))
            throw new IllegalArgumentException("stk_limit row outside frozen trade_date");
    }

    private void persistIncomplete(LocalDate date, Map<String,Object> params, PageExecutor.Page response,
                                   Exception failure) throws Exception {
        var body = new LinkedHashMap<String,Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "stk_limit"); body.put("parameters", params);
        body.put("fields", FIELDS); body.put("tradeDate", date); body.put("responseRows", response.rows().size());
        body.put("rawRows", response.rows()); body.put("sourceComplete", false);
        body.put("evidenceStatus", "unverified_raw_response"); body.put("sourceRowCap", API_ROW_CAP);
        body.put("explicitEnd", response.explicitEnd()); body.put("failureCategory", failure.getClass().getSimpleName());
        body.put("failure", failure.getMessage());
        if (response.sourceVersion() != null) body.put("sourceVersion", response.sourceVersion());
        byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(body); requireEvidenceSize(bytes);
        Files.createDirectories(evidenceRoot);
        Files.write(evidenceRoot.resolve("incomplete-" + UUID.randomUUID() + ".json"), bytes, StandardOpenOption.CREATE_NEW);
    }
    private static void requireEvidenceSize(byte[] bytes) {
        if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("stk_limit evidence exceeds 32 MiB budget");
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
