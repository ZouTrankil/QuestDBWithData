package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.EtfShare;
import com.zoutrankil.questdbwithdata.domain.EtfShareDataset;
import com.zoutrankil.questdbwithdata.domain.EtfShareKey;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.mapper.EtfShareMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
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
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Bounded fund_share responses for one date, partitioned by provider-supported SH/SZ/O market filters. */
public final class EtfShareSource {
    public static final int REQUEST_CAP = 2000;
    /** Each market response is rejected at 2000; at most 1999 complete rows per market. */
    public static final int MAX_ROWS_PER_DATE = 3 * (REQUEST_CAP - 1);
    public static final int MAX_REQUESTS_PER_DATE = 3;
    public static final int MAX_EVIDENCE_BYTES = 32 * 1024 * 1024;
    public static final List<String> REQUESTED_MARKETS = List.of("SH", "SZ", "O");
    public static final List<String> FIELDS = EtfShareMapper.SOURCE_FIELDS;
    public static final PageContract CONTRACT = new PageContract("fund_share", FIELDS,
            List.of("ts_code", "trade_date"), Set.of("trade_date", "market"),
            PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
            null, null, REQUEST_CAP, REQUEST_CAP, 1, REQUEST_CAP,
            "Official Tushare fund_share doc207 (checked 2026-09-30): one response is capped at 2000 rows; docs describe SH/SZ market filters and no offset/limit paging. Bounded explicit-field probes accepted fund_type and market. A 2026-09-17 unfiltered five-field diagnostic proved 1774 rows = SH 999 + SZ 766 + O 9; market=O independently returned the same nine .OF codes. Java requests SH/SZ/O separately, maps .OF to provider market O, rejects any 2000-row partition, and has a conservative 5997-row date ceiling.");

    private record MarketCapture(String market, int rows, List<Map<String, JsonNode>> rawRows) {}
    private static final ObjectMapper RECEIPT_JSON = JobDefinitionJson.mapper()
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final TusharePageService pages;
    private final EtfShareMapper mapper;
    private final Path evidenceRoot;

    public EtfShareSource(TusharePageService pages, EtfShareMapper mapper, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages);
        this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<EtfShare> fetch(LocalDate date, Instant observedAt,
                                               BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(date); Objects.requireNonNull(cancelled);
        EtfShareDataset.requireObservation(observedAt);
        String basic = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        var captures = new ArrayList<MarketCapture>();
        var attempts = new ArrayList<Map<String, Object>>();
        var allRows = new ArrayList<Map<String, JsonNode>>();
        try {
            var fetcher = pages.fetcher(CONTRACT, cancelled);
            for (String market : REQUESTED_MARKETS) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException("fund_share market slice cancelled");
                var parameters = new LinkedHashMap<String, Object>();
                parameters.put("trade_date", basic);
                parameters.put("market", market);
                attempts.add(new LinkedHashMap<>(parameters));
                var completed = new PageExecutor().execute(CONTRACT, parameters, request -> {
                    PageExecutor.Page response = fetcher.fetch(request);
                    captures.add(new MarketCapture(market, response.rows().size(), response.rows()));
                    var rows = response.rows().stream().map(EtfShareSource::orderedRow)
                            .sorted(Comparator.comparing(row -> row.get("ts_code").asText())).toList();
                    captures.set(captures.size() - 1, new MarketCapture(market, rows.size(), rows));
                    return new PageExecutor.Page(rows, null, false, null);
                }, (page, receipt) -> allRows.addAll(page.rows()),
                        row -> validate(row, basic, market), cancelled);
                if (completed.pages() != 1 || completed.rows() != captures.getLast().rows())
                    throw new IllegalStateException("fund_share market response did not complete as one short response");
            }
        } catch (Exception failure) {
            try { persistIncomplete(date, observedAt, attempts, captures, failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
        if (captures.size() != REQUESTED_MARKETS.size() || allRows.size() > MAX_ROWS_PER_DATE
                || allRows.size() != captures.stream().mapToInt(MarketCapture::rows).sum())
            throw new IllegalStateException("fund_share did not produce exactly three complete bounded market responses");

        allRows.sort(Comparator.comparing(row -> row.get("ts_code").asText()));
        var typed = new ArrayList<EtfShare>();
        var keys = new HashSet<EtfShareKey>();
        for (var capture : captures) {
            for (var row : capture.rawRows()) {
                EtfShare mapped = mapper.fromSource(row, capture.market(), observedAt);
                if (!mapped.tradeDate().equals(date) || !keys.add(mapped.key()))
                    throw new IllegalStateException("fund_share contains duplicate or out-of-date full business keys");
                typed.add(mapped);
            }
        }
        typed.sort(Comparator.comparing(EtfShare::tsCode).thenComparing(EtfShare::tradeDate));

        var marketEvidence = captures.stream().map(capture -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("market", capture.market()); value.put("rows", capture.rows());
            return value;
        }).toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "fund_share");
        body.put("fields", FIELDS); body.put("tradeDate", date);
        body.put("observedAt", observedAt.toString()); body.put("requestCap", REQUEST_CAP);
        body.put("requests", attempts);
        body.put("dateRowCap", MAX_ROWS_PER_DATE); body.put("marketResponses", marketEvidence);
        body.put("rawRowsByMarket", captures.stream().map(MarketCapture::rawRows).toList());
        body.put("returnedRows", typed.size()); body.put("sourceComplete", true); body.put("sourceVersion", null);
        byte[] bytes = RECEIPT_JSON.writeValueAsBytes(body);
        requireEvidenceSize(bytes);
        String fingerprint = sha256(bytes);
        Files.createDirectories(evidenceRoot);
        Path receipt = evidenceRoot.resolve("etf-share-" + basic + "-" + UUID.randomUUID() + ".json");
        Files.write(receipt, bytes, StandardOpenOption.CREATE_NEW);
        return new SyncJobRunner.Page<>(typed, fingerprint, receipt.toString(), basic);
    }

