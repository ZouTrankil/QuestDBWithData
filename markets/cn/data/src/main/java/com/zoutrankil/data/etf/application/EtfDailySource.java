package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.TusharePageService;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.etf.mapper.EtfDailyMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One all-market trade_date request. A 5000-row response fails closed because etf_daily has no documented cursor. */
public final class EtfDailySource {
    public static final int API_ROW_CAP = 5000;
    public static final int MAX_EVIDENCE_BYTES = 32 * 1024 * 1024;
    public static final List<String> FIELDS = EtfDailyMapper.SOURCE_FIELDS;
    public static final PageContract CONTRACT = new PageContract("fund_daily", FIELDS,
            List.of("ts_code", "trade_date"), Set.of("trade_date"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Official Tushare fund_daily doc127 (2026-09-30): one trade_date request, maximum 5000 records, no offset/cursor route declared.");

    private final TusharePageService pages;
    private final EtfDailyMapper mapper;
    private final Path evidenceRoot;
    public EtfDailySource(TusharePageService pages, EtfDailyMapper mapper, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages); this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<EtfDaily> fetch(LocalDate date, BooleanSupplier cancelled) throws Exception {
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
            throw new IllegalStateException("etf_daily must produce one complete response for each exact trade_date");
        // Stable receipt identity must not depend on the provider's response ordering.
        raw.sort(Comparator.comparing((Map<String,JsonNode> row) -> row.get("ts_code").asText())
                .thenComparing(row -> row.get("trade_date").asText()));
        var typed = raw.stream().map(mapper::dto).map(mapper::fromSource).toList();
        var keys = new HashSet<EtfDailyKey>();
        for (var row : typed) if (!row.tradeDate().equals(date) || !keys.add(row.key()))
            throw new IllegalStateException("etf_daily source contains duplicate or out-of-date business keys");
        var json = JobDefinitionJson.canonicalMapper();
        var body = new LinkedHashMap<String,Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "fund_daily"); body.put("parameters", parameters);
        body.put("fields", FIELDS); body.put("tradeDate", date); body.put("rawRows", raw);
        body.put("returnedRows", typed.size()); body.put("sourceComplete", true);
        body.put("apiMaximumRows", API_ROW_CAP); body.put("sourceVersion", complete.sourceVersion());
        byte[] bytes = json.writeValueAsBytes(body);
        requireEvidenceSize(bytes);
        String fingerprint = sha256(bytes);
        Files.createDirectories(evidenceRoot);
        Path receipt = evidenceRoot.resolve("etf-daily-" + basic + "-" + UUID.randomUUID() + ".json");
        FileEvidenceStore.writeNew(receipt, bytes);
        return new SyncJobRunner.Page<>(typed, fingerprint, receipt.toString(), basic);
    }

    /** Validates immutable response evidence when rebuilding checkpoint/readback proofs. */
    public static SyncJobRunner.Page<EtfDaily> reopen(Path receipt, String expectedFingerprint,
                                                          LocalDate expectedDate) throws Exception {
        Objects.requireNonNull(receipt); Objects.requireNonNull(expectedDate);
        if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(receipt) || Files.size(receipt) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("Bounded etf_daily receipt and SHA-256 required");
        byte[] bytes = FileEvidenceStore.readBounded(receipt, MAX_EVIDENCE_BYTES,
                () -> new IllegalArgumentException("Bounded etf_daily receipt and SHA-256 required"));
        if (!sha256(bytes).equals(expectedFingerprint)) throw new IllegalStateException("etf_daily source receipt fingerprint changed");
        var json = JobDefinitionJson.mapper(); var proof = json.readTree(bytes);
        String basic = expectedDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!proof.path("endpoint").asText().equals("fund_daily") || !proof.path("sourceComplete").asBoolean(false)
                || !proof.path("tradeDate").asText().equals(expectedDate.toString())
                || !json.valueToTree(FIELDS).equals(proof.path("fields")) || !proof.path("rawRows").isArray()
                || proof.path("returnedRows").asInt(-1) != proof.path("rawRows").size()
                || !proof.path("parameters").path("trade_date").asText().equals(basic))
            throw new IllegalStateException("etf_daily receipt scope or completion evidence differs");
        var raw = json.convertValue(proof.path("rawRows"), new TypeReference<List<Map<String,JsonNode>>>() {});
        var mapper = new EtfDailyMapper(); var rows = new ArrayList<EtfDaily>();
        var keys = new HashSet<EtfDailyKey>();
        for (var value : raw) {
            validate(value, basic); var row = mapper.fromSource(mapper.dto(value));
            if (!keys.add(row.key())) throw new IllegalStateException("Duplicate full key in etf_daily receipt");
            rows.add(row);
        }
        return new SyncJobRunner.Page<>(rows, expectedFingerprint, receipt.toAbsolutePath().normalize().toString(), basic);
    }

    private static void validate(Map<String,JsonNode> row, String date) {
        var mapper = new EtfDailyMapper();
        EtfDaily value = mapper.fromSource(mapper.dto(row));
        if (!date.equals(value.tradeDate().format(DateTimeFormatter.BASIC_ISO_DATE)))
            throw new IllegalArgumentException("etf_daily row outside frozen trade_date");
    }

    private void persistIncomplete(LocalDate date, Map<String,Object> params, PageExecutor.Page response,
                                   Exception failure) throws Exception {
        var body = new LinkedHashMap<String,Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "fund_daily"); body.put("parameters", params);
        body.put("fields", FIELDS); body.put("tradeDate", date); body.put("responseRows", response.rows().size());
        body.put("rawRows", response.rows()); body.put("sourceComplete", false);
        body.put("evidenceStatus", "unverified_raw_response"); body.put("sourceRowCap", API_ROW_CAP);
        body.put("explicitEnd", response.explicitEnd()); body.put("failureCategory", failure.getClass().getSimpleName());
        body.put("failure", failure.getMessage());
        if (response.sourceVersion() != null) body.put("sourceVersion", response.sourceVersion());
        byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(body); requireEvidenceSize(bytes);
        Files.createDirectories(evidenceRoot);
        FileEvidenceStore.writeNew(evidenceRoot.resolve("incomplete-" + UUID.randomUUID() + ".json"), bytes);
    }
    private static void requireEvidenceSize(byte[] bytes) {
        if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("etf_daily evidence exceeds 32 MiB budget");
    }
    private static String sha256(byte[] bytes) throws Exception {
        return FileEvidenceStore.sha256(bytes);
    }
}
