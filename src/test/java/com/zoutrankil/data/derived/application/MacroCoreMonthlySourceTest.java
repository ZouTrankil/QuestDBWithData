package com.zoutrankil.data.derived.application;
import com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData;
import com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData.*;
import com.zoutrankil.data.derived.port.MacroCoreMonthlySourceReadPort;
import com.zoutrankil.data.derived.storage.QuestDbMacroCoreMonthlySourceReader;
import com.zoutrankil.data.derived.storage.QuestDbMacroCoreMonthlyTarget;

import com.zoutrankil.data.service.*;

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

class MacroCoreMonthlySourceTest {
    static QuestDbMacroCoreMonthlySourceReader sql(){return new QuestDbMacroCoreMonthlySourceReader(new JdbcTemplate(mock(DataSource.class)));}
    static final LocalDate JUNE=LocalDate.of(2026,6,1),JULY=LocalDate.of(2026,7,1),AUGUST=LocalDate.of(2026,8,1);
    static Map<String,Object> raw(String table,LocalDate month){
        var spec=MacroCoreMonthlySource.SOURCE_SPECS.get(table);var row=new LinkedHashMap<String,Object>();
        for(String field:spec.fields())row.put(field,field.equals(spec.timestamp())?month:field.equals("quarter")?month.getYear()+"Q"+(month.getMonthValue()/3):10.25);
        if(table.equals("sf_month")){row.put("stk_endval",100.0+month.getMonthValue());row.put("inc_month",25.125);}
        return row;
    }
    static LinkedHashMap<String,List<Map<String,Object>>> initialWindow(){
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:MacroCoreMonthlySource.SOURCE_TABLES){var rows=new ArrayList<Map<String,Object>>();
            if(table.equals("cn_gdp"))rows.add(raw(table,JUNE.withDayOfMonth(30)));else {rows.add(raw(table,JUNE));rows.add(raw(table,JULY));}result.put(table,rows);
        }return result;
    }
    static List<Map<String,Object>> initialContext(){var result=new ArrayList<Map<String,Object>>();for(int i=12;i>0;i--)result.add(raw("sf_month",JUNE.minusMonths(i)));return result;}
    static final class MutableSource extends MacroCoreMonthlySource {
        final LinkedHashMap<String,List<Map<String,Object>>> window=initialWindow();final List<Map<String,Object>> context=initialContext();long txn=1;int reads;
        MutableSource(){super(mock(MacroCoreMonthlySourceReadPort.class));}
        @Override public Snapshot snapshot(){var snapshots=new ArrayList<PhysicalSnapshot>();int id=17;for(String table:SOURCE_TABLES)snapshots.add(new PhysicalSnapshot(table,id++,table+"~unit",txn,txn,txn,txn,0,0,(long)(window.get(table).size()+(table.equals("sf_month")?context.size():0)),"a".repeat(64)));return new Snapshot(snapshots);}
        @Override public Batch read(LocalDate from,LocalDate to){
            requireWindow(from,to);reads++;var scoped=new LinkedHashMap<String,List<Map<String,Object>>>();
            for(String table:SOURCE_TABLES){String timestamp=SOURCE_SPECS.get(table).timestamp();scoped.put(table,window.get(table).stream().filter(r->!((LocalDate)r.get(timestamp)).isBefore(from)&&((LocalDate)r.get(timestamp)).isBefore(YearMonth.from(to).plusMonths(1).atDay(1))).toList());}
            var preceding=new ArrayList<Map<String,Object>>(context);preceding.addAll(window.get("sf_month"));preceding.removeIf(r->!((LocalDate)r.get("month")).isBefore(from));preceding.sort(Comparator.comparing(r->(LocalDate)r.get("month")));if(preceding.size()>12)preceding=new ArrayList<>(preceding.subList(preceding.size()-12,preceding.size()));
            var derived=derive(scoped,preceding,from,to);var counts=new LinkedHashMap<String,Integer>();scoped.forEach((table,rows)->counts.put(table,rows.size()));
            return new Batch(snapshot(),rawFingerprint(scoped,preceding),preceding.size()+scoped.values().stream().mapToInt(List::size).sum(),derived.rows(),derived.candidateMonths(),derived.withheldMonths(),preceding.size(),counts);
        }
        void appendAugust(){for(String table:SOURCE_TABLES)if(!table.equals("cn_gdp"))window.get(table).add(raw(table,AUGUST));txn++;}
        void revisePrefix(){window.get("cn_cpi").getFirst().put("nt_val",999.125);txn++;}
        void reviseContext(){context.getFirst().put("inc_cumval",999.125);txn++;}
        void empty(){window.values().forEach(List::clear);context.clear();txn++;}
        void withholdJuly(){window.get("cn_pmi").getLast().put("pmi010000",null);txn++;}
        void removeMonth(LocalDate month){for(String table:SOURCE_TABLES){String stamp=SOURCE_SPECS.get(table).timestamp();window.get(table).removeIf(r->YearMonth.from((LocalDate)r.get(stamp)).equals(YearMonth.from(month)));}txn++;}
    }
    @Test void fixedSourceSchemasMatchNativeColumnCountsAndMonthlyDedupKeys(){
        assertEquals(List.of(13,31,60,10,10,4),MacroCoreMonthlySource.SOURCE_SPECS.values().stream().map(s->s.fields().size()).toList());
        assertEquals("report_date",MacroCoreMonthlySource.SOURCE_SPECS.get("cn_gdp").timestamp());assertEquals("STRING",MacroCoreMonthlySource.SOURCE_SPECS.get("cn_gdp").type("quarter"));
        assertEquals(6,MacroCoreMonthlySource.REQUIRED_MONTHLY_FIELDS.size());assertFalse(MacroCoreMonthlySource.REQUIRED_MONTHLY_FIELDS.contains("gdp_yoy"));assertFalse(MacroCoreMonthlySource.REQUIRED_MONTHLY_FIELDS.contains("social_financing_yoy"));
    }
    @Test void completeSixSourceJoinPreservesStoredUnitsAndOwnGdpQuarterMonth(){
        var source=new MutableSource();var batch=source.read(JUNE,JULY);assertEquals(23,batch.rawRows());assertEquals(12,batch.sfContextRows());assertEquals(2,batch.rows().size());assertTrue(batch.withheldMonths().isEmpty());
        var june=batch.rows().getFirst();var july=batch.rows().getLast();assertEquals(10.25,june.cpiYoy());assertEquals(10.25,june.pmiMfg());assertEquals(10.25,june.gdpYoy());assertNull(july.gdpYoy());
        assertEquals(25.125,june.newRmbLoan());assertEquals(106.0,june.socialFinancingStock());assertEquals(106.0/106.0-1.0,june.socialFinancingYoy());
        source.window.get("sf_month").getFirst().put("stk_endval",159.0);assertEquals(0.5,source.read(JUNE,JULY).rows().getFirst().socialFinancingYoy());
    }
    @Test void socialFinancingUsesPrecedingTwelvePhysicalObservationsAcrossCalendarGaps(){
        var context=new ArrayList<Map<String,Object>>();for(int i=0;i<12;i++)context.add(raw("sf_month",JUNE.minusMonths(24-i*2)));context.getFirst().put("stk_endval",53.0);
        var row=MacroCoreMonthlySource.derive(initialWindow(),context,JUNE,JULY).rows().getFirst();assertEquals(1.0,row.socialFinancingYoy());
    }
    @Test void missingOrNullableSfContextLeavesYoyNullWithoutWithholdingCompleteMonth(){
        var source=new MutableSource();source.context.removeFirst();var batch=source.read(JUNE,JULY);assertNull(batch.rows().getFirst().socialFinancingYoy());assertNotNull(batch.rows().getLast().socialFinancingYoy());
        source=new MutableSource();source.context.getFirst().put("stk_endval",null);assertNull(source.read(JUNE,JULY).rows().getFirst().socialFinancingYoy());
        source.context.clear();assertEquals(2,source.read(JUNE,JULY).rows().size());
    }
    @Test void zeroDenominatorAndNonfiniteSfRatioAreRejected(){
        for(double zero:List.of(0.0,-0.0)){var source=new MutableSource();source.context.getFirst().put("stk_endval",zero);assertThrows(IllegalStateException.class,()->source.read(JUNE,JULY));}
        var source=new MutableSource();source.context.getFirst().put("stk_endval",Double.MIN_VALUE);source.window.get("sf_month").getFirst().put("stk_endval",Double.MAX_VALUE);assertThrows(IllegalStateException.class,()->source.read(JUNE,JULY));
    }
    @Test void incompleteRequiredSourceMonthsAreExplicitlyWithheldAndNeverIncremental(){
        var source=new MutableSource();source.withholdJuly();var batch=source.read(JUNE,JULY);assertEquals(List.of(YearMonth.from(JUNE),YearMonth.from(JULY)),batch.candidateMonths());assertEquals(List.of(YearMonth.from(JULY)),batch.withheldMonths());assertEquals(1,batch.rows().size());
        assertThrows(IllegalStateException.class,()->MacroCoreMonthlySource.requireIncrementalCoverage(batch.rows(),JUNE,JULY));
        source=new MutableSource();source.window.get("cn_gdp").clear();assertEquals(2,source.read(JUNE,JULY).rows().size());assertTrue(source.read(JUNE,JULY).rows().stream().allMatch(r->r.gdpYoy()==null));
    }
    @Test void normalizedMonthlyDuplicateAndUnorderedObservationsAreRejected(){
        var source=new MutableSource();source.window.get("cn_cpi").add(new LinkedHashMap<>(source.window.get("cn_cpi").getLast()));var duplicate=source;assertThrows(IllegalStateException.class,()->duplicate.read(JUNE,JULY));
        source=new MutableSource();Collections.reverse(source.window.get("cn_ppi"));var bad=source;assertThrows(IllegalStateException.class,()->bad.read(JUNE,JULY));
        var context=initialContext();context.set(1,new LinkedHashMap<>(context.getFirst()));assertThrows(IllegalStateException.class,()->MacroCoreMonthlySource.derive(initialWindow(),context,JUNE,JULY));
    }
    @Test void sourceFullRawFieldsStrictDatesQuarterAndFiniteDoubleAreRequired(){
        for(Object value:List.of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,1L,1)){var source=new MutableSource();source.window.get("cn_pmi").getFirst().put("pmi011900",value);assertThrows(IllegalArgumentException.class,()->source.read(JUNE,JULY));}
        var source=new MutableSource();source.window.get("cn_cpi").getFirst().remove("town_val");var incomplete=source;assertThrows(IllegalArgumentException.class,()->incomplete.read(JUNE,JULY));
        source=new MutableSource();source.window.get("cn_gdp").getFirst().put("quarter","2026Q3");var wrongQuarter=source;assertThrows(IllegalArgumentException.class,()->wrongQuarter.read(JUNE,JULY));
        source=new MutableSource();source.window.get("cn_m").getFirst().put("month",JUNE.plusDays(1));var wrongDay=source;assertThrows(IllegalArgumentException.class,()->wrongDay.read(JUNE,JULY));
        source=new MutableSource();source.window.get("cn_m").getFirst().put("month",Instant.EPOCH);var wrongCarrier=source;assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlySource.derive(wrongCarrier.window,wrongCarrier.context,JUNE,JULY));
    }
    @Test void allRawFieldsContextNullsAndSignedZeroContributeToDataOnlyFingerprint(){
        var source=new MutableSource();var before=source.read(JUNE,JULY);source.revisePrefix();var after=source.read(JUNE,JULY);assertNotEquals(before.rawFingerprint(),after.rawFingerprint());assertEquals(before.rows(),after.rows());
        source.reviseContext();assertNotEquals(after.rawFingerprint(),source.read(JUNE,JULY).rawFingerprint());
        source.context.getFirst().put("inc_cumval",-0.0);String negative=source.read(JUNE,JULY).rawFingerprint();source.context.getFirst().put("inc_cumval",0.0);assertNotEquals(negative,source.read(JUNE,JULY).rawFingerprint());
        String positive=source.read(JUNE,JULY).rawFingerprint();source.txn++;assertEquals(positive,source.read(JUNE,JULY).rawFingerprint());assertNotEquals(before.snapshot().version(),source.snapshot().version());
    }
    @Test void explicitFirstDayTwelveMonthClosedBudgetAndFiniteAllFieldSql(){
        var source=new MutableSource();MacroCoreMonthlySource.requireWindow(JUNE,JUNE.plusMonths(11));
        for(LocalDate to:List.of(JUNE.plusMonths(12),JUNE.minusMonths(1),JUNE.plusDays(1)))assertThrows(IllegalArgumentException.class,()->sql().sourceSql("cn_cpi",JUNE,to));
        assertThrows(IllegalArgumentException.class,()->sql().sourceSql("cn_cpi",null,JUNE));assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlySource.requireClosedWindow(JUNE,JULY,JULY));
        String sql=sql().sourceSql("cn_cpi",JUNE,JULY);assertTrue(sql.contains("month >= '2026-06-01'"));assertTrue(sql.contains("month < '2026-08-01'"));assertTrue(sql.endsWith("ORDER BY month LIMIT 13"));assertFalse(sql.contains("SELECT *"));
        assertTrue(sql().contextSql(JUNE).endsWith("ORDER BY month DESC LIMIT 12"));assertThrows(IllegalArgumentException.class,()->sql().sourceSql("arbitrary",JUNE,JULY));
    }
    @Test void malformedWindowFailsBeforeJdbcAndEmptyMaterializeIsDistinctFromIncremental(){
        var ds=mock(DataSource.class);var source=new MacroCoreMonthlySource(new QuestDbMacroCoreMonthlySourceReader(new JdbcTemplate(ds)));assertThrows(IllegalArgumentException.class,()->source.read(JUNE.plusDays(1),JULY));verifyNoInteractions(ds);
        var empty=new MutableSource();empty.empty();var batch=empty.read(JUNE,JULY);assertEquals(0,batch.rawRows());assertTrue(batch.rows().isEmpty());assertThrows(IllegalStateException.class,()->MacroCoreMonthlySource.requireIncrementalCoverage(batch.rows(),JUNE,JULY));
        var context=initialContext();context.addFirst(raw("sf_month",JUNE.minusMonths(13)));assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlySource.derive(initialWindow(),context,JUNE,JULY));
    }
    @Test void exactFrozenSixSchemasYearWalDedupAndSettledWalAreRequired()throws Exception{
        var f=new MetadataFixture();assertEquals(6,f.source.snapshot().sources().size());var meta=f.metadata.get("cn_gdp");
        for(String field:List.of("dedup","walEnabled")){meta.put(field,false);assertThrows(IllegalStateException.class,()->f.source.snapshot());meta.put(field,true);}
        for(String field:List.of("suspended","table_suspended")){meta.put(field,true);assertThrows(IllegalStateException.class,()->f.source.snapshot());meta.put(field,false);}
        meta.put("partitionBy","MONTH");assertThrows(IllegalStateException.class,()->f.source.snapshot());meta.put("partitionBy","YEAR");meta.put("writerTxn",0L);assertThrows(IllegalStateException.class,()->f.source.snapshot());
    }
    @Test void nullableNewEmptyGdpPhysicalFrontierRequiresIndependentCountAndRemainsNull()throws Exception{
        var f=new MetadataFixture();f.empty("cn_gdp");var snapshot=f.source.snapshot();var gdp=snapshot.sources().get(4);assertNull(gdp.physicalTxn());assertNull(gdp.walTxn());assertNull(gdp.metadataRowCount());assertEquals(0,gdp.writerTxn());assertEquals(1,f.countQueries);
        f.metadata.get("cn_gdp").put("table_txn",0L);f.metadata.get("cn_gdp").put("wal_txn",0L);f.metadata.get("cn_gdp").put("table_row_count",0L);assertNotEquals(snapshot.version(),f.source.snapshot().version());
    }
    @Test void emptyOptionalGdpProducesNullableGdpRowsAfterStableIndependentSourceCounts()throws Exception{
        var f=new MetadataFixture();f.empty("cn_gdp");var batch=f.source.read(JUNE,JULY);assertEquals(22,batch.rawRows());assertEquals(2,batch.rows().size());assertEquals(2,f.countQueries);assertTrue(batch.rows().stream().allMatch(r->r.gdpYoy()==null));assertNull(batch.snapshot().sources().get(4).physicalTxn());
    }
    @Test void nullablePhysicalSourceFrontierSerializesExplicitNullWithoutZeroCoercion()throws Exception{
        var f=new MetadataFixture();f.empty("cn_gdp");var snapshot=f.source.snapshot();var json=JobDefinitionJson.mapper().readTree(JobDefinitionJson.mapper().writeValueAsString(snapshot));var gdp=json.path("sources").get(4);
        assertTrue(gdp.get("physicalTxn").isNull());assertTrue(gdp.get("walTxn").isNull());assertTrue(gdp.get("metadataRowCount").isNull());assertEquals(0,gdp.path("writerTxn").longValue());assertNotEquals(snapshot.version(),new MutableSource().snapshot().version());
    }
    @Test void anySingleNullableFrontierRequiresZeroWalAndCountEvidence()throws Exception{
        for(String field:List.of("table_txn","wal_txn","table_row_count")){var f=new MetadataFixture();f.empty("cn_gdp");var meta=f.metadata.get("cn_gdp");meta.put("table_txn",0L);meta.put("wal_txn",0L);meta.put("table_row_count",0L);meta.put(field,null);assertEquals(6,f.source.snapshot().sources().size());assertEquals(1,f.countQueries);}
    }
    @Test void nullableFrontierWithNonemptyActualCountIsRejected()throws Exception{
        var f=new MetadataFixture();f.empty("cn_gdp");f.actualCounts.put("cn_gdp",1L);assertThrows(IllegalStateException.class,()->f.source.snapshot());assertEquals(1,f.countQueries);
    }
    @Test void nullableFrontierWithKnownNonzeroPhysicalWalOrRowsIsRejectedWithoutCount()throws Exception{
        for(String field:List.of("table_txn","table_row_count")){var f=new MetadataFixture();f.empty("cn_gdp");f.metadata.get("cn_gdp").put(field,1L);assertThrows(IllegalStateException.class,()->f.source.snapshot());assertEquals(0,f.countQueries);}
        var f=new MetadataFixture();f.empty("cn_gdp");for(String field:List.of("sequencerTxn","writerTxn","wal_txn"))f.metadata.get("cn_gdp").put(field,1L);assertThrows(IllegalStateException.class,()->f.source.snapshot());assertEquals(0,f.countQueries);
    }
    @Test void nullableMetadataMustRemainExactlyStableAroundIndependentCount()throws Exception{
        var f=new MetadataFixture();f.empty("cn_gdp");f.afterCount=()->f.metadata.get("cn_gdp").put("table_txn",0L);assertThrows(IllegalStateException.class,()->f.source.snapshot());assertEquals(1,f.countQueries);
    }
    @Test void nonemptySourceRawWalTxnMustBeKnownAndEqualToAppliedWriter()throws Exception{
        var f=new MetadataFixture();f.metadata.get("cn_gdp").put("wal_txn",0L);assertThrows(IllegalStateException.class,()->f.source.snapshot());
        f.metadata.get("cn_gdp").put("wal_txn",null);assertThrows(IllegalStateException.class,()->f.source.snapshot());assertEquals(0,f.countQueries);
        f.metadata.get("cn_gdp").put("wal_txn",1L);f.metadata.get("cn_gdp").put("table_txn",2L);assertEquals(2L,f.source.snapshot().sources().get(4).physicalTxn().longValue());
    }
    @Test void nullableIndependentCountMustBeStrictKnownUniqueAndBounded()throws Exception{
        for(Object value:Arrays.asList(null,true,0.0,-1L)){var f=new MetadataFixture();f.empty("cn_gdp");f.actualCounts.put("cn_gdp",value);assertThrows(IllegalStateException.class,()->f.source.snapshot());}
        var f=new MetadataFixture();f.empty("cn_gdp");f.duplicateCount=true;var duplicate=f;assertThrows(IllegalStateException.class,()->duplicate.source.snapshot());
        f=new MetadataFixture();f.empty("cn_gdp");f.source.snapshot();int countAt=f.sqls.indexOf("SELECT count() AS actual_rows FROM \"cn_gdp\" LIMIT 2");assertTrue(countAt>=0);verify(f.statements.get(countAt)).setMaxRows(2);
    }
    @Test void completeReadHasFiniteMetadataAndRawSentinelsWithTwentySecondTimeout()throws Exception{
        var f=new MetadataFixture();assertEquals(23,f.source.read(JUNE,JULY).rawRows());assertTrue(f.sqls.stream().allMatch(s->s.contains("LIMIT ")));
        for(var statement:f.statements){verify(statement).setQueryTimeout(intThat(t->t>=1&&t<=20));verify(statement).setMaxRows(intThat(cap->cap>=2&&cap<=61));}
        assertTrue(f.sqls.contains(sql().contextSql(JUNE)));assertTrue(f.sqls.stream().anyMatch(s->s.contains("table_columns('cn_pmi') LIMIT 61")));
    }
    @Test void completeSourcePhysicalVectorDriftFailsAfterFullRawRead()throws Exception{
        var f=new MetadataFixture();f.afterContext=()->f.metadata.get("cn_cpi").put("table_txn",2L);assertThrows(IllegalStateException.class,()->f.source.read(JUNE,JULY));
    }
    @Test void rawWindowTruncationAndUnknownCounterCarriersAreRejected()throws Exception{
        var f=new MetadataFixture();var rows=f.business.get("cn_cpi");while(rows.size()<13)rows.add(raw("cn_cpi",JUNE));assertThrows(IllegalStateException.class,()->f.source.read(JUNE,JULY));
        for(Object bad:Arrays.asList(null,true,1.0,-1L)){var g=new MetadataFixture();g.metadata.get("cn_gdp").put("writerTxn",bad);assertThrows(IllegalStateException.class,()->g.source.snapshot());}
    }
    static final class MetadataFixture {
        final Map<String,Map<String,Object>> metadata=new LinkedHashMap<>();final Map<String,Object> actualCounts=new HashMap<>();final LinkedHashMap<String,List<Map<String,Object>>> business=initialWindow();
        final List<PreparedStatement> statements=new ArrayList<>();final List<String> sqls=new ArrayList<>();final MacroCoreMonthlySource source;int countQueries;boolean duplicateCount;Runnable afterCount=()->{},afterContext=()->{};
        MetadataFixture()throws Exception{
            business.get("sf_month").addAll(0,initialContext());int id=17;
            for(var spec:MacroCoreMonthlySource.SOURCE_SPECS.values()){var meta=new HashMap<String,Object>();meta.put("id",id++);meta.put("directoryName",spec.table()+"~unit");meta.put("table_txn",1L);meta.put("wal_txn",1L);meta.put("table_row_count",(long)business.get(spec.table()).size());meta.put("partitionBy","YEAR");meta.put("designatedTimestamp",spec.timestamp());
                for(String field:List.of("walEnabled","dedup"))meta.put(field,true);for(String field:List.of("matView","table_suspended","suspended"))meta.put(field,false);for(String field:List.of("wal_pending_row_count","bufferedTxnSize"))meta.put(field,0L);meta.put("sequencerTxn",1L);meta.put("writerTxn",1L);metadata.put(spec.table(),meta);actualCounts.put(spec.table(),(long)business.get(spec.table()).size());}
            var ds=mock(DataSource.class);var connection=mock(Connection.class);when(ds.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenAnswer(call->{String sql=call.getArgument(0);var statement=mock(PreparedStatement.class);sqls.add(sql);statements.add(statement);String table=MacroCoreMonthlySource.SOURCE_TABLES.stream().filter(t->sql.contains("'"+t+"'")||sql.contains("FROM \""+t+"\"")).findFirst().orElseThrow();var spec=MacroCoreMonthlySource.SOURCE_SPECS.get(table);var rows=new ArrayList<Map<String,Object>>();
                if(sql.contains("table_columns"))for(String field:spec.fields())rows.add(Map.of("column",field,"type",spec.type(field),"designated",field.equals(spec.timestamp()),"upsertKey",field.equals(spec.timestamp())));
                else if(sql.contains("FROM tables()"))rows.add(new HashMap<>(metadata.get(table)));
                else if(sql.startsWith("SELECT count()")){var row=new HashMap<String,Object>();row.put("actual_rows",actualCounts.get(table));rows.add(row);if(duplicateCount)rows.add(row);countQueries++;afterCount.run();}
                else {var selected=new ArrayList<>(business.get(table).stream().filter(r->{var day=(LocalDate)r.get(spec.timestamp());return sql.contains("ORDER BY month DESC")?day.isBefore(JUNE):!day.isBefore(JUNE)&&day.isBefore(AUGUST);}).toList());if(sql.contains("ORDER BY month DESC")){Collections.reverse(selected);selected=new ArrayList<>(selected.stream().limit(12).toList());afterContext.run();}rows.addAll(selected);}
                var rs=mock(ResultSet.class);int[] at={-1};when(rs.next()).thenAnswer(a->++at[0]<rows.size());when(rs.getString(anyString())).thenAnswer(a->{Object value=rows.get(at[0]).get(a.getArgument(0));return value==null?null:value.toString();});when(rs.getObject(anyString())).thenAnswer(a->rows.get(at[0]).get(a.getArgument(0)));
                when(rs.getLong(anyString())).thenAnswer(a->{Object value=rows.get(at[0]).get(a.getArgument(0));return value instanceof LocalDate date?date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)*1_000_000L:value==null?0L:((Number)value).longValue();});when(statement.executeQuery()).thenReturn(rs);return statement;});
            source=new MacroCoreMonthlySource(new QuestDbMacroCoreMonthlySourceReader(new JdbcTemplate(ds)));
        }
        void empty(String table){var meta=metadata.get(table);for(String field:List.of("table_txn","wal_txn","table_row_count"))meta.put(field,null);for(String field:List.of("sequencerTxn","writerTxn","wal_pending_row_count","bufferedTxnSize"))meta.put(field,0L);actualCounts.put(table,0L);business.get(table).clear();}
    }
}
