package com.zoutrankil.data.index.domain.policy;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.*;

/** Pure business projection and physical row decoding shared with the mapper. */
public final class IndexMembershipValues {
    private IndexMembershipValues() {}
    public static IndexMembership fromStorage(IndexMemberRow row) {
        return new IndexMembership(row.indexCode(),row.tsCode(),row.updateTime(),row.indexName(),row.conCode(),row.conName(),
                date(row.inDate(),true),date(row.outDate(),true),row.isNew(),row.weight(),row.level(),row.l1Name(),row.l2Name(),row.l3Name());
    }
    private static LocalDate date(String value,boolean legacy) {
        if(value==null || value.isEmpty() || legacy && value.equals("None")) return null;
        return TemporalValues.businessDate(value,TemporalValues.DateFormat.BASIC);
    }
}
