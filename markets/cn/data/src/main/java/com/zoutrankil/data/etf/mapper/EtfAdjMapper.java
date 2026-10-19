package com.zoutrankil.data.etf.mapper;


import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareEtfAdjDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.EtfAdj;
import com.zoutrankil.data.domain.EtfAdjKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit Tushare -> typed DTO -> business model -> QuestDB mapping. */
public final class EtfAdjMapper {
    public static final List<String> SOURCE_FIELDS = List.of("ts_code", "trade_date", "adj_factor");

    public TushareEtfAdjDto dto(Map<String, JsonNode> row) {
        return new TushareEtfAdjDto(text(row, "ts_code"), text(row, "trade_date"), number(row, "adj_factor"));
    }

    public EtfAdj fromSource(TushareEtfAdjDto source) {
        if (source == null) throw new IllegalArgumentException("Tushare fund_adj row required");
        return new EtfAdj(new EtfAdjKey(source.tsCode(),
                TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC)), source.adjFactor());
    }

    public DatasetValues values(EtfAdj row) {
        if (row == null) throw new IllegalArgumentException("etf_adj row required");
        var values = new LinkedHashMap<String, Object>();
        values.put("ts_code", row.tsCode());
        values.put("trade_date", row.tradeDate());
        values.put("adj_factor", row.adjFactor());
        return new DatasetValues(values);
    }

    public EtfAdj fromValues(DatasetValues values) {
        return new EtfAdj(new EtfAdjKey(values.get("ts_code", String.class), values.get("trade_date", LocalDate.class)),
                values.get("adj_factor", Double.class));
    }

    private static String text(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("Required nonblank text fund_adj field: " + field);
        return value.textValue();
    }

    private static Double number(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Finite numeric fund_adj field or null required: " + field);
        return value.doubleValue();
    }
}
