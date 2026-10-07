package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MoneyflowThs;
import com.zoutrankil.data.domain.MoneyflowThsKey;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.mapper.MoneyflowThsMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One bounded non-paged Tushare moneyflow_ths(trade_date=YYYYMMDD) source request per SSE session. */
public final class MoneyflowThsSource {
    public static final int API_ROW_CAP = 6_000;
    public static final int MAX_EVIDENCE_BYTES = 16 * 1024 * 1024;
    public static final List<String> FIELDS = List.of("ts_code", "trade_date", "name", "pct_change", "latest",
            "net_amount", "net_d5_amount", "buy_lg_amount", "buy_lg_amount_rate", "buy_md_amount",
            "buy_md_amount_rate", "buy_sm_amount", "buy_sm_amount_rate");
    public static final PageContract CONTRACT = new PageContract("moneyflow_ths", FIELDS,
            List.of("ts_code", "trade_date"), Set.of("trade_date"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Python requests one whole-market response per SSE open trade date. This interface has no observed offset paging; a response at 6000 rows is possibly truncated and rejected.");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private final TusharePageService pages;
    private final MoneyflowThsMapper mapper = new MoneyflowThsMapper();
    private final Path evidenceRoot;

    public MoneyflowThsSource(TusharePageService pages, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<MoneyflowThs> fetch(LocalDate date, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(date); Objects.requireNonNull(cancelled);
        String basic = date.format(BASIC);
        Map<String, Object> parameters = Map.of("trade_date", basic);
        var raw = new ArrayList<Map<String, JsonNode>>();
        PageExecutor.Completed completed;
        var fetcher = pages.fetcher(CONTRACT, cancelled);
        try {
            completed = new PageExecutor().execute(CONTRACT, parameters, request -> {
                PageExecutor.Page response = fetcher.fetch(request);
                raw.addAll(response.rows());
                return response;
            }, (page, receipt) -> {}, row -> validateRow(row, basic), cancelled);
        } catch (Exception failure) {
            try { persistUnverified(date, parameters, raw, null, failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
        if (completed.pages() != 1 || completed.rows() != raw.size() || raw.size() >= API_ROW_CAP) {
            var failure = new IllegalStateException("D025 moneyflow_ths response is incomplete or reaches the 6000-row cap");
            try { persistUnverified(date, parameters, raw, completed.sourceVersion(), failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
        try {
            raw.sort(Comparator.comparing(row -> value(row, "ts_code")));
            var keys = new HashSet<MoneyflowThsKey>();
            var typed = new ArrayList<MoneyflowThs>(raw.size());
            for (var item : raw) {
                MoneyflowThs row = mapper.fromSource(mapper.dto(item));
                if (!date.equals(row.tradeDate()) || !keys.add(row.key()))
                    throw new IllegalArgumentException("D025 duplicate complete key or trade_date outside the frozen session");
                typed.add(row);
            }
            byte[] bytes = body(date, parameters, raw, completed.sourceVersion(), true, null);
            if (bytes.length > MAX_EVIDENCE_BYTES)
                throw new IllegalStateException("D025 complete raw source receipt exceeds 16 MiB");
            String fingerprint = sha(bytes);
            Files.createDirectories(evidenceRoot);
            Path receipt = evidenceRoot.resolve("moneyflow-ths-" + basic + "-" + fingerprint + ".json");
            persist(receipt, bytes);
            return new SyncJobRunner.Page<>(typed, fingerprint, receipt.toString(), basic);
        } catch (Exception failure) {
            try { persistUnverified(date, parameters, raw, completed.sourceVersion(), failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
    }

    /** Reopens only a retained successful receipt; the caller must also constrain its path to its evidence root. */
    public static SyncJobRunner.Page<MoneyflowThs> reopen(Path receipt, String fingerprint, LocalDate expectedDate)
            throws Exception {
        Path path = receipt.toAbsolutePath().normalize();
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || !Files.isRegularFile(path)
                || Files.size(path) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("D025 bounded source receipt and SHA-256 required");
        byte[] bytes = FileEvidenceStore.readBounded(path, MAX_EVIDENCE_BYTES,
                () -> new IllegalArgumentException("D025 bounded source receipt and SHA-256 required"));
        if (!sha(bytes).equals(fingerprint)) throw new IllegalStateException("D025 source receipt SHA-256 mismatch");
        var json = JobDefinitionJson.mapper();
        JsonNode proof = json.readTree(bytes);
        if (!"tushare".equals(proof.path("sourceKind").asText())
                || !"moneyflow_ths".equals(proof.path("endpoint").asText())
                || !proof.path("sourceComplete").asBoolean(false)
                || !expectedDate.toString().equals(proof.path("tradeDate").asText())
                || proof.path("apiMaximumRows").asInt(-1) != API_ROW_CAP
                || !json.valueToTree(FIELDS).equals(proof.path("fields"))
                || !expectedDate.format(BASIC).equals(proof.path("parameters").path("trade_date").asText())
                || !proof.path("rawRows").isArray()
                || proof.path("returnedRows").asInt(-1) != proof.path("rawRows").size()
                || proof.path("rawRows").size() >= API_ROW_CAP)
            throw new IllegalStateException("D025 receipt differs from frozen endpoint/date/cap/field contract");
        List<Map<String, JsonNode>> raw = json.convertValue(proof.path("rawRows"), new TypeReference<>() {});
        var rows = new ArrayList<MoneyflowThs>(raw.size());
        var keys = new HashSet<MoneyflowThsKey>();
        for (var item : raw) {
            validateRow(item, expectedDate.format(BASIC));
            var row = new MoneyflowThsMapper().fromSource(new MoneyflowThsMapper().dto(item));
            if (!keys.add(row.key())) throw new IllegalStateException("D025 successful receipt has a duplicate business key");
            rows.add(row);
        }
        var ordered = new ArrayList<>(raw);
        ordered.sort(Comparator.comparing(row -> value(row, "ts_code")));
        if (!json.valueToTree(raw).equals(json.valueToTree(ordered)))
            throw new IllegalStateException("D025 receipt rows are not in canonical key order");
        return new SyncJobRunner.Page<>(rows, fingerprint, path.toString(), expectedDate.format(BASIC));
    }

    private static void validateRow(Map<String, JsonNode> row, String requestedDate) {
        if (!row.keySet().equals(new HashSet<>(FIELDS)))
            throw new IllegalArgumentException("D025 response fields differ from the frozen 13-column contract");
        String code = value(row, "ts_code");
        if (!code.matches("[0-9]{6}\\.(?:SH|SZ|BJ)"))
            throw new IllegalArgumentException("D025 source returned an invalid exchange-qualified stock code");
        if (!requestedDate.equals(value(row, "trade_date")))
            throw new IllegalArgumentException("D025 response row is outside the requested trade date");
        new MoneyflowThsMapper().fromSource(new MoneyflowThsMapper().dto(row));
    }

    private static byte[] body(LocalDate date, Map<String, Object> parameters,
            List<Map<String, JsonNode>> rows, String sourceVersion, boolean complete, Exception failure) throws Exception {
        var json = JobDefinitionJson.canonicalMapper();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "moneyflow_ths");
        body.put("sourceContractVersion", 1); body.put("parameters", parameters); body.put("fields", FIELDS);
        body.put("tradeDate", date.toString()); body.put("apiMaximumRows", API_ROW_CAP);
        body.put("returnedRows", rows.size()); body.put("rawRows", rows); body.put("sourceComplete", complete);
        if (sourceVersion != null) body.put("sourceVersion", sourceVersion);
        if (failure != null) body.put("failureType", failure.getClass().getSimpleName());
        return json.writeValueAsBytes(body);
    }

    private void persistUnverified(LocalDate date, Map<String, Object> parameters,
            List<Map<String, JsonNode>> rows, String sourceVersion, Exception failure) throws Exception {
        byte[] bytes = body(date, parameters, rows, sourceVersion, false, failure);
        if (bytes.length > MAX_EVIDENCE_BYTES)
            throw new IllegalStateException("D025 incomplete raw response exceeds evidence cap", failure);
        Files.createDirectories(evidenceRoot);
        Path file = evidenceRoot.resolve("unverified-" + date.format(BASIC) + "-" + sha(bytes) + ".json");
        persist(file, bytes);
    }

    private static void persist(Path path, byte[] bytes) throws Exception {
        try { FileEvidenceStore.writeNew(path, bytes); }
        catch (java.nio.file.FileAlreadyExistsException exists) {
            if (!Arrays.equals(FileEvidenceStore.readBounded(path, Math.max(1, bytes.length),
                    () -> new IllegalStateException("Conflicting immutable D025 receipt", exists)), bytes))
                throw new IllegalStateException("Conflicting immutable D025 receipt", exists);
        }
    }
    private static String value(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
    private static String sha(byte[] bytes) throws Exception {
        return FileEvidenceStore.sha256(bytes);
    }
}
