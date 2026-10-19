package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.TusharePageService;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.EtfAdj;
import com.zoutrankil.data.domain.EtfAdjKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.etf.mapper.EtfAdjMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Complete bounded fund_adj pages for one exact trade_date; no fallback to an unpaged truncated response. */
public final class EtfAdjSource {
    /** Official fund_adj maximum per request. */
    public static final int PAGE_SIZE = 2000;
    /** Local acceptance bound: at most five full pages plus one terminal short/empty request. */
    public static final int MAX_ROWS_PER_DATE = 10_000;
    public static final int MAX_PAGES_PER_DATE = 6;
    public static final int MAX_EVIDENCE_BYTES = 32 * 1024 * 1024;
    public static final List<String> FIELDS = EtfAdjMapper.SOURCE_FIELDS;
    public static final PageContract CONTRACT = new PageContract("fund_adj", FIELDS,
            List.of("ts_code", "trade_date"), Set.of("trade_date", "limit", "offset"),
            PageContract.Paging.OFFSET, PageContract.Completion.SHORT_PAGE,
            "limit", "offset", PAGE_SIZE, PAGE_SIZE, MAX_PAGES_PER_DATE, MAX_ROWS_PER_DATE,
            "Official Tushare fund_adj doc199 (checked 2026-09-30): each request returns at most 2000 rows; offset/limit are supported and total rows are not limited. Python _get_etf_adj_by_date uses 2000-row offset pages. Local date cap is 10000 rows and fails closed before terminal evidence.");

    private record CapturedPage(long offset, int rows, List<Map<String, JsonNode>> rawRows) {}

    private final TusharePageService pages;
    private final EtfAdjMapper mapper;
    private final Path evidenceRoot;

