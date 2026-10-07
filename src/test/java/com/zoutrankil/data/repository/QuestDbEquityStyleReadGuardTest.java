package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class QuestDbEquityStyleReadGuardTest {
    private static final DatasetDefinition DEFINITION = EquityStyleMonthlyDataset.DEFINITION;
    private static final LocalDate JUNE = LocalDate.of(2026,6,1), SEPTEMBER = LocalDate.of(2026,9,1);
    private static DatasetReadQuery range(LocalDate from, LocalDate to) {
        return new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "month", from, to, 1, null);
    }
    @Test void finiteMonthlyPreparationDoesNotConnect() {
        var jdbc = mock(JdbcTemplate.class);
        var reader = new QuestDbBoundedReader(jdbc);
        var sql = reader.prepare(DEFINITION, range(JUNE, SEPTEMBER), null);
        assertTrue(sql.sql().endsWith("ORDER BY \"month\" ASC LIMIT 2"));
        verifyNoInteractions(jdbc);
    }
    @Test void noWindowIsRejectedBeforeConnection() {
        assertThrows(IllegalArgumentException.class, () -> QuestDbEquityStyleReadGuard.validate(DEFINITION,
                new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), null,null,null,1,null)));
    }
    @Test void fullProjectionIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> QuestDbEquityStyleReadGuard.validate(DEFINITION,
                new DatasetReadQuery(List.of("month"), Map.of(),"month",JUNE,SEPTEMBER,1,null)));
    }
    @Test void monthFirstIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> QuestDbEquityStyleReadGuard.validate(DEFINITION,range(JUNE.plusDays(1),SEPTEMBER)));
    }
    @Test void thirteenMonthWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> QuestDbEquityStyleReadGuard.validate(DEFINITION,range(JUNE,JUNE.plusMonths(13))));
    }
    @Test void pageLimitIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> QuestDbEquityStyleReadGuard.validate(DEFINITION,
                new DatasetReadQuery(DEFINITION.storageColumns(),Map.of(),"month",JUNE,SEPTEMBER,13,null)));
    }
    @Test void exactMonthRejectsWrongDateType() {
        assertThrows(IllegalArgumentException.class, () -> QuestDbEquityStyleReadGuard.validate(DEFINITION,
                new DatasetReadQuery(DEFINITION.storageColumns(),Map.of("month","202606"),null,null,null,1,null)));
    }
    @Test void physicalVersionAndAllMetadataStatementsAreBounded() throws SQLException {
        var fixture = new Fixture(sql -> sql.contains("table_columns") ? schema() : List.of(state()));
        assertTrue(QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION).contains(":txn:4:wal:3"));
        assertEquals(List.of(2,31,2),fixture.limits);
        for (var statement : fixture.statements) verify(statement).setQueryTimeout(20);
    }
    @Test void metadataSchemaOrderCannotDrift() {
        var schema = new ArrayList<>(schema()); Collections.swap(schema,1,2);
        var fixture = new Fixture(sql -> sql.contains("table_columns") ? schema : List.of(state()));
        assertThrows(IllegalStateException.class, () -> QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION));
    }
    @Test void schemaExtraColumnCannotBeIgnored() {
        var schema = new ArrayList<>(schema()); schema.add(Map.of("column","extra","type","DOUBLE","designated",false,"upsertKey",false));
        var fixture = new Fixture(sql -> sql.contains("table_columns") ? schema : List.of(state()));
        assertThrows(IllegalStateException.class, () -> QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION));
    }
    @Test void metadataBooleanNullFailsClosed() {
        var state = new LinkedHashMap<>(state()); state.put("dedup",null);
        var fixture = new Fixture(sql -> List.of(state));
        assertThrows(IllegalStateException.class, () -> QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION));
    }
    @Test void laggingWalIsRejected() {
        var state = new LinkedHashMap<>(state()); state.put("sequencerTxn",4L);
        var fixture = new Fixture(sql -> List.of(state));
        assertThrows(IllegalStateException.class, () -> QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION));
    }
    @Test void generationChangeAcrossSchemaReadIsRejected() {
        var calls = new AtomicInteger();
        var fixture = new Fixture(sql -> {
            if(sql.contains("table_columns")) return schema();
            var state = new LinkedHashMap<>(state()); if(calls.incrementAndGet()>1)state.put("table_txn",5L);
            return List.of(state);
        });
        assertThrows(IllegalStateException.class, () -> QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION));
    }
    @Test void uninitializedTableRequiresActualZeroAndKeepsNullableVersionWithBoundedStatements() throws SQLException {
        var fixture=emptyFixture(uninitialized(),0L);
        String version=QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION);
        assertTrue(version.contains(":txn:uninitialized:wal:0:wal-physical:null:metadata-rows:null"));
        assertEquals(List.of(2,2,2,31,2,2,2),fixture.limits);
        for(var statement:fixture.statements)verify(statement).setQueryTimeout(20);
    }
    @Test void nullPhysicalTxnCannotHideActualRowsOrMissingCount() {
        for(Long actual:Arrays.asList(1L,null)){
            var fixture=emptyFixture(uninitialized(),actual);
            assertThrows(IllegalStateException.class,()->QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION));
        }
    }
    @Test void nullPhysicalTxnCannotHideAnyNonzeroWalOrPendingFrontier() {
        for(String field:List.of("writerTxn","sequencerTxn","wal_txn","wal_pending_row_count","bufferedTxnSize")){
            var physical=uninitialized();physical.put(field,1L);var fixture=emptyFixture(physical,0L);
            assertThrows(IllegalStateException.class,()->QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION));
        }
    }
    @Test void rawNullableRowMetadataMustBeZeroOrNullAndCannotBeNegative() {
        for(String field:List.of("table_row_count","wal_txn"))for(long number:List.of(-1L,1L)){
            var physical=uninitialized();physical.put(field,number);var fixture=emptyFixture(physical,0L);
            assertThrows(IllegalStateException.class,()->QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION));
        }
        var negative=uninitialized();negative.put("table_txn",-1L);
        assertThrows(IllegalStateException.class,()->QuestDbEquityStyleReadGuard.version(emptyFixture(negative,0L).jdbc,DEFINITION));
    }
    @Test void nullableMetadataChangeAcrossActualCountCannotBeNormalizedAway() {
        var calls=new AtomicInteger();var fixture=new Fixture(sql->{
            if(sql.contains("table_columns"))return schema();
            if(sql.contains("SELECT count()"))return List.of(Map.of("actual_rows",0L));
            var physical=uninitialized();if(calls.incrementAndGet()>1)physical.put("table_row_count",0L);return List.of(physical);
        });
        assertThrows(IllegalStateException.class,()->QuestDbEquityStyleReadGuard.version(fixture.jdbc,DEFINITION));
    }
    @Test void uninitializedCursorIsRejectedWhenFirstPhysicalTxnBecomesZeroBeforeRowQuery() {
        var empty=emptyFixture(uninitialized(),0L);String oldVersion=QuestDbEquityStyleReadGuard.version(empty.jdbc,DEFINITION);
        var rowQueries=new AtomicInteger();var fixture=new Fixture(sql->{
            if(sql.contains("table_columns"))return schema();
            if(sql.contains("FROM \""))rowQueries.incrementAndGet();
            var physical=uninitialized();physical.put("table_txn",0L);physical.put("wal_txn",0L);physical.put("table_row_count",0L);return List.of(physical);
        });
        var reader=new QuestDbBoundedReader(fixture.jdbc);var original=range(JUNE,SEPTEMBER);
        var cursor=new DatasetReadCursor(reader.prepare(DEFINITION,original,oldVersion).fingerprint(),List.of(JUNE),oldVersion);
        var next=new DatasetReadQuery(DEFINITION.storageColumns(),Map.of(),"month",JUNE,SEPTEMBER,1,cursor);
        assertThrows(IllegalArgumentException.class,()->reader.read(DEFINITION,next,null,values->values));assertEquals(0,rowQueries.get());
    }
    private static Map<String,Object> uninitialized(){
        var row=new LinkedHashMap<>(state());row.put("table_txn",null);row.put("wal_txn",null);row.put("table_row_count",null);row.put("writerTxn",0L);row.put("sequencerTxn",0L);return row;
    }
    private static Fixture emptyFixture(Map<String,Object> physical,Long actual){
        return new Fixture(sql->{
            if(sql.contains("table_columns"))return schema();
            if(sql.contains("SELECT count()")){var count=new LinkedHashMap<String,Object>();count.put("actual_rows",actual);return List.of(count);}
            return List.of(physical);
        });
    }
    private static Map<String,Object> state() {
        var row = new LinkedHashMap<String,Object>();
        row.put("id",5L); row.put("directoryName","equity_style_monthly~5"); row.put("table_txn",4L);row.put("wal_txn",3L);row.put("table_row_count",2L);
        row.put("writerTxn",3L); row.put("sequencerTxn",3L); row.put("wal_pending_row_count",0L); row.put("bufferedTxnSize",0L);
        row.put("walEnabled",true); row.put("dedup",true); row.put("matView",false); row.put("table_suspended",false);row.put("suspended",false);
        row.put("partitionBy","YEAR");row.put("designatedTimestamp","month");return row;
    }
    private static List<Map<String,Object>> schema() {
        return DEFINITION.columns().stream().map(c -> {
            var row = new LinkedHashMap<String,Object>();row.put("column",c.storageName());row.put("type",c.storageType().name());
            row.put("designated",c.storageName().equals("month")); row.put("upsertKey",c.storageName().equals("month"));
            return (Map<String,Object>)row;
        }).toList();
    }
    private static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final List<Integer> limits = new ArrayList<>();
        final List<PreparedStatement> statements = new ArrayList<>();
        @SuppressWarnings("unchecked") Fixture(Function<String,List<Map<String,Object>>> rows) {
            doAnswer(call -> {
                var connection = mock(Connection.class); var sql = new AtomicReference<String>();
                var statement = mock(PreparedStatement.class);statements.add(statement);
                when(connection.prepareStatement(any(String.class))).thenAnswer(arg -> {sql.set(arg.getArgument(0));return statement;});
                doAnswer(arg -> {limits.add(arg.getArgument(0));return null;}).when(statement).setMaxRows(anyInt());
                call.getArgument(0,PreparedStatementCreator.class).createPreparedStatement(connection);
                var records = rows.apply(sql.get());var index=new AtomicInteger(-1);var wasNull=new AtomicBoolean();
                var rs=(ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,args)->{
                    if(method.getName().equals("next"))return index.incrementAndGet()<records.size();
                    if(method.getName().equals("wasNull"))return wasNull.get();
                    Object value=records.get(index.get()).get(args[0].toString());wasNull.set(value==null);
                    return switch(method.getName()) {
                        case "getString" -> value==null ? null : value.toString();
                        case "getLong" -> value==null ? 0L : ((Number)value).longValue();
                        case "getBoolean" -> value!=null && (Boolean)value;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
                return call.getArgument(1,ResultSetExtractor.class).extractData(rs);
            }).when(jdbc).query(any(PreparedStatementCreator.class),any(ResultSetExtractor.class));
        }
    }
}
