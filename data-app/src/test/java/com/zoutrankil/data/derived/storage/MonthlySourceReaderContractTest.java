package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real JdbcTemplate statement/decoder path, with only physical JDBC responses faked. */
class MonthlySourceReaderContractTest {
    static final LocalDate MONTH=LocalDate.of(1969,12,1);
    static long micros(LocalDate date){return date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()*1_000_000L;}

    @Test void equityReadsAllFourteenFieldsAndRetainsNullSignedZeroAndNegativeEpochMicros()throws Exception {
        var raw=new LinkedHashMap<String,Object>();
        for(var column:IndexMonthlyDataset.columns())raw.put(column.storageName(),switch(column.storageType()){
            case SYMBOL -> column.storageName().equals("ts_code")?"000300.SH":null;
            case TIMESTAMP -> column.storageName().equals("trade_date")?micros(MONTH): -1L;
            default -> column.storageName().equals("pct_chg")?-0.0:null;
        });
        var f=new Fixture(List.of(raw));var reader=new QuestDbEquityStyleMonthlySourceReader(f.jdbc,"index_monthly");
        var rows=reader.readWindow(MONTH,MONTH);assertEquals(1,rows.size());
        assertEquals(IndexMonthlyDataset.columns().stream().map(DatasetDefinition.Column::storageName).toList(),List.copyOf(rows.getFirst().keySet()));
        assertEquals(MONTH,rows.getFirst().get("trade_date"));assertEquals(Instant.ofEpochSecond(-1,999999000),rows.getFirst().get("update_time"));
        assertNull(rows.getFirst().get("close"));assertNull(rows.getFirst().get("layer"));
        assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits((Double)rows.getFirst().get("pct_chg")));
        assertTrue(f.sql.getFirst().contains("cast(\"update_time\" AS LONG) AS \"update_time\""));
        assertTrue(f.sql.getFirst().endsWith("trade_date < '1970-01-01' ORDER BY trade_date,ts_code LIMIT 6201"));
        // The statement creator and JdbcTemplate both apply the original 20-second timeout.
        verify(f.statements.getFirst(),times(2)).setQueryTimeout(20);verify(f.statements.getFirst()).setMaxRows(6201);
        assertThrows(UnsupportedOperationException.class,()->rows.getFirst().put("close",1D));
    }

    @Test void equitySentinelAndInvalidDoubleFailWithoutClaimingACompleteCensus()throws Exception {
        var row=new LinkedHashMap<String,Object>();for(var c:IndexMonthlyDataset.columns())row.put(c.storageName(),switch(c.storageType()){
            case SYMBOL->"000300.SH";case TIMESTAMP->micros(MONTH);default->1D;});
        var capped=new Fixture(Collections.nCopies(6201,row));
        assertEquals("D103 source row-cap sentinel reached; completeness is unknown",assertThrows(IllegalStateException.class,
                ()->new QuestDbEquityStyleMonthlySourceReader(capped.jdbc,"index_monthly").readWindow(MONTH,MONTH)).getMessage());
        row.put("pct_chg",Double.NaN);var invalid=new Fixture(List.of(row));
        assertThrows(IllegalStateException.class,()->new QuestDbEquityStyleMonthlySourceReader(invalid.jdbc,"index_monthly").readWindow(MONTH,MONTH));
    }

    @Test void macroReadKeepsCompleteNullableDoubleSchemaAndOneCallerDeadline()throws Exception {
        var raw=new LinkedHashMap<String,Object>();for(String field:MacroCoreMonthlySourceData.SOURCE_SPECS.get("cn_cpi").fields())raw.put(field,field.equals("month")?(Object)micros(MONTH):field.equals("nt_yoy")?-0.0:null);
        var f=new Fixture(List.of(raw));var reader=new QuestDbMacroCoreMonthlySourceReader(f.jdbc);
        var rows=reader.readWindow("cn_cpi",MONTH,MONTH,System.nanoTime()+Duration.ofSeconds(20).toNanos());
        assertEquals(MacroCoreMonthlySourceData.SOURCE_SPECS.get("cn_cpi").fields(),List.copyOf(rows.getFirst().keySet()));
        assertEquals(MONTH,rows.getFirst().get("month"));assertNull(rows.getFirst().get("nt_val"));
        assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits((Double)rows.getFirst().get("nt_yoy")));
        assertEquals("SELECT \"month\",\"nt_val\",\"nt_yoy\",\"nt_mom\",\"nt_accu\",\"town_val\",\"town_yoy\",\"town_mom\",\"town_accu\",\"cnt_val\",\"cnt_yoy\",\"cnt_mom\",\"cnt_accu\" FROM \"cn_cpi\" WHERE month >= '1969-12-01' AND month < '1970-01-01' ORDER BY month LIMIT 13",
                f.sql.getFirst().replace("cast(\"month\" AS LONG) AS \"month\"","\"month\""));
        verify(f.statements.getFirst()).setMaxRows(13);verify(f.statements.getFirst()).setQueryTimeout(intThat(seconds->seconds>=1&&seconds<=20));
        clearInvocations(f.ds);assertThrows(IllegalStateException.class,()->reader.snapshot(System.nanoTime()-1));verifyNoInteractions(f.ds);
    }

    @Test void macroRejectsPhysicalCarrierCoercionAndKeepsDescendingContextSql()throws Exception {
        var raw=new LinkedHashMap<String,Object>();raw.put("month",micros(MONTH));raw.put("inc_month",1L);raw.put("inc_cumval",null);raw.put("stk_endval",1D);
        var f=new Fixture(List.of(raw));var reader=new QuestDbMacroCoreMonthlySourceReader(f.jdbc);
        assertThrows(IllegalStateException.class,()->reader.readWindow("sf_month",MONTH,MONTH,System.nanoTime()+20_000_000_000L));
        raw.put("inc_month",1D);raw.put("month",micros(MONTH)+1);var wrongTime=new Fixture(List.of(raw));
        assertThrows(IllegalStateException.class,()->new QuestDbMacroCoreMonthlySourceReader(wrongTime.jdbc).readWindow("sf_month",MONTH,MONTH,System.nanoTime()+20_000_000_000L));
        assertEquals("SELECT cast(\"month\" AS LONG) AS \"month\",\"inc_month\",\"inc_cumval\",\"stk_endval\" FROM \"sf_month\" WHERE month < '1969-12-01' ORDER BY month DESC LIMIT 12",reader.contextSql(MONTH));
    }

    static final class Fixture {
        final DataSource ds=mock(DataSource.class);final JdbcTemplate jdbc=new JdbcTemplate(ds);
        final List<String> sql=new ArrayList<>();final List<PreparedStatement> statements=new ArrayList<>();
        Fixture(List<Map<String,Object>> rows)throws Exception {
            var c=mock(Connection.class);when(ds.getConnection()).thenReturn(c);
            when(c.prepareStatement(anyString())).thenAnswer(call->{
                sql.add(call.getArgument(0));var st=mock(PreparedStatement.class);statements.add(st);var rs=mock(ResultSet.class);int[] at={-1};boolean[] wasNull={false};
                when(rs.next()).thenAnswer(i->++at[0]<rows.size());
                when(rs.getObject(anyString())).thenAnswer(i->rows.get(at[0]).get(i.getArgument(0)));
                when(rs.getObject(anyString(),any(Class.class))).thenAnswer(i->rows.get(at[0]).get(i.getArgument(0)));
                when(rs.getString(anyString())).thenAnswer(i->(String)rows.get(at[0]).get(i.getArgument(0)));
                when(rs.getLong(anyString())).thenAnswer(i->{Object value=rows.get(at[0]).get(i.getArgument(0));wasNull[0]=value==null;return value==null?0L:((Number)value).longValue();});
                when(rs.wasNull()).thenAnswer(i->wasNull[0]);when(st.executeQuery()).thenReturn(rs);return st;
            });
        }
    }
}