    public EtfAdjSource(TusharePageService pages, EtfAdjMapper mapper, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages);
        this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<EtfAdj> fetch(LocalDate date, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(date); Objects.requireNonNull(cancelled);
        String basic = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        Map<String, Object> parameters = Map.of("trade_date", basic);
        var raw = new ArrayList<Map<String, JsonNode>>();
        var captured = new ArrayList<CapturedPage>();
        var attempts = new ArrayList<Map<String, Object>>();
        var fetcher = pages.fetcher(CONTRACT, cancelled);
        PageExecutor.Completed complete;
        try {
            complete = new PageExecutor().execute(CONTRACT, parameters, request -> {
                attempts.add(Map.copyOf(request));
                PageExecutor.Page response = fetcher.fetch(request);
                long offset = ((Number) request.get("offset")).longValue();
                captured.add(new CapturedPage(offset, response.rows().size(), response.rows()));
                return response;
            }, (page, receipt) -> raw.addAll(page.rows()), row -> validate(row, basic), cancelled);
        } catch (Exception failure) {
            try { persistIncomplete(date, parameters, attempts, captured, failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
        if (complete.pages() != captured.size() || complete.rows() != raw.size() || captured.isEmpty()
                || raw.size() > MAX_ROWS_PER_DATE)
            throw new IllegalStateException("etf_adj did not produce one bounded complete page sequence for the frozen date");

        // Stable receipt identity is independent of provider row ordering while preserving every request offset/count.
        raw.sort(Comparator.comparing((Map<String, JsonNode> row) -> row.get("ts_code").asText())
                .thenComparing(row -> row.get("trade_date").asText()));
        var typed = raw.stream().map(mapper::dto).map(mapper::fromSource).toList();
        var keys = new HashSet<EtfAdjKey>();
        for (var row : typed) if (!row.tradeDate().equals(date) || !keys.add(row.key()))
            throw new IllegalStateException("etf_adj source contains duplicate or out-of-date business keys");

        var pageEvidence = captured.stream().map(page -> Map.of(
                "offset", page.offset(), "limit", PAGE_SIZE, "rows", page.rows())).toList();
        var json = JobDefinitionJson.canonicalMapper();
        var body = new LinkedHashMap<String, Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "fund_adj"); body.put("parameters", parameters);
        body.put("pageSize", PAGE_SIZE); body.put("pageEvidence", pageEvidence); body.put("fields", FIELDS);
        body.put("tradeDate", date); body.put("rawRows", raw); body.put("returnedRows", typed.size());
        body.put("sourceComplete", true); body.put("dateRowCap", MAX_ROWS_PER_DATE);
        body.put("sourceVersion", complete.sourceVersion());
        byte[] bytes = json.writeValueAsBytes(body);
        requireEvidenceSize(bytes);
        String fingerprint = sha256(bytes);
        Files.createDirectories(evidenceRoot);
        Path receipt = evidenceRoot.resolve("etf-adj-" + basic + "-" + UUID.randomUUID() + ".json");
        FileEvidenceStore.writeNew(receipt, bytes);
        return new SyncJobRunner.Page<>(typed, fingerprint, receipt.toString(), basic);
    }

    /** Validates immutable complete response evidence when rebuilding checkpoint/readback proofs. */
    public static SyncJobRunner.Page<EtfAdj> reopen(Path receipt, String expectedFingerprint,
                                                      LocalDate expectedDate) throws Exception {
        Objects.requireNonNull(receipt); Objects.requireNonNull(expectedDate);
        if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(receipt) || Files.size(receipt) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("Bounded etf_adj receipt and SHA-256 required");
        byte[] bytes = FileEvidenceStore.readBounded(receipt, MAX_EVIDENCE_BYTES,
                () -> new IllegalArgumentException("Bounded etf_adj receipt and SHA-256 required"));
        if (!sha256(bytes).equals(expectedFingerprint)) throw new IllegalStateException("etf_adj source receipt fingerprint changed");
        var json = JobDefinitionJson.mapper(); var proof = json.readTree(bytes);
        String basic = expectedDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!proof.path("endpoint").asText().equals("fund_adj") || !proof.path("sourceComplete").asBoolean(false)
                || !proof.path("tradeDate").asText().equals(expectedDate.toString())
                || !json.valueToTree(FIELDS).equals(proof.path("fields")) || !proof.path("rawRows").isArray()
                || proof.path("pageSize").asInt(-1) != PAGE_SIZE
                || proof.path("dateRowCap").asInt(-1) != MAX_ROWS_PER_DATE
                || proof.path("returnedRows").asInt(-1) != proof.path("rawRows").size()
                || proof.path("rawRows").size() > MAX_ROWS_PER_DATE
                || !proof.path("parameters").path("trade_date").asText().equals(basic)
                || proof.path("pageEvidence").isMissingNode() || !validPageEvidence(proof.path("pageEvidence")))
            throw new IllegalStateException("etf_adj receipt scope, paging or completion evidence differs");
        var raw = json.convertValue(proof.path("rawRows"), new TypeReference<List<Map<String, JsonNode>>>() {});
        var mapper = new EtfAdjMapper(); var rows = new ArrayList<EtfAdj>();
        var keys = new HashSet<EtfAdjKey>();
        for (var value : raw) {
            validate(value, basic); var row = mapper.fromSource(mapper.dto(value));
            if (!keys.add(row.key())) throw new IllegalStateException("Duplicate full key in etf_adj receipt");
            rows.add(row);
        }
        int counted = 0;
        for (var page : proof.path("pageEvidence")) counted = Math.addExact(counted, page.path("rows").asInt(-1));
        if (counted != rows.size()) throw new IllegalStateException("etf_adj receipt page counts differ from raw rows");
        return new SyncJobRunner.Page<>(rows, expectedFingerprint, receipt.toAbsolutePath().normalize().toString(), basic);
    }

    private static boolean validPageEvidence(JsonNode pages) {
        if (!pages.isArray() || pages.size() < 1 || pages.size() > MAX_PAGES_PER_DATE) return false;
        long expectedOffset = 0;
        for (int index = 0; index < pages.size(); index++) {
            JsonNode page = pages.get(index);
            int count = page.path("rows").asInt(-1);
            if (page.path("offset").asLong(-1) != expectedOffset
                    || page.path("limit").asInt(-1) != PAGE_SIZE || count < 0 || count > PAGE_SIZE) return false;
            expectedOffset = Math.addExact(expectedOffset, count);
            if (count < PAGE_SIZE && index != pages.size() - 1) return false;
        }
        return expectedOffset <= MAX_ROWS_PER_DATE && pages.get(pages.size() - 1).path("rows").asInt() < PAGE_SIZE;
    }

    private static void validate(Map<String, JsonNode> row, String date) {
        var mapper = new EtfAdjMapper();
        EtfAdj value = mapper.fromSource(mapper.dto(row));
        if (!date.equals(value.tradeDate().format(DateTimeFormatter.BASIC_ISO_DATE)))
            throw new IllegalArgumentException("etf_adj row outside frozen trade_date");
    }

    private void persistIncomplete(LocalDate date, Map<String, Object> params, List<Map<String, Object>> attempts,
                                   List<CapturedPage> pages,
                                   Exception failure) throws Exception {
        var responses = pages.stream().map(page -> Map.of("offset", page.offset(), "rows", page.rawRows())).toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "fund_adj"); body.put("parameters", params);
        body.put("pageSize", PAGE_SIZE); body.put("dateRowCap", MAX_ROWS_PER_DATE);
        body.put("attemptedRequests", attempts); body.put("pages", responses);
        body.put("tradeDate", date); body.put("sourceComplete", false);
        body.put("evidenceStatus", "unverified_raw_pages"); body.put("failureCategory", failure.getClass().getSimpleName());
        body.put("failure", failure.getMessage());
        byte[] bytes = JobDefinitionJson.canonicalMapper()
                .writeValueAsBytes(body);
        requireEvidenceSize(bytes);
        Files.createDirectories(evidenceRoot);
        FileEvidenceStore.writeNew(evidenceRoot.resolve("incomplete-" + UUID.randomUUID() + ".json"), bytes);
    }

    private static void requireEvidenceSize(byte[] bytes) {
        if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("etf_adj evidence exceeds 32 MiB budget");
    }
    private static String sha256(byte[] bytes) throws Exception {
        return FileEvidenceStore.sha256(bytes);
    }
}
