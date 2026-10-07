package com.zoutrankil.data.flow.storage;

import com.zoutrankil.data.calendar.storage.QuestDbSseCalendarWindowReadPort;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtRows;
import com.zoutrankil.data.margin.domain.MarginSecsRows;
import com.zoutrankil.data.margin.domain.MarginSecsState;
import com.zoutrankil.data.margin.storage.*;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlowMarginTargetContractTest {
    static final String PHYSICAL="static-v2-"+"1".repeat(64), STAGE_ID="static-v2-"+"2".repeat(64);
    static final LocalDate DAY=LocalDate.of(2026,9,17);
    @Test void singletonTargetConfigurationProducesIsolatedWriterStateWithoutConnecting()throws Exception {
        var ds=mock(DataSource.class);var jdbc=new JdbcTemplate(ds);var questdb=mock(QuestDB.class);
        var hsgt=new QuestDbMoneyflowHsgtTarget("moneyflow_hsgt",jdbc,questdb);
        var first=hsgt.newWriter(PHYSICAL);var second=hsgt.newWriter(PHYSICAL);
        assertNotSame(first,second);assertSame(first.codec(),second.codec());
        first.useStage("java_d027_moneyflow_hsgt_stage_"+"a".repeat(32),STAGE_ID);
        assertEquals("moneyflow_hsgt",second.stageTable());assertNotEquals(first.stageTable(),second.stageTable());
        assertFalse(first.uncertainSenderStopped());assertFalse(second.uncertainSenderStopped());
        assertThrows(IllegalStateException.class,()->second.send(List.of(hsgtRow())));
        assertNotSame(hsgt.newTables(),hsgt.newTables());
        var secs=new QuestDbMarginSecsTarget("java_d030_margin_secs_contract",jdbc,questdb);
        var a=secs.newWriter(PHYSICAL);var b=secs.newWriter(PHYSICAL);assertNotSame(a,b);assertSame(a.codec(),b.codec());
        assertFalse(a.uncertainSenderStopped());assertFalse(b.uncertainSenderStopped());
        assertThrows(IllegalArgumentException.class,()->new QuestDbMarginSecsTarget("margin_secs",jdbc,questdb));
        verifyNoInteractions(ds,questdb);
    }
    @Test void copiedStageSqlKeepsDayWalNoDedupAndExclusiveUpperMidnight() {
        var source=new JdbcTemplate(mock(DataSource.class));
        try(var construction=mockConstruction(JdbcTemplate.class)){
            var tables=new QuestDbMoneyflowHsgtTables(source);
            tables.createOutsideStage("moneyflow_hsgt","java_d027_moneyflow_hsgt_stage_a",DAY,DAY.plusDays(1));
            var jdbc=construction.constructed().getFirst();verify(jdbc).setQueryTimeout(120);
            verify(jdbc).execute("CREATE TABLE \"java_d027_moneyflow_hsgt_stage_a\" AS (SELECT trade_date,ggt_ss,ggt_sz,hgt,sgt,north_money,south_money FROM \"moneyflow_hsgt\" WHERE trade_date<cast('2026-09-17T00:00:00.000000Z' AS TIMESTAMP) OR trade_date>=cast('2026-09-19T00:00:00.000000Z' AS TIMESTAMP)) TIMESTAMP(trade_date) PARTITION BY DAY WAL");
        }
    }
    @Test void sseStreamingSqlRetainsThirtyThreeRowLimitMicrosecondBoundsAndTypedFlags()throws Exception {
        var jdbc=mock(JdbcTemplate.class);var row=mock(ResultSet.class);long micros=DAY.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()*1000;
        when(row.getObject("cal_micros")).thenReturn(micros);when(row.getObject("is_open")).thenReturn(1);
        doAnswer(call->{((RowCallbackHandler)call.getArgument(1)).processRow(row);return null;}).when(jdbc).query(anyString(),any(RowCallbackHandler.class),any(Object[].class));
        var calendar=new QuestDbSseCalendarWindowReadPort(jdbc);var returned=new ArrayList<String>();calendar.readSseDates(DAY,DAY,(date,open)->returned.add(date+":"+open));
        assertEquals(List.of("2026-09-17:1"),returned);
        verify(jdbc).query(eq("SELECT cast(cal_date AS long) AS cal_micros,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date LIMIT 33"),any(RowCallbackHandler.class),eq(micros),eq(micros+86_400_000_000L));
        when(row.getObject("is_open")).thenReturn(2);
        assertEquals("Formal northbound coverage requires typed SSE calendar rows",assertThrows(IllegalStateException.class,()->calendar.readSseDates(DAY,DAY,(d,o)->fail())).getMessage());
        when(row.getObject("is_open")).thenReturn(1);when(row.getObject("cal_micros")).thenReturn("20260917");
        assertThrows(IllegalStateException.class,()->calendar.readSseDates(DAY,DAY,(d,o)->fail()));
    }
    @Test void secsRangeUsesMicrosecondsAndKeepsEmptyAndMalformedCountGuards()throws Exception {
        var source=new JdbcTemplate(mock(DataSource.class));var rs=mock(ResultSet.class);when(rs.next()).thenReturn(true);
        long micros=DAY.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()*1000;
        when(rs.getObject("min_micros")).thenReturn(micros);when(rs.getObject("max_micros")).thenReturn(micros);when(rs.getObject("row_count")).thenReturn(2L);
        try(var construction=mockConstruction(JdbcTemplate.class,(jdbc,context)->doAnswer(call->((ResultSetExtractor<?>)call.getArgument(1)).extractData(rs)).when(jdbc).query(anyString(),any(ResultSetExtractor.class)))){
            var storage=new MarginSecsStorage(source,"java_d030_margin_secs_contract");
            assertEquals(new MarginSecsState.TargetRange(DAY,DAY,2),storage.targetRange());var jdbc=construction.constructed().getFirst();
            verify(jdbc).setQueryTimeout(30);verify(jdbc).setMaxRows(1_000_001);
            verify(jdbc).query(eq("SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros,count(*) AS row_count FROM \"java_d030_margin_secs_contract\""),any(ResultSetExtractor.class));
            when(rs.getObject("min_micros")).thenReturn(null);when(rs.getObject("max_micros")).thenReturn(null);when(rs.getObject("row_count")).thenReturn(0L);
            assertEquals(new MarginSecsState.TargetRange(null,null,0),storage.targetRange());
            when(rs.getObject("row_count")).thenReturn(1L);assertEquals("D030 empty target range/count mismatch",assertThrows(java.sql.SQLException.class,storage::targetRange).getMessage());
            when(rs.getObject("row_count")).thenReturn(1_000_001L);assertEquals("D030 target row count unavailable/over bound",assertThrows(java.sql.SQLException.class,storage::targetRange).getMessage());
        }
    }
    @Test void rowPoliciesKeepLiteralFieldsOrderNullSignedZeroAndStorageCodecBytes() {
        String hsgt="{\"trade_date\":\"2026-09-17\",\"ggt_ss\":null,\"ggt_sz\":null,\"hgt\":null,\"sgt\":null,\"north_money\":-0.0,\"south_money\":null}";
        String secs="{\"trade_date\":\"2026-09-17\",\"ts_code\":\"600000.SH\",\"name\":null,\"exchange\":\"SSE\"}";
        var sec=new MarginSecs(new MarginSecsKey(DAY,"600000.SH"),null,"SSE");
        assertArrayEquals(hsgt.getBytes(StandardCharsets.UTF_8),MoneyflowHsgtRows.canonicalBytes(hsgtRow()));
        assertArrayEquals(secs.getBytes(StandardCharsets.UTF_8),MarginSecsRows.canonicalBytes(sec));
        assertArrayEquals(MoneyflowHsgtRows.canonicalBytes(hsgtRow()),MoneyflowHsgtWritePort.CODEC.canonicalBytes(hsgtRow()));
        assertArrayEquals(MarginSecsRows.canonicalBytes(sec),MarginSecsWritePort.CODEC.canonicalBytes(sec));
        assertFalse(MoneyflowHsgtRows.sameRows(List.of(hsgtRow()),List.of(new MoneyflowHsgt(new MoneyflowHsgtKey(DAY),null,null,null,null,0.0,null))));
        assertTrue(MarginSecsRows.sameRows(List.of(sec),List.of(sec)));assertFalse(MarginSecsRows.sameRows(null,List.of()));
    }
    static MoneyflowHsgt hsgtRow(){return new MoneyflowHsgt(new MoneyflowHsgtKey(DAY),null,null,null,null,-0.0,null);}
}
