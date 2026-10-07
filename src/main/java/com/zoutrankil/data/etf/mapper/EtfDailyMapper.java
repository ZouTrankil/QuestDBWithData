package com.zoutrankil.data.etf.mapper;


import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareEtfDailyDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.EtfDaily;
import com.zoutrankil.data.domain.EtfDailyKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit Tushare -> typed DTO -> business model -> QuestDB mapping. */
public final class EtfDailyMapper {
    public static final List<String> SOURCE_FIELDS = List.of("ts_code", "trade_date", "pre_close", "open", "high", "low", "close", "change", "pct_chg", "vol", "amount");

    public TushareEtfDailyDto dto(Map<String, JsonNode> row) {
        return new TushareEtfDailyDto(text(row, "ts_code"), text(row, "trade_date"),
                number(row, "pre_close"), number(row, "open"), number(row, "high"), number(row, "low"), number(row, "close"), number(row, "change"), number(row, "pct_chg"), number(row, "vol"), number(row, "amount"));
    }

    public EtfDaily fromSource(TushareEtfDailyDto source) {
        if (source == null) throw new IllegalArgumentException("Tushare etf_daily row required");
        return new EtfDaily(new EtfDailyKey(source.tsCode(),
                TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC)),
                source.preClose(), source.open(), source.high(), source.low(), source.close(), source.change(), source.pctChg(), source.vol(), source.amount());
    }

    public DatasetValues values(EtfDaily row) {
        if (row == null) throw new IllegalArgumentException("etf_daily row required");
        var values = new LinkedHashMap<String, Object>();
        values.put("ts_code", row.tsCode()); values.put("trade_date", row.tradeDate());
        values.put("pre_close", row.preClose());
        values.put("open", row.open());
        values.put("high", row.high());
        values.put("low", row.low());
        values.put("close", row.close());
        values.put("change", row.change());
        values.put("pct_chg", row.pctChg());
        values.put("vol", row.vol());
        values.put("amount", row.amount());
        return new DatasetValues(values);
    }

    public EtfDaily fromValues(DatasetValues values) {
        return new EtfDaily(new EtfDailyKey(values.get("ts_code", String.class),
                values.get("trade_date", LocalDate.class)), values.get("pre_close", Double.class), values.get("open", Double.class), values.get("high", Double.class), values.get("low", Double.class), values.get("close", Double.class), values.get("change", Double.class), values.get("pct_chg", Double.class), values.get("vol", Double.class), values.get("amount", Double.class));
    }

    private static String text(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isTextual())
            throw new IllegalArgumentException("Required text etf_daily field: " + field);
        return value.textValue();
    }

    private static Double number(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Finite numeric etf_daily field or null required: " + field);
        return value.doubleValue();
    }
}
