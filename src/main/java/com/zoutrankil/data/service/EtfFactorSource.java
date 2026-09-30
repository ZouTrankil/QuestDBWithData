package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.EtfFactor;
import com.zoutrankil.data.domain.EtfFactorDataset;
import com.zoutrankil.data.domain.EtfFactorKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.mapper.EtfFactorMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** One unpaged all-fund request per date, with canonical raw evidence and an 8000-row truncation ceiling. */
public final class EtfFactorSource {
    /** Official fund_factor_pro maximum (doc 359); a response at the cap is ambiguous and rejected. */
    public static final int SOURCE_ROW_CAP = 8_000;
    public static final int MAX_EVIDENCE_BYTES = 32 * 1024 * 1024;
    public static final PageContract CONTRACT = new PageContract("fund_factor_pro", EtfFactorMapper.SOURCE_FIELDS,
            List.of("ts_code", "trade_date"), Set.of("trade_date"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, SOURCE_ROW_CAP, SOURCE_ROW_CAP, 1,
            SOURCE_ROW_CAP, "Official Tushare doc 359 checked 2026-09-30: fund_factor_pro accepts trade_date and has an 8000-row per-request limit; no limit/offset input is documented. Request is one complete date; reaching 8000 rows fails closed. Runtime requests still use the shared credential rate budget.");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private final TusharePageService pages;
    private final EtfFactorMapper mapper;
    private final Path evidenceRoot;
    public EtfFactorSource(TusharePageService pages, EtfFactorMapper mapper, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages); this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<EtfFactor> fetch(LocalDate date, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(date); Objects.requireNonNull(cancelled);
        String basic = date.format(BASIC); Map<String,Object> parameters = Map.of("trade_date", basic);
        var raw = new ArrayList<Map<String, JsonNode>>();
        var fetcher = pages.fetcher(CONTRACT, cancelled);
        PageExecutor.Completed completed;
        try {
            completed = new PageExecutor().execute(CONTRACT, parameters, request -> {
                PageExecutor.Page page = fetcher.fetch(request);
                raw.addAll(page.rows()); // Preserve even an unverified at-cap response for diagnosis.
                return page;
            }, (page, receipt) -> {}, row -> validate(row, basic), cancelled);
        } catch (Exception failure) {
            persistIncomplete(date, parameters, raw, failure);
            throw failure;
        }
        if (completed.pages() != 1 || completed.rows() != raw.size() || raw.size() >= SOURCE_ROW_CAP)
            throw new IllegalStateException("fund_factor_pro date response is incomplete or at its source cap");
        raw.sort(Comparator.comparing((Map<String, JsonNode> row) -> row.get("ts_code").asText())
                .thenComparing(row -> row.get("trade_date").asText()));
        var rows = new ArrayList<EtfFactor>(raw.size()); var keys = new HashSet<EtfFactorKey>();
        for (var source : raw) {
            EtfFactor row = mapper.fromSource(mapper.dto(source));
            if (!row.tradeDate().equals(date) || !keys.add(row.key()))
                throw new IllegalStateException("fund_factor_pro returned a duplicate key or row outside requested trade_date");
            rows.add(row);
        }
        byte[] canonical = canonical(date, parameters, raw, completed.sourceVersion(), true);
        if (canonical.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("etf_factor receipt exceeds 32 MiB evidence bound");
        String fingerprint = sha256(canonical); Files.createDirectories(evidenceRoot);
        Path receipt = evidenceRoot.resolve("fund-factor-pro-" + basic + "-" + fingerprint + ".json");
        persistDeterministically(receipt, canonical);
        return new SyncJobRunner.Page<>(List.copyOf(rows), fingerprint, receipt.toString(), basic);
    }

    /** Reopens and revalidates the immutable receipt used by checkpoint and target reconciliation. */
    public static SyncJobRunner.Page<EtfFactor> reopen(Path receipt, String expectedFingerprint, LocalDate expectedDate) throws Exception {
        Objects.requireNonNull(receipt); Objects.requireNonNull(expectedDate);
        if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(receipt) || Files.size(receipt) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("Bounded etf_factor receipt and SHA-256 required");
        byte[] bytes = Files.readAllBytes(receipt);
        if (!sha256(bytes).equals(expectedFingerprint)) throw new IllegalStateException("etf_factor receipt fingerprint changed");
        var json = JobDefinitionJson.mapper(); JsonNode proof = json.readTree(bytes);
        String basic = expectedDate.format(BASIC);
        if (!proof.path("sourceKind").asText().equals("tushare")
                || !proof.path("endpoint").asText().equals("fund_factor_pro")
                || !proof.path("sourceComplete").asBoolean(false)
                || !proof.path("tradeDate").asText().equals(expectedDate.toString())
                || proof.path("sourceRowCap").asInt(-1) != SOURCE_ROW_CAP
                || proof.path("returnedRows").asInt(-1) != proof.path("rawRows").size()
                || proof.path("rawRows").size() >= SOURCE_ROW_CAP || !proof.path("rawRows").isArray()
                || !json.valueToTree(EtfFactorMapper.SOURCE_FIELDS).equals(proof.path("fields"))
                || !basic.equals(proof.path("parameters").path("trade_date").asText()))
            throw new IllegalStateException("etf_factor receipt contract, range or cap differs");
        List<Map<String,JsonNode>> raw = json.convertValue(proof.path("rawRows"), new TypeReference<>() {});
        raw.sort(Comparator.comparing((Map<String, JsonNode> row) -> row.get("ts_code").asText())
                .thenComparing(row -> row.get("trade_date").asText()));
        var mapper = new EtfFactorMapper(); var rows = new ArrayList<EtfFactor>(); var keys = new HashSet<EtfFactorKey>();
        for (var source : raw) {
            validate(source, basic); EtfFactor row = mapper.fromSource(mapper.dto(source));
            if (!keys.add(row.key())) throw new IllegalStateException("Duplicate complete key in etf_factor receipt");
            rows.add(row);
        }
        byte[] recomputed = canonical(expectedDate, Map.of("trade_date", basic), raw,
                proof.hasNonNull("sourceVersion") ? proof.path("sourceVersion").asText() : null, true);
        if (!MessageDigest.isEqual(recomputed, bytes)) throw new IllegalStateException("etf_factor canonical receipt bytes are not stable");
        return new SyncJobRunner.Page<>(List.copyOf(rows), expectedFingerprint, receipt.toAbsolutePath().normalize().toString(), basic);
    }

    private void persistIncomplete(LocalDate date, Map<String,Object> parameters,
                                   List<Map<String, JsonNode>> raw, Exception failure) throws Exception {
        var sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparing((Map<String, JsonNode> row) -> row.get("ts_code") == null ? "" : row.get("ts_code").asText())
                .thenComparing(row -> row.get("trade_date") == null ? "" : row.get("trade_date").asText()));
        byte[] bytes = canonical(date, parameters, sorted, null, false, failure);
        if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("etf_factor incomplete evidence exceeds 32 MiB");
        Files.createDirectories(evidenceRoot);
        Path file = evidenceRoot.resolve("unverified-" + date.format(BASIC) + "-" + sha256(bytes) + ".json");
        persistDeterministically(file, bytes);
    }
    private static byte[] canonical(LocalDate date, Map<String,Object> parameters, List<Map<String, JsonNode>> rows,
                                    String version, boolean complete) throws Exception {
        return canonical(date, parameters, rows, version, complete, null);
    }
    private static byte[] canonical(LocalDate date, Map<String,Object> parameters, List<Map<String, JsonNode>> rows,
                                    String version, boolean complete, Exception failure) throws Exception {
        var body = new LinkedHashMap<String,Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "fund_factor_pro");
        body.put("parameters", parameters); body.put("fields", EtfFactorMapper.SOURCE_FIELDS);
        body.put("tradeDate", date); body.put("sourceRowCap", SOURCE_ROW_CAP); body.put("rawRows", rows);
        body.put("returnedRows", rows.size()); body.put("sourceComplete", complete);
        if (version != null) body.put("sourceVersion", version);
        if (!complete) { body.put("evidenceStatus", "unverified_raw_response");
            body.put("failureCategory", failure == null ? "unknown" : failure.getClass().getSimpleName());
            body.put("failure", failure == null ? "source response did not reach terminal evidence" : String.valueOf(failure.getMessage())); }
        return JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValueAsBytes(body);
    }
    private static void validate(Map<String, JsonNode> source, String date) {
        EtfFactor row = new EtfFactorMapper().fromSource(new EtfFactorMapper().dto(source));
        if (!date.equals(row.tradeDate().format(BASIC))) throw new IllegalArgumentException("fund_factor_pro row is outside requested date");
    }
    private static void persistDeterministically(Path path, byte[] bytes) throws Exception {
        if (Files.exists(path)) {
            if (!MessageDigest.isEqual(Files.readAllBytes(path), bytes)) throw new IllegalStateException("Canonical etf_factor evidence path collision");
            return;
        }
        try { Files.write(path, bytes, StandardOpenOption.CREATE_NEW); }
        catch (java.nio.file.FileAlreadyExistsException raced) {
            if (!MessageDigest.isEqual(Files.readAllBytes(path), bytes)) throw new IllegalStateException("Canonical etf_factor evidence changed concurrently", raced);
        }
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
