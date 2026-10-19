package com.zoutrankil.data.domain;

import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.*;
import java.util.UUID;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Pure logical-to-storage value conversion shared by readers and prepared writes. */
public final class DatasetStorageValues {
    private DatasetStorageValues() {}

    private static boolean isTemporalStorage(Column c) {
        return c.storageType() == StorageType.TIMESTAMP || c.storageType() == StorageType.TIMESTAMP_NS || c.storageType() == StorageType.DATE;
    }

    public static Object storageValue(Column c, Object value) {
        if (value == null) throw new IllegalArgumentException("Null ordered/bound value");
        if (c.temporal() != null) {
            if (c.temporal().kind() == TemporalKind.BUSINESS_DATE) {
                if (!(value instanceof LocalDate date)) throw new IllegalArgumentException("LocalDate required for " + c.logicalName());
                if (isTemporalStorage(c)) return epoch(c, new TemporalValues.CalendarTimestamp(date).storageCarrier());
                if (c.storageType() == StorageType.STRING || c.storageType() == StorageType.VARCHAR || c.storageType() == StorageType.SYMBOL) {
                    return TemporalValues.formatDate(date, TemporalValues.DateFormat.valueOf(c.temporal().sourceFormat()));
                }
                throw new IllegalArgumentException("Unsupported business date storage");
            }
            Instant instant;
            if (c.temporal().kind() == TemporalKind.TECHNICAL) {
                if (!(value instanceof TemporalValues.TechnicalTimestamp technical)) throw new IllegalArgumentException("Technical marker required");
                instant = technical.storageCarrier();
            } else {
                if (!(value instanceof Instant input)) throw new IllegalArgumentException("Instant required for " + c.logicalName());
                instant = input;
            }
            if (!isTemporalStorage(c)) throw new IllegalArgumentException("Unsupported instant storage");
            return epoch(c, instant);
        }
        Class<?> expected = switch (c.storageType()) {
            case SYMBOL, STRING, VARCHAR, LONG256, IPV4 -> String.class;
            case CHAR -> Character.class;
            case BOOLEAN -> Boolean.class;
            case BYTE -> Byte.class;
            case SHORT -> Short.class;
            case INT -> Integer.class;
            case LONG -> Long.class;
            case FLOAT -> Float.class;
            case DOUBLE -> Double.class;
            case UUID -> UUID.class;
            case BINARY -> byte[].class;
            default -> throw new IllegalArgumentException("Missing temporal contract");
        };
        if (!expected.isInstance(value) || value instanceof Double d && !Double.isFinite(d)
                || value instanceof Float f && !Float.isFinite(f)) throw new IllegalArgumentException("Wrong or nonfinite value for " + c.logicalName());
        return value;
    }

    private static long epoch(Column c, Instant instant) {
        var precision = switch (c.storageType()) {
            case DATE -> TemporalValues.Precision.MILLIS;
            case TIMESTAMP -> TemporalValues.Precision.MICROS;
            case TIMESTAMP_NS -> TemporalValues.Precision.NANOS;
            default -> throw new IllegalArgumentException("Not a time storage type");
        };
        TemporalValues.requirePrecision(instant, precision);
        long perSecond = precision == TemporalValues.Precision.NANOS ? 1000000000L
                : precision == TemporalValues.Precision.MICROS ? 1000000L : 1000L;
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), perSecond), instant.getNano() / (1000000000L / perSecond));
    }

}
