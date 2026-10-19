package com.zoutrankil.data.stock.mapper;


import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareStockLimitDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.StockLimit;
import com.zoutrankil.data.domain.StockLimitKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit Tushare -> typed DTO -> business model -> QuestDB mapping. */
public final class StockLimitMapper {
    public static final List<String> SOURCE_FIELDS = List.of("ts_code", "trade_date", "up_limit", "down_limit");

    public TushareStockLimitDto dto(Map<String, JsonNode> row) {
        return new TushareStockLimitDto(text(row, "ts_code"), text(row, "trade_date"),
                number(row, "up_limit"), number(row, "down_limit"));
    }

    public StockLimit fromSource(TushareStockLimitDto source) {
        if (source == null) throw new IllegalArgumentException("Tushare stk_limit row required");
        return new StockLimit(new StockLimitKey(source.tsCode(),
                TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC)),
                source.upLimit(), source.downLimit());
    }

    public DatasetValues values(StockLimit row) {
        if (row == null) throw new IllegalArgumentException("stk_limit row required");
        var values = new LinkedHashMap<String, Object>();
        values.put("ts_code", row.tsCode()); values.put("trade_date", row.tradeDate());
        values.put("up_limit", row.upLimit()); values.put("down_limit", row.downLimit());
        return new DatasetValues(values);
    }

    public StockLimit fromValues(DatasetValues values) {
        return new StockLimit(new StockLimitKey(values.get("ts_code", String.class),
                values.get("trade_date", LocalDate.class)), values.get("up_limit", Double.class),
                values.get("down_limit", Double.class));
    }

    private static String text(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isTextual())
            throw new IllegalArgumentException("Required text stk_limit field: " + field);
        return value.textValue();
    }

    private static Double number(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Finite numeric stk_limit field or null required: " + field);
        return value.doubleValue();
    }
}
