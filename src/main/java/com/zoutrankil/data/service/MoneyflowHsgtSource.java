package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MoneyflowHsgt;
import com.zoutrankil.data.domain.MoneyflowHsgtKey;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.mapper.MoneyflowHsgtMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** One bounded, non-offset moneyflow_hsgt range request with immutable raw response receipts. */
public final class MoneyflowHsgtSource {
    public static final int API_ROW_CAP = 300;
    public static final int MAX_RANGE_DAYS = 31;
    public static final int MAX_EVIDENCE_BYTES = 2 * 1024 * 1024;
    public static final List<String> FIELDS = List.of("trade_date", "ggt_ss", "ggt_sz", "hgt", "sgt", "north_money", "south_money");
    public static final PageContract CONTRACT = new PageContract("moneyflow_hsgt", FIELDS, List.of("trade_date"),
            Set.of("start_date", "end_date"), PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
            null, null, API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Python calls one range request; the API documents at most 300 returned rows and has no offset. Java splits into at most 31 calendar days, rejects 300-row cap hits, and rejects duplicate/out-of-range dates.");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private final TusharePageService pages;
    private final Path evidenceRoot;
    private final MoneyflowHsgtMapper mapper = new MoneyflowHsgtMapper();

    public MoneyflowHsgtSource(TusharePageService pages, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<MoneyflowHsgt> fetch(LocalDate from, LocalDate to, BooleanSupplier cancelled) throws Exception {
        requireWindow(from, to); Objects.requireNonNull(cancelled);
        String first = from.format(BASIC), last = to.format(BASIC);
        Map<String,Object> parameters = Map.of("start_date", first, "end_date", last);
        var raw = new ArrayList<Map<String,JsonNode>>();
        PageExecutor.Completed completed;
        var fetcher = pages.fetcher(CONTRACT, cancelled);
        try {
            completed = new PageExecutor().execute(CONTRACT, parameters, request -> {
                PageExecutor.Page response = fetcher.fetch(request);
                raw.addAll(response.rows());
                return response;
            }, (page, receipt) -> {}, row -> validateRow(row, from, to), cancelled);
        } catch (Exception failure) {
            try { persistIncomplete(from, to, parameters, raw, failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
        if (completed.pages() != 1 || completed.rows() != raw.size() || raw.size() >= API_ROW_CAP) {
            var failure = new IllegalStateException("D027 unpaged response is incomplete or reached the 300-row source cap");
            try { persistIncomplete(from, to, parameters, raw, failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
        try {
            raw.sort(Comparator.comparing(row -> field(row,"trade_date")));
            var keys = new HashSet<MoneyflowHsgtKey>(); var typed = new ArrayList<MoneyflowHsgt>(raw.size());
            for (var row : raw) {
                validateExactFields(row);
                MoneyflowHsgt value = mapper.fromSource(mapper.dto(row));
                if (value.tradeDate().isBefore(from) || value.tradeDate().isAfter(to) || !keys.add(value.key()))
                    throw new IllegalArgumentException("D027 duplicate or out-of-range trade_date in provider response");
                typed.add(value);
            }
            byte[] bytes = body(from, to, parameters, raw, completed.sourceVersion(), true, null);
            if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalStateException("D027 raw receipt exceeds 2 MiB");
            String fingerprint = sha(bytes); Files.createDirectories(evidenceRoot);
            Path receipt = evidenceRoot.resolve("moneyflow-hsgt-" + first + "-" + last + "-" + fingerprint + ".json");
            persist(receipt, bytes);
            return new SyncJobRunner.Page<>(List.copyOf(typed), fingerprint, receipt.toString(), first + ".." + last);
        } catch (Exception failure) {
            try { persistIncomplete(from, to, parameters, raw, failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
    }

    public static SyncJobRunner.Page<MoneyflowHsgt> reopen(Path receipt, String fingerprint,
            LocalDate expectedFrom, LocalDate expectedTo) throws Exception {
        requireWindow(expectedFrom, expectedTo);
        Path file = receipt.toAbsolutePath().normalize();
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || !Files.isRegularFile(file)
                || Files.isSymbolicLink(file) || Files.size(file) < 1 || Files.size(file) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("D027 bounded raw receipt and SHA-256 required");
        byte[] bytes = Files.readAllBytes(file);
        if (!sha(bytes).equals(fingerprint)) throw new IllegalStateException("D027 receipt SHA-256 mismatch");
        var json = JobDefinitionJson.mapper(); JsonNode body = json.readTree(bytes);
        if (!"tushare".equals(body.path("sourceKind").asText()) || !"moneyflow_hsgt".equals(body.path("endpoint").asText())
                || !body.path("sourceComplete").asBoolean(false)
                || !expectedFrom.toString().equals(body.path("fromInclusive").asText())
                || !expectedTo.toString().equals(body.path("toInclusive").asText())
                || body.path("apiMaximumRows").asInt(-1) != API_ROW_CAP
                || !json.valueToTree(FIELDS).equals(body.path("fields"))
                || !expectedFrom.format(BASIC).equals(body.path("parameters").path("start_date").asText())
                || !expectedTo.format(BASIC).equals(body.path("parameters").path("end_date").asText())
                || !body.path("rawRows").isArray() || body.path("returnedRows").asInt(-1) != body.path("rawRows").size()
                || body.path("rawRows").size() >= API_ROW_CAP)
            throw new IllegalStateException("D027 receipt differs from frozen range/field/cap contract");
        List<Map<String,JsonNode>> raw = json.convertValue(body.path("rawRows"), new TypeReference<>() {});
        var rows = new ArrayList<MoneyflowHsgt>(raw.size()); var keys = new HashSet<MoneyflowHsgtKey>();
        String prior = null;
        for (var row : raw) {
            validateRow(row, expectedFrom, expectedTo);
            String current = field(row,"trade_date");
            if (prior != null && prior.compareTo(current) >= 0) throw new IllegalStateException("D027 receipt rows are not strictly date-ordered");
            prior = current;
            var typed = new MoneyflowHsgtMapper().fromSource(new MoneyflowHsgtMapper().dto(row));
            if (!keys.add(typed.key())) throw new IllegalStateException("D027 receipt has duplicate natural dates");
            rows.add(typed);
        }
        return new SyncJobRunner.Page<>(rows, fingerprint, file.toString(), expectedFrom.format(BASIC) + ".." + expectedTo.format(BASIC));
    }

    private static void validateRow(Map<String,JsonNode> row, LocalDate from, LocalDate to) {
        validateExactFields(row);
        MoneyflowHsgt value = new MoneyflowHsgtMapper().fromSource(new MoneyflowHsgtMapper().dto(row));
        if (value.tradeDate().isBefore(from) || value.tradeDate().isAfter(to))
            throw new IllegalArgumentException("D027 provider date falls outside requested inclusive range");
    }
    private static void validateExactFields(Map<String,JsonNode> row) {
        if (row == null || !row.keySet().equals(new HashSet<>(FIELDS)))
            throw new IllegalArgumentException("D027 provider columns differ from its seven-field source contract");
    }
    private static byte[] body(LocalDate from, LocalDate to, Map<String,Object> params,
            List<Map<String,JsonNode>> rows, String version, boolean complete, Exception failure) throws Exception {
        var json = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        var body = new java.util.LinkedHashMap<String,Object>();
        body.put("sourceKind","tushare"); body.put("endpoint","moneyflow_hsgt"); body.put("sourceContractVersion",1);
        body.put("parameters",params); body.put("fields",FIELDS); body.put("fromInclusive",from); body.put("toInclusive",to);
        body.put("apiMaximumRows",API_ROW_CAP); body.put("returnedRows",rows.size()); body.put("rawRows",rows);
        body.put("sourceComplete",complete); if(version!=null)body.put("sourceVersion",version);
        if(failure!=null)body.put("failureType",failure.getClass().getSimpleName());
        return json.writeValueAsBytes(body);
    }
    private void persistIncomplete(LocalDate from,LocalDate to,Map<String,Object> params,
            List<Map<String,JsonNode>> rows,Exception failure)throws Exception {
        byte[] bytes=body(from,to,params,rows,null,false,failure);
        if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("D027 incomplete raw receipt exceeds 2 MiB",failure);
        Files.createDirectories(evidenceRoot);persist(evidenceRoot.resolve("incomplete-"+from.format(BASIC)+"-"+to.format(BASIC)+"-"+sha(bytes)+".json"),bytes);
    }
    private static void persist(Path path,byte[] bytes)throws Exception {
        try{Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
        catch(java.nio.file.FileAlreadyExistsException exists){if(!Arrays.equals(Files.readAllBytes(path),bytes))throw new IllegalStateException("Conflicting immutable D027 receipt",exists);}
    }
    private static String field(Map<String,JsonNode> row,String name) {
        JsonNode value=row.get(name);return value==null||value.isNull()?"":value.asText();
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void requireWindow(LocalDate from,LocalDate to) {
        if(from==null||to==null||from.isAfter(to)||ChronoUnit.DAYS.between(from,to)+1>MAX_RANGE_DAYS)
            throw new IllegalArgumentException("D027 source range must be ordered and at most 31 calendar days");
    }
}
