package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.client.dto.StockSuspendDto;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import com.zoutrankil.questdbwithdata.domain.StockSuspend;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.mapper.StockSuspendMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One finite suspend_d request for one calendar date. */
public final class StockSuspendSource {
    /** Official suspend_d documentation caps a response at 5,000 rows and documents no offset cursor. */
    public static final int SOURCE_ROW_CAP = 5_000;
    public static final int MAX_EVIDENCE_BYTES = 16 * 1024 * 1024;
    public static final PageContract CONTRACT = new PageContract("suspend_d", StockSuspendMapper.SOURCE_FIELDS,
            List.of("ts_code", "trade_date"), Set.of("trade_date", "suspend_type"),
            PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE, null, null,
            SOURCE_ROW_CAP, SOURCE_ROW_CAP, 1, SOURCE_ROW_CAP,
            "Official Tushare suspend_d docs (https://tushare.pro/document/2?doc_id=214): date query, max 5,000 rows; no offset/cursor contract.");

    public record Query(LocalDate tradeDate) {
        public Query { Objects.requireNonNull(tradeDate); }
        public Map<String,Object> parameters() {
            return Map.of("trade_date", tradeDate.format(DateTimeFormatter.BASIC_ISO_DATE), "suspend_type", "S");
        }
    }
    public record Result(List<StockSuspend> rows, String fingerprint, String receipt, int pages) {
        public Result { rows = List.copyOf(rows); }
    }

    private final TusharePageService pages;
    private final Path evidenceRoot;
    private final StockSuspendMapper mapper = new StockSuspendMapper();
    private final ObjectMapper json = JobDefinitionJson.mapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public StockSuspendSource(TusharePageService pages, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages);
        this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
    }

    public Result fetch(Query query, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(query); Objects.requireNonNull(cancelled);
        var raw = new ArrayList<Map<String,JsonNode>>();
        var captured = new PageExecutor.Page[1];
        PageExecutor.Completed complete;
        try {
            var fetcher = pages.fetcher(CONTRACT, cancelled);
            complete = new PageExecutor().execute(CONTRACT, query.parameters(), parameters -> {
                var response = fetcher.fetch(parameters);
                captured[0] = response; // Retain the unmodified response before cap/row validation.
                return response;
            }, (page, receipt) -> raw.addAll(page.rows()), row -> {
                StockSuspendDto dto = mapper.fromSource(row);
                StockSuspend typed = mapper.fromSource(dto);
                if (!typed.tradeDate().equals(query.tradeDate()))
                    throw new IllegalArgumentException("suspend_d row lies outside the frozen trade_date");
            }, cancelled);
        } catch (Exception failure) {
            if (captured[0] != null && failure instanceof PageExecutor.Incomplete) {
                try { persistIncompleteEvidence(query, captured[0], failure); }
                catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            }
            throw failure;
        }
        if (complete.pages() != 1 || complete.rows() != raw.size())
            throw new IllegalStateException("suspend_d completion count differs from captured response");

        // Provider row order is not an identity guarantee. Canonicalize only order; keep every raw field/value intact.
        raw.sort(Comparator.comparing((Map<String,JsonNode> row) -> row.get("ts_code").asText())
                .thenComparing(row -> row.get("trade_date").asText()));

        var rows = new ArrayList<StockSuspend>(raw.size());
        var normalized = new ArrayList<Map<String,Object>>(raw.size());
        for (var item : raw) {
            StockSuspendDto dto = mapper.fromSource(item);
            StockSuspend row = mapper.fromSource(dto);
            if (!row.tradeDate().equals(query.tradeDate())) throw new IllegalStateException("suspend_d scope changed after mapping");
            rows.add(row);
            var evidenceRow = new LinkedHashMap<String,Object>(mapper.values(row).asMap());
            evidenceRow.put("suspend_timing", dto.suspendTiming());
            normalized.add(evidenceRow);
        }
        byte[] body = json.writeValueAsBytes(Map.ofEntries(
                Map.entry("sourceKind", "tushare"), Map.entry("endpoint", "suspend_d"),
                Map.entry("parameters", query.parameters()), Map.entry("fields", StockSuspendMapper.SOURCE_FIELDS),
                Map.entry("tradeDate", query.tradeDate().toString()), Map.entry("sourceRowCap", SOURCE_ROW_CAP),
                Map.entry("returnedRows", raw.size()), Map.entry("normalizedRows", normalized),
                Map.entry("rows", raw), Map.entry("sourceComplete", true), Map.entry("pages", complete.pages())));
        if (body.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("suspend_d source evidence exceeds byte budget");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        Files.createDirectories(evidenceRoot);
        Path receipt = evidenceRoot.resolve((rows.isEmpty() ? "empty-" : "source-") + UUID.randomUUID() + ".json");
        Files.write(receipt, body, StandardOpenOption.CREATE_NEW);
        return new Result(rows, hash, receipt.toString(), complete.pages());
    }

    private void persistIncompleteEvidence(Query query, PageExecutor.Page response, Exception failure) throws Exception {
        var body = new LinkedHashMap<String,Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "suspend_d");
        body.put("parameters", query.parameters()); body.put("fields", StockSuspendMapper.SOURCE_FIELDS);
        body.put("tradeDate", query.tradeDate().toString()); body.put("responseRows", response.rows().size());
        body.put("rows", response.rows()); body.put("sourceComplete", false);
        body.put("evidenceStatus", "unverified_raw_response"); body.put("failureCategory", failure.getClass().getSimpleName());
        body.put("failure", failure.getMessage()); body.put("sourceRowCap", SOURCE_ROW_CAP);
        if (response.sourceVersion() != null) body.put("sourceVersion", response.sourceVersion());
        byte[] bytes = json.writeValueAsBytes(body);
        if (bytes.length > MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("suspend_d incomplete evidence exceeds byte budget");
        Files.createDirectories(evidenceRoot);
        Files.write(evidenceRoot.resolve("incomplete-" + UUID.randomUUID() + ".json"), bytes, StandardOpenOption.CREATE_NEW);
    }
}
