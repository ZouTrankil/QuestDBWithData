package com.zoutrankil.questdbwithdata.mapper;

import com.zoutrankil.questdbwithdata.client.dto.TushareThsIndexDto;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.ThsIndexRow;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.time.*;
import java.util.LinkedHashMap;

public final class ThsIndexMapper {
    public ThsIndex fromSource(TushareThsIndexDto row,Instant observedAt) {
        return new ThsIndex(row.tsCode(),row.name(),row.count(),row.exchange(),date(row.listDate()),row.type(),observedAt);
    }
    public ThsIndex fromStorage(ThsIndexRow row) {
        return new ThsIndex(row.tsCode(),row.name(),row.count(),row.exchange(),date(row.listDate()),row.type(),row.updateTime());
    }
    public ThsIndexRow toStorage(ThsIndex row) {
        return new ThsIndexRow(row.tsCode(),row.name(),row.memberCount(),row.exchange(),row.listingDate()==null?null:
                TemporalValues.formatDate(row.listingDate(),TemporalValues.DateFormat.BASIC),row.indexType(),row.observedAt());
    }
    public DatasetValues values(ThsIndex row) {
        var values=new LinkedHashMap<String,Object>();values.put("ts_code",row.tsCode());values.put("name",row.name());
        values.put("member_count",row.memberCount());values.put("exchange",row.exchange());values.put("listing_date",row.listingDate());
        values.put("index_type",row.indexType());values.put("observed_at",row.observedAt());return new DatasetValues(values);
    }
    public ThsIndex fromValues(DatasetValues values) {
        return new ThsIndex(values.get("ts_code",String.class),values.get("name",String.class),values.get("member_count",Integer.class),
                values.get("exchange",String.class),values.get("listing_date",LocalDate.class),values.get("index_type",String.class),
                values.get("observed_at",Instant.class));
    }
    private static LocalDate date(String value) {
        return value==null || value.isEmpty()?null:TemporalValues.businessDate(value,TemporalValues.DateFormat.BASIC);
    }
}
