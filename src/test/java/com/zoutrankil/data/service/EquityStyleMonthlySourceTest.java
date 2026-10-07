package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.zoutrankil.data.domain.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;

class EquityStyleMonthlySourceTest {
    static final LocalDate JUNE=LocalDate.of(2026,6,1),JULY=LocalDate.of(2026,7,1),AUGUST=LocalDate.of(2026,8,1);
    static List<Map<String,Object>> month(LocalDate month){
        var codes=new ArrayList<>(EquityStyleMonthlySource.FEATURE_CODES.values());Collections.sort(codes);
        var result=new ArrayList<Map<String,Object>>();int i=0;
        for(String code:codes)result.add(raw(code,month.withDayOfMonth(28),++i+0.125));return result;
    }
    static Map<String,Object> raw(String code,LocalDate day,Double pct){
        var row=new LinkedHashMap<String,Object>();
        for(String field:EquityStyleMonthlySource.SOURCE_FIELDS)row.put(field,switch(field){
            case "ts_code"->code;case "trade_date"->day;case "layer"->"base";case "bucket"->"style";
            case "update_time"->Instant.parse("2026-10-06T01:02:03.123456Z");case "pct_chg"->pct;default->10.25;
        });return row;
    }
    static List<Map<String,Object>> sorted(List<Map<String,Object>> values){
        var rows=new ArrayList<>(values);rows.sort(Comparator.comparing((Map<String,Object> r)->(LocalDate)r.get("trade_date")).thenComparing(r->(String)r.get("ts_code")));return rows;
    }
    static final class MutableSource extends EquityStyleMonthlySource {
        final List<Map<String,Object>> raw=new ArrayList<>();long txn=1;int reads;
        MutableSource(){super("index_monthly");raw.addAll(month(JUNE));raw.addAll(month(JULY));}
        @Override public Snapshot snapshot(){return new Snapshot(table(),17,"index_monthly~17",txn,txn,"a".repeat(64));}
        @Override public Batch read(LocalDate from,LocalDate to){
            requireWindow(from,to);reads++;var rows=sorted(raw.stream().filter(r->!((LocalDate)r.get("trade_date")).isBefore(from)&&((LocalDate)r.get("trade_date")).isBefore(YearMonth.from(to).plusMonths(1).atDay(1))).toList());
            return new Batch(snapshot(),rawFingerprint(rows),rows.size(),derive(rows,from,to));
        }
        void appendAugust(){raw.addAll(month(AUGUST));txn++;}
        void revisePrefix(){raw.getFirst().put("close",999.125);txn++;}
    }
    @Test void preservesLegacyPercentageValuesCodeBindingsAndPercentagePointSpreads(){
        var source=month(JUNE);var result=EquityStyleMonthlySource.derive(source,JUNE,JUNE).getFirst();
        var byCode=new HashMap<String,Double>();source.forEach(r->byCode.put((String)r.get("ts_code"),(Double)r.get("pct_chg")));
        assertEquals(byCode.get("000300.SH"),result.hs300Ret1m());assertEquals(byCode.get("000920.SH"),result.valueRet1m());assertEquals(byCode.get("000921.SH"),result.growthRet1m());
        assertEquals(byCode.get("000921.SH")-byCode.get("000920.SH"),result.growthValueRet1m());
        assertEquals(byCode.get("000986.SH")-byCode.get("000985.SH"),result.energyVsAllA1m());assertEquals(YearMonth.of(2026,6),result.month());
    }
    @Test void pivotUsesLastNonnullPctRatherThanLatestCloseOrNull(){
        var raw=new ArrayList<>(month(JUNE));raw.add(raw("000300.SH",JUNE.withDayOfMonth(29),7.25));raw.add(raw("000300.SH",JUNE.withDayOfMonth(30),null));
        assertEquals(7.25,EquityStyleMonthlySource.derive(sorted(raw),JUNE,JUNE).getFirst().hs300Ret1m());
    }
    @Test void nullablePerMonthLegacyCellsRemainNullAndEntireNullMonthIsOmitted(){
        var raw=new ArrayList<>(month(JUNE));raw.add(raw("000300.SH",JULY.withDayOfMonth(28),-0.0));
        var result=EquityStyleMonthlySource.derive(sorted(raw),JUNE,JULY);assertEquals(2,result.size());assertNull(result.getLast().growthRet1m());assertNull(result.getLast().growthValueRet1m());assertNull(result.getLast().energyVsAllA1m());
        assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits(result.getLast().hs300Ret1m()));
        raw.set(raw.size()-1,raw("000300.SH",JULY.withDayOfMonth(28),null));assertEquals(1,EquityStyleMonthlySource.derive(sorted(raw),JUNE,JULY).size());
    }
    @Test void missingOrGloballyAllNullRequiredCodeFailsInsteadOfFabricatingZero(){
        var missing=month(JUNE);missing.removeIf(r->r.get("ts_code").equals("000920.SH"));assertThrows(IllegalStateException.class,()->EquityStyleMonthlySource.derive(missing,JUNE,JUNE));
        var nullable=month(JUNE);nullable.stream().filter(r->r.get("ts_code").equals("000920.SH")).forEach(r->r.put("pct_chg",null));assertThrows(IllegalStateException.class,()->EquityStyleMonthlySource.derive(nullable,JUNE,JUNE));
    }
    @Test void duplicateNonDeduplicatingSourceFullKeyAndWrongOrderingAreRejected(){
        var duplicate=new ArrayList<>(month(JUNE));duplicate.add(new LinkedHashMap<>(duplicate.getFirst()));assertThrows(IllegalStateException.class,()->EquityStyleMonthlySource.derive(sorted(duplicate),JUNE,JUNE));
        var wrong=month(JUNE);Collections.reverse(wrong);assertThrows(IllegalStateException.class,()->EquityStyleMonthlySource.derive(wrong,JUNE,JUNE));
    }
    @Test void fullFourteenFieldCarriersAndFiniteBinary64AreMandatory(){
        for(Object value:List.of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,1L,1)){
            var raw=month(JUNE);raw.getFirst().put("pct_chg",value);assertThrows(IllegalArgumentException.class,()->EquityStyleMonthlySource.derive(raw,JUNE,JUNE));
        }
        var wrong=month(JUNE);wrong.getFirst().put("layer",5);assertThrows(IllegalArgumentException.class,()->EquityStyleMonthlySource.derive(wrong,JUNE,JUNE));
        var bad=month(JUNE);bad.getFirst().put("update_time",Instant.parse("2026-01-01T00:00:00.123456789Z"));assertThrows(IllegalArgumentException.class,()->EquityStyleMonthlySource.derive(bad,JUNE,JUNE));
        var incomplete=month(JUNE);incomplete.getFirst().remove("close");assertThrows(IllegalArgumentException.class,()->EquityStyleMonthlySource.derive(incomplete,JUNE,JUNE));
    }
    @Test void nonfiniteDerivedDifferenceIsRejected(){
        var raw=month(JUNE);raw.forEach(r->{if(r.get("ts_code").equals("000921.SH"))r.put("pct_chg",Double.MAX_VALUE);if(r.get("ts_code").equals("000920.SH"))r.put("pct_chg",-Double.MAX_VALUE);});
        assertThrows(IllegalStateException.class,()->EquityStyleMonthlySource.derive(raw,JUNE,JUNE));
    }
    @Test void allRawFieldsAndSignedZeroContributeToFingerprint(){
        var raw=month(JUNE);String initial=EquityStyleMonthlySource.rawFingerprint(raw);raw.getFirst().put("close",-0.0);String negative=EquityStyleMonthlySource.rawFingerprint(raw);raw.getFirst().put("close",0.0);
        assertNotEquals(initial,negative);assertNotEquals(negative,EquityStyleMonthlySource.rawFingerprint(raw));raw.getFirst().put("update_time",Instant.EPOCH);assertNotEquals(initial,EquityStyleMonthlySource.rawFingerprint(raw));
    }
    @Test void explicitFirstDayTwelveMonthBudgetAndFixedCodeBoundedSql(){
        var source=new MutableSource();EquityStyleMonthlySource.requireWindow(JUNE,JUNE.plusMonths(11));
        for(LocalDate to:List.of(JUNE.plusMonths(12),JUNE.minusMonths(1),JUNE.plusDays(1)))assertThrows(IllegalArgumentException.class,()->source.sourceSql(JUNE,to));
        assertThrows(IllegalArgumentException.class,()->source.sourceSql(null,JUNE));
        String sql=source.sourceSql(JUNE,JULY);assertTrue(sql.contains("trade_date >= '2026-06-01'"));assertTrue(sql.contains("trade_date < '2026-08-01'"));assertTrue(sql.endsWith("ORDER BY trade_date,ts_code LIMIT 6201"));
        assertEquals(16,EquityStyleMonthlySource.FEATURE_CODES.size());assertTrue(sql.contains("cast(\"update_time\" AS LONG)"));assertFalse(sql.contains("SELECT *"));
        assertTrue(EquityStyleMonthlySource.derive(List.of(),JUNE,JUNE).isEmpty());
    }
    @Test void malformedWindowIsRejectedBeforeAnyJdbcAccess()throws Exception{
        var ds=mock(DataSource.class);var source=new EquityStyleMonthlySource(new JdbcTemplate(ds),"index_monthly");
        assertThrows(IllegalArgumentException.class,()->source.read(JUNE.plusDays(1),JULY));verifyNoInteractions(ds);
        assertThrows(IllegalArgumentException.class,()->new EquityStyleMonthlySource(new JdbcTemplate(ds),"index_monthly;drop"));verifyNoInteractions(ds);
    }
    @Test void physicalSourceMustHaveExactYearWalNonDedupSchemaAndSettledCounters()throws Exception{
        var f=new MetadataFixture();assertEquals(4,f.source.snapshot().physicalTxn());
        f.meta.put("dedup",true);assertThrows(IllegalStateException.class,()->f.source.snapshot());f.meta.put("dedup",false);
        f.meta.put("partitionBy","MONTH");assertThrows(IllegalStateException.class,()->f.source.snapshot());f.meta.put("partitionBy","YEAR");
        f.meta.put("writerTxn",3L);assertThrows(IllegalStateException.class,()->f.source.snapshot());f.meta.put("writerTxn",4L);
        f.meta.put("table_txn",null);assertThrows(IllegalStateException.class,()->f.source.snapshot());
    }
    @Test void metadataQueriesCarryTimeoutAndFiniteRowSentinels()throws Exception{
        var f=new MetadataFixture();f.source.snapshot();assertEquals(2,f.statements.size());
        f.statements.forEach(s->{try{verify(s,atLeastOnce()).setQueryTimeout(20);}catch(SQLException e){throw new RuntimeException(e);}});
        verify(f.statements.getFirst()).setMaxRows(15);verify(f.statements.getLast()).setMaxRows(2);
    }
    static final class MetadataFixture {
        final Map<String,Object> meta=new HashMap<>();final List<PreparedStatement> statements=new ArrayList<>();final EquityStyleMonthlySource source;
        MetadataFixture()throws Exception{
            meta.put("id",17);meta.put("directoryName","index_monthly~17");meta.put("table_txn",4L);meta.put("partitionBy","YEAR");meta.put("designatedTimestamp","trade_date");
            for(String field:List.of("walEnabled"))meta.put(field,true);for(String field:List.of("dedup","matView","table_suspended","suspended"))meta.put(field,false);
            for(String field:List.of("wal_pending_row_count","bufferedTxnSize"))meta.put(field,0L);meta.put("sequencerTxn",4L);meta.put("writerTxn",4L);
            var ds=mock(DataSource.class);var connection=mock(Connection.class);when(ds.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenAnswer(call->{String sql=call.getArgument(0);var statement=mock(PreparedStatement.class);statements.add(statement);
                var rows=new ArrayList<Map<String,Object>>();if(sql.contains("table_columns"))for(var c:IndexMonthlyDataset.columns())rows.add(Map.of("column",c.storageName(),"type",c.storageType().name(),"designated",c.storageName().equals("trade_date"),"upsertKey",false));else rows.add(meta);
                var rs=mock(ResultSet.class);int[] at={-1};when(rs.next()).thenAnswer(a->++at[0]<rows.size());
                when(rs.getString(anyString())).thenAnswer(a->{Object value=rows.get(at[0]).get(a.getArgument(0));return value==null?null:value.toString();});
                when(rs.getObject(anyString())).thenAnswer(a->rows.get(at[0]).get(a.getArgument(0)));when(statement.executeQuery()).thenReturn(rs);return statement;});
            source=new EquityStyleMonthlySource(new JdbcTemplate(ds),"index_monthly");
        }
    }
}
