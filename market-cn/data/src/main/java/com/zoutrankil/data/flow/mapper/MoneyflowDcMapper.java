package com.zoutrankil.data.flow.mapper;


import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareMoneyflowDcDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MoneyflowDc;
import com.zoutrankil.data.domain.MoneyflowDcKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit provider DTO -> business row -> QuestDB physical mapping for all 15 columns. */
public final class MoneyflowDcMapper {
    public TushareMoneyflowDcDto dto(Map<String, JsonNode> row) {
        return new TushareMoneyflowDcDto(text(row,"ts_code"), text(row,"trade_date"), textOrNull(row,"name"),
                scalar(row,"pct_change"), scalar(row,"close"), scalar(row,"net_amount"), scalar(row,"net_amount_rate"),
                scalar(row,"buy_elg_amount"), scalar(row,"buy_elg_amount_rate"), scalar(row,"buy_lg_amount"), scalar(row,"buy_lg_amount_rate"),
                scalar(row,"buy_md_amount"), scalar(row,"buy_md_amount_rate"), scalar(row,"buy_sm_amount"), scalar(row,"buy_sm_amount_rate"));
    }
    public MoneyflowDc fromSource(TushareMoneyflowDcDto d) {
        if (d == null) throw new IllegalArgumentException("moneyflow_dc source DTO required");
        LocalDate date = TemporalValues.businessDate(d.tradeDate(), TemporalValues.DateFormat.BASIC);
        return new MoneyflowDc(new MoneyflowDcKey(d.tsCode(), date), d.name(), number(d.pctChange()), number(d.close()),
                number(d.netAmount()), number(d.netAmountRate()), number(d.buyElgAmount()), number(d.buyElgAmountRate()),
                number(d.buyLgAmount()), number(d.buyLgAmountRate()), number(d.buyMdAmount()), number(d.buyMdAmountRate()),
                number(d.buySmAmount()), number(d.buySmAmountRate()));
    }
    public DatasetValues values(MoneyflowDc r) {
        var v = new LinkedHashMap<String,Object>(); v.put("ts_code",r.tsCode()); v.put("trade_date",r.tradeDate()); v.put("name",r.name());
        v.put("pct_change",r.pctChange()); v.put("close",r.close()); v.put("net_amount",r.netAmount()); v.put("net_amount_rate",r.netAmountRate());
        v.put("buy_elg_amount",r.buyElgAmount()); v.put("buy_elg_amount_rate",r.buyElgAmountRate());
        v.put("buy_lg_amount",r.buyLgAmount()); v.put("buy_lg_amount_rate",r.buyLgAmountRate());
        v.put("buy_md_amount",r.buyMdAmount()); v.put("buy_md_amount_rate",r.buyMdAmountRate());
        v.put("buy_sm_amount",r.buySmAmount()); v.put("buy_sm_amount_rate",r.buySmAmountRate()); return new DatasetValues(v);
    }
    public MoneyflowDc fromValues(DatasetValues v) {
        return new MoneyflowDc(new MoneyflowDcKey(v.get("ts_code",String.class),v.get("trade_date",LocalDate.class)),
                v.get("name",String.class),v.get("pct_change",Double.class),v.get("close",Double.class),
                v.get("net_amount",Double.class),v.get("net_amount_rate",Double.class),
                v.get("buy_elg_amount",Double.class),v.get("buy_elg_amount_rate",Double.class),
                v.get("buy_lg_amount",Double.class),v.get("buy_lg_amount_rate",Double.class),
                v.get("buy_md_amount",Double.class),v.get("buy_md_amount_rate",Double.class),
                v.get("buy_sm_amount",Double.class),v.get("buy_sm_amount_rate",Double.class));
    }
    private static String text(Map<String,JsonNode> row,String key) { JsonNode v=row.get(key); if(v==null||v.isNull()||!v.isTextual()||v.asText().isBlank())throw new IllegalArgumentException("Required moneyflow_dc text field: "+key); return v.textValue(); }
    private static String textOrNull(Map<String,JsonNode> row,String key) { JsonNode v=row.get(key); if(v==null||v.isNull())return null; if(!v.isTextual())throw new IllegalArgumentException("moneyflow_dc string field required: "+key); return v.textValue(); }
    private static String scalar(Map<String,JsonNode> row,String key) { JsonNode v=row.get(key); if(v==null||v.isNull())return null; if(v.isNumber()||v.isTextual())return v.asText(); throw new IllegalArgumentException("moneyflow_dc numeric scalar required: "+key); }
    private static Double number(String value) { if(value==null||value.isBlank())return null; try { double n=new BigDecimal(value).doubleValue(); if(!Double.isFinite(n))throw new NumberFormatException(); return n; } catch(Exception e) { throw new IllegalArgumentException("moneyflow_dc numeric field must be finite DOUBLE or null",e); } }
}
