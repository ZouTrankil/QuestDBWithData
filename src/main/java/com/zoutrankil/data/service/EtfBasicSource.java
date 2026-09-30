package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.domain.EtfBasic;
import com.zoutrankil.data.mapper.EtfBasicMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One unpaged, complete fund_basic(market=E) snapshot with raw evidence before validation. */
public final class EtfBasicSource {
    public static final String ENDPOINT = "fund_basic";
    public static final int CONTRACT_VERSION = 1;
    /** Official fund_basic maximum. At exactly this count completeness is unknown, so fail closed. */
    public static final int SOURCE_ROW_CAP = 15_000;
    public static final int MAX_EVIDENCE_BYTES = 64 * 1024 * 1024;
    public static final PageContract CONTRACT = new PageContract(ENDPOINT, EtfBasicMapper.SOURCE_FIELDS,
            List.of("ts_code"), Set.of("market"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, SOURCE_ROW_CAP, SOURCE_ROW_CAP, 1, SOURCE_ROW_CAP,
            "Official Tushare fund_basic supports market/status/ts_code and documents a 15000-row maximum; no offset/cursor is documented. D013 uses one unfiltered market=E request, preserving null-status rows and failing closed at the cap.");

    public record Result(List<EtfBasic> rows, String fingerprint, String receipt, int sourcePages) {
        public Result { rows = List.copyOf(rows); }
    }
    private record Persisted(byte[] bytes, Path path) {}

    private final TusharePageService pages;
    private final EtfBasicMapper mapper = new EtfBasicMapper();
    private final Path evidenceRoot;
    private final ObjectMapper json = JobDefinitionJson.mapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public EtfBasicSource(TusharePageService pages, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public Result fetch(Instant observedAt, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(observedAt); Objects.requireNonNull(cancelled);
        var parameters = Map.<String, Object>of("market", "E");
        var rawRows = new ArrayList<Map<String, JsonNode>>();
        var captured = new PageExecutor.Page[1];
        PageExecutor.Completed completed;
        var fetcher = pages.fetcher(CONTRACT, cancelled);
        try {
            completed = new PageExecutor().execute(CONTRACT, parameters, request -> {
                var response = fetcher.fetch(request);
                captured[0] = response; // Keep the original response available if cap/field validation rejects it.
                return response;
            }, (page, receipt) -> rawRows.addAll(page.rows()), row -> {
                var source = mapper.dto(row);
                if (!"E".equals(source.market())) throw new IllegalArgumentException("fund_basic response escaped market=E");
                mapper.fromSource(source, observedAt); // Validate all declared field semantics before any write.
            }, cancelled);
        } catch (Exception failure) {
            if (captured[0] != null && failure instanceof PageExecutor.Incomplete) {
                try { persistIncomplete(parameters, captured[0], observedAt, failure); }
                catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            }
            throw failure;
        }
        if (completed.pages() != 1 || completed.rows() != rawRows.size())
            throw new IllegalStateException("fund_basic completion differs from captured response");
        // An empty market-wide directory response is not accepted as a meaningful snapshot.
        if (rawRows.isEmpty()) {
            persistComplete(parameters, rawRows, captured[0], observedAt, false, "empty_market_response");
            throw new IllegalStateException("fund_basic market=E returned no rows; preserve receipt and fail closed");
        }

        rawRows.sort(Comparator.comparing(row -> row.get("ts_code").asText()));
        var typed = rawRows.stream().map(mapper::dto).map(row -> mapper.fromSource(row, observedAt)).toList();
        var persisted = persistComplete(parameters, rawRows, captured[0], observedAt, true, null);
        String fingerprint = sha256(persisted.bytes());
        return new Result(typed, fingerprint, persisted.path().toString(), completed.pages());
    }

    private Persisted persistComplete(Map<String, Object> parameters, List<Map<String, JsonNode>> rows,
                                   PageExecutor.Page response, Instant observedAt,
                                   boolean sourceComplete, String failureCategory) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", ENDPOINT);
        body.put("sourceContractVersion", CONTRACT_VERSION); body.put("parameters", parameters);
        body.put("fields", EtfBasicMapper.SOURCE_FIELDS); body.put("observedAt", observedAt);
        body.put("responseRows", rows.size()); body.put("rows", rows);
        body.put("sourceRowCap", SOURCE_ROW_CAP); body.put("sourceComplete", sourceComplete);
        body.put("explicitEnd", response != null && response.explicitEnd());
        if (response != null && response.sourceVersion() != null) body.put("sourceVersion", response.sourceVersion());
        if (failureCategory != null) body.put("failureCategory", failureCategory);
        byte[] bytes = json.writeValueAsBytes(body);
        if (bytes.length > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("fund_basic source evidence exceeds the 64 MiB budget");
        Files.createDirectories(evidenceRoot);
        Path file = evidenceRoot.resolve((sourceComplete ? "source-" : "incomplete-") + UUID.randomUUID() + ".json");
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        return new Persisted(bytes, file);
    }

    private void persistIncomplete(Map<String, Object> parameters, PageExecutor.Page response,
                                   Instant observedAt, Exception failure) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", ENDPOINT);
        body.put("sourceContractVersion", CONTRACT_VERSION); body.put("parameters", parameters);
        body.put("fields", EtfBasicMapper.SOURCE_FIELDS); body.put("observedAt", observedAt);
        body.put("responseRows", response.rows().size()); body.put("rows", response.rows());
        body.put("sourceRowCap", SOURCE_ROW_CAP); body.put("sourceComplete", false);
        body.put("evidenceStatus", "unverified_raw_response");
        body.put("failureCategory", failure.getClass().getSimpleName());
        body.put("failure", failure.getMessage()); body.put("explicitEnd", response.explicitEnd());
        if (response.sourceVersion() != null) body.put("sourceVersion", response.sourceVersion());
        byte[] bytes = json.writeValueAsBytes(body);
        if (bytes.length > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("fund_basic incomplete response evidence exceeds the 64 MiB budget");
        Files.createDirectories(evidenceRoot);
        Files.write(evidenceRoot.resolve("incomplete-" + UUID.randomUUID() + ".json"), bytes,
                StandardOpenOption.CREATE_NEW);
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
