package com.zoutrankil.data.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

/** A view's natural identity is the base observation month, with no independent UPSERT key. */
public record MacroCoreMonthlyViewKey(YearMonth month) {
    public MacroCoreMonthlyViewKey { new MacroCoreMonthlyKey(month); }
    public LocalDate storageDate() { return new MacroCoreMonthlyKey(month).storageDate(); }
    public Instant storageCarrier() { return new MacroCoreMonthlyKey(month).storageCarrier(); }
    public long storageMicros() { return new MacroCoreMonthlyKey(month).storageMicros(); }
    public static MacroCoreMonthlyViewKey fromDate(LocalDate date) {
        return new MacroCoreMonthlyViewKey(MacroCoreMonthlyKey.fromDate(date).month());
    }
    public static MacroCoreMonthlyViewKey fromStorage(Instant carrier) {
        return new MacroCoreMonthlyViewKey(MacroCoreMonthlyKey.fromStorage(carrier).month());
    }
}
