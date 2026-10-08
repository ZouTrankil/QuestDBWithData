package com.zoutrankil.data.derived.storage;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegimeSourceStreamingTest {
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = FROM.plusDays(2);

    private record Fixture(RegimeFeaturesMonitorDailyStorage store, ResultSet rows, List<String> events) {}

    private Fixture fixture() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        var cursor = new AtomicInteger(-1);
        var events = new ArrayList<String>();
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            assertTrue(call.<String>getArgument(0).endsWith("ORDER BY sf.trade_date,sf.ts_code LIMIT 200001"));
            return statement;
        });
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenAnswer(call -> cursor.incrementAndGet() < 2);
        when(rows.getObject("trade_micros")).thenAnswer(call -> FROM.plusDays(cursor.get()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() * 1000);
        when(rows.getString(anyString())).thenReturn("000001.SZ");
        when(rows.getDouble(anyString())).thenAnswer(call -> {
            events.add("number-" + cursor.get());
            return 10d;
        });
        return new Fixture(new RegimeFeaturesMonitorDailyStorage(new JdbcTemplate(source)), rows, events);
    }

    @Test void eachDayCompletesBeforeDecodingTheNextDaysNumericFields() throws Exception {
        var fixture = fixture();
        long[] count = {0};
        var completed = new ArrayList<LocalDate>();
        fixture.store.readPanelMonth(FROM, TO, Set.of(FROM, FROM.plusDays(1)), count,
                MessageDigest.getInstance("SHA-256"), () -> false, (date, stocks, codes, basic, limits) -> {
                    assertEquals(1, stocks.size());
                    assertEquals(1, codes.size());
                    assertEquals(1, basic);
                    assertEquals(1, limits);
                    completed.add(date);
                    fixture.events.add("complete-" + date);
                });
        assertEquals(List.of(FROM, FROM.plusDays(1)), completed);
        assertEquals(2, count[0]);
        assertTrue(fixture.events.indexOf("complete-" + FROM) < fixture.events.indexOf("number-1"));
        assertEquals("complete-" + FROM.plusDays(1), fixture.events.getLast());
        verify(fixture.rows).close();
    }

    @Test void firstRowCancellationHappensBeforeDateOrValueDecoding() throws Exception {
        var fixture = fixture();
        long[] count = {0};
        assertThrows(CancellationException.class, () -> fixture.store.readPanelMonth(FROM, TO, Set.of(FROM), count,
                MessageDigest.getInstance("SHA-256"), () -> true,
                (date, stocks, codes, basic, limits) -> fail("No day may complete")));
        assertEquals(0, count[0]);
        verify(fixture.rows, never()).getObject(anyString());
        verify(fixture.rows, never()).getDouble(anyString());
        verify(fixture.rows).close();
    }
}
