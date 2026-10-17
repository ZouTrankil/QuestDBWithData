package com.zoutrankil.data.stock.mapper;


import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareStockFactorDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Explicit source, typed-domain, and physical-column mapping for all audited stk_factor fields. */
public final class StockFactorMapper {
    public static final List<String> SOURCE_FIELDS = List.of(
            "ts_code", "trade_date", "close", "open", "high", "low", "pre_close", "change", "pct_change",
            "vol", "amount", "adj_factor", "open_hfq", "open_qfq", "close_hfq", "close_qfq",
            "high_hfq", "high_qfq", "low_hfq", "low_qfq", "pre_close_hfq", "pre_close_qfq",
            "macd_dif", "macd_dea", "macd", "kdj_k", "kdj_d", "kdj_j", "rsi_6", "rsi_12", "rsi_24",
            "boll_upper", "boll_mid", "boll_lower", "cci");
    public TushareStockFactorDto fromSource(Map<String, JsonNode> row) {
        if (row == null || !row.keySet().containsAll(SOURCE_FIELDS))
            throw new IllegalArgumentException("Complete declared stk_factor source row required");
        return new TushareStockFactorDto(text(row, "ts_code"), text(row, "trade_date"), new StockFactorFields(
                number(row,"close"),number(row,"open"),number(row,"high"),number(row,"low"),
                number(row,"pre_close"),number(row,"change"),number(row,"pct_change"),number(row,"vol"),
                number(row,"amount"),number(row,"adj_factor"),number(row,"open_hfq"),number(row,"open_qfq"),
                number(row,"close_hfq"),number(row,"close_qfq"),number(row,"high_hfq"),number(row,"high_qfq"),
                number(row,"low_hfq"),number(row,"low_qfq"),number(row,"pre_close_hfq"),number(row,"pre_close_qfq"),
                number(row,"macd_dif"),number(row,"macd_dea"),number(row,"macd"),number(row,"kdj_k"),
                number(row,"kdj_d"),number(row,"kdj_j"),number(row,"rsi_6"),number(row,"rsi_12"),
                number(row,"rsi_24"),number(row,"boll_upper"),number(row,"boll_mid"),number(row,"boll_lower"),
                number(row,"cci")));
    }

    public StockFactor fromSource(TushareStockFactorDto source) {
        Objects.requireNonNull(source);
        if (source.tradeDate() == null) throw new IllegalArgumentException("Source trade_date required");
        return new StockFactor(new StockFactorKey(source.tsCode(),
                TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC)), source.fields());
    }

    public DatasetValues values(StockFactor row) {
        Objects.requireNonNull(row);
        var v = new LinkedHashMap<String,Object>();
        v.put("ts_code",row.tsCode()); v.put("trade_date",row.tradeDate());
        var f=row.fields();
        v.put("close",f.close()); v.put("open",f.open()); v.put("high",f.high()); v.put("low",f.low());
        v.put("pre_close",f.preClose()); v.put("change",f.change()); v.put("pct_change",f.pctChange());
        v.put("vol",f.vol()); v.put("amount",f.amount()); v.put("adj_factor",f.adjFactor());
        v.put("open_hfq",f.openHfq()); v.put("open_qfq",f.openQfq()); v.put("close_hfq",f.closeHfq());
        v.put("close_qfq",f.closeQfq()); v.put("high_hfq",f.highHfq()); v.put("high_qfq",f.highQfq());
        v.put("low_hfq",f.lowHfq()); v.put("low_qfq",f.lowQfq()); v.put("pre_close_hfq",f.preCloseHfq());
        v.put("pre_close_qfq",f.preCloseQfq()); v.put("macd_dif",f.macdDif()); v.put("macd_dea",f.macdDea());
        v.put("macd",f.macd()); v.put("kdj_k",f.kdjK()); v.put("kdj_d",f.kdjD()); v.put("kdj_j",f.kdjJ());
        v.put("rsi_6",f.rsi6()); v.put("rsi_12",f.rsi12()); v.put("rsi_24",f.rsi24());
        v.put("boll_upper",f.bollUpper()); v.put("boll_mid",f.bollMid()); v.put("boll_lower",f.bollLower());
        v.put("cci",f.cci());
        if (!v.keySet().equals(new HashSet<>(StockFactorDataset.DEFINITION.columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList())))
            throw new IllegalStateException("Stock factor mapper and frozen dataset definition differ");
        return new DatasetValues(v);
    }

    public StockFactor fromValues(DatasetValues values) {
        Objects.requireNonNull(values);
        return new StockFactor(new StockFactorKey(values.get("ts_code",String.class), values.get("trade_date",LocalDate.class)),
                new StockFactorFields(values.get("close",Double.class),values.get("open",Double.class),
                        values.get("high",Double.class),values.get("low",Double.class),values.get("pre_close",Double.class),
                        values.get("change",Double.class),values.get("pct_change",Double.class),values.get("vol",Double.class),
                        values.get("amount",Double.class),values.get("adj_factor",Double.class),
                        values.get("open_hfq",Double.class),values.get("open_qfq",Double.class),
                        values.get("close_hfq",Double.class),values.get("close_qfq",Double.class),
                        values.get("high_hfq",Double.class),values.get("high_qfq",Double.class),
                        values.get("low_hfq",Double.class),values.get("low_qfq",Double.class),
                        values.get("pre_close_hfq",Double.class),values.get("pre_close_qfq",Double.class),
                        values.get("macd_dif",Double.class),values.get("macd_dea",Double.class),values.get("macd",Double.class),
                        values.get("kdj_k",Double.class),values.get("kdj_d",Double.class),values.get("kdj_j",Double.class),
                        values.get("rsi_6",Double.class),values.get("rsi_12",Double.class),values.get("rsi_24",Double.class),
                        values.get("boll_upper",Double.class),values.get("boll_mid",Double.class),
                        values.get("boll_lower",Double.class),values.get("cci",Double.class)));
    }

    private static String text(Map<String,JsonNode> row,String field) {
        JsonNode value=row.get(field);
        if(value==null || value.isNull()) return null;
        if(!value.isTextual()) throw new IllegalArgumentException("Text source field required: "+field);
        return value.textValue();
    }
    private static Double number(Map<String,JsonNode> row,String field) {
        JsonNode value=row.get(field);
        if(value==null || value.isNull()) return null;
        if(!value.isNumber() && !value.isTextual()) throw new IllegalArgumentException("Numeric source field required: "+field);
        String raw=value.asText();
        if(raw.isBlank()) throw new IllegalArgumentException("Empty non-null numeric source field: "+field);
        double parsed;
        try { parsed=new BigDecimal(raw).doubleValue(); }
        catch(NumberFormatException invalid) { throw new IllegalArgumentException("Invalid numeric source field: "+field,invalid); }
        if(!Double.isFinite(parsed)) throw new IllegalArgumentException("Non-finite numeric source field: "+field);
        return parsed;
    }
}
