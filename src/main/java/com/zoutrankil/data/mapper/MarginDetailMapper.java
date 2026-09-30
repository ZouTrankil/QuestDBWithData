package com.zoutrankil.data.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareMarginDetailDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MarginDetail;
import com.zoutrankil.data.domain.MarginDetailKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit source DTO -> business row -> all 11 physical columns mapping. */
public final class MarginDetailMapper {
    public TushareMarginDetailDto dto(Map<String, JsonNode> row) {
        return new TushareMarginDetailDto(text(row,"trade_date"), text(row,"ts_code"), textOrNull(row,"name"),
                scalar(row,"rzye"), scalar(row,"rzmre"), scalar(row,"rzche"), scalar(row,"rqye"),
                scalar(row,"rqyl"), scalar(row,"rqchl"), scalar(row,"rqmcl"), scalar(row,"rzrqye"));
    }
    public MarginDetail fromSource(TushareMarginDetailDto dto) {
        if (dto == null) throw new IllegalArgumentException("margin_detail source DTO required");
        LocalDate date = TemporalValues.businessDate(dto.tradeDate(), TemporalValues.DateFormat.BASIC);
        return new MarginDetail(new MarginDetailKey(dto.tsCode(), date), dto.name(), number(dto.rzye()),
                number(dto.rzmre()), number(dto.rzche()), number(dto.rqye()), number(dto.rqyl()),
                number(dto.rqchl()), number(dto.rqmcl()), number(dto.rzrqye()));
    }
    public DatasetValues values(MarginDetail row) {
        var values = new LinkedHashMap<String,Object>(); values.put("ts_code",row.tsCode());values.put("trade_date",row.tradeDate());
        values.put("name",row.name());values.put("rzye",row.rzye());values.put("rzmre",row.rzmre());values.put("rzche",row.rzche());
        values.put("rqye",row.rqye());values.put("rqyl",row.rqyl());values.put("rqchl",row.rqchl());values.put("rqmcl",row.rqmcl());values.put("rzrqye",row.rzrqye());
        return new DatasetValues(values);
    }
    public MarginDetail fromValues(DatasetValues values) {
        return new MarginDetail(new MarginDetailKey(values.get("ts_code",String.class),values.get("trade_date",LocalDate.class)),
                values.get("name",String.class),values.get("rzye",Double.class),values.get("rzmre",Double.class),values.get("rzche",Double.class),
                values.get("rqye",Double.class),values.get("rqyl",Double.class),values.get("rqchl",Double.class),values.get("rqmcl",Double.class),values.get("rzrqye",Double.class));
    }
    private static String text(Map<String,JsonNode> row,String field) { JsonNode value=row.get(field);if(value==null||value.isNull()||!value.isTextual()||value.asText().isBlank())throw new IllegalArgumentException("Required margin_detail text field: "+field);return value.textValue(); }
    private static String textOrNull(Map<String,JsonNode> row,String field) { JsonNode value=row.get(field);if(value==null||value.isNull())return null;if(!value.isTextual())throw new IllegalArgumentException("margin_detail name must be text or null");return value.textValue(); }
    private static String scalar(Map<String,JsonNode> row,String field) { JsonNode value=row.get(field);if(value==null||value.isNull())return null;if(value.isNumber()||value.isTextual())return value.asText();throw new IllegalArgumentException("margin_detail numeric scalar required: "+field); }
    private static Double number(String value) { if(value==null||value.isBlank())return null;try{double number=new BigDecimal(value.strip()).doubleValue();if(!Double.isFinite(number))throw new NumberFormatException();return number;}catch(Exception invalid){throw new IllegalArgumentException("margin_detail number must be a finite DOUBLE or null",invalid);} }
}
