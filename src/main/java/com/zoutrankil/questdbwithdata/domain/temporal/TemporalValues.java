package com.zoutrankil.questdbwithdata.domain.temporal;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Explicit source contracts: never infer semantics from a column name or number length. */
public final class TemporalValues {
    private TemporalValues() {}

    public enum DateFormat { BASIC, ISO }
    public enum EpochUnit { SECONDS, MILLIS, MICROS, NANOS }
    public enum Precision { MILLIS, MICROS, NANOS }

    public static LocalDate businessDate(String value, DateFormat format) {
        Objects.requireNonNull(format, "date format required");
        Objects.requireNonNull(value, "date required");
        String pattern = format == DateFormat.BASIC ? "[0-9]{8}" : "[0-9]{4}-[0-9]{2}-[0-9]{2}";
        if (!value.matches(pattern)) {
            throw new DateTimeParseException("Exact calendar date required", value, 0);
        }
        return LocalDate.parse(value, format == DateFormat.BASIC
                ? DateTimeFormatter.BASIC_ISO_DATE : DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String formatDate(LocalDate value, DateFormat format) {
        Objects.requireNonNull(format, "date format required");
        String result = value.format(format == DateFormat.BASIC
                ? DateTimeFormatter.BASIC_ISO_DATE : DateTimeFormatter.ISO_LOCAL_DATE);
        businessDate(result, format); // Enforce the same supported range on writes.
        return result;
    }

    public static Instant offsetInstant(String value, Precision precision) {
        return requirePrecision(OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant(), precision);
    }

    public static Instant localInstant(LocalDateTime value, ZoneId zone, Precision precision) {
        var offsets = Objects.requireNonNull(zone, "explicit zone required")
                .getRules().getValidOffsets(value);
        if (offsets.size() != 1) {
            throw new IllegalArgumentException("Nonexistent or ambiguous local time; provide explicit offset");
        }
        return requirePrecision(value.toInstant(offsets.getFirst()), precision);
    }

    public static Instant epoch(long value, EpochUnit unit, Precision precision) {
        Objects.requireNonNull(unit, "epoch unit required");
        long perSecond = switch (unit) {
            case SECONDS -> 1L;
            case MILLIS -> 1_000L;
            case MICROS -> 1_000_000L;
            case NANOS -> 1_000_000_000L;
        };
        return requirePrecision(Instant.ofEpochSecond(Math.floorDiv(value, perSecond),
                Math.floorMod(value, perSecond) * (1_000_000_000L / perSecond)), precision);
    }

    /** Encode an instant for a declared QuestDB timestamp unit without truncation. */
    public static long epochValue(Instant value, EpochUnit unit) {
        Objects.requireNonNull(unit, "epoch unit required");
        Precision precision = switch (unit) {
            case SECONDS -> Precision.NANOS;
            case MILLIS -> Precision.MILLIS;
            case MICROS -> Precision.MICROS;
            case NANOS -> Precision.NANOS;
        };
        requirePrecision(value, precision);
        if (unit == EpochUnit.SECONDS && value.getNano() != 0) {
            throw new IllegalArgumentException("Timestamp precision would be lost: seconds");
        }
        long perSecond = switch (unit) {
            case SECONDS -> 1L;
            case MILLIS -> 1_000L;
            case MICROS -> 1_000_000L;
            case NANOS -> 1_000_000_000L;
        };
        return Math.addExact(Math.multiplyExact(value.getEpochSecond(), perSecond),
                value.getNano() / (1_000_000_000L / perSecond));
    }

    public static Instant requirePrecision(Instant value, Precision precision) {
        Objects.requireNonNull(value, "instant required");
        Objects.requireNonNull(precision, "storage precision required");
        int quantum = switch (precision) {
            case MILLIS -> 1_000_000;
            case MICROS -> 1_000;
            case NANOS -> 1;
        };
        if (value.getNano() % quantum != 0) {
            throw new IllegalArgumentException("Timestamp precision would be lost: " + precision);
        }
        return value;
    }

    /** Storage carrier only: this UTC midnight does not assert an event occurred at UTC midnight. */
    public record CalendarTimestamp(LocalDate date) {
        public CalendarTimestamp { Objects.requireNonNull(date, "calendar date required"); }
        public Instant storageCarrier() { return date.atStartOfDay().toInstant(ZoneOffset.UTC); }
        public long storageEpoch(EpochUnit unit) { return epochValue(storageCarrier(), unit); }
        public static CalendarTimestamp fromStorageEpoch(long value, EpochUnit unit) {
            return fromStorage(epoch(value, unit, Precision.NANOS));
        }
        public static CalendarTimestamp fromStorage(Instant carrier) {
            var utc = carrier.atOffset(ZoneOffset.UTC);
            if (!utc.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                throw new IllegalArgumentException("Calendar storage carrier must be midnight exactly");
            }
            return new CalendarTimestamp(utc.toLocalDate());
        }
    }

    /** Explicit marker excluded from business freshness calculations by type. */
    public record TechnicalTimestamp(Instant storageCarrier, String reason) {
        public TechnicalTimestamp {
            Objects.requireNonNull(storageCarrier, "technical carrier required");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Technical reason required");
        }
    }
}
