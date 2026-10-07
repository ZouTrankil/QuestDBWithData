package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuestDbMacroCoreViewReadGuardTest {
    private static final DatasetDefinition FORMAL=MacroCoreMonthlyViewDataset.DEFINITION;
    private static final DatasetDefinition PRIVATE=MacroCoreMonthlyViewDataset.definition("java_d105_v_macro_core_monthly_acceptance");
    private static final LocalDate JUNE=LocalDate.of(2026,6,1),SEPTEMBER=LocalDate.of(2026,9,1);
    private static DatasetReadQuery range(){return range(JUNE,SEPTEMBER,1,null);}
    private static DatasetReadQuery range(Object from,Object to,int page,DatasetReadCursor cursor){
        return new DatasetReadQuery(FORMAL.storageColumns(),Map.of(),"month",from,to,page,cursor);
    }
    private static DatasetReadQuery exact(Object month){
        var equal=new LinkedHashMap<String,Object>();equal.put("month",month);
        return new DatasetReadQuery(FORMAL.storageColumns(),equal,null,null,null,1,null);
    }
    @Test void appliesOnlyToRegisteredLogicalOrPhysicalAliasIdentity(){
        assertTrue(QuestDbMacroCoreViewReadGuard.applies(FORMAL));assertTrue(QuestDbMacroCoreViewReadGuard.applies(PRIVATE));
        assertFalse(QuestDbMacroCoreViewReadGuard.applies(MacroCoreMonthlyDataset.DEFINITION));
        var disguised=definition("wrong_alias",FORMAL.objectName(),FORMAL.objectKind(),FORMAL.dependencies(),1);
        assertTrue(QuestDbMacroCoreViewReadGuard.applies(disguised));
        assertThrows(IllegalArgumentException.class,()->QuestDbMacroCoreViewReadGuard.validate(disguised,range()));
    }
    @Test void validFinitePreparationAndTwelveMonthsDoNotConnect(){
        var jdbc=mock(JdbcTemplate.class);var reader=new QuestDbBoundedReader(jdbc);
        assertTrue(reader.prepare(FORMAL,range(JUNE,JUNE.plusMonths(12),12,null),null).sql().endsWith("LIMIT 13"));
        assertDoesNotThrow(()->QuestDbMacroCoreViewReadGuard.validate(FORMAL,exact(JUNE)));
        verifyNoInteractions(jdbc);
    }
    @Test void unboundedReadAndPrepareRejectBeforeJdbc(){
        var jdbc=mock(JdbcTemplate.class);var reader=new QuestDbBoundedReader(jdbc);
        var query=new DatasetReadQuery(FORMAL.storageColumns(),Map.of(),null,null,null,1,null);
        assertThrows(IllegalArgumentException.class,()->reader.prepare(FORMAL,query,null));
        assertThrows(IllegalArgumentException.class,()->reader.read(FORMAL,query,null,v->v));verifyNoInteractions(jdbc);
    }
    @Test void completeOrderedNineColumnProjectionIsRequiredBeforeJdbc(){
        var jdbc=mock(JdbcTemplate.class);var reader=new QuestDbBoundedReader(jdbc);
        var reversed=new ArrayList<>(FORMAL.storageColumns());Collections.swap(reversed,1,2);
        for(var columns:List.of(List.of("month"),reversed)){
            var query=new DatasetReadQuery(columns,Map.of(),"month",JUNE,SEPTEMBER,1,null);
            assertThrows(IllegalArgumentException.class,()->reader.read(FORMAL,query,null,v->v));
        }
        verifyNoInteractions(jdbc);
    }
    @Test void monthCarriersRejectNullStringsInstantYearMonthAndNonFirstDay(){
        for(Object value:Arrays.asList(null,"2026-06",Instant.parse("2026-06-01T00:00:00Z"),YearMonth.of(2026,6),JUNE.plusDays(1),LocalDate.of(0,1,1),LocalDate.of(10000,1,1)))
            assertThrows(IllegalArgumentException.class,()->QuestDbMacroCoreViewReadGuard.validate(FORMAL,exact(value)));
    }
    @Test void monthRangeRejectsWrongFieldTypesDaysReverseAndThirteenMonths(){
        for(var query:List.of(range(JUNE.plusDays(1),SEPTEMBER,1,null),range(JUNE,JUNE,1,null),range(SEPTEMBER,JUNE,1,null),
                range(JUNE,JUNE.plusMonths(13),1,null),range("2026-06-01","2026-09-01",1,null),
                new DatasetReadQuery(FORMAL.storageColumns(),Map.of(),"cpi_yoy",1.0,2.0,1,null)))
            assertThrows(IllegalArgumentException.class,()->QuestDbMacroCoreViewReadGuard.validate(FORMAL,query));
    }
    @Test void oversizedPageAndNonMonthOrMixedFiltersRejectBeforeJdbc(){
        var jdbc=mock(JdbcTemplate.class);var reader=new QuestDbBoundedReader(jdbc);
        for(var query:List.of(range(JUNE,SEPTEMBER,13,null),
                new DatasetReadQuery(FORMAL.storageColumns(),Map.of("cpi_yoy",1.0),"month",JUNE,SEPTEMBER,1,null),
                new DatasetReadQuery(FORMAL.storageColumns(),Map.of("month",JUNE),"month",JUNE,SEPTEMBER,1,null)))
            assertThrows(IllegalArgumentException.class,()->reader.read(FORMAL,query,null,v->v));
        verifyNoInteractions(jdbc);
    }
    @Test void wrongDependenciesKindVersionAndUnknownPrivateObjectRejectBeforeJdbc(){
        var jdbc=mock(JdbcTemplate.class);var reader=new QuestDbBoundedReader(jdbc);
        for(var def:List.of(definition(FORMAL.datasetId(),FORMAL.objectName(),FORMAL.objectKind(),List.of(),1),
                definition(FORMAL.datasetId(),FORMAL.objectName(),FORMAL.objectKind(),List.of("cn_cpi"),1),
                definition(FORMAL.datasetId(),FORMAL.objectName(),DatasetDefinition.ObjectKind.TABLE,FORMAL.dependencies(),1),
                definition(FORMAL.datasetId(),FORMAL.objectName(),FORMAL.objectKind(),FORMAL.dependencies(),2),
                definition(FORMAL.datasetId(),"java_d105_v_macro_core_monthly_other",FORMAL.objectKind(),FORMAL.dependencies(),1)))
            assertThrows(IllegalArgumentException.class,()->reader.read(def,range(),null,v->v));
        verifyNoInteractions(jdbc);
    }
    @Test void incompleteOrWrongMonthCursorKeyRejectsBeforeJdbc(){
        var jdbc=mock(JdbcTemplate.class);var reader=new QuestDbBoundedReader(jdbc);
        for(var keys:List.of(List.of(),List.of(JUNE,SEPTEMBER),List.of("2026-06"),List.of(JUNE.plusDays(1)))){
            var cursor=new DatasetReadCursor("fingerprint",new ArrayList<Object>(keys),"version");
            assertThrows(IllegalArgumentException.class,()->reader.read(FORMAL,range(JUNE,SEPTEMBER,1,cursor),null,v->v));
        }
        verifyNoInteractions(jdbc);
    }
    @Test void interruptedCallerCancelsBeforeAnyMetadataQuery(){
        var jdbc=mock(JdbcTemplate.class);boolean wasInterrupted=Thread.currentThread().isInterrupted();
        try{Thread.currentThread().interrupt();assertThrows(CancellationException.class,()->QuestDbMacroCoreViewReadGuard.version(jdbc,FORMAL));verifyNoInteractions(jdbc);}
        finally{Thread.interrupted();if(wasInterrupted)Thread.currentThread().interrupt();}
    }
    @Test void formalViewVersionPinsDirectoryBodySchemaAndIndependentBasePhysicalWalDomains(){
        var fixture=stable(FORMAL);String token=QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL);
        assertTrue(token.startsWith("view:v_macro_core_monthly:directory:v_macro_core_monthly~239:id:239:definition:"));
        assertTrue(token.contains(":body:"));assertTrue(token.contains(":schema:"));
        assertTrue(token.endsWith(":source:table:macro_core_monthly:id:15:dir:macro_core_monthly~15:txn:5:wal:4:wal-physical:4:metadata-rows:3"));
    }
    @Test void privateViewPinsOnlyItsExplicitPrivateBase(){
        var fixture=stable(PRIVATE);String token=QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,PRIVATE);
        assertTrue(token.contains("table:java_d104_macro_core_monthly_acceptance:id:15"));
        assertTrue(fixture.sql.stream().anyMatch(s->s.contains("table_name='java_d104_macro_core_monthly_acceptance'")));
        assertFalse(fixture.sql.stream().anyMatch(s->s.contains("table_name='macro_core_monthly'")));
        var wrong=alias(PRIVATE);wrong.put("view_sql","SELECT * FROM macro_core_monthly");
        assertThrows(IllegalStateException.class,()->versionWithAlias(PRIVATE,wrong));
    }
    @Test void ownerSqlAllowsOnlyCaseWhitespaceBaseIdentifierQuotesAndOriginalOuterParentheses(){
        for(String sql:List.of("select * from macro_core_monthly","  SELECT\n * FROM \"macro_core_monthly\"  ","(SELECT * FROM macro_core_monthly)")){
            var alias=alias(FORMAL);alias.put("view_sql",sql);
            assertDoesNotThrow(()->versionWithAlias(FORMAL,alias));
        }
    }
    @Test void ownerSqlRejectsFilteringJoiningQualificationProjectionCommentsAndFallback(){
        for(String sql:List.of("SELECT * FROM macro_core_monthly WHERE month >= '2026-06-01'","SELECT * FROM other",
                "SELECT * FROM macro_core_monthly UNION ALL SELECT * FROM macro_core_monthly","SELECT * FROM public.macro_core_monthly",
                "SELECT month FROM macro_core_monthly","SELECT * FROM macro_core_monthly m","SELECT * FROM macro_core_monthly;",
                "SELECT * FROM macro_core_monthly -- note","((SELECT * FROM macro_core_monthly))","SELECT * FROM cn_cpi")){
            var alias=alias(FORMAL);alias.put("view_sql",sql);assertThrows(IllegalStateException.class,()->versionWithAlias(FORMAL,alias));
        }
    }
    @Test void missingDuplicateOrWrongNativeViewIdentityFailsClosed(){
        assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(new Fixture(s->s.contains("FROM views()")?List.of():records(FORMAL,s)).jdbc,FORMAL));
        assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(new Fixture(s->s.contains("FROM views()")?List.of(alias(FORMAL),alias(FORMAL)):records(FORMAL,s)).jdbc,FORMAL));
        var wrong=alias(FORMAL);wrong.put("view_name","wrong");assertThrows(IllegalStateException.class,()->versionWithAlias(FORMAL,wrong));
    }
    @Test void invalidViewBlankIdentityStatusHistoryOrInvalidationReasonRejectFirstPage(){
        for(String field:List.of("view_sql","view_table_dir_name","view_status_update_time","view_status"))for(Object value:Arrays.asList(null,"")){
            var alias=alias(FORMAL);alias.put(field,value);assertThrows(IllegalStateException.class,()->versionWithAlias(FORMAL,alias));
        }
        var invalid=alias(FORMAL);invalid.put("view_status","invalid");assertThrows(IllegalStateException.class,()->versionWithAlias(FORMAL,invalid));
        var reason=alias(FORMAL);reason.put("invalidation_reason","changed source");assertThrows(IllegalStateException.class,()->versionWithAlias(FORMAL,reason));
    }
    @Test void actualOrdinaryViewTypeDirectoryAndIdMustAgreeWithViewsMetadata(){
        for(String field:List.of("view_id","view_directory","view_mat_view","view_partition","view_dedup","view_timestamp")){
            var alias=alias(FORMAL);alias.put(field,switch(field){case "view_id"->-1L;case "view_directory"->"wrong~999";
                case "view_mat_view","view_dedup"->true;case "view_partition"->"YEAR";default->"other";});
            assertThrows(IllegalStateException.class,()->versionWithAlias(FORMAL,alias));
            alias.put(field,null);assertThrows(IllegalStateException.class,()->versionWithAlias(FORMAL,alias));
        }
    }
    @Test void exactObservedViewNativeTimestampAndNoUpsertFlagsAreRequiredOnFirstPage(){
        for(int column:List.of(0,1))for(String field:List.of("designated","upsertKey")){
            var schema=new ArrayList<>(schema(false));var row=new LinkedHashMap<>(schema.get(column));row.put(field,!(Boolean)row.get(field));schema.set(column,row);
            assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(new Fixture(s->isViewSchema(FORMAL,s)?schema:records(FORMAL,s)).jdbc,FORMAL));
        }
    }
    @Test void viewSchemaMustHaveExactOrderTypesAndNineUniqueColumns(){
        for(String mutation:List.of("order","type","extra","missing","duplicate")){
            var schema=new ArrayList<>(schema(false));
            if(mutation.equals("order"))Collections.swap(schema,1,2);
            if(mutation.equals("type")){var row=new LinkedHashMap<>(schema.get(1));row.put("type","FLOAT");schema.set(1,row);}
            if(mutation.equals("extra"))schema.add(Map.of("column","extra","type","DOUBLE","designated",false,"upsertKey",false));
            if(mutation.equals("missing"))schema.removeLast();
            if(mutation.equals("duplicate"))schema.set(8,schema.get(1));
            var fixture=new Fixture(s->isViewSchema(FORMAL,s)?schema:records(FORMAL,s));
            assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL));
        }
    }
    @Test void absentNativeViewColumnFlagsCannotBecomeFalse(){
        for(String field:List.of("designated","upsertKey")){
            var schema=new ArrayList<>(schema(false));var row=new LinkedHashMap<>(schema.getFirst());row.put(field,null);schema.set(0,row);
            assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(new Fixture(s->isViewSchema(FORMAL,s)?schema:records(FORMAL,s)).jdbc,FORMAL));
        }
    }
    @Test void viewMetadataChangeAcrossItsSchemaCheckIsRejected(){
        var aliases=new AtomicInteger();var fixture=new Fixture(s->{
            if(s.contains("FROM views()")){var row=alias(FORMAL);if(aliases.incrementAndGet()>1)row.put("view_table_dir_name","v_macro_core_monthly~999");return List.of(row);}
            return records(FORMAL,s);
        });assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL));
    }
    @Test void viewNativeSchemaFlagChangeDuringSnapshotIsRejected(){
        var schemas=new AtomicInteger();var fixture=new Fixture(s->{
            if(isViewSchema(FORMAL,s)){var rows=new ArrayList<>(schema(false));if(schemas.incrementAndGet()>1){var row=new LinkedHashMap<>(rows.getFirst());row.put("upsertKey",true);rows.set(0,row);}return rows;}
            return records(FORMAL,s);
        });assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL));
    }
    @Test void basePhysicalRevisionBetweenCompleteViewSnapshotsIsRejected(){
        var calls=new AtomicInteger();var fixture=new Fixture(s->{
            if(s.contains("FROM tables()")){var row=physical(FORMAL);if(calls.incrementAndGet()>2)row.put("table_txn",6L);return List.of(row);}
            return records(FORMAL,s);
        });assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL));
    }
    @Test void baseSchemaTypeAndDedupKeyMustRemainD104Exact(){
        for(String mutation:List.of("type","key")){
            var rows=new ArrayList<>(schema(true));var row=new LinkedHashMap<>(rows.getFirst());row.put(mutation.equals("type")?"type":"upsertKey",mutation.equals("type")?"TIMESTAMP_NS":false);rows.set(0,row);
            var fixture=new Fixture(s->s.contains("table_columns('")&&!isViewSchema(FORMAL,s)?rows:records(FORMAL,s));
            assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL));
        }
    }
    @Test void viewValidFlagDoesNotAllowWrongBasePhysicalContract(){
        for(String field:List.of("walEnabled","dedup","matView","partitionBy","designatedTimestamp")){
            var row=physical(FORMAL);row.put(field,switch(field){case "partitionBy"->"MONTH";case "designatedTimestamp"->"other";case "matView"->true;default->false;});
            assertThrows(IllegalStateException.class,()->versionWithPhysical(row));
        }
    }
    @Test void viewValidFlagDoesNotAllowSuspendedPendingBufferedOrLaggingBaseWal(){
        for(String field:List.of("table_suspended","suspended","wal_pending_row_count","bufferedTxnSize","sequencerTxn","wal_txn")){
            var row=physical(FORMAL);row.put(field,field.endsWith("suspended")?true:field.equals("sequencerTxn")||field.equals("wal_txn")?5L:1L);
            assertThrows(IllegalStateException.class,()->versionWithPhysical(row));
        }
    }
    @Test void baseIdOrDirectoryChangeRejectsOldCursorBeforeAnyRowQuery(){
        String old=QuestDbMacroCoreViewReadGuard.version(stable(FORMAL).jdbc,FORMAL);
        for(String field:List.of("id","directoryName")){
            var row=physical(FORMAL);row.put(field,field.equals("id")?16L:"macro_core_monthly~16");
            assertOldCursorRejected(old,new Fixture(s->s.contains("FROM tables()")?List.of(row):records(FORMAL,s)));
        }
    }
    @Test void sameBaseBusinessValuesWithNewPhysicalOrWalTxnRejectOldCursor(){
        String old=QuestDbMacroCoreViewReadGuard.version(stable(FORMAL).jdbc,FORMAL);
        for(String domain:List.of("physical","wal")){
            var row=physical(FORMAL);
            if(domain.equals("physical"))row.put("table_txn",6L);else for(String field:List.of("writerTxn","sequencerTxn","wal_txn"))row.put(field,5L);
            assertOldCursorRejected(old,new Fixture(s->s.contains("FROM tables()")?List.of(row):records(FORMAL,s)));
        }
    }
    @Test void aliasDropRecreateOrStatusHistoryChangeRejectsOldCursor(){
        String old=QuestDbMacroCoreViewReadGuard.version(stable(FORMAL).jdbc,FORMAL);
        for(String field:List.of("view_table_dir_name","view_status_update_time")){
            var alias=alias(FORMAL);alias.put(field,field.equals("view_table_dir_name")?"v_macro_core_monthly~240":"2026-10-07T01:00:00.000001Z");
            if(field.equals("view_table_dir_name")){alias.put("view_directory","v_macro_core_monthly~240");alias.put("view_id",240L);}
            assertOldCursorRejected(old,new Fixture(s->s.contains("FROM views()")?List.of(alias):records(FORMAL,s)));
        }
    }
    @Test void equivalentDefinitionWithChangedActualBodyHashStillRejectsOldCursor(){
        String old=QuestDbMacroCoreViewReadGuard.version(stable(FORMAL).jdbc,FORMAL);
        var alias=alias(FORMAL);alias.put("view_sql","select * from macro_core_monthly");
        assertOldCursorRejected(old,new Fixture(s->s.contains("FROM views()")?List.of(alias):records(FORMAL,s)));
    }
    @Test void actualBaseChangeAfterRowSelectReturnsNoPage(){
        var published=new AtomicBoolean();var fixture=new Fixture(s->{
            if(s.contains("FROM tables()")){var row=physical(FORMAL);if(published.get())row.put("table_txn",6L);return List.of(row);}
            return records(FORMAL,s);
        });fixture.afterRows=()->published.set(true);var mapped=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->new QuestDbBoundedReader(fixture.jdbc).read(FORMAL,range(),null,v->{mapped.incrementAndGet();return v;}));
        assertEquals(1,fixture.rowQueries.get());assertEquals(0,mapped.get());
    }
    @Test void uninitializedBaseRequiresStableActualCountZeroAndPreservesNullPhysicalToken(){
        var physical=empty();var fixture=emptyFixture(physical,0L);
        String version=QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL);
        assertTrue(version.endsWith(":txn:uninitialized:wal:0:wal-physical:null:metadata-rows:null"));
        assertEquals(4,fixture.sql.stream().filter(s->s.contains("SELECT count()")).count());
    }
    @Test void emptyBaseCannotHideActualRowsMissingCountOrAmbiguousCount(){
        for(Long count:Arrays.asList(1L,null))assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(emptyFixture(empty(),count).jdbc,FORMAL));
        var fixture=new Fixture(s->s.contains("SELECT count()")?List.of(Map.of("actual_rows",0L),Map.of("actual_rows",0L)):s.contains("FROM tables()")?List.of(empty()):records(FORMAL,s));
        assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL));
    }
    @Test void positivePhysicalTxnCannotPassNullableEmptyBaseEvenWithCountZero(){
        var row=empty();row.put("table_txn",7L);var fixture=emptyFixture(row,0L);
        assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL));
        assertFalse(fixture.sql.stream().anyMatch(s->s.contains("SELECT count()")));
    }
    @Test void nullableBaseCannotHideNonzeroWalPendingOrRawMetadata(){
        for(String field:List.of("writerTxn","sequencerTxn","wal_txn","wal_pending_row_count","bufferedTxnSize","table_row_count")){
            var row=empty();row.put(field,1L);
            assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(emptyFixture(row,0L).jdbc,FORMAL));
        }
    }
    @Test void nullableBaseMetadataChangeAcrossCountCannotBeNormalizedAway(){
        var calls=new AtomicInteger();var fixture=new Fixture(s->{
            if(s.contains("FROM tables()")){var row=empty();if(calls.incrementAndGet()>1)row.put("table_row_count",0L);return List.of(row);}
            return records(FORMAL,s);
        });assertThrows(IllegalStateException.class,()->QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL));
    }
    @Test void everyMetadataQueryHasFiniteTimeoutRowsAndQuotedSchemaColumns()throws SQLException{
        var fixture=stable(FORMAL);QuestDbMacroCoreViewReadGuard.version(fixture.jdbc,FORMAL);
        assertEquals(List.of(2,10,2,2,10,2,2,10,2,2,10,2),fixture.limits);
        for(var statement:fixture.statements){verify(statement).setQueryTimeout(20);verify(statement).setMaxRows(anyInt());}
        assertTrue(fixture.sql.stream().filter(s->s.contains("table_columns")).allMatch(s->s.startsWith("SELECT \"column\",\"type\"")));
        assertTrue(fixture.sql.stream().allMatch(s->s.contains("LIMIT 2")||s.contains("LIMIT 10")));
    }
    private static void assertOldCursorRejected(String old,Fixture fixture){
        var reader=new QuestDbBoundedReader(fixture.jdbc);var original=range();
        var cursor=new DatasetReadCursor(reader.prepare(FORMAL,original,old).fingerprint(),List.of(JUNE),old);
        assertThrows(IllegalArgumentException.class,()->reader.read(FORMAL,original.after(cursor),null,v->v));
        assertEquals(0,fixture.rowQueries.get());
    }
    private static DatasetDefinition definition(String id,String object,DatasetDefinition.ObjectKind kind,List<String> dependencies,int version){
        return new DatasetDefinition(id,version,FORMAL.provider(),FORMAL.owner(),object,kind,FORMAL.columns(),FORMAL.businessKey(),FORMAL.dedupKey(),
                FORMAL.designatedTimestamp(),FORMAL.partition(),FORMAL.wal(),FORMAL.capabilities(),dependencies,FORMAL.storageRationale());
    }
    private static String base(DatasetDefinition def){return def==PRIVATE?"java_d104_macro_core_monthly_acceptance":"macro_core_monthly";}
    private static Map<String,Object> alias(DatasetDefinition def){
        var row=new LinkedHashMap<String,Object>();row.put("view_name",def.objectName());row.put("view_sql","SELECT * FROM "+base(def));
        row.put("view_table_dir_name",def.objectName()+"~239");row.put("view_status","valid");row.put("invalidation_reason",null);
        row.put("view_status_update_time","2026-09-30T01:27:52.535072Z");
        row.put("view_id",239L);row.put("view_directory",def.objectName()+"~239");row.put("view_mat_view",false);
        row.put("view_partition","N/A");row.put("view_dedup",false);row.put("view_timestamp","month");return row;
    }
    private static Map<String,Object> physical(DatasetDefinition def){
        var row=new LinkedHashMap<String,Object>();row.put("id",15L);row.put("directoryName",base(def)+"~15");
        row.put("table_txn",5L);row.put("wal_txn",4L);row.put("table_row_count",3L);row.put("writerTxn",4L);row.put("sequencerTxn",4L);
        row.put("wal_pending_row_count",0L);row.put("bufferedTxnSize",0L);row.put("walEnabled",true);row.put("dedup",true);row.put("matView",false);
        row.put("table_suspended",false);row.put("suspended",false);row.put("partitionBy","YEAR");row.put("designatedTimestamp","month");return row;
    }
    private static Map<String,Object> empty(){
        var row=physical(FORMAL);for(String field:List.of("table_txn","wal_txn","table_row_count"))row.put(field,null);
        row.put("writerTxn",0L);row.put("sequencerTxn",0L);return row;
    }
    private static List<Map<String,Object>> schema(boolean base){
        return FORMAL.columns().stream().map(c->{var row=new LinkedHashMap<String,Object>();row.put("column",c.storageName());row.put("type",c.storageType().name());
            row.put("designated",c.storageName().equals("month"));row.put("upsertKey",base&&c.storageName().equals("month"));return (Map<String,Object>)row;}).toList();
    }
    private static boolean isViewSchema(DatasetDefinition def,String sql){return sql.contains("table_columns('"+def.objectName()+"')");}
    private static List<Map<String,Object>> records(DatasetDefinition def,String sql){
        if(sql.contains("FROM views()"))return List.of(alias(def));
        if(sql.contains("table_columns"))return schema(!isViewSchema(def,sql));
        if(sql.contains("SELECT count()"))return List.of(Map.of("actual_rows",0L));
        if(sql.contains("FROM tables()"))return List.of(physical(def));
        var row=new LinkedHashMap<String,Object>();for(String field:FORMAL.storageColumns())row.put(field,field.equals("month")?(Object)Long.valueOf(JUNE.atStartOfDay().toInstant(ZoneOffset.UTC).getEpochSecond()*1_000_000L):Double.valueOf(1.0));return List.of(row);
    }
    private static Fixture stable(DatasetDefinition def){return new Fixture(s->records(def,s));}
    private static String versionWithAlias(DatasetDefinition def,Map<String,Object> alias){return QuestDbMacroCoreViewReadGuard.version(new Fixture(s->s.contains("FROM views()")?List.of(alias):records(def,s)).jdbc,def);}
    private static String versionWithPhysical(Map<String,Object> physical){return QuestDbMacroCoreViewReadGuard.version(new Fixture(s->s.contains("FROM tables()")?List.of(physical):records(FORMAL,s)).jdbc,FORMAL);}
    private static Fixture emptyFixture(Map<String,Object> physical,Long count){
        return new Fixture(s->{if(s.contains("FROM tables()"))return List.of(physical);if(s.contains("SELECT count()")){var row=new LinkedHashMap<String,Object>();row.put("actual_rows",count);return List.of(row);}return records(FORMAL,s);});
    }
    private static final class Fixture{
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);final List<String> sql=new ArrayList<>();final List<Integer> limits=new ArrayList<>();
        final List<PreparedStatement> statements=new ArrayList<>();final AtomicInteger rowQueries=new AtomicInteger();Runnable afterRows=()->{};
        @SuppressWarnings("unchecked") Fixture(Function<String,List<Map<String,Object>>> records){
            doAnswer(call->{String statement=prepare(call.getArgument(0,PreparedStatementCreator.class));
                return call.getArgument(1,ResultSetExtractor.class).extractData(resultSet(records.apply(statement)));
            }).when(jdbc).query(any(PreparedStatementCreator.class),any(ResultSetExtractor.class));
            doAnswer(call->{String statement=prepare(call.getArgument(0,PreparedStatementCreator.class));rowQueries.incrementAndGet();
                var rs=resultSet(records.apply(statement));var mapper=call.getArgument(1,RowMapper.class);var mapped=new ArrayList<Object>();
                while(rs.next())mapped.add(mapper.mapRow(rs,mapped.size()));afterRows.run();return mapped;
            }).when(jdbc).query(any(PreparedStatementCreator.class),any(RowMapper.class));
        }
        private String prepare(PreparedStatementCreator creator)throws SQLException{
            var connection=mock(Connection.class);var text=new AtomicReference<String>();var statement=mock(PreparedStatement.class);statements.add(statement);
            when(connection.prepareStatement(anyString())).thenAnswer(arg->{text.set(arg.getArgument(0));return statement;});
            doAnswer(arg->{limits.add(arg.getArgument(0));return null;}).when(statement).setMaxRows(anyInt());
            creator.createPreparedStatement(connection);sql.add(text.get());return text.get();
        }
        private ResultSet resultSet(List<Map<String,Object>> records){
            var index=new AtomicInteger(-1);var wasNull=new AtomicBoolean();
            return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,args)->{
                if(method.getName().equals("next"))return index.incrementAndGet()<records.size();
                if(method.getName().equals("wasNull"))return wasNull.get();
                Object value=records.get(index.get()).get(args[0].toString());wasNull.set(value==null);
                return switch(method.getName()){
                    case "getString"->value==null?null:value.toString();case "getLong"->value==null?0L:((Number)value).longValue();
                    case "getBoolean"->value!=null&&(Boolean)value;case "getObject"->value;
                    default->throw new UnsupportedOperationException(method.getName());
                };
            });
        }
    }
}
