package com.zoutrankil.data.strategy.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import javax.sql.DataSource;

class StrategyInputTemporalMappingsTest {
    private Object decode(String role, String column, Object value) {
        return StrategyInputTemporalMappings.decodeLegacyV1(
                StrategyInputTemporalMappings.binding(role), column, value);
    }

    @Test
    void tradingDatesUseTheBoundObjectRatherThanABareTimestampName() {
        var day = LocalDate.of(2026, 9, 30);
        var carrier = Timestamp.from(Instant.parse("2026-09-30T00:00:00Z"));
        assertEquals(day, decode("etf_daily", "timestamp", carrier));
        assertEquals(day, decode("l2_raw", "ts", carrier));
        assertEquals(day, decode("calendar", "cal_date", carrier));
        assertThrows(
                IllegalStateException.class, () -> decode("market_context", "timestamp", carrier));
        assertThrows(
                IllegalArgumentException.class,
                () -> StrategyInputTemporalMappings.binding("unknown"));
    }

    @Test
    void technicalEpochKeepsOnlyItsExplicitV1Encoding() {
        assertEquals(
                LocalDate.ofEpochDay(0),
                decode("etf_reference", "timestamp", Timestamp.from(Instant.EPOCH)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        decode(
                                "etf_reference",
                                "timestamp",
                                Timestamp.from(Instant.parse("2026-09-30T00:00:00Z"))));
        assertThrows(
                IllegalStateException.class,
                () ->
                        decode(
                                "etf_reference",
                                "timestamp",
                                Timestamp.from(Instant.parse("1970-01-01T08:00:00Z"))));
    }

    @Test
    void calculationTimesKeepTheirExactInstantAndCannotBeInventedFromADate() {
        var at = Instant.parse("2026-09-30T08:01:02.123456789Z");
        assertEquals(at.toString(), decode("market_context", "updated_at", Timestamp.from(at)));
        assertEquals(at.toString(), decode("sentiment", "updated_at", Timestamp.from(at)));
        assertEquals(at.toString(), decode("etf_reference", "update_time", Timestamp.from(at)));
        assertThrows(
                IllegalStateException.class,
                () -> decode("sentiment", "updated_at", Date.valueOf("2026-09-30")));
    }

    @Test
    void businessDatesNeverReceiveAnEightHourShiftOrLoseNonmidnightEvidence() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        decode(
                                "etf_daily",
                                "timestamp",
                                Timestamp.from(Instant.parse("2026-09-30T08:00:00Z"))));
        assertThrows(
                IllegalStateException.class,
                () ->
                        decode(
                                "l2_raw",
                                "ts",
                                Timestamp.from(Instant.parse("2026-09-30T00:00:00.000000001Z"))));
        assertEquals("None", decode("calendar", "pretrade_date", "None"));
        assertEquals("20260930", decode("stock_metadata", "list_date", "20260930"));
    }

    @Test
    void actualReadPathUsesTheBoundContractAndRetainsV1FrozenValues() throws Exception {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("timestamp");
        when(metadata.getColumnLabel(2)).thenReturn("update_time");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.getObject(1)).thenReturn(Timestamp.from(Instant.EPOCH));
        var observed = Instant.parse("2026-09-30T08:01:02.123456Z");
        when(rs.getObject(2)).thenReturn(Timestamp.from(observed));
        var original = mock(JdbcTemplate.class);
        when(original.getDataSource()).thenReturn(mock(DataSource.class));
        try (var templates =
                mockConstruction(
                        JdbcTemplate.class,
                        (mock, context) -> {
                            doAnswer(
                                            call -> {
                                                call.getArgument(1, RowCallbackHandler.class)
                                                        .processRow(rs);
                                                return null;
                                            })
                                    .when(mock)
                                    .query(
                                            anyString(),
                                            any(RowCallbackHandler.class),
                                            any(Object[].class));
                        })) {
            var reader = new QuestDbStrategyInputs(original, 10_000);
            var rows =
                    reader.readSql(
                            "SELECT timestamp,update_time FROM etf_basic",
                            new Object[0],
                            "etf_reference",
                            10);
            assertEquals(1, rows.size());
            assertEquals(LocalDate.ofEpochDay(0), rows.getFirst().get("timestamp"));
            assertEquals(observed.toString(), rows.getFirst().get("update_time"));
        }
    }
}
