package com.zoutrankil.data.margin.mapper;

import com.zoutrankil.data.margin.domain.MarginSecsRows;


import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareMarginSecsDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MarginSecs;
import com.zoutrankil.data.domain.MarginSecsKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.Map;

/** Explicit D030 raw DTO -> business row -> physical field mapping. */
public final class MarginSecsMapper {
    public TushareMarginSecsDto dto(Map<String, JsonNode> row) {
        return new TushareMarginSecsDto(required(row, "trade_date"), required(row, "ts_code"),
                optional(row, "name"), required(row, "exchange"));
    }

    public MarginSecs fromSource(TushareMarginSecsDto row) {
        if (row == null) throw new IllegalArgumentException("D030 source DTO required");
        String sourceDate = row.tradeDate() == null ? null : row.tradeDate().strip().replace("-", "");
        LocalDate date = TemporalValues.businessDate(sourceDate, TemporalValues.DateFormat.BASIC);
        String code = row.tsCode() == null ? null : row.tsCode().strip();
        return new MarginSecs(new MarginSecsKey(date, code), row.name(), row.exchange());
    }

    public DatasetValues values(MarginSecs row) { return com.zoutrankil.data.margin.domain.MarginSecsRows.values(row); }

    public MarginSecs fromValues(DatasetValues values) {
        return new MarginSecs(new MarginSecsKey(values.get("trade_date", LocalDate.class),
                values.get("ts_code", String.class)), values.get("name", String.class),
                values.get("exchange", String.class));
    }

    private static String required(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !(value.isTextual() || value.isIntegralNumber()) || value.asText().isBlank())
            throw new IllegalArgumentException("Required D030 field missing: " + field);
        return value.asText();
    }

    private static String optional(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("D030 optional name must be textual or null");
        return value.textValue();
    }
}
