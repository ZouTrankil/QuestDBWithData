package com.zoutrankil.data.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareIndexMonthlyDto;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.IndexMonthly;
import com.zoutrankil.data.domain.IndexMonthlyKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.domain.policy.IndexMonthlyUniverse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Frozen eleven-field source map plus Python-derived layer/bucket and the Java observation timestamp. */
public final class IndexMonthlyMapper {
    public static final java.util.List<String> FIELDS = java.util.List.of("ts_code", "trade_date", "close", "open",
            "high", "low", "pre_close", "change", "pct_chg", "vol", "amount");
    public TushareIndexMonthlyDto dto(Map<String,JsonNode> row) {
        return new TushareIndexMonthlyDto(text(row,"ts_code"),text(row,"trade_date"),number(row,"close"),number(row,"open"),
                number(row,"high"),number(row,"low"),number(row,"pre_close"),number(row,"change"),number(row,"pct_chg"),
                number(row,"vol"),number(row,"amount"));
    }
    public IndexMonthly fromSource(TushareIndexMonthlyDto source, IndexMonthlyUniverse.Index index, Instant observedAt) {
        if (source == null || index == null || !index.providerCode().equals(source.tsCode()))
            throw new IllegalArgumentException("Provider monthly code differs from frozen universe alias");
        LocalDate date = TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC);
        return new IndexMonthly(new IndexMonthlyKey(index.providerCode(),date),source.close(),source.open(),source.high(),source.low(),
                source.preClose(),source.change(),source.pctChg(),source.vol(),source.amount(),index.layer(),index.bucket(),observedAt);
    }
    public DatasetValues values(IndexMonthly row) {
        var values=new LinkedHashMap<String,Object>(); values.put("ts_code",row.tsCode());values.put("trade_date",row.tradeDate());
        values.put("close",row.close());values.put("open",row.open());values.put("high",row.high());values.put("low",row.low());
        values.put("pre_close",row.preClose());values.put("change",row.change());values.put("pct_chg",row.pctChg());
        values.put("vol",row.vol());values.put("amount",row.amount());values.put("layer",row.layer());values.put("bucket",row.bucket());
        values.put("update_time",row.updateTime());return new DatasetValues(values);
    }
    public IndexMonthly fromValues(DatasetValues values) {
        return new IndexMonthly(new IndexMonthlyKey(values.get("ts_code",String.class),values.get("trade_date",LocalDate.class)),
                values.get("close",Double.class),values.get("open",Double.class),values.get("high",Double.class),values.get("low",Double.class),
                values.get("pre_close",Double.class),values.get("change",Double.class),values.get("pct_chg",Double.class),
                values.get("vol",Double.class),values.get("amount",Double.class),values.get("layer",String.class),
                values.get("bucket",String.class),values.get("update_time",Instant.class));
    }
    private static String text(Map<String,JsonNode> row,String field) {
        JsonNode value=row.get(field);
        if(value==null||value.isNull()||!value.isTextual()||value.asText().isBlank())
            throw new IllegalArgumentException("Required nonblank D022 source text: "+field);
        return value.textValue();
    }
    private static Double number(Map<String,JsonNode> row,String field) {
        JsonNode value=row.get(field); if(value==null||value.isNull())return null;
        if(!value.isNumber()||!Double.isFinite(value.doubleValue()))throw new IllegalArgumentException("Finite D022 number or null required: "+field);
        return value.doubleValue();
    }
}
