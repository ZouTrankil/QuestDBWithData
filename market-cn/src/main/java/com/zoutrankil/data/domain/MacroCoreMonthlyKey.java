package com.zoutrankil.data.domain;

import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** The source YYYYMM period is carried at its first calendar day, exactly UTC midnight. */
public record MacroCoreMonthlyKey(YearMonth month) {
    public MacroCoreMonthlyKey {
        Objects.requireNonNull(month, "month required");
        if (month.getYear() < 1 || month.getYear() > 9999)
            throw new IllegalArgumentException("YYYYMM month requires a four-digit positive year");
    }
    public LocalDate storageDate() { return month.atDay(1); }
    public Instant storageCarrier() { return new TemporalValues.CalendarTimestamp(storageDate()).storageCarrier(); }
    public long storageMicros() { return new TemporalValues.CalendarTimestamp(storageDate())
            .storageEpoch(TemporalValues.EpochUnit.MICROS); }
    public static MacroCoreMonthlyKey fromDate(LocalDate date) {
        Objects.requireNonNull(date, "month carrier required");
        if (date.getDayOfMonth() != 1) throw new IllegalArgumentException("Month carrier must be the first calendar day");
        return new MacroCoreMonthlyKey(YearMonth.from(date));
    }
    public static MacroCoreMonthlyKey fromStorage(Instant carrier) {
        return fromDate(TemporalValues.CalendarTimestamp.fromStorage(Objects.requireNonNull(carrier)).date());
    }
}

