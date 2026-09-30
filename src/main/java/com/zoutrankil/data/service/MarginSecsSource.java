package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginSecs;
import com.zoutrankil.data.domain.MarginSecsKey;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.mapper.MarginSecsMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** One bounded non-offset margin_secs(trade_date) call with immutable raw provider receipts. */
public final class MarginSecsSource {
    /** The separate Python historical reader treats 6000 as an unverified cap; exact-cap responses fail closed. */
    public static final int API_ROW_CAP = 6000;
    public static final int MAX_EVIDENCE_BYTES = 16 * 1024 * 1024;
    public static final List<String> FIELDS = List.of("trade_date", "ts_code", "name", "exchange");
    public static final PageContract CONTRACT = new PageContract("margin_secs", FIELDS,
            List.of("trade_date", "ts_code"), Set.of("trade_date"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Python calls pro.margin_secs(trade_date=YYYYMMDD) once per SSE open date, with no offset. The adjacent historical reader flags >=6000 rows as cap_hit_unverified; Java treats that as a conservative ceiling.");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private final TusharePageService pages;
    private final Path evidenceRoot;
    private final MarginSecsMapper mapper = new MarginSecsMapper();

    public MarginSecsSource(TusharePageService pages, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<MarginSecs> fetch(LocalDate date, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(date); Objects.requireNonNull(cancelled);
        String basic = date.format(BASIC); Map<String,Object> parameters = Map.of("trade_date", basic);
        var raw = new ArrayList<Map<String,JsonNode>>();
        PageExecutor.Completed completed;
        var fetcher = pages.fetcher(CONTRACT, cancelled);
        try {
            completed = new PageExecutor().execute(CONTRACT, parameters, request -> {
                PageExecutor.Page response = fetcher.fetch(request);
                raw.addAll(response.rows());
                return response;
            }, (page, receipt) -> {}, row -> validateRow(row, date), cancelled);
        } catch (Exception failure) {
            try { persistIncomplete(date, parameters, raw, failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
        if (completed.pages() != 1 || completed.rows() != raw.size() || raw.size() >= API_ROW_CAP) {
            var failure = new IllegalStateException("D030 unpaged margin_secs response is incomplete or reached the conservative 6000-row cap");
            persistIncomplete(date, parameters, raw, failure);
            throw failure;
        }
        try {
            raw.sort(Comparator.comparing(row -> value(row, "ts_code")));
            var typed = new ArrayList<MarginSecs>(raw.size());
            var keys = new HashSet<MarginSecsKey>();
            for (var row : raw) {
                validateRow(row, date);
                MarginSecs normalized = mapper.fromSource(mapper.dto(row));
                if (!keys.add(normalized.key())) throw new IllegalArgumentException("D030 duplicate (trade_date,ts_code) source key");
                typed.add(normalized);
            }
            byte[] bytes = body(date, parameters, raw, completed.sourceVersion(), true, null);
            if (bytes.length < 1 || bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalStateException("D030 raw receipt exceeds 16 MiB");
            String fingerprint = sha(bytes); Files.createDirectories(evidenceRoot);
            Path receipt = evidenceRoot.resolve("margin-secs-" + basic + "-" + fingerprint + ".json");
            persist(receipt, bytes);
            return new SyncJobRunner.Page<>(List.copyOf(typed), fingerprint, receipt.toString(), basic);
        } catch (Exception failure) {
            try { persistIncomplete(date, parameters, raw, failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
    }

    public static SyncJobRunner.Page<MarginSecs> reopen(Path receipt, String fingerprint, LocalDate expectedDate) throws Exception {
        Objects.requireNonNull(expectedDate);
        Path file = receipt.toAbsolutePath().normalize();
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || !Files.isRegularFile(file)
                || Files.isSymbolicLink(file) || Files.size(file) < 1 || Files.size(file) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("D030 bounded raw receipt and SHA-256 required");
        byte[] bytes = Files.readAllBytes(file);
        if (!sha(bytes).equals(fingerprint)) throw new IllegalStateException("D030 receipt SHA-256 mismatch");
        var json = JobDefinitionJson.mapper(); JsonNode body = json.readTree(bytes); String basic = expectedDate.format(BASIC);
        if (!"tushare".equals(body.path("sourceKind").asText()) || !"margin_secs".equals(body.path("endpoint").asText())
                || !body.path("sourceComplete").asBoolean(false) || !expectedDate.toString().equals(body.path("tradeDate").asText())
                || body.path("apiMaximumRows").asInt(-1) != API_ROW_CAP || !json.valueToTree(FIELDS).equals(body.path("fields"))
                || !basic.equals(body.path("parameters").path("trade_date").asText())
                || !body.path("rawRows").isArray() || body.path("returnedRows").asInt(-1) != body.path("rawRows").size()
                || body.path("rawRows").size() >= API_ROW_CAP)
            throw new IllegalStateException("D030 receipt differs from frozen endpoint/date/fields/cap contract");
        List<Map<String,JsonNode>> raw = json.convertValue(body.path("rawRows"), new TypeReference<>() {});
        var typed = new ArrayList<MarginSecs>(raw.size()); var keys = new HashSet<MarginSecsKey>(); String prior = null;
        for (var row : raw) {
            validateRow(row, expectedDate); String code = value(row, "ts_code");
            if (prior != null && prior.compareTo(code) >= 0) throw new IllegalStateException("D030 receipt rows are not in strict code order");
            prior = code; var item = new MarginSecsMapper().fromSource(new MarginSecsMapper().dto(row));
            if (!keys.add(item.key())) throw new IllegalStateException("D030 receipt contains duplicate source keys");
            typed.add(item);
        }
        return new SyncJobRunner.Page<>(List.copyOf(typed), fingerprint, file.toString(), basic);
    }

    private static void validateRow(Map<String,JsonNode> row, LocalDate date) {
        if (row == null || !row.keySet().equals(new HashSet<>(FIELDS)))
            throw new IllegalArgumentException("D030 provider fields differ from its four-field contract");
        String rawDate = value(row, "trade_date").replace("-", "");
        if (!date.format(BASIC).equals(rawDate)) throw new IllegalArgumentException("D030 provider row is outside frozen trade_date");
        String code = value(row, "ts_code").strip();
        if (!code.matches("[0-9]{6}\\.(?:SH|SZ|BJ)")) throw new IllegalArgumentException("D030 ts_code is invalid");
        JsonNode exchange = row.get("exchange");
        if (exchange == null || exchange.isNull() || !exchange.isTextual() || exchange.asText().isBlank()
                || !exchange.asText().equals(exchange.asText().strip()))
            throw new IllegalArgumentException("D030 provider exchange is required and trimmed");
        JsonNode name = row.get("name");
        if (name != null && !name.isNull() && !name.isTextual()) throw new IllegalArgumentException("D030 name must be text or null");
    }

    private static byte[] body(LocalDate date, Map<String,Object> parameters, List<Map<String,JsonNode>> rows,
            String version, boolean complete, Exception failure) throws Exception {
        var json = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        var body = new LinkedHashMap<String,Object>(); body.put("sourceKind", "tushare"); body.put("endpoint", "margin_secs");
        body.put("sourceContractVersion", 1); body.put("parameters", parameters); body.put("fields", FIELDS);
        body.put("tradeDate", date); body.put("apiMaximumRows", API_ROW_CAP); body.put("returnedRows", rows.size());
        body.put("rawRows", rows); body.put("sourceComplete", complete);
        if (version != null) body.put("sourceVersion", version);
        if (failure != null) body.put("failureType", failure.getClass().getSimpleName());
        return json.writeValueAsBytes(body);
    }

    private void persistIncomplete(LocalDate date, Map<String,Object> parameters, List<Map<String,JsonNode>> rows,
            Exception failure) throws Exception {
        byte[] bytes = body(date, parameters, rows, null, false, failure);
        if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalStateException("D030 incomplete receipt exceeds 16 MiB", failure);
        Files.createDirectories(evidenceRoot);
        persist(evidenceRoot.resolve("incomplete-" + date.format(BASIC) + "-" + sha(bytes) + ".json"), bytes);
    }

    private static void persist(Path path, byte[] bytes) throws Exception {
        try { Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
        catch (java.nio.file.FileAlreadyExistsException exists) {
            if (!Arrays.equals(Files.readAllBytes(path), bytes)) throw new IllegalStateException("Conflicting immutable D030 receipt", exists);
        }
    }
    private static String value(Map<String,JsonNode> row, String field) { JsonNode value = row.get(field); return value == null || value.isNull() ? "" : value.asText(); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
