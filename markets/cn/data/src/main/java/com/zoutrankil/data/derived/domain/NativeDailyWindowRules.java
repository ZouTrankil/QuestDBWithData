package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.DatasetDefinition;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

public final class NativeDailyWindowRules {
    private NativeDailyWindowRules() {}

    public static void requireTarget(String table, String exactFormal, String prefix) {
        DatasetDefinition.identifier(table);
        DatasetDefinition.identifier(exactFormal);
        DatasetDefinition.identifier(prefix);
        if (!table.equals(exactFormal)
                && !table.matches(Pattern.quote(prefix) + "_[a-z0-9_]{1,64}"))
            throw new IllegalArgumentException("Explicit typed daily target required");
    }

    public static void requireWindow(LocalDate from, LocalDate to) {
        if (from == null
                || to == null
                || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) >= 366)
            throw new IllegalArgumentException(
                    "Explicit ordered daily window of at most 366 days required");
    }

    public static long micros(Instant time) {
        return Math.addExact(
                Math.multiplyExact(time.getEpochSecond(), 1000000), time.getNano() / 1000);
    }

    public static Instant fromMicros(long time) {
        return Instant.ofEpochSecond(
                Math.floorDiv(time, 1000000), Math.floorMod(time, 1000000) * 1000);
    }
}
