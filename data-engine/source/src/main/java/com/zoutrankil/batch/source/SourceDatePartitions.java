package com.zoutrankil.batch.source;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;

/** 有些来源按发布日期查询；只规划显式有限日期，不猜 NYSE 或 SSE 开市日。 */
public final class SourceDatePartitions {
    public static final String VERSION="source-date-v1";
    private SourceDatePartitions() {}
    public static List<LocalDate> plan(LocalDate start,LocalDate end,int limit) {
        if (start.isAfter(end)||limit<1||limit>366||ChronoUnit.DAYS.between(start,end)>3660)
            throw new IllegalArgumentException("Invalid finite source-date budget");
        var dates=new ArrayList<LocalDate>();
        for (var day=start;!day.isAfter(end);day=day.plusDays(1)) {
            if (dates.size()==limit) throw new IllegalArgumentException("Partition quota exceeded");
            dates.add(day);
        }
        return List.copyOf(dates);
    }
}
