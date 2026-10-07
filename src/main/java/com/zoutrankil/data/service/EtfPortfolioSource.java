package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.EtfPortfolio;
import com.zoutrankil.data.domain.EtfPortfolioKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.mapper.EtfPortfolioMapper;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Complete bounded fund_portfolio response for one exact calendar announcement date. */
public final class EtfPortfolioSource {
    public static final String ENDPOINT = "fund_portfolio";
    public static final int CONTRACT_VERSION = 1;
    public static final int PAGE_SIZE = 8_000;
    /** Finite local cap: larger announcement dates preserve incomplete evidence and fail closed. */
    public static final int MAX_ROWS_PER_ANN_DATE = 1_000_000;
    public static final int MAX_PAGES_PER_ANN_DATE = MAX_ROWS_PER_ANN_DATE / PAGE_SIZE + 1;
    public static final int RUNNER_PAGE_ROWS = 10_000;
    public static final int MAX_CHUNKS_PER_ANN_DATE = (MAX_ROWS_PER_ANN_DATE + RUNNER_PAGE_ROWS - 1) / RUNNER_PAGE_ROWS;
    public static final int MAX_EVIDENCE_BYTES = 256 * 1024 * 1024;
    private static final int LEGACY_ROW_CAP = 256_000;
    private static final String CHUNK_REFERENCE_ENCODING = "source_receipt_reference_v1";
    public static final List<String> FIELDS = EtfPortfolioMapper.SOURCE_FIELDS;
    public static final PageContract CONTRACT = new PageContract(ENDPOINT, FIELDS,
            List.of("ts_code", "ann_date", "end_date", "symbol"), Set.of("ann_date", "limit", "offset"),
            PageContract.Paging.OFFSET, PageContract.Completion.SHORT_PAGE, "limit", "offset", PAGE_SIZE,
            PAGE_SIZE, MAX_PAGES_PER_ANN_DATE, MAX_ROWS_PER_ANN_DATE,
            "Python etf_portfolio_sync._get_etf_portfolio_by_ann_date sends limit=8000 and increasing offset; official Tushare fund_portfolio doc_id=121 confirms ann_date but does not document paging. Java follows the observed connector parameters, caps each ann_date at 1000000 rows/126 calls including a terminal empty page, emits verified 10000-row runner chunks, and fails closed without a short terminal response.");

    private record CapturedPage(long offset, int rows, List<Map<String, JsonNode>> rawRows) {}
    private record Persisted(byte[] bytes, Path path) {}

    public record Chunk(int index, int count, int offset, int totalRows, int sourcePages, List<EtfPortfolio> rows,
                        String fingerprint, String responseEvidence, String cursor,
                        String sourceFingerprint, Path sourceReceipt) {
        public Chunk { rows = List.copyOf(rows); Objects.requireNonNull(sourceReceipt); }
    }
    public record Result(LocalDate date, List<EtfPortfolio> rows, List<Chunk> chunks,
                         String fingerprint, String receipt, int sourcePages) {
        public Result { rows = List.copyOf(rows); chunks = List.copyOf(chunks); }
    }

    private final TusharePageService pages;
    private final EtfPortfolioMapper mapper;
    private final Path evidenceRoot;

