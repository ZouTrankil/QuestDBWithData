package com.zoutrankil.questdbwithdata.mapper;

import com.zoutrankil.questdbwithdata.client.dto.TushareThsMemberDto;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.ThsMember;
import com.zoutrankil.questdbwithdata.domain.table.ThsMemberRow;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;

public final class ThsMemberMapper {
    public ThsMember fromSource(TushareThsMemberDto row, Instant observedAt) {
        return new ThsMember(row.tsCode(), row.constituentCode(), row.constituentName(), row.weight(),
                date(row.inDate()), date(row.outDate()), row.isNew(), observedAt);
    }

    public ThsMember fromStorage(ThsMemberRow row) {
        return new ThsMember(row.tsCode(), row.conCode(), row.conName(), row.weight(), date(row.inDate()),
                date(row.outDate()), row.isNew(), row.updateTime());
    }

    public ThsMemberRow toStorage(ThsMember row) {
        return new ThsMemberRow(row.boardCode(), row.constituentCode(), row.constituentName(), row.weight(),
                format(row.inDate()), format(row.outDate()), row.isNew(), row.observedAt());
    }

    public DatasetValues values(ThsMember row) {
        var v = new LinkedHashMap<String, Object>();
        v.put("board_code", row.boardCode());
        v.put("constituent_code", row.constituentCode());
        v.put("constituent_name", row.constituentName());
        v.put("weight", row.weight());
        v.put("in_date", row.inDate());
        v.put("out_date", row.outDate());
        v.put("is_new", row.isNew());
        v.put("observed_at", row.observedAt());
        return new DatasetValues(v);
    }

    public ThsMember fromValues(DatasetValues v) {
        return new ThsMember(v.get("board_code", String.class), v.get("constituent_code", String.class),
                v.get("constituent_name", String.class), v.get("weight", Double.class),
                v.get("in_date", LocalDate.class), v.get("out_date", LocalDate.class),
                v.get("is_new", String.class), v.get("observed_at", Instant.class));
    }

    private static LocalDate date(String value) {
        return value == null || value.isEmpty() ? null : TemporalValues.businessDate(value, TemporalValues.DateFormat.BASIC);
    }

    private static String format(LocalDate value) {
        return value == null ? null : TemporalValues.formatDate(value, TemporalValues.DateFormat.BASIC);
    }
}
