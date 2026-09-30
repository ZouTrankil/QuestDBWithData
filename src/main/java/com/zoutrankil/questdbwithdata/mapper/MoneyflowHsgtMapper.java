package com.zoutrankil.questdbwithdata.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.client.dto.TushareMoneyflowHsgtDto;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.MoneyflowHsgt;
import com.zoutrankil.questdbwithdata.domain.MoneyflowHsgtKey;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit D027 raw DTO -> typed business row -> QuestDB column mapping. */
public final class MoneyflowHsgtMapper {
    public TushareMoneyflowHsgtDto dto(Map<String, JsonNode> row) {
        return new TushareMoneyflowHsgtDto(required(row, "trade_date"), scalar(row, "ggt_ss"),
                scalar(row, "ggt_sz"), scalar(row, "hgt"), scalar(row, "sgt"),
                scalar(row, "north_money"), scalar(row, "south_money"));
    }
    public MoneyflowHsgt fromSource(TushareMoneyflowHsgtDto row) {
        if (row == null) throw new IllegalArgumentException("D027 source DTO required");
        LocalDate date = TemporalValues.businessDate(row.tradeDate(), TemporalValues.DateFormat.BASIC);
        return new MoneyflowHsgt(new MoneyflowHsgtKey(date), finite(row.ggtSs(), "ggt_ss"),
                finite(row.ggtSz(), "ggt_sz"), finite(row.hgt(), "hgt"), finite(row.sgt(), "sgt"),
                finite(row.northMoney(), "north_money"), finite(row.southMoney(), "south_money"));
    }
    public DatasetValues values(MoneyflowHsgt row) {
        var values = new LinkedHashMap<String,Object>();
        values.put("trade_date", row.tradeDate()); values.put("ggt_ss", row.ggtSs());
        values.put("ggt_sz", row.ggtSz()); values.put("hgt", row.hgt()); values.put("sgt", row.sgt());
        values.put("north_money", row.northMoney()); values.put("south_money", row.southMoney());
        return new DatasetValues(values);
    }
    public MoneyflowHsgt fromValues(DatasetValues values) {
        return new MoneyflowHsgt(new MoneyflowHsgtKey(values.get("trade_date", LocalDate.class)),
                values.get("ggt_ss", Double.class), values.get("ggt_sz", Double.class),
                values.get("hgt", Double.class), values.get("sgt", Double.class),
                values.get("north_money", Double.class), values.get("south_money", Double.class));
    }
    private static String required(Map<String,JsonNode> row,String field) {
        JsonNode value=row.get(field);
        if(value==null||value.isNull()||!(value.isTextual()||value.isIntegralNumber())||value.asText().isBlank())
            throw new IllegalArgumentException("Required D027 field missing: " + field);
        return value.asText();
    }
    private static String scalar(Map<String,JsonNode> row,String field) {
        JsonNode value=row.get(field);
        if(value==null||value.isNull())return null;
        if(value.isNumber()||value.isTextual())return value.asText();
        throw new IllegalArgumentException("D027 metric must be a scalar: " + field);
    }
    private static Double finite(String raw,String field) {
        if(raw==null||raw.isBlank())return null;
        try { double value=new BigDecimal(raw.strip()).doubleValue();if(!Double.isFinite(value))throw new NumberFormatException();return value; }
        catch(RuntimeException invalid) { throw new IllegalArgumentException("Invalid D027 numeric value: " + field,invalid); }
    }
}