    /** Rebuilds the exact frozen normalized rows from an immutable three-market source receipt. */
    public static SyncJobRunner.Page<EtfShare> reopen(Path receipt, String expectedFingerprint,
                                                       LocalDate expectedDate) throws Exception {
        Objects.requireNonNull(receipt); Objects.requireNonNull(expectedDate);
        if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(receipt) || Files.size(receipt) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("Bounded etf_share receipt and SHA-256 required");
        byte[] bytes = Files.readAllBytes(receipt);
        if (!sha256(bytes).equals(expectedFingerprint)) throw new IllegalStateException("etf_share source receipt fingerprint changed");
        var proof = RECEIPT_JSON.readTree(bytes);
        if (!proof.path("endpoint").asText().equals("fund_share") || !proof.path("sourceComplete").asBoolean(false)
                || !proof.path("tradeDate").asText().equals(expectedDate.toString())
                || !RECEIPT_JSON.valueToTree(FIELDS).equals(proof.path("fields"))
                || proof.path("requestCap").asInt(-1) != REQUEST_CAP
                || proof.path("dateRowCap").asInt(-1) != MAX_ROWS_PER_DATE
                || proof.path("requests").size() != REQUESTED_MARKETS.size()
                || proof.path("rawRowsByMarket").size() != REQUESTED_MARKETS.size()
                || proof.path("marketResponses").size() != REQUESTED_MARKETS.size())
            throw new IllegalStateException("etf_share receipt scope, fields or completion evidence differs");
        Instant observedAt;
        try { observedAt = EtfShareDataset.requireObservation(Instant.parse(proof.path("observedAt").asText())); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid frozen etf_share receipt observation time", invalid); }

        var responseRows = RECEIPT_JSON.convertValue(proof.path("rawRowsByMarket"),
                new TypeReference<List<List<Map<String, JsonNode>>>>() {});
        var normalized = new ArrayList<EtfShare>();
        var seen = new HashSet<EtfShareKey>();
        int total = 0;
        for (int index = 0; index < REQUESTED_MARKETS.size(); index++) {
            String market = REQUESTED_MARKETS.get(index);
            JsonNode summary = proof.path("marketResponses").get(index);
            JsonNode request = proof.path("requests").get(index);
            List<Map<String, JsonNode>> rows = responseRows.get(index);
            if (!request.path("trade_date").asText().equals(expectedDate.format(DateTimeFormatter.BASIC_ISO_DATE))
                    || !request.path("market").asText().equals(market)
                    || !summary.path("market").asText().equals(market) || summary.path("rows").asInt(-1) != rows.size()
                    || rows.size() >= REQUEST_CAP)
                throw new IllegalStateException("etf_share market receipt is unordered, inconsistent or at source cap");
            String previousCode = null;
            for (var row : rows) {
                validate(row, expectedDate.format(DateTimeFormatter.BASIC_ISO_DATE), market);
                if (previousCode != null && previousCode.compareTo(row.get("ts_code").asText()) >= 0)
                    throw new IllegalStateException("etf_share receipt rows are not strictly canonical by ts_code");
                previousCode = row.get("ts_code").asText();
                var item = new EtfShareMapper().fromSource(row, market, observedAt);
                if (!seen.add(item.key())) throw new IllegalStateException("Duplicate full etf_share key in receipt");
                normalized.add(item); total++;
            }
        }
        if (total > MAX_ROWS_PER_DATE || total != proof.path("returnedRows").asInt(-1))
            throw new IllegalStateException("etf_share receipt row totals differ");
        return new SyncJobRunner.Page<>(normalized, expectedFingerprint,
                receipt.toAbsolutePath().normalize().toString(), expectedDate.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    private static Map<String, JsonNode> orderedRow(Map<String, JsonNode> row) {
        if (row == null || !row.keySet().equals(new HashSet<>(FIELDS)))
            throw new IllegalArgumentException("fund_share response fields differ from the frozen five-field projection");
        var ordered = new LinkedHashMap<String, JsonNode>();
        for (String field : FIELDS) ordered.put(field, row.get(field));
        return ordered;
    }

    private static void validate(Map<String, JsonNode> row, String date, String market) {
        orderedRow(row); // Reject unknown/missing source columns even when reopening a saved receipt.
        var source = new EtfShareMapper().dto(row);
        LocalDate sourceDate = TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC);
        var key = new EtfShareKey(source.tsCode(), sourceDate);
        if (!date.equals(key.tradeDate().format(DateTimeFormatter.BASIC_ISO_DATE)))
            throw new IllegalArgumentException("fund_share row lies outside frozen trade_date");
        String suffix = key.tsCode().substring(key.tsCode().length() - 2);
        String suffixMarket = suffix.equals("OF") ? "O" : suffix;
        if (!suffixMarket.equals(market) || !market.equals(source.market()))
            throw new IllegalArgumentException("fund_share code/provider market differs from requested market=" + market);
    }

    private void persistIncomplete(LocalDate date, Instant observedAt, List<Map<String, Object>> attempts,
                                   List<MarketCapture> captures, Exception failure) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "fund_share"); body.put("fields", FIELDS);
        body.put("tradeDate", date); body.put("observedAt", observedAt.toString()); body.put("requestCap", REQUEST_CAP);
        body.put("dateRowCap", MAX_ROWS_PER_DATE); body.put("attemptedRequests", attempts);
        body.put("capturedResponses", captures.stream().map(capture -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("market", capture.market()); item.put("rows", capture.rows());
            item.put("rawRows", capture.rawRows());
            return item;
        }).toList());
        body.put("sourceComplete", false); body.put("evidenceStatus", "unverified_raw_response");
        body.put("failureCategory", failure.getClass().getSimpleName()); body.put("failure", failure.getMessage());
        byte[] bytes = RECEIPT_JSON.writeValueAsBytes(body);
        requireEvidenceSize(bytes); Files.createDirectories(evidenceRoot);
        Files.write(evidenceRoot.resolve("incomplete-" + UUID.randomUUID() + ".json"), bytes, StandardOpenOption.CREATE_NEW);
    }

    private static void requireEvidenceSize(byte[] bytes) {
        if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("etf_share evidence exceeds 32 MiB budget");
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
