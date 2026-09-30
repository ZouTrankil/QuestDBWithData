package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.IndexDailyBasicMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One canonical index code × bounded date-range request to Tushare index_dailybasic. */
public final class IndexDailyBasicSource {
    public static final int MAX_WINDOW_DAYS = 366;
    public static final int API_ROW_CAP = 3000;
    public static final int MAX_EVIDENCE_BYTES = 16 * 1024 * 1024;
    public static final List<String> FIELDS = List.of("ts_code", "trade_date", "total_mv", "float_mv",
            "total_share", "float_share", "free_share", "turnover_rate", "turnover_rate_f", "pe", "pe_ttm", "pb");
    public static final PageContract CONTRACT = new PageContract("index_dailybasic", FIELDS,
            List.of("ts_code", "trade_date"), Set.of("ts_code", "start_date", "end_date"),
            PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE, null, null,
            API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Official Tushare index_dailybasic doc128 accepts ts_code/start_date/end_date and documents a 3000-row single-request ceiling. The implementation limits each code to 366 calendar days and rejects responses at the 3000-row cap as potentially truncated; no offset parameter is documented.");

    private final TusharePageService pages;
    private final IndexDailyBasicMapper mapper;
    private final Path evidenceRoot;
    public IndexDailyBasicSource(TusharePageService pages, IndexDailyBasicMapper mapper, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages); this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<IndexDailyBasic> fetch(String code, LocalDate from, LocalDate to,
            BooleanSupplier cancelled) throws Exception {
        String canonical = requireCode(code); requireWindow(from, to);
        String start = from.format(DateTimeFormatter.BASIC_ISO_DATE), end = to.format(DateTimeFormatter.BASIC_ISO_DATE);
        var params = new LinkedHashMap<String,Object>();
        params.put("ts_code", canonical); params.put("start_date", start); params.put("end_date", end);
        var raw = new ArrayList<Map<String,JsonNode>>(); var captured = new PageExecutor.Page[1];
        PageExecutor.Completed completed;
        try {
            var fetcher = pages.fetcher(CONTRACT, cancelled);
            completed = new PageExecutor().execute(CONTRACT, params, request -> {
                var response = fetcher.fetch(request); captured[0] = response; return response;
            }, (page, receipt) -> raw.addAll(page.rows()),
                    row -> validate(row, canonical, from, to), cancelled);
        } catch (Exception failure) {
            if (captured[0] != null) try { persistIncomplete(canonical, from, to, params, captured[0], failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
        if (completed.pages() != 1 || completed.rows() != raw.size() || captured[0] == null)
            throw new IllegalStateException("D020 expects exactly one complete response per code/range");
        raw.sort(Comparator.comparing((Map<String,JsonNode> row) -> row.get("ts_code").asText())
                .thenComparing(row -> row.get("trade_date").asText()));
        var typed = raw.stream().map(mapper::dto).map(dto -> mapper.fromSource(dto, canonical)).toList();
        var seen = new HashSet<IndexDailyBasicKey>();
        for (var row : typed) if (!row.tsCode().equals(canonical) || row.tradeDate().isBefore(from)
                || row.tradeDate().isAfter(to) || !seen.add(row.key()))
            throw new IllegalStateException("D020 source response contains duplicate or out-of-range complete key");
        var body = new LinkedHashMap<String,Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "index_dailybasic"); body.put("tsCode", canonical);
        body.put("from", from); body.put("to", to); body.put("parameters", params); body.put("fields", FIELDS);
        body.put("rawRows", raw); body.put("returnedRows", typed.size()); body.put("sourceComplete", true);
        body.put("sourceRowCap", API_ROW_CAP); body.put("sourceVersion", completed.sourceVersion());
        byte[] bytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(body);
        requireEvidenceSize(bytes); Files.createDirectories(evidenceRoot);
        Path receipt = evidenceRoot.resolve("index-daily-basic-" + canonical.replace('.', '-') + "-" + start + "-" + end
                + "-" + UUID.randomUUID() + ".json");
        Files.write(receipt, bytes, StandardOpenOption.CREATE_NEW);
        return new SyncJobRunner.Page<>(typed, sha256(bytes), receipt.toString(), canonical);
    }

    /** Revalidates immutable source evidence before a recovery/reconciliation uses it. */
    public static SyncJobRunner.Page<IndexDailyBasic> reopen(Path receipt, String fingerprint, String expectedCode,
            LocalDate expectedFrom, LocalDate expectedTo) throws Exception {
        String code = requireCode(expectedCode); requireWindow(expectedFrom, expectedTo);
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || !Files.isRegularFile(receipt)
                || Files.size(receipt) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("Bounded D020 receipt and SHA-256 required");
        byte[] bytes = Files.readAllBytes(receipt);
        if (!sha256(bytes).equals(fingerprint)) throw new IllegalStateException("D020 source receipt fingerprint changed");
        var json = JobDefinitionJson.mapper(); var proof = json.readTree(bytes);
        if (!"tushare".equals(proof.path("sourceKind").asText()) || !"index_dailybasic".equals(proof.path("endpoint").asText())
                || !code.equals(proof.path("tsCode").asText()) || !expectedFrom.toString().equals(proof.path("from").asText())
                || !expectedTo.toString().equals(proof.path("to").asText())
                || !json.valueToTree(FIELDS).equals(proof.path("fields")) || !proof.path("sourceComplete").asBoolean(false)
                || !proof.path("rawRows").isArray() || proof.path("returnedRows").asInt(-1) != proof.path("rawRows").size()
                || proof.path("sourceRowCap").asInt(-1) != API_ROW_CAP)
            throw new IllegalStateException("D020 source receipt scope or completion differs");
        String start = expectedFrom.format(DateTimeFormatter.BASIC_ISO_DATE), end = expectedTo.format(DateTimeFormatter.BASIC_ISO_DATE);
        var params = proof.path("parameters");
        if (!code.equals(params.path("ts_code").asText()) || !start.equals(params.path("start_date").asText())
                || !end.equals(params.path("end_date").asText())) throw new IllegalStateException("D020 receipt request parameters differ");
        var raw = json.convertValue(proof.path("rawRows"), new TypeReference<List<Map<String,JsonNode>>>() {});
        var mapper = new IndexDailyBasicMapper(); var out = new ArrayList<IndexDailyBasic>();
        var keys = new HashSet<IndexDailyBasicKey>();
        for (var row : raw) {
            validate(row, code, expectedFrom, expectedTo);
            var value = mapper.fromSource(mapper.dto(row), code);
            if (!keys.add(value.key())) throw new IllegalStateException("Duplicate complete key in D020 receipt");
            out.add(value);
        }
        return new SyncJobRunner.Page<>(out, fingerprint, receipt.toAbsolutePath().normalize().toString(), code);
    }

    private static void validate(Map<String,JsonNode> row, String code, LocalDate from, LocalDate to) {
        if (row == null || !row.keySet().equals(Set.copyOf(FIELDS)))
            throw new IllegalArgumentException("D020 response row fields differ from the frozen 12-field projection");
        var mapped = new IndexDailyBasicMapper().fromSource(new IndexDailyBasicMapper().dto(row), code);
        if (mapped.tradeDate().isBefore(from) || mapped.tradeDate().isAfter(to))
            throw new IllegalArgumentException("D020 row outside frozen source interval");
    }
    private void persistIncomplete(String code, LocalDate from, LocalDate to, Map<String,Object> params,
            PageExecutor.Page response, Exception failure) throws Exception {
        var body = new LinkedHashMap<String,Object>(); body.put("sourceKind", "tushare");
        body.put("endpoint", "index_dailybasic"); body.put("tsCode", code); body.put("from", from); body.put("to", to);
        body.put("parameters", params); body.put("fields", FIELDS); body.put("responseRows", response.rows().size());
        body.put("rawRows", response.rows()); body.put("sourceComplete", false); body.put("sourceRowCap", API_ROW_CAP);
        body.put("evidenceStatus", "unverified_raw_response"); body.put("explicitEnd", response.explicitEnd());
        body.put("failureCategory", failure.getClass().getSimpleName()); body.put("failure", failure.getMessage());
        if (response.sourceVersion() != null) body.put("sourceVersion", response.sourceVersion());
        byte[] bytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(body);
        requireEvidenceSize(bytes); Files.createDirectories(evidenceRoot);
        Files.write(evidenceRoot.resolve("incomplete-" + UUID.randomUUID() + ".json"), bytes, StandardOpenOption.CREATE_NEW);
    }
    public static String requireCode(String code) {
        String canonical = IndexDailyBasicUniverse.resolve(code);
        if (canonical == null) throw new IllegalArgumentException("D020 code must be one of the frozen Python CORE_INDICES");
        return canonical;
    }
    public static void requireWindow(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > MAX_WINDOW_DAYS) throw new IllegalArgumentException("D020 interval must be 1..366 calendar days");
    }
    private static void requireEvidenceSize(byte[] bytes) {
        if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("D020 source evidence exceeds 16 MiB");
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
