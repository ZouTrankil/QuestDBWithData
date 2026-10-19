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
        var emptyStatement = mock(PreparedStatement.class);
        when(emptyStatement.executeQuery()).thenReturn(mock(ResultSet.class));
        var rows = mock(ResultSet.class);
        var cursor = new AtomicInteger(-1);
        var events = new ArrayList<String>();
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            if (call.<String>getArgument(0).contains("AND ts_code LIKE '%.BJ'")) return emptyStatement;
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

    private Fixture aliasFixture(boolean conflicting) throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        var old=new HashMap<String,Object>(Map.of("trade_date",Timestamp.from(FROM.atStartOfDay().toInstant(ZoneOffset.UTC)),
                "ts_code","839729.BJ","close",10d,"pre_close",10d,"amount",10d));
        var canonical=new HashMap<>(old);canonical.put("ts_code","920729.BJ");
        if(conflicting)canonical.put("close",11d);
        var factorRows=mappedRows(List.of(old,canonical));
        var dailyRows=mappedRows(List.of(Map.of("trade_date",old.get("trade_date"),"ts_code","920729.BJ")));
        var panelOld=new HashMap<>(old);panelOld.put("trade_micros",FROM.toEpochDay()*86_400_000_000L);
        var panelCanonical=new HashMap<>(canonical);panelCanonical.put("trade_micros",panelOld.get("trade_micros"));
        panelCanonical.put("basic_code","920729.BJ");panelCanonical.put("limit_code","920729.BJ");
        var panelRows=mappedRows(List.of(panelOld,panelCanonical));
        when(connection.prepareStatement(anyString())).thenAnswer(call->{
            String sql=call.getArgument(0);var statement=mock(PreparedStatement.class);
            if(sql.startsWith("SELECT trade_date,ts_code,close,pre_close,amount FROM stk_factor"))when(statement.executeQuery()).thenReturn(factorRows);
            else if(sql.startsWith("SELECT trade_date,ts_code FROM daily"))when(statement.executeQuery()).thenReturn(dailyRows);
            else {assertTrue(sql.endsWith("ORDER BY sf.trade_date,sf.ts_code LIMIT 200001"));when(statement.executeQuery()).thenReturn(panelRows);}
            return statement;
        });
        return new Fixture(new RegimeFeaturesMonitorDailyStorage(new JdbcTemplate(source)),panelRows,new ArrayList<>());
    }

    private ResultSet mappedRows(List<? extends Map<String,Object>> data)throws Exception {
        var rows=mock(ResultSet.class);var cursor=new AtomicInteger(-1);var missing=new java.util.concurrent.atomic.AtomicBoolean();
        when(rows.next()).thenAnswer(call->cursor.incrementAndGet()<data.size());
        when(rows.getObject(anyString())).thenAnswer(call->data.get(cursor.get()).get(call.<String>getArgument(0)));
        when(rows.getString(anyString())).thenAnswer(call->{var value=data.get(cursor.get()).get(call.<String>getArgument(0));return value==null?null:value.toString();});
        when(rows.getTimestamp(anyString())).thenAnswer(call->data.get(cursor.get()).get(call.<String>getArgument(0)));
        when(rows.getDouble(anyString())).thenAnswer(call->{var value=data.get(cursor.get()).get(call.<String>getArgument(0));missing.set(value==null);return value instanceof Number n?n.doubleValue():0d;});
        when(rows.wasNull()).thenAnswer(call->missing.get());return rows;
    }

    @Test void canonicalBseCopyIsKeptAndOnlyItsProvenLegacyDuplicateIsExcluded()throws Exception {
        var fixture=aliasFixture(false);long[] raw={0};var audits=new ArrayList<Map<String,Object>>();var days=new ArrayList<LocalDate>();
        fixture.store.readPanelMonth(FROM,TO,Set.of(FROM),raw,MessageDigest.getInstance("SHA-256"),()->false,
                new com.zoutrankil.data.derived.port.RegimeFeaturesMonitorDailySourceReadPort.PanelDayConsumer(){
                    public void stockScopeAudit(Map<String,Object> proof){audits.add(proof);}
                    public void accept(LocalDate date,List<com.zoutrankil.data.domain.policy.RegimeFeaturesMonitorDailyCalculation.Stock> stocks,Set<String> codes,int basic,int limits){
                        assertEquals(Set.of("920729.BJ"),codes);assertEquals(1,stocks.size());assertEquals(1,basic);assertEquals(1,limits);days.add(date);
                    }
                });
        assertEquals(List.of(FROM),days);assertEquals(2,raw[0]);assertEquals(1,audits.getFirst().get("verifiedDuplicateAliasRows"));
        assertEquals(true,audits.getFirst().get("rawSourceRowsPreserved"));verify(fixture.rows).close();
    }

    @Test void conflictingBseCopiesFailBeforeTheOrdinaryStockPanelIsDecoded()throws Exception {
        var fixture=aliasFixture(true);
        assertThrows(IllegalStateException.class,()->fixture.store.readPanelMonth(FROM,TO,Set.of(FROM),new long[]{0},MessageDigest.getInstance("SHA-256"),()->false,
                (date,stocks,codes,basic,limits)->fail("Conflicting identities cannot authorize alias exclusion")));
        verify(fixture.rows,never()).next();
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
