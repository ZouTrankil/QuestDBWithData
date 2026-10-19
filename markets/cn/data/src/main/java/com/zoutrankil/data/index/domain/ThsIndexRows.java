package com.zoutrankil.data.index.domain;

import com.zoutrankil.data.domain.ThsIndex;
import com.zoutrankil.data.domain.table.ThsIndexRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;

/** Pure conversion used by physical snapshots and explicit dataset mapping. */
public final class ThsIndexRows {
    private ThsIndexRows() {}

    public static ThsIndex fromStorage(ThsIndexRow row) {
        return new ThsIndex(row.tsCode(),row.name(),row.count(),row.exchange(),date(row.listDate()),row.type(),row.updateTime());
    }

    private static LocalDate date(String value) {
        return value==null || value.isEmpty()?null:TemporalValues.businessDate(value,TemporalValues.DateFormat.BASIC);
    }
}
