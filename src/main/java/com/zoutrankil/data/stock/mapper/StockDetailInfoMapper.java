package com.zoutrankil.data.stock.mapper;

import com.zoutrankil.data.mapper.*;

import com.zoutrankil.data.client.dto.TushareStockDetailDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.StockDetailInfoRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.*;
import java.util.LinkedHashMap;

public final class StockDetailInfoMapper {
    public StockDetailInfo fromSource(TushareStockDetailDto r,Instant observedAt) {
        return new StockDetailInfo(r.tsCode(),observedAt,r.symbol(),r.name(),r.market(),r.exchange(),r.listStatus(),
                date(r.listDate()),r.fullname(),r.enname(),r.cnspell(),r.area(),r.industry(),r.currType(),
                date(r.delistDate()),r.isHs(),r.actName(),r.actEntType());
    }
    public StockDetailInfo fromStorage(StockDetailInfoRow r) {
        return com.zoutrankil.data.stock.domain.policy.StockDetailRows.fromStorage(r);
    }
    public StockDetailInfoRow toStorage(StockDetailInfo r) {
        return new StockDetailInfoRow(r.tsCode(),r.observedAt(),r.symbol(),r.name(),r.market(),r.exchange(),r.listStatus(),
                formatted(r.listingDate()),r.fullname(),r.enname(),r.cnspell(),r.area(),r.industry(),r.currType(),
                formatted(r.delistingDate()),r.isHs(),r.actName(),r.actEntType());
    }
    public DatasetValues values(StockDetailInfo r) {
        var v=new LinkedHashMap<String,Object>();
        v.put("ts_code",r.tsCode());v.put("observed_at",r.observedAt());v.put("symbol",r.symbol());v.put("name",r.name());
        v.put("market",r.market());v.put("exchange",r.exchange());v.put("list_status",r.listStatus());
        v.put("listing_date",r.listingDate());v.put("fullname",r.fullname());v.put("enname",r.enname());
        v.put("cnspell",r.cnspell());v.put("area",r.area());v.put("industry",r.industry());v.put("curr_type",r.currType());
        v.put("delisting_date",r.delistingDate());v.put("is_hs",r.isHs());v.put("act_name",r.actName());v.put("act_ent_type",r.actEntType());
        return new DatasetValues(v);
    }
    public StockDetailInfo fromValues(DatasetValues v) {
        return new StockDetailInfo(v.get("ts_code",String.class),v.get("observed_at",Instant.class),
                v.get("symbol",String.class),v.get("name",String.class),v.get("market",String.class),
                v.get("exchange",String.class),v.get("list_status",String.class),v.get("listing_date",LocalDate.class),
                v.get("fullname",String.class),v.get("enname",String.class),v.get("cnspell",String.class),
                v.get("area",String.class),v.get("industry",String.class),v.get("curr_type",String.class),
                v.get("delisting_date",LocalDate.class),v.get("is_hs",String.class),v.get("act_name",String.class),
                v.get("act_ent_type",String.class));
    }
    private static LocalDate date(String value) {
        return value==null || value.isEmpty()?null:TemporalValues.businessDate(value,TemporalValues.DateFormat.BASIC);
    }
    private static LocalDate legacyDate(String value) { return "None".equals(value)?null:date(value); }
    private static String formatted(LocalDate value) {
        return value==null?null:TemporalValues.formatDate(value,TemporalValues.DateFormat.BASIC);
    }
}
