package com.zoutrankil.data.margin.application;

import com.zoutrankil.data.margin.domain.MarginAllState;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginAll;
import com.zoutrankil.data.domain.MarginAllKey;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.margin.mapper.MarginAllMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One bounded Tushare margin(trade_date=YYYYMMDD) source slice and immutable raw receipt. */
public final class MarginAllSource {
    public static final int API_ROW_CAP = MarginAllState.API_ROW_CAP;
    public static final int MAX_EVIDENCE_BYTES = 4 * 1024 * 1024;
    public static final List<String> FIELDS = List.of("trade_date", "exchange_id", "rzye", "rzmre", "rzche", "rqye", "rqmcl", "rzrqye", "rqyl");
    public static final PageContract CONTRACT = new PageContract("margin", FIELDS,
            List.of("trade_date", "exchange_id"), Set.of("trade_date"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Python calls Tushare margin by start_date/end_date; official API also supports exact trade_date and exchange_id, no documented offset. Java requests one date at a time; cap=4000 is conservatively rejected if reached.");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private final TusharePageService pages;
    private final Path evidenceRoot;
    private final MarginAllMapper mapper = new MarginAllMapper();
    public MarginAllSource(TusharePageService pages, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages); this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }
    public SyncJobRunner.Page<MarginAll> fetch(LocalDate date, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(date); Objects.requireNonNull(cancelled); String basic = date.format(BASIC);
        Map<String,Object> params = Map.of("trade_date", basic); var raw = new ArrayList<Map<String,JsonNode>>();
        var fetcher = pages.fetcher(CONTRACT, cancelled); PageExecutor.Completed completed;
        try {
            completed = new PageExecutor().execute(CONTRACT, params, request -> {
                PageExecutor.Page response = fetcher.fetch(request); raw.addAll(response.rows()); return response;
            }, (page, receipt) -> {}, row -> validateRow(row, basic), cancelled);
        } catch (Exception failure) { persistIncomplete(date, params, raw, null, failure); throw failure; }
        raw.sort(Comparator.comparing((Map<String,JsonNode> row) -> value(row,"exchange_id"))
                .thenComparing(row -> value(row,"trade_date")));
        if (completed.pages() != 1 || completed.rows() != raw.size() || raw.size() >= API_ROW_CAP) {
            var failure = new IllegalStateException("D028 margin response is incomplete or reaches the 4000-row source cap");
            persistIncomplete(date, params, raw, completed.sourceVersion(), failure); throw failure;
        }
        var rows = new ArrayList<MarginAll>(raw.size()); var keys = new HashSet<MarginAllKey>();
        try {
            for (var item : raw) {
                validateRow(item, basic); var row = mapper.fromSource(mapper.dto(item));
                if (!row.tradeDate().equals(date) || !keys.add(row.key())) throw new IllegalArgumentException("D028 duplicate key or source row outside frozen trade_date");
                rows.add(row);
            }
            byte[] bytes = body(date, params, raw, completed.sourceVersion(), true, null);
            if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalStateException("D028 raw source receipt exceeds 4 MiB");
            String fingerprint = sha(bytes); Files.createDirectories(evidenceRoot);
            Path receipt = evidenceRoot.resolve("margin-all-" + basic + "-" + fingerprint + ".json"); persist(receipt, bytes);
            return new SyncJobRunner.Page<>(List.copyOf(rows), fingerprint, receipt.toString(), basic);
        } catch (Exception failure) { persistIncomplete(date, params, raw, completed.sourceVersion(), failure); throw failure; }
    }
    public static SyncJobRunner.Page<MarginAll> reopen(Path receipt, String fingerprint, LocalDate expectedDate) throws Exception {
        Path file = receipt.toAbsolutePath().normalize();
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || !Files.isRegularFile(file)
                || Files.isSymbolicLink(file) || Files.size(file) < 1 || Files.size(file) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("D028 bounded raw receipt and SHA-256 required");
        byte[] bytes = FileEvidenceStore.readBounded(file, MAX_EVIDENCE_BYTES,
                () -> new IllegalArgumentException("D028 bounded raw receipt and SHA-256 required")); if (!sha(bytes).equals(fingerprint)) throw new IllegalStateException("D028 receipt SHA-256 mismatch");
        var json = JobDefinitionJson.mapper(); JsonNode proof = json.readTree(bytes); String basic = expectedDate.format(BASIC);
        if (!"tushare".equals(proof.path("sourceKind").asText()) || !"margin".equals(proof.path("endpoint").asText())
                || !proof.path("sourceComplete").asBoolean(false) || !expectedDate.toString().equals(proof.path("tradeDate").asText())
                || proof.path("apiMaximumRows").asInt(-1) != API_ROW_CAP || !json.valueToTree(FIELDS).equals(proof.path("fields"))
                || !basic.equals(proof.path("parameters").path("trade_date").asText())
                || !proof.path("rawRows").isArray() || proof.path("returnedRows").asInt(-1) != proof.path("rawRows").size()
                || proof.path("rawRows").size() >= API_ROW_CAP)
            throw new IllegalStateException("D028 receipt differs from frozen endpoint/date/fields/cap contract");
        List<Map<String,JsonNode>> raw = json.convertValue(proof.path("rawRows"), new TypeReference<>() {});
        var rows = new ArrayList<MarginAll>(); var keys = new HashSet<MarginAllKey>(); String prior = null;
        for (var item : raw) {
            validateRow(item, basic); String exchange = value(item,"exchange_id");
            if (prior != null && prior.compareTo(exchange) >= 0) throw new IllegalStateException("D028 receipt rows are not in strict key order"); prior = exchange;
            var row = new MarginAllMapper().fromSource(new MarginAllMapper().dto(item));
            if (!keys.add(row.key())) throw new IllegalStateException("D028 receipt contains duplicate natural key"); rows.add(row);
        }
        return new SyncJobRunner.Page<>(List.copyOf(rows), fingerprint, file.toString(), basic);
    }
    private static void validateRow(Map<String,JsonNode> row, String expectedDate) {
        if (row == null || !row.keySet().equals(new HashSet<>(FIELDS))) throw new IllegalArgumentException("D028 provider fields differ from the nine-field source contract");
        if (!expectedDate.equals(value(row,"trade_date"))) throw new IllegalArgumentException("D028 source row is outside requested trade_date");
        if (!Set.of("SSE","SZSE","BSE").contains(value(row,"exchange_id"))) throw new IllegalArgumentException("D028 unknown provider exchange_id");
        for (String field : FIELDS) if (!Set.of("trade_date","exchange_id").contains(field)) {
            JsonNode v = row.get(field); if (v == null || v.isNull() || !(v.isNumber() || v.isTextual())) throw new IllegalArgumentException("D028 required numeric scalar missing: " + field);
            try { double n = new java.math.BigDecimal(v.asText().strip()).doubleValue(); if (!Double.isFinite(n)) throw new NumberFormatException(); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("D028 invalid numeric scalar: " + field, invalid); }
        }
    }
    private static byte[] body(LocalDate date, Map<String,Object> params, List<Map<String,JsonNode>> rows, String version, boolean complete, Exception failure) throws Exception {
        var json = JobDefinitionJson.canonicalMapper();
        var body = new LinkedHashMap<String,Object>(); body.put("sourceKind","tushare"); body.put("endpoint","margin"); body.put("sourceContractVersion",1);
        body.put("parameters",params); body.put("fields",FIELDS); body.put("tradeDate",date.toString()); body.put("apiMaximumRows",API_ROW_CAP);
        body.put("returnedRows",rows.size()); body.put("rawRows",rows); body.put("sourceComplete",complete);
        if (version != null) body.put("sourceVersion",version); if (failure != null) body.put("failureType",failure.getClass().getSimpleName());
        return json.writeValueAsBytes(body);
    }
    private void persistIncomplete(LocalDate date, Map<String,Object> params, List<Map<String,JsonNode>> rows, String version, Exception failure) throws Exception {
        byte[] bytes = body(date,params,rows,version,false,failure); if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalStateException("D028 incomplete raw receipt exceeds 4 MiB",failure);
        Files.createDirectories(evidenceRoot); persist(evidenceRoot.resolve("incomplete-"+date.format(BASIC)+"-"+sha(bytes)+".json"),bytes);
    }
    private static void persist(Path path, byte[] bytes) throws Exception {
        try { FileEvidenceStore.writeNew(path,bytes); }
        catch (java.nio.file.FileAlreadyExistsException exists) { if (!Arrays.equals(FileEvidenceStore.readBounded(path, Math.max(1, bytes.length),
                    () -> new IllegalStateException("Conflicting immutable D028 receipt", exists)),bytes)) throw new IllegalStateException("Conflicting immutable D028 receipt",exists); }
    }
    private static String value(Map<String,JsonNode> row, String field) { JsonNode v = row.get(field); return v == null || v.isNull() ? "" : v.asText(); }
    private static String sha(byte[] bytes) throws Exception { return FileEvidenceStore.sha256(bytes); }
}
