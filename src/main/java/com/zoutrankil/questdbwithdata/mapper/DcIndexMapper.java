package com.zoutrankil.questdbwithdata.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.client.dto.TushareDcIndexDto;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.DcIndex;
import com.zoutrankil.questdbwithdata.domain.DcIndexKey;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Frozen same-name mapping from Python dc_index fields to physical columns. */
public final class DcIndexMapper {
    public TushareDcIndexDto dto(Map<String, JsonNode> row) {
        Objects.requireNonNull(row);
        return new TushareDcIndexDto(text(row,"ts_code",false), text(row,"trade_date",false),
                text(row,"name",true), text(row,"leading",true), text(row,"leading_code",true),
                number(row,"pct_change"), number(row,"leading_pct"), number(row,"total_mv"), number(row,"turnover_rate"),
                integer(row,"up_num"), integer(row,"down_num"));
    }
    public DcIndex fromSource(TushareDcIndexDto dto) {
        Objects.requireNonNull(dto);
        return new DcIndex(new DcIndexKey(dto.tsCode(), TemporalValues.businessDate(dto.tradeDate(), TemporalValues.DateFormat.BASIC)),
                dto.name(), dto.leading(), dto.leadingCode(), dto.pctChange(), dto.leadingPct(), dto.totalMv(),
                dto.turnoverRate(), dto.upNum(), dto.downNum());
    }
    public DatasetValues values(DcIndex row) {
        var m = new LinkedHashMap<String,Object>();
        m.put("ts_code",row.tsCode()); m.put("trade_date",row.tradeDate()); m.put("name",row.name());
        m.put("leading",row.leading()); m.put("leading_code",row.leadingCode()); m.put("pct_change",row.pctChange());
        m.put("leading_pct",row.leadingPct()); m.put("total_mv",row.totalMv()); m.put("turnover_rate",row.turnoverRate());
        m.put("up_num",row.upNum()); m.put("down_num",row.downNum());
        return new DatasetValues(m);
    }
    public DcIndex fromValues(DatasetValues v) {
        return new DcIndex(new DcIndexKey(v.get("ts_code",String.class), v.get("trade_date",LocalDate.class)),
                v.get("name",String.class),v.get("leading",String.class),v.get("leading_code",String.class),
                v.get("pct_change",Double.class),v.get("leading_pct",Double.class),v.get("total_mv",Double.class),
                v.get("turnover_rate",Double.class),v.get("up_num",Integer.class),v.get("down_num",Integer.class));
    }
    private static String text(Map<String,JsonNode> row,String field,boolean nullable) {
        JsonNode v=row.get(field); if(v==null||v.isNull()) { if(nullable)return null; throw new IllegalArgumentException("Missing dc_index text field: "+field); }
        if(!v.isTextual()) throw new IllegalArgumentException("Text dc_index field required: "+field);
        return v.textValue();
    }
    private static Double number(Map<String,JsonNode> row,String field) {
        JsonNode v=row.get(field);if(v==null||v.isNull())return null;
        if(!v.isNumber()&&!v.isTextual())throw new IllegalArgumentException("Numeric dc_index scalar or null required: "+field);
        String raw=v.asText();if(raw.isBlank())throw new IllegalArgumentException("Blank nonnull dc_index number: "+field);
        double parsed;try{parsed=new BigDecimal(raw).doubleValue();}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid dc_index number: "+field,e);}
        if(!Double.isFinite(parsed))throw new IllegalArgumentException("Non-finite dc_index number: "+field);return parsed;
    }
    private static Integer integer(Map<String,JsonNode> row,String field) {
        JsonNode v=row.get(field);if(v==null||v.isNull())return null;
        if(!v.isIntegralNumber()&&!v.isTextual())throw new IllegalArgumentException("Integral dc_index count or null required: "+field);
        try { int parsed=Integer.parseInt(v.asText()); if(parsed<0)throw new NumberFormatException();return parsed; }
        catch(NumberFormatException e){throw new IllegalArgumentException("Invalid nonnegative dc_index count: "+field,e);}
    }
}
