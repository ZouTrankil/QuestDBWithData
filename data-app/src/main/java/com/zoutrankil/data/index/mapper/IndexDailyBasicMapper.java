package com.zoutrankil.data.index.mapper;


import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareIndexDailyBasicDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.IndexDailyBasic;
import com.zoutrankil.data.domain.IndexDailyBasicKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.domain.policy.IndexDailyBasicUniverse;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit D020 wire, business, and physical-field mapping. */
public final class IndexDailyBasicMapper {
    public TushareIndexDailyBasicDto dto(Map<String, JsonNode> row) {
        return new TushareIndexDailyBasicDto(text(row, "ts_code"), text(row, "trade_date"),
                number(row, "total_mv"), number(row, "float_mv"), number(row, "total_share"),
                number(row, "float_share"), number(row, "free_share"), number(row, "turnover_rate"),
                number(row, "turnover_rate_f"), number(row, "pe"), number(row, "pe_ttm"), number(row, "pb"));
    }
    public IndexDailyBasic fromSource(TushareIndexDailyBasicDto source, String expectedCode) {
        String canonical = IndexDailyBasicUniverse.resolve(expectedCode);
        if (source == null || canonical == null || !canonical.equals(source.tsCode()))
            throw new IllegalArgumentException("Provider index code differs from frozen D020 source identity");
        LocalDate date = TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC);
        return new IndexDailyBasic(new IndexDailyBasicKey(source.tsCode(), date), source.totalMv(), source.floatMv(),
                source.totalShare(), source.floatShare(), source.freeShare(), source.turnoverRate(),
                source.turnoverRateF(), source.pe(), source.peTtm(), source.pb());
    }
    public DatasetValues values(IndexDailyBasic row) {
        var values = new LinkedHashMap<String,Object>();
        values.put("ts_code", row.tsCode()); values.put("trade_date", row.tradeDate());
        values.put("total_mv", row.totalMv()); values.put("float_mv", row.floatMv());
        values.put("total_share", row.totalShare()); values.put("float_share", row.floatShare());
        values.put("free_share", row.freeShare()); values.put("turnover_rate", row.turnoverRate());
        values.put("turnover_rate_f", row.turnoverRateF()); values.put("pe", row.pe());
        values.put("pe_ttm", row.peTtm()); values.put("pb", row.pb());
        return new DatasetValues(values);
    }
    public IndexDailyBasic fromValues(DatasetValues values) {
        return new IndexDailyBasic(new IndexDailyBasicKey(values.get("ts_code", String.class),
                values.get("trade_date", LocalDate.class)), values.get("total_mv", Double.class),
                values.get("float_mv", Double.class), values.get("total_share", Double.class),
                values.get("float_share", Double.class), values.get("free_share", Double.class),
                values.get("turnover_rate", Double.class), values.get("turnover_rate_f", Double.class),
                values.get("pe", Double.class), values.get("pe_ttm", Double.class), values.get("pb", Double.class));
    }
    private static String text(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("Required nonblank D020 source text: " + field);
        return value.textValue();
    }
    private static Double number(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Finite D020 number or null required: " + field);
        return value.doubleValue();
    }
}
