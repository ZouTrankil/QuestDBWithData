package com.zoutrankil.data.domain.temporal;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.domain.temporal.TemporalValues.*;

class TemporalValuesTest {
    @Test void calendarDatesRejectMalformedInputsAndKeepTheTradingDay() {
        assertEquals(LocalDate.of(2024, 2, 29), businessDate("20240229", DateFormat.BASIC));
        for (String invalid : new String[]{"20230229", "20241301", "20240101Z", "20240101+0800", " 20240101"}) {
            assertThrows(DateTimeException.class, () -> businessDate(invalid, DateFormat.BASIC));
        }
        var date = businessDate("2026-09-29", DateFormat.ISO);
        assertEquals(date, CalendarTimestamp.fromStorage(new CalendarTimestamp(date).storageCarrier()).date());
        assertEquals(date, CalendarTimestamp.fromStorageEpoch(
                new CalendarTimestamp(date).storageEpoch(EpochUnit.MICROS), EpochUnit.MICROS).date());
        assertEquals(date, CalendarTimestamp.fromStorageEpoch(
                new CalendarTimestamp(date).storageEpoch(EpochUnit.NANOS), EpochUnit.NANOS).date());
        assertThrows(IllegalArgumentException.class,
                () -> CalendarTimestamp.fromStorageEpoch(1, EpochUnit.MICROS));
        assertThrows(IllegalArgumentException.class,
                () -> CalendarTimestamp.fromStorage(Instant.parse("2026-09-29T00:00:00.000001Z")));
    }

    @Test void instantsRequireAnOffsetAndPreserveNanoseconds() {
        var value = offsetInstant("2026-09-29T00:00:00.123456789+08:00", Precision.NANOS);
        assertEquals(Instant.parse("2026-09-28T16:00:00.123456789Z"), value);
        assertEquals(value, offsetInstant(value.toString(), Precision.NANOS));
        assertThrows(DateTimeException.class, () -> offsetInstant("2026-09-29T00:00:00", Precision.NANOS));
        assertThrows(IllegalArgumentException.class, () -> requirePrecision(value, Precision.MICROS));
        assertThrows(NullPointerException.class, () -> epoch(123, null, Precision.NANOS));
        assertEquals(Instant.parse("1969-12-31T23:59:59.999999999Z"), epoch(-1, EpochUnit.NANOS, Precision.NANOS));
        assertEquals(Instant.parse("1969-12-31T23:59:59.999999Z"), epoch(-1, EpochUnit.MICROS, Precision.MICROS));
        assertEquals(-1, epochValue(Instant.parse("1969-12-31T23:59:59.999999Z"), EpochUnit.MICROS));
        assertEquals(value, epoch(epochValue(value, EpochUnit.NANOS), EpochUnit.NANOS, Precision.NANOS));
        assertThrows(IllegalArgumentException.class, () -> epochValue(value, EpochUnit.MICROS));
        assertThrows(ArithmeticException.class,
                () -> epochValue(Instant.parse("2500-01-01T00:00:00Z"), EpochUnit.NANOS));
    }

    @Test void localTimesRejectBothDstAmbiguityAndGaps() {
        var ny = ZoneId.of("America/New_York");
        assertThrows(IllegalArgumentException.class,
                () -> localInstant(LocalDateTime.parse("2026-03-08T02:30:00"), ny, Precision.MICROS));
        assertThrows(IllegalArgumentException.class,
                () -> localInstant(LocalDateTime.parse("2026-11-01T01:30:00"), ny, Precision.MICROS));
        assertEquals(Instant.parse("2026-09-28T16:00:00Z"),
                localInstant(LocalDateTime.parse("2026-09-29T00:00:00"), ZoneId.of("Asia/Shanghai"), Precision.MICROS));
        assertThrows(IllegalArgumentException.class, () -> new TechnicalTimestamp(Instant.EPOCH, ""));
    }
}