    public EtfPortfolioSource(TusharePageService pages, EtfPortfolioMapper mapper, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages); this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public Result fetch(LocalDate annDate, Instant observedAt, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(annDate); Objects.requireNonNull(observedAt); Objects.requireNonNull(cancelled);
        com.zoutrankil.data.domain.temporal.TemporalValues.requirePrecision(observedAt,
                com.zoutrankil.data.domain.temporal.TemporalValues.Precision.MICROS);
        String basic = annDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        Map<String, Object> parameters = Map.of("ann_date", basic);
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
            }, (page, receipt) -> raw.addAll(page.rows()), row -> validate(row, annDate, observedAt), cancelled);
        } catch (Exception failure) {
            try { persistIncomplete(annDate, parameters, attempts, captured, failure); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
        if (complete.pages() != captured.size() || complete.rows() != raw.size() || captured.isEmpty()
                || raw.size() > MAX_ROWS_PER_ANN_DATE)
            throw new IllegalStateException("fund_portfolio did not produce a bounded complete ann_date page sequence");

        raw.sort(Comparator.comparing((Map<String, JsonNode> row) -> row.get("ts_code").asText())
                .thenComparing(row -> row.get("ann_date").asText())
                .thenComparing(row -> row.get("end_date").asText())
                .thenComparing(row -> row.get("symbol").asText()));
        var canonicalRows = raw.stream().map(EtfPortfolioSource::canonicalRow).toList();
        var typed = canonicalRows.stream().map(mapper::dto).map(row -> mapper.fromSource(row, annDate, observedAt)).toList();
        var keys = new HashSet<EtfPortfolioKey>();
        for (var row : typed) if (!keys.add(row.key()))
            throw new IllegalStateException("fund_portfolio source contains a duplicate complete key");

        var json = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        var pageEvidence = new ArrayList<Map<String,Object>>(captured.size());
        for(var page:captured)pageEvidence.add(Map.of("offset",page.offset(),"limit",PAGE_SIZE,"rows",page.rows(),
                "rawSha256",sha256(json.writeValueAsBytes(page.rawRows().stream().map(EtfPortfolioSource::canonicalRow).toList()))));
        var body = new LinkedHashMap<String, Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", ENDPOINT);
        body.put("sourceContractVersion", CONTRACT_VERSION); body.put("parameters", parameters);
        body.put("fields", FIELDS); body.put("announcementDate", annDate); body.put("observedAt", observedAt);
        body.put("pageSize", PAGE_SIZE); body.put("pageEvidence", pageEvidence);
        body.put("rawRows", canonicalRows); body.put("returnedRows", typed.size());
        body.put("sourceComplete", true); body.put("annDateRowCap", MAX_ROWS_PER_ANN_DATE);
        body.put("sourcePages", complete.pages());
        body.put("sourceVersion", complete.sourceVersion());
        byte[] bytes = json.writeValueAsBytes(body); requireEvidenceSize(bytes);
        String fingerprint = sha256(bytes);
        Files.createDirectories(evidenceRoot);
        Path receipt = evidenceRoot.resolve("source-" + basic + "-" + fingerprint + ".json");
        writeImmutable(receipt, bytes);
        int chunkCount = Math.max(1, (typed.size() + RUNNER_PAGE_ROWS - 1) / RUNNER_PAGE_ROWS);
        if (chunkCount > MAX_CHUNKS_PER_ANN_DATE) throw new IllegalStateException("fund_portfolio chunk count exceeds local budget");
        var chunks = new ArrayList<Chunk>(chunkCount);
        for (int index = 0; index < chunkCount; index++) {
            int offset = index * RUNNER_PAGE_ROWS;
            int end = Math.min(typed.size(), offset + RUNNER_PAGE_ROWS);
            List<EtfPortfolio> rows = List.copyOf(typed.subList(offset, end));
            List<Map<String, JsonNode>> chunkRaw = List.copyOf(canonicalRows.subList(offset, end));
            var chunkBody = new LinkedHashMap<String, Object>();
            chunkBody.put("sourceKind", "tushare"); chunkBody.put("endpoint", ENDPOINT);
            chunkBody.put("sourceContractVersion", CONTRACT_VERSION); chunkBody.put("parameters", parameters);
            chunkBody.put("fields", FIELDS); chunkBody.put("announcementDate", annDate); chunkBody.put("observedAt", observedAt);
            chunkBody.put("pageSize", PAGE_SIZE); chunkBody.put("pageEvidence", pageEvidence);
            chunkBody.put("annDateRowCap", MAX_ROWS_PER_ANN_DATE); chunkBody.put("sourceComplete", true);
            chunkBody.put("sourceFingerprint", fingerprint); chunkBody.put("sourceReceipt", receipt.getFileName().toString());
            chunkBody.put("totalReturnedRows", typed.size()); chunkBody.put("chunkIndex", index);
            chunkBody.put("chunkCount", chunkCount); chunkBody.put("chunkOffset", offset);
            // The full immutable response owns the raw rows. Runner receipts carry only a bounded subset reference.
            chunkBody.put("chunkEncoding", CHUNK_REFERENCE_ENCODING);
            chunkBody.put("chunkRowsSha256", sha256(json.writeValueAsBytes(chunkRaw)));chunkBody.put("returnedRows", rows.size());
            byte[] chunkBytes = json.writeValueAsBytes(chunkBody); requireEvidenceSize(chunkBytes);
            String chunkFingerprint = sha256(chunkBytes);
            Path chunkReceipt = evidenceRoot.resolve("chunk-" + basic + "-" + fingerprint + "-" + index + ".json");
            writeImmutable(chunkReceipt, chunkBytes);
            chunks.add(new Chunk(index, chunkCount, offset, typed.size(), complete.pages(), rows, chunkFingerprint, chunkReceipt.toString(),
                    basic + "#" + index, fingerprint, receipt));
        }
        return new Result(annDate, typed, chunks, fingerprint, receipt.toString(), complete.pages());
    }

    /** Reopens a runner-sized receipt chunk and proves its rows against the complete response receipt. */
    public static Chunk reopen(Path receipt, String expectedFingerprint,
            LocalDate expectedDate, Instant expectedObservedAt) throws Exception {
        Objects.requireNonNull(receipt); Objects.requireNonNull(expectedDate); Objects.requireNonNull(expectedObservedAt);
        if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(receipt) || Files.size(receipt) > MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("Bounded fund_portfolio receipt and SHA-256 required");
        byte[] bytes = Files.readAllBytes(receipt);
        if (!sha256(bytes).equals(expectedFingerprint)) throw new IllegalStateException("fund_portfolio receipt fingerprint changed");
        var json = JobDefinitionJson.mapper(); var proof = json.readTree(bytes);
        String basic = expectedDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        int rowCap=proof.path("annDateRowCap").asInt(-1),returned=proof.path("returnedRows").asInt(-1);
        boolean referenced=CHUNK_REFERENCE_ENCODING.equals(proof.path("chunkEncoding").asText(""));
        if (!proof.path("sourceKind").asText().equals("tushare") || !proof.path("endpoint").asText().equals(ENDPOINT)
                || !proof.path("sourceComplete").asBoolean(false)
                || proof.path("sourceContractVersion").asInt(-1) != CONTRACT_VERSION
                || !proof.path("announcementDate").asText().equals(expectedDate.toString())
                || !proof.path("observedAt").asText().equals(expectedObservedAt.toString())
                || !json.valueToTree(FIELDS).equals(proof.path("fields"))
                || proof.path("pageSize").asInt(-1) != PAGE_SIZE
                || !supportedRowCap(rowCap) || returned<0 || returned>RUNNER_PAGE_ROWS
                || (referenced ? !proof.path("chunkRowsSha256").asText("").matches("[0-9a-f]{64}")||proof.has("rawRows")
                    : proof.has("chunkEncoding")||!proof.path("rawRows").isArray()||returned!=proof.path("rawRows").size())
                || !proof.path("parameters").path("ann_date").asText().equals(basic)
                || proof.path("pageEvidence").isMissingNode())
            throw new IllegalStateException("fund_portfolio receipt scope, paging or completion evidence differs");
        String globalFingerprint = proof.path("sourceFingerprint").asText("");
        String fullName = proof.path("sourceReceipt").asText("");
        int index = proof.path("chunkIndex").asInt(-1), count = proof.path("chunkCount").asInt(-1);
        int offset = proof.path("chunkOffset").asInt(-1), total = proof.path("totalReturnedRows").asInt(-1);
        if (!globalFingerprint.matches("[0-9a-f]{64}") || !fullName.matches("source-" + basic + "-[0-9a-f]{64}\\.json")
                || !fullName.endsWith(globalFingerprint + ".json") || index < 0 || count < 1
                || count > (rowCap+RUNNER_PAGE_ROWS-1)/RUNNER_PAGE_ROWS || index >= count || total < 0 || total > rowCap
                || count != Math.max(1, (total + RUNNER_PAGE_ROWS - 1) / RUNNER_PAGE_ROWS)
                || offset != index * RUNNER_PAGE_ROWS || returned!=Math.min(RUNNER_PAGE_ROWS,total-offset)
                || !validPageEvidence(proof.path("pageEvidence"), total,rowCap))
            throw new IllegalStateException("Invalid fund_portfolio chunk manifest");
        Path parent = receipt.toAbsolutePath().normalize().getParent();
        Path realParent = parent.toRealPath();
        if (!receipt.toRealPath().startsWith(realParent))
            throw new IllegalStateException("fund_portfolio chunk receipt resolves outside its evidence directory");
        Path fullReceipt = parent.resolve(fullName).normalize();
        if (!fullReceipt.startsWith(parent) || !Files.isRegularFile(fullReceipt) || Files.size(fullReceipt) > MAX_EVIDENCE_BYTES)
            throw new IllegalStateException("fund_portfolio chunk points outside/misses complete response receipt");
        if (!fullReceipt.toRealPath().startsWith(realParent))
            throw new IllegalStateException("fund_portfolio complete receipt resolves outside its evidence directory");
        byte[] fullBytes = Files.readAllBytes(fullReceipt);
        if (!sha256(fullBytes).equals(globalFingerprint)) throw new IllegalStateException("Complete fund_portfolio response receipt hash changed");
        var full = json.readTree(fullBytes);
        int sourcePages = full.path("sourcePages").asInt(-1);
        if (!full.path("sourceKind").asText().equals("tushare") || !full.path("endpoint").asText().equals(ENDPOINT)
                || !full.path("sourceComplete").asBoolean(false)
                || full.path("sourceContractVersion").asInt(-1) != CONTRACT_VERSION
                || !full.path("announcementDate").asText().equals(expectedDate.toString())
                || !full.path("observedAt").asText().equals(expectedObservedAt.toString())
                || !json.valueToTree(FIELDS).equals(full.path("fields")) || !full.path("rawRows").isArray() || full.path("rawRows").size() != total
                || full.path("returnedRows").asInt(-1) != total || !full.path("parameters").path("ann_date").asText().equals(basic)
                || full.path("pageSize").asInt(-1) != PAGE_SIZE
                || full.path("annDateRowCap").asInt(-1) != rowCap
                || sourcePages < 1 || sourcePages > rowCap/PAGE_SIZE+1 || sourcePages!=full.path("pageEvidence").size()
                || !json.valueToTree(proof.path("pageEvidence")).equals(full.path("pageEvidence"))
                || !validPageEvidence(full.path("pageEvidence"), total,rowCap))
            throw new IllegalStateException("Complete fund_portfolio response receipt differs from chunk manifest");
        var mapper = new EtfPortfolioMapper(); var rows = new ArrayList<EtfPortfolio>();
        var keys = new HashSet<EtfPortfolioKey>();
        var subset=json.createArrayNode();for(int position=offset;position<offset+returned;position++)subset.add(full.path("rawRows").get(position));
        var raw = json.convertValue(referenced?subset:proof.path("rawRows"), new TypeReference<List<Map<String, JsonNode>>>() {});
        if (referenced ? !sha256(json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(raw)).equals(proof.path("chunkRowsSha256").asText())
                : !proof.path("rawRows").equals(subset))
            throw new IllegalStateException("fund_portfolio chunk rows differ from complete raw response receipt");
        for (var value : raw) {
            validate(value, expectedDate, expectedObservedAt);
            var row = mapper.fromSource(mapper.dto(value), expectedDate, expectedObservedAt);
            if (!keys.add(row.key())) throw new IllegalStateException("Duplicate complete key in fund_portfolio receipt");
            rows.add(row);
        }
        return new Chunk(index, count, offset, total, sourcePages, rows, expectedFingerprint, receipt.toAbsolutePath().normalize().toString(),
                basic + "#" + index, globalFingerprint, fullReceipt);
    }

    private static boolean validPageEvidence(JsonNode pages, int totalRows,int rowCap) {
        if (!supportedRowCap(rowCap)||!pages.isArray() || pages.size() < 1 || pages.size() > rowCap/PAGE_SIZE+1) return false;
        long expectedOffset = 0; int counted = 0;
        for (int index = 0; index < pages.size(); index++) {
            JsonNode page = pages.get(index); int count = page.path("rows").asInt(-1);
            if (page.path("offset").asLong(-1) != expectedOffset || page.path("limit").asInt(-1) != PAGE_SIZE
                    || count < 0 || count > PAGE_SIZE || count < PAGE_SIZE && index != pages.size() - 1
                    || (rowCap!=LEGACY_ROW_CAP||page.has("rawSha256"))&&!page.path("rawSha256").asText("").matches("[0-9a-f]{64}")) return false;
            expectedOffset = Math.addExact(expectedOffset, count); counted = Math.addExact(counted, count);
        }
        return counted == totalRows && counted <= rowCap
                && pages.get(pages.size() - 1).path("rows").asInt() < PAGE_SIZE;
    }
    private static boolean supportedRowCap(int rowCap){return rowCap==LEGACY_ROW_CAP||rowCap==MAX_ROWS_PER_ANN_DATE;}

    private static void validate(Map<String, JsonNode> row, LocalDate date, Instant observedAt) {
        new EtfPortfolioMapper().fromSource(row, date, observedAt);
    }

    private void persistIncomplete(LocalDate date, Map<String, Object> params, List<Map<String, Object>> attempts,
                                   List<CapturedPage> pages, Exception failure) throws Exception {
        var responses = pages.stream().map(page -> Map.of("offset", page.offset(), "rows", page.rawRows())).toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", ENDPOINT); body.put("parameters", params);
        body.put("fields", FIELDS); body.put("announcementDate", date); body.put("pageSize", PAGE_SIZE);
        body.put("annDateRowCap", MAX_ROWS_PER_ANN_DATE); body.put("attemptedRequests", attempts);
        body.put("pages", responses); body.put("sourceComplete", false);
        body.put("evidenceStatus", "unverified_raw_pages");
        body.put("failureCategory", failure.getClass().getSimpleName()); body.put("failure", failure.getMessage());
        byte[] bytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(body);
        requireEvidenceSize(bytes); Files.createDirectories(evidenceRoot);
        Files.write(evidenceRoot.resolve("incomplete-" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + UUID.randomUUID() + ".json"), bytes, StandardOpenOption.CREATE_NEW);
    }

    private static Map<String, JsonNode> canonicalRow(Map<String, JsonNode> row) {
        return new TreeMap<>(row);
    }
    private static void requireEvidenceSize(byte[] bytes) {
        if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("fund_portfolio evidence exceeds 256 MiB");
    }
    private static void writeImmutable(Path path, byte[] bytes) throws Exception {
        try { Files.write(path, bytes, StandardOpenOption.CREATE_NEW); }
        catch (java.nio.file.FileAlreadyExistsException existing) {
            if (!java.util.Arrays.equals(Files.readAllBytes(path), bytes))
                throw new IllegalStateException("Existing fund_portfolio evidence filename has different bytes", existing);
        }
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
