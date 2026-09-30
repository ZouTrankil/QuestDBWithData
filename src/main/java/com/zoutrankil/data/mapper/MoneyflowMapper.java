package com.zoutrankil.data.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareMoneyflowDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.Moneyflow;
import com.zoutrankil.data.domain.MoneyflowKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit moneyflow provider-to-domain and domain-to-QuestDB mappings. */
public final class MoneyflowMapper {
    public TushareMoneyflowDto dto(Map<String,JsonNode> row) {
        return new TushareMoneyflowDto(text(row,"ts_code"),text(row,"trade_date"),
                scalar(row,"buy_sm_vol"),scalar(row,"buy_sm_amount"),scalar(row,"sell_sm_vol"),scalar(row,"sell_sm_amount"),
                scalar(row,"buy_md_vol"),scalar(row,"buy_md_amount"),scalar(row,"sell_md_vol"),scalar(row,"sell_md_amount"),
                scalar(row,"buy_lg_vol"),scalar(row,"buy_lg_amount"),scalar(row,"sell_lg_vol"),scalar(row,"sell_lg_amount"),
                scalar(row,"buy_elg_vol"),scalar(row,"buy_elg_amount"),scalar(row,"sell_elg_vol"),scalar(row,"sell_elg_amount"),
                scalar(row,"net_mf_vol"),scalar(row,"net_mf_amount"));
    }
    public Moneyflow fromSource(TushareMoneyflowDto d) {
        if(d==null)throw new IllegalArgumentException("moneyflow source DTO required");
        LocalDate date=TemporalValues.businessDate(d.tradeDate(),TemporalValues.DateFormat.BASIC);
        return new Moneyflow(new MoneyflowKey(d.tsCode(),date),volume(d.buySmVol()),amount(d.buySmAmount()),
                volume(d.sellSmVol()),amount(d.sellSmAmount()),volume(d.buyMdVol()),amount(d.buyMdAmount()),
                volume(d.sellMdVol()),amount(d.sellMdAmount()),volume(d.buyLgVol()),amount(d.buyLgAmount()),
                volume(d.sellLgVol()),amount(d.sellLgAmount()),volume(d.buyElgVol()),amount(d.buyElgAmount()),
                volume(d.sellElgVol()),amount(d.sellElgAmount()),volume(d.netMfVol()),amount(d.netMfAmount()));
    }
    public DatasetValues values(Moneyflow row) {
        var v=new LinkedHashMap<String,Object>();v.put("ts_code",row.tsCode());v.put("trade_date",row.tradeDate());
        v.put("buy_sm_vol",row.buySmVol());v.put("buy_sm_amount",row.buySmAmount());v.put("sell_sm_vol",row.sellSmVol());v.put("sell_sm_amount",row.sellSmAmount());
        v.put("buy_md_vol",row.buyMdVol());v.put("buy_md_amount",row.buyMdAmount());v.put("sell_md_vol",row.sellMdVol());v.put("sell_md_amount",row.sellMdAmount());
        v.put("buy_lg_vol",row.buyLgVol());v.put("buy_lg_amount",row.buyLgAmount());v.put("sell_lg_vol",row.sellLgVol());v.put("sell_lg_amount",row.sellLgAmount());
        v.put("buy_elg_vol",row.buyElgVol());v.put("buy_elg_amount",row.buyElgAmount());v.put("sell_elg_vol",row.sellElgVol());v.put("sell_elg_amount",row.sellElgAmount());
        v.put("net_mf_vol",row.netMfVol());v.put("net_mf_amount",row.netMfAmount());return new DatasetValues(v);
    }
    public Moneyflow fromValues(DatasetValues v) {
        return new Moneyflow(new MoneyflowKey(v.get("ts_code",String.class),v.get("trade_date",LocalDate.class)),
                zeroVolume(v.get("buy_sm_vol",Long.class)),v.get("buy_sm_amount",Double.class),zeroVolume(v.get("sell_sm_vol",Long.class)),v.get("sell_sm_amount",Double.class),
                zeroVolume(v.get("buy_md_vol",Long.class)),v.get("buy_md_amount",Double.class),zeroVolume(v.get("sell_md_vol",Long.class)),v.get("sell_md_amount",Double.class),
                zeroVolume(v.get("buy_lg_vol",Long.class)),v.get("buy_lg_amount",Double.class),zeroVolume(v.get("sell_lg_vol",Long.class)),v.get("sell_lg_amount",Double.class),
                zeroVolume(v.get("buy_elg_vol",Long.class)),v.get("buy_elg_amount",Double.class),zeroVolume(v.get("sell_elg_vol",Long.class)),v.get("sell_elg_amount",Double.class),
                zeroVolume(v.get("net_mf_vol",Long.class)),v.get("net_mf_amount",Double.class));
    }
    private static String text(Map<String,JsonNode> row,String name){JsonNode v=row.get(name);if(v==null||v.isNull()||!v.isTextual()||v.asText().isBlank())throw new IllegalArgumentException("Required moneyflow text field: "+name);return v.textValue();}
    private static String scalar(Map<String,JsonNode> row,String name){JsonNode v=row.get(name);if(v==null||v.isNull())return null;if(v.isNumber()||v.isTextual())return v.asText();throw new IllegalArgumentException("moneyflow scalar field required: "+name);}
    private static Long zeroVolume(Long value){return value==null?0L:value;}
    private static Long volume(String value){if(value==null||value.isBlank())return 0L;try{return new BigDecimal(value).longValueExact();}catch(Exception e){throw new IllegalArgumentException("moneyflow hand-volume must be an exact LONG or null",e);}}
    private static Double amount(String value){if(value==null||value.isBlank())return null;try{double n=new BigDecimal(value).doubleValue();if(!Double.isFinite(n))throw new NumberFormatException();return n;}catch(Exception e){throw new IllegalArgumentException("moneyflow amount must be a finite DOUBLE or null",e);}}
}
