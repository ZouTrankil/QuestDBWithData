package com.zoutrankil.data.index.mapper;

import com.zoutrankil.data.mapper.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareIndexDailyDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.IndexDailyMarket;
import com.zoutrankil.data.domain.IndexDailyMarketKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.domain.policy.IndexDailyMarketUniverse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit endpoint alias and source-to-storage mapping for D019. */
public final class IndexDailyMarketMapper {
    public static final List<String> INDEX_DAILY_FIELDS = List.of("ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_chg", "vol", "amount");
    public static final List<String> SW_DAILY_FIELDS = List.of("ts_code", "trade_date", "close", "open", "high", "low", "change", "pct_change", "vol", "amount");

    public TushareIndexDailyDto dto(Map<String, JsonNode> row, IndexDailyMarketUniverse.Index index) {
        String pctField = index.route() == IndexDailyMarketUniverse.Route.SW_DAILY ? "pct_change" : "pct_chg";
        return new TushareIndexDailyDto(text(row, "ts_code"), text(row, "trade_date"),
                number(row, "close"), number(row, "open"), number(row, "high"), number(row, "low"),
                index.route() == IndexDailyMarketUniverse.Route.SW_DAILY ? null : number(row, "pre_close"),
                number(row, "change"), number(row, pctField),
                number(row, "vol"), number(row, "amount"));
    }
    public IndexDailyMarket fromSource(TushareIndexDailyDto source, IndexDailyMarketUniverse.Index index,
                                       Instant observedAt) {
        if (source == null || index == null) throw new IllegalArgumentException("Index daily source row and frozen index required");
        if (!index.tsCode().equals(source.tsCode())) throw new IllegalArgumentException("Provider index ts_code differs from frozen canonical code");
        LocalDate date = TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC);
        return new IndexDailyMarket(new IndexDailyMarketKey(source.tsCode(), date), source.close(), source.open(),
                source.high(), source.low(), source.preClose(), source.change(), source.pctChg(), source.vol(),
                source.amount(), observedAt);
    }
    public DatasetValues values(IndexDailyMarket row) {
        var values = new LinkedHashMap<String,Object>();
        values.put("ts_code", row.tsCode()); values.put("trade_date", row.tradeDate());
        values.put("close", row.close()); values.put("open", row.open()); values.put("high", row.high());
        values.put("low", row.low()); values.put("pre_close", row.preClose()); values.put("change", row.change());
        values.put("pct_chg", row.pctChg()); values.put("vol", row.vol()); values.put("amount", row.amount());
        values.put("update_time", row.updateTime());
        return new DatasetValues(values);
    }
    public IndexDailyMarket fromValues(DatasetValues values) {
        return new IndexDailyMarket(new IndexDailyMarketKey(values.get("ts_code", String.class),
                values.get("trade_date", LocalDate.class)), values.get("close", Double.class),
                values.get("open", Double.class), values.get("high", Double.class), values.get("low", Double.class),
                values.get("pre_close", Double.class), values.get("change", Double.class),
                values.get("pct_chg", Double.class), values.get("vol", Double.class),
                values.get("amount", Double.class), values.get("update_time", Instant.class));
    }
    private static String text(Map<String,JsonNode> row, String name) {
        JsonNode value = row.get(name);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("Required nonblank text index_daily field: " + name);
        return value.textValue();
    }
    private static Double number(Map<String,JsonNode> row, String name) {
        JsonNode value = row.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Finite numeric index_daily field or null required: " + name);
        return value.doubleValue();
    }
}
