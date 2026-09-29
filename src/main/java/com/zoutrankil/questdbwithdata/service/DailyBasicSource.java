package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.client.dto.TushareDailyBasicDto;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.DailyBasicMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One explicit trade_date request, with the official 6000-row cap treated as possible truncation. */
public final class DailyBasicSource {
    public static final int API_ROW_CAP = 6000;
    public static final List<String> FIELDS = List.of("ts_code", "trade_date", "close", "turnover_rate",
            "turnover_rate_f", "volume_ratio", "pe", "pe_ttm", "pb", "ps", "ps_ttm", "dv_ratio",
            "dv_ttm", "total_share", "float_share", "free_share", "total_mv", "circ_mv");
    private static final PageContract CONTRACT = new PageContract("daily_basic", FIELDS,
            List.of("ts_code", "trade_date"), Set.of("trade_date"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Official Tushare daily_basic API: one trade_date request, maximum 6000 rows, no documented offset cursor");

    private final TusharePageService pages;
    private final DailyBasicMapper mapper;
    private final Path evidenceRoot;
    public DailyBasicSource(TusharePageService pages, DailyBasicMapper mapper, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages);
        this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<DailyBasic> fetch(LocalDate tradeDate, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(tradeDate); Objects.requireNonNull(cancelled);
        String date = tradeDate.toString().replace("-", "");
        var parameters = Map.<String, Object>of("trade_date", date);
        var rawRows = new ArrayList<Map<String, JsonNode>>();
        var receipt = new PageExecutor.Receipt[1];
        var responsePath = new Path[1];
        Files.createDirectories(evidenceRoot);
        var fetcher = pages.fetcher(CONTRACT, cancelled);
        var complete = new PageExecutor().execute(CONTRACT, parameters, request -> {
            var response = fetcher.fetch(request);
            var rawEvidence = new LinkedHashMap<String, Object>();
            rawEvidence.put("endpoint", "daily_basic"); rawEvidence.put("parameters", parameters);
            rawEvidence.put("fields", FIELDS); rawEvidence.put("rows", response.rows());
            rawEvidence.put("explicitEnd", response.explicitEnd());
            if (response.sourceVersion() != null) rawEvidence.put("sourceVersion", response.sourceVersion());
            byte[] rawBytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsBytes(rawEvidence);
            responsePath[0] = evidenceRoot.resolve("response-" + UUID.randomUUID() + ".json");
            Files.write(responsePath[0], rawBytes, StandardOpenOption.CREATE_NEW);
            return response;
        }, (page, sourceReceipt) -> {
            rawRows.addAll(page.rows());
            receipt[0] = sourceReceipt;
        }, row -> {
            String code = text(row, "ts_code");
            String rowDate = text(row, "trade_date");
            if (code == null || !code.matches("[0-9]{6}\\.(SZ|SH|BJ)"))
                throw new IllegalArgumentException("daily_basic returned an invalid stock code");
            if (!date.equals(rowDate)) throw new IllegalArgumentException("daily_basic returned a row for another trade_date");
        }, cancelled);
        if (complete.pages() != 1 || complete.rows() != rawRows.size())
            throw new IllegalStateException("daily_basic date response did not produce one complete bounded response");
        if (responsePath[0] == null) throw new IllegalStateException("daily_basic response receipt was not preserved");
        var typed = new ArrayList<DailyBasic>(rawRows.size());
        for (var row : rawRows) typed.add(mapper.fromSource(dto(row)));

        var json = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        var body = new LinkedHashMap<String, Object>();
        body.put("endpoint", "daily_basic"); body.put("parameters", parameters); body.put("fields", FIELDS);
        body.put("tradeDate", tradeDate); body.put("responseEvidence", responsePath[0].toString());
        body.put("rows", rawRows); body.put("receipt", receipt[0]);
        body.put("sourceComplete", true); body.put("apiMaximumRows", API_ROW_CAP);
        byte[] bytes = json.writeValueAsBytes(body);
        var fingerprintBody = new LinkedHashMap<String, Object>();
        fingerprintBody.put("endpoint", "daily_basic"); fingerprintBody.put("parameters", parameters);
        fingerprintBody.put("fields", FIELDS); fingerprintBody.put("tradeDate", tradeDate); fingerprintBody.put("rows", rawRows);
        if (complete.sourceVersion() != null) fingerprintBody.put("sourceVersion", complete.sourceVersion());
        byte[] canonical = json.writeValueAsBytes(fingerprintBody);
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        Path file = evidenceRoot.resolve("source-" + UUID.randomUUID() + ".json");
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        return new SyncJobRunner.Page<>(typed, fingerprint, file.toString(), null);
    }

    private static TushareDailyBasicDto dto(Map<String, JsonNode> row) {
        return new TushareDailyBasicDto(text(row, "ts_code"), text(row, "trade_date"), numberText(row, "close"),
                numberText(row, "turnover_rate"), numberText(row, "turnover_rate_f"), numberText(row, "volume_ratio"),
                numberText(row, "pe"), numberText(row, "pe_ttm"), numberText(row, "pb"), numberText(row, "ps"),
                numberText(row, "ps_ttm"), numberText(row, "dv_ratio"), numberText(row, "dv_ttm"),
                numberText(row, "total_share"), numberText(row, "float_share"), numberText(row, "free_share"),
                numberText(row, "total_mv"), numberText(row, "circ_mv"));
    }
    private static String text(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Text source field required: " + field);
        return value.textValue();
    }
    private static String numberText(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.asText();
        if (value.isTextual()) return value.textValue();
        throw new IllegalArgumentException("Numeric scalar source field required: " + field);
    }
}
