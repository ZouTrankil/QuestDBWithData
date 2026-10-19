package com.zoutrankil.data.margin.mapper;

import com.zoutrankil.data.margin.domain.MarginZrzRows;


import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareMarginZrzDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MarginZrz;
import com.zoutrankil.data.domain.MarginZrzKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit six-column source -> DTO -> domain -> QuestDB mapping; units and nulls are unchanged. */
public final class MarginZrzMapper {
    public TushareMarginZrzDto dto(Map<String, JsonNode> row) {
        return new TushareMarginZrzDto(requiredDate(row), scalar(row,"ob"), scalar(row,"auc_amount"),
                scalar(row,"repo_amount"), scalar(row,"repay_amount"), scalar(row,"cb"));
    }
    public MarginZrz fromSource(TushareMarginZrzDto dto) {
        if (dto == null) throw new IllegalArgumentException("D031 source DTO required");
        LocalDate date=TemporalValues.businessDate(dto.tradeDate(),TemporalValues.DateFormat.BASIC);
        return new MarginZrz(new MarginZrzKey(date),finite(dto.ob(),"ob"),finite(dto.aucAmount(),"auc_amount"),
                finite(dto.repoAmount(),"repo_amount"),finite(dto.repayAmount(),"repay_amount"),finite(dto.cb(),"cb"));
    }
    public DatasetValues values(MarginZrz row) {return MarginZrzRows.values(row);}
    public MarginZrz fromValues(DatasetValues v) {
        return new MarginZrz(new MarginZrzKey(v.get("trade_date",LocalDate.class)),v.get("ob",Double.class),
                v.get("auc_amount",Double.class),v.get("repo_amount",Double.class),
                v.get("repay_amount",Double.class),v.get("cb",Double.class));
    }
    private static String requiredDate(Map<String,JsonNode> row) {
        JsonNode value=row.get("trade_date");
        if(value==null||value.isNull()||!(value.isTextual()||value.isIntegralNumber())||value.asText().isBlank())
            throw new IllegalArgumentException("Required D031 trade_date missing");
        return value.asText();
    }
    private static String scalar(Map<String,JsonNode> row,String field) {
        JsonNode value=row.get(field);
        if(value==null)throw new IllegalArgumentException("D031 response omitted declared field: "+field);
        if(value.isNull())return null;
        if(value.isNumber()||value.isTextual())return value.asText();
        throw new IllegalArgumentException("D031 numeric scalar or null required: "+field);
    }
    private static Double finite(String raw,String field) {
        if(raw==null)return null;
        try{double value=new BigDecimal(raw.strip()).doubleValue();if(!Double.isFinite(value))throw new NumberFormatException();return value;}
        catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid D031 numeric value: "+field,invalid);}
    }
}
