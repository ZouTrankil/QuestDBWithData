package com.zoutrankil.batch;

import java.time.*;
import java.util.*;

public final class RecoveryPolicy {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final String MONTHLY_PERIOD_VERSION="month-start-v1";
    public static final String QUARTERLY_PERIOD_VERSION="quarter-end-v1";
    private RecoveryPolicy() {}
    public static LocalDate nightLogicalDate(Instant scheduledAt, TradingCalendar calendar) {
        var local = scheduledAt.atZone(ZONE);
        var windowDate = local.toLocalTime().isBefore(LocalTime.of(6, 0))
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
        return calendar.previousOrSame(windowDate);
    }
    /** One current probe only, never replay every missed quarter-hour. */
    public static boolean mayProbe(RunRequest request, Instant now, int attempts, Instant nextAttempt,
                                   TradingCalendar calendar) {
        var local = now.atZone(ZONE);
        return calendar.isOpen(request.logicalDate()) && local.toLocalDate().equals(request.logicalDate())
                && !local.toLocalTime().isBefore(LocalTime.of(19, 0))
                && local.toLocalTime().isBefore(LocalTime.of(23, 59))
                && attempts >= 0 && attempts < 21 && (nextAttempt == null || !now.isBefore(nextAttempt));
    }
    public static List<LocalDate> backfill(LocalDate start, LocalDate end, int maxPartitions,
                                         TradingCalendar calendar) {
        if (start.isAfter(end) || maxPartitions < 1 || maxPartitions > 366)
            throw new IllegalArgumentException("Invalid finite backfill budget");
        var dates = new ArrayList<LocalDate>();
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > 3660)
            throw new IllegalArgumentException("Backfill request exceeds planning budget");
        for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (calendar.isOpen(date)) dates.add(date);
            if (dates.size() > maxPartitions) throw new IllegalArgumentException("Partition quota exceeded");
        }
        return List.copyOf(dates);
    }
    public static List<LocalDate> monthlyBackfill(LocalDate start,LocalDate end,int maxPartitions) {
        if(start.isAfter(end)||start.getDayOfMonth()!=1||end.getDayOfMonth()!=1||maxPartitions<1||maxPartitions>2000)
            throw new IllegalArgumentException("Monthly backfill requires first-of-month bounds and a 1..2000 period budget");
        long periods=YearMonth.from(start).until(YearMonth.from(end),java.time.temporal.ChronoUnit.MONTHS)+1;
        if(periods>maxPartitions) throw new IllegalArgumentException("Monthly period quota exceeded");
        var months=new ArrayList<LocalDate>((int)periods);
        for(var month=YearMonth.from(start);!month.isAfter(YearMonth.from(end));month=month.plusMonths(1)) months.add(month.atDay(1));
        return List.copyOf(months);
    }
    public static List<LocalDate> quarterlyBackfill(LocalDate start,LocalDate end,int maxPartitions) {
        if(maxPartitions<1||maxPartitions>2000||start.isAfter(end)||!SourceCollector.isQuarterEnd(start)||!SourceCollector.isQuarterEnd(end))
            throw new IllegalArgumentException("Quarterly backfill requires ordered quarter ends and a 1..2000 partition limit");
        var result=new ArrayList<LocalDate>();var cursor=YearMonth.from(start);var last=YearMonth.from(end);
        while(!cursor.isAfter(last)) { if(result.size()==maxPartitions) throw new IllegalArgumentException("Quarterly backfill exceeds partition limit");result.add(cursor.atEndOfMonth());cursor=cursor.plusMonths(3); }
        return List.copyOf(result);
    }
    public static LocalDate contiguousWatermark(List<LocalDate> expected, Map<LocalDate, CompletionEvidence> certificates,
                                               String definitionVersion, String inputFingerprint) {
        LocalDate watermark = null;
        var dates = new TreeSet<>(expected);
        for (var date : dates) {
            var certificate = certificates.get(date);
            if (certificate == null || !certificate.state().ready() || !certificate.logicalDate().equals(date)
                    || !certificate.definitionVersion().equals(definitionVersion)
                    || !certificate.inputFingerprint().equals(inputFingerprint)) break;
            watermark = date;
        }
        return watermark;
    }
}
