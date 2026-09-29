package com.zoutrankil.questdbwithdata.mapper;

import com.zoutrankil.questdbwithdata.client.dto.TushareIndexMembershipDto;
import com.zoutrankil.questdbwithdata.domain.IndexMembership;
import com.zoutrankil.questdbwithdata.domain.table.IndexMemberRow;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.time.*;
import java.util.Objects;
import java.util.LinkedHashMap;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;

public final class IndexMembershipMapper {
    public IndexMembership fromValues(DatasetValues v) {
        return new IndexMembership(v.get("index_code",String.class),v.get("ts_code",String.class),v.get("observed_at",Instant.class),
                v.get("index_name",String.class),v.get("constituent_code",String.class),v.get("constituent_name",String.class),
                v.get("membership_start_date",LocalDate.class),v.get("membership_end_date",LocalDate.class),
                v.get("latest_flag",String.class),v.get("weight",Double.class),v.get("level",String.class),
                v.get("l1_name",String.class),v.get("l2_name",String.class),v.get("l3_name",String.class));
    }
    public DatasetValues values(IndexMembership r) {
        var v=new LinkedHashMap<String,Object>();
        v.put("index_code",r.indexCode());v.put("ts_code",r.tsCode());v.put("observed_at",r.observedAt());
        v.put("index_name",r.indexName());v.put("constituent_code",r.constituentCode());v.put("constituent_name",r.constituentName());
        v.put("membership_start_date",r.membershipStartDate());v.put("membership_end_date",r.membershipEndDate());
        v.put("latest_flag",r.latestFlag());v.put("weight",r.weight());v.put("level",r.level());
        v.put("l1_name",r.l1Name());v.put("l2_name",r.l2Name());v.put("l3_name",r.l3Name());return new DatasetValues(v);
    }
    public IndexMembership fromSource(TushareIndexMembershipDto row,String expectedL2,String industryName,Instant observedAt) {
        if(!Objects.equals(expectedL2,row.l2Code())) throw new IllegalArgumentException("Membership outside frozen L2 scope");
        for(String code:new String[]{row.l1Code(),row.l2Code(),row.l3Code()})
            if(code==null || !code.matches("[0-9]{6}\\.SI")) throw new IllegalArgumentException("Complete source hierarchy required");
        return new IndexMembership(row.l2Code(),row.tsCode(),observedAt,industryName,null,row.name(),
                date(row.inDate(),false),date(row.outDate(),false),row.isNew(),null,"L2",row.l1Name(),row.l2Name(),row.l3Name());
    }
    public IndexMembership fromStorage(IndexMemberRow row) {
        return new IndexMembership(row.indexCode(),row.tsCode(),row.updateTime(),row.indexName(),row.conCode(),row.conName(),
                date(row.inDate(),true),date(row.outDate(),true),row.isNew(),row.weight(),row.level(),row.l1Name(),row.l2Name(),row.l3Name());
    }
    public IndexMemberRow toStorage(IndexMembership row) {
        return new IndexMemberRow(row.indexCode(),row.tsCode(),row.observedAt(),row.indexName(),row.constituentCode(),row.constituentName(),
                format(row.membershipStartDate()),format(row.membershipEndDate()),row.latestFlag(),row.weight(),row.level(),row.l1Name(),row.l2Name(),row.l3Name());
    }
    private static LocalDate date(String value,boolean legacy) {
        if(value==null || value.isEmpty() || legacy && value.equals("None")) return null;
        return TemporalValues.businessDate(value,TemporalValues.DateFormat.BASIC);
    }
    private static String format(LocalDate date) { return date==null?null:TemporalValues.formatDate(date,TemporalValues.DateFormat.BASIC); }
}
