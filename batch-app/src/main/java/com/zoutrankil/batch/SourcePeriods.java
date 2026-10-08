package com.zoutrankil.batch;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Calendar-period operations shared by request admission and coverage policies. */
final class SourcePeriods {
    private SourcePeriods() {}

    static boolean coversMonths(List<Map<String,Object>> rows,LocalDate start,LocalDate end) {
        if(start.equals(end)&&rows.isEmpty()) return true;
        var months=new TreeSet<LocalDate>(); for(var row:rows) { Object value=row.get("month"); if(!(value instanceof String s)) return false; months.add(LocalDate.parse(s)); }
        long expected=java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(start),java.time.YearMonth.from(end))+1;
        if(months.size()!=expected) return false;
        var cursor=java.time.YearMonth.from(start); for(LocalDate month:months) { if(!month.equals(cursor.atDay(1))) return false; cursor=cursor.plusMonths(1); }
        return true;
    }
    static boolean coversQuarters(List<Map<String,Object>> rows,LocalDate start,LocalDate end) {
        if(start.equals(end)&&rows.isEmpty()) return true;
        var quarters=new TreeSet<LocalDate>();for(var row:rows) { Object value=row.get("report_date");if(!(value instanceof String s))return false;quarters.add(LocalDate.parse(s)); }
        long expected=quartersBetween(start,end)+1;if(quarters.size()!=expected)return false;
        var cursor=YearMonth.from(start);for(LocalDate date:quarters) { if(!date.equals(cursor.atEndOfMonth()))return false;cursor=cursor.plusMonths(3); }
        return true;
    }
    static String quarter(LocalDate date) { return date.getYear()+"Q"+((date.getMonthValue()-1)/3+1); }
    static boolean isQuarterEnd(LocalDate date) { return Set.of(3,6,9,12).contains(date.getMonthValue())&&date.getDayOfMonth()==date.lengthOfMonth(); }
    static long quartersBetween(LocalDate start,LocalDate end) { return YearMonth.from(start).until(YearMonth.from(end),java.time.temporal.ChronoUnit.MONTHS)/3; }
}
