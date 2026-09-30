package com.zoutrankil.data.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.StockStNameChangeDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.StockStDaily;
import com.zoutrankil.data.domain.StockStDailyKey;
import com.zoutrankil.data.domain.StockStPeriod;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent mapping for raw namechange intervals and derived daily status rows. */
public final class StockStDailyMapper {
    public static final List<String> SOURCE_FIELDS = List.of("ts_code", "name", "start_date", "end_date");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    public StockStPeriod period(Map<String, JsonNode> row) {
        if (row == null || !row.keySet().containsAll(SOURCE_FIELDS))
            throw new IllegalArgumentException("Complete namechange interval fields required");
        String code = text(row, "ts_code", false);
        String name = text(row, "name", false);
        String start = text(row, "start_date", false);
        String end = text(row, "end_date", true);
        var period = new StockStPeriod(code, name, day(start, false), day(end, true));
        if (period.isStName() && !code.matches("[0-9]{6}\\.(?:SH|SZ|BJ)"))
            throw new IllegalArgumentException("ST namechange interval requires a six-digit listed-stock code");
        return period;
    }

    public StockStDaily toDaily(String code, LocalDate tradeDate) {
        return new StockStDaily(new StockStDailyKey(code, tradeDate), 1);
    }

    public DatasetValues values(StockStDaily row) {
        if (row == null) throw new IllegalArgumentException("stk_st_daily row required");
        var values = new LinkedHashMap<String,Object>();
        values.put("ts_code", row.tsCode()); values.put("is_st", row.isSt()); values.put("timestamp", row.timestamp());
        return new DatasetValues(values);
    }

    public StockStDaily fromValues(DatasetValues values) {
        return new StockStDaily(new StockStDailyKey(values.get("ts_code", String.class),
                values.get("timestamp", LocalDate.class)), values.get("is_st", Integer.class));
    }

    private static String text(Map<String,JsonNode> row, String field, boolean blankAllowed) {
        JsonNode node = row.get(field);
        if (node == null || node.isNull()) {
            if (blankAllowed) return "";
            throw new IllegalArgumentException("Required namechange text field: " + field);
        }
        if (!node.isTextual()) throw new IllegalArgumentException("Namechange field must be text: " + field);
        String value = node.textValue().trim();
        if (value.isEmpty() && !blankAllowed)
            throw new IllegalArgumentException("Required namechange text field: " + field);
        return value;
    }
    private static LocalDate day(String raw, boolean blankMeansOpen) {
        if (raw.isEmpty() && blankMeansOpen) return null;
        if (!raw.matches("[0-9]{8}")) throw new IllegalArgumentException("Namechange date must be YYYYMMDD");
        try { return TemporalValues.businessDate(raw, TemporalValues.DateFormat.BASIC); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid namechange calendar date", invalid); }
    }
}
