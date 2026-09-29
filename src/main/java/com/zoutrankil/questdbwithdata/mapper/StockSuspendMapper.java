package com.zoutrankil.questdbwithdata.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.client.dto.StockSuspendDto;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.*;

/** Explicit four-field source to three-field physical mapping, including the business-date rename. */
public final class StockSuspendMapper {
    public static final List<String> SOURCE_FIELDS = List.of("ts_code", "trade_date", "suspend_timing", "suspend_type");

    public StockSuspendDto fromSource(Map<String, JsonNode> row) {
        if (row == null || !row.keySet().containsAll(SOURCE_FIELDS))
            throw new IllegalArgumentException("Complete declared suspend_d row required");
        String timing = nullableText(row, "suspend_timing");
        return new StockSuspendDto(requiredText(row, "ts_code"), requiredText(row, "trade_date"), timing,
                requiredText(row, "suspend_type"));
    }

    public StockSuspend fromSource(StockSuspendDto source) {
        Objects.requireNonNull(source);
        LocalDate date = TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC);
        if (!source.tsCode().matches("[0-9]{6}\\.(?:SH|SZ|BJ)"))
            throw new IllegalArgumentException("Invalid Tushare A-share code");
        return new StockSuspend(new StockSuspendKey(source.tsCode(), date), 1L);
    }

    public DatasetValues values(StockSuspend row) {
        Objects.requireNonNull(row);
        var values = new LinkedHashMap<String,Object>();
        values.put("ts_code", row.tsCode());
        values.put("is_suspended", row.isSuspended());
        values.put("trade_date", row.tradeDate());
        if (!values.keySet().equals(new LinkedHashSet<>(StockSuspendDataset.DEFINITION.columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList())))
            throw new IllegalStateException("StockSuspend mapper differs from frozen dataset definition");
        return new DatasetValues(values);
    }

    public StockSuspend fromValues(DatasetValues values) {
        Objects.requireNonNull(values);
        return new StockSuspend(new StockSuspendKey(values.get("ts_code", String.class),
                values.get("trade_date", LocalDate.class)), values.get("is_suspended", Long.class));
    }

    private static String requiredText(Map<String,JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("Nonblank textual source field required: " + field);
        return value.textValue().trim();
    }
    private static String nullableText(Map<String,JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Text or null source field required: " + field);
        return value.textValue();
    }
}
