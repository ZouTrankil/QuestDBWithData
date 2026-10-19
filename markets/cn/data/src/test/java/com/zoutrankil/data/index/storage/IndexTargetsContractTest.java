package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.policy.IndexDailyBasicUniverse;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IndexTargetsContractTest {
    private static final String ID="static-v2-"+"a".repeat(64);
    enum Family {
        MARKET("java_d019_index_daily_market_contract","timestamp","Exact isolated D019 QuestDB target identity required",100001),
        BASIC("java_d020_index_daily_basic_contract","trade_date","Exact isolated D020 QuestDB target identity required",100001),
        WEIGHT("java_d021_index_weight_contract","trade_date","Exact isolated D021 physical target identity required",20001);
        final String table,column,identityError; final int maxRows;
        Family(String table,String column,String identityError,int maxRows) {
            this.table=table;this.column=column;this.identityError=identityError;this.maxRows=maxRows;
        }
        Object target(String table,JdbcTemplate jdbc,QuestDB quest) {
            return switch(this) {
                case MARKET -> new QuestDbIndexDailyMarketTarget(table,jdbc,quest);
                case BASIC -> new QuestDbIndexDailyBasicTarget(table,jdbc,quest);
                case WEIGHT -> new QuestDbIndexWeightTarget(table,jdbc,quest);
            };
        }
        String identity(JdbcTemplate jdbc,QuestDB quest) {
            return switch(this) {
                case MARKET -> new QuestDbIndexDailyMarketTarget(table,jdbc,quest).targetId();
                case BASIC -> new QuestDbIndexDailyBasicTarget(table,jdbc,quest).targetId();
                case WEIGHT -> new QuestDbIndexWeightTarget(table,jdbc,quest).targetId();
            };
        }
        VerifiedWriteSession<?,?> writer(JdbcTemplate jdbc,QuestDB quest) {
            return switch(this) {
                case MARKET -> new QuestDbIndexDailyMarketTarget(table,jdbc,quest).newWriter(ID);
                case BASIC -> new QuestDbIndexDailyBasicTarget(table,jdbc,quest).newWriter(ID);
                case WEIGHT -> new QuestDbIndexWeightTarget(table,jdbc,quest).newWriter(ID);
            };
        }
        Object codec() { return switch(this) {
            case MARKET -> IndexDailyMarketWritePort.CODEC;
            case BASIC -> IndexDailyBasicWritePort.CODEC;
            case WEIGHT -> IndexWeightWritePort.CODEC;
        }; }
        Object range(VerifiedWriteSession<?,?> writer) { return switch(this) {
            case MARKET -> ((IndexDailyMarketWritePort)writer).readExistingRange("000300.SH");
            case BASIC -> ((IndexDailyBasicWritePort)writer).readExistingRange("000300.SH");
            case WEIGHT -> ((IndexWeightWritePort)writer).readExistingRange();
        }; }
        Object expected(LocalDate min,LocalDate max) { return switch(this) {
            case MARKET -> new IndexDailyMarketTargetRange(min,max);
            case BASIC -> new IndexDailyBasicTargetRange(min,max);
            case WEIGHT -> new IndexWeightTargetRange(min,max);
        }; }
        String rangeSql() { return "SELECT cast(min("+column+") AS long) AS min_micros, cast(max("+column+") AS long) AS max_micros FROM \""+table+"\""+(this==WEIGHT?"":" WHERE ts_code = ?"); }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void constructorDoesNoIoAndWriterStateIsNeverShared(Family f) throws Exception {
        var jdbc=mock(JdbcTemplate.class);var quest=mock(QuestDB.class);var ds=mock(DataSource.class);
        f.target(f.table,jdbc,quest);verifyNoInteractions(jdbc,quest,ds);
        when(jdbc.getDataSource()).thenReturn(ds);
        var a=f.writer(jdbc,quest);var b=f.writer(jdbc,quest);
        assertNotSame(a,b);assertSame(f.codec(),a.codec());assertSame(a.codec(),b.codec());
        var aj=jdbc(a);var bj=jdbc(b);assertNotSame(aj,bj);assertNotSame(jdbc,aj);
        assertEquals(20,aj.getQueryTimeout());assertEquals(f.maxRows,aj.getMaxRows());
        assertFalse(a.uncertainSenderStopped());assertFalse(b.uncertainSenderStopped());
        Field stopped=a.getClass().getDeclaredField("uncertainSenderStopped");stopped.setAccessible(true);stopped.setBoolean(a,true);
        assertTrue(a.uncertainSenderStopped());assertFalse(b.uncertainSenderStopped());
        verify(jdbc,times(2)).getDataSource();verifyNoInteractions(ds,quest);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void identityQueryAndWeightSchemaCheckKeepTheirOrder(Family f) {
        var jdbc=mock(JdbcTemplate.class);var quest=mock(QuestDB.class);var calls=new ArrayList<String>();
        when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?",f.table)).thenAnswer(i->{calls.add("metadata");return List.of(Map.of("id",7L,"directoryName","generation-7"));});
        try(var checks=mockStatic(QuestDbWriteChecks.class);var identity=mockStatic(StaticTargetIdentity.class)) {
            checks.when(()->QuestDbWriteChecks.preflight(eq(jdbc),eq(f.table),any(DatasetDefinition.class))).thenAnswer(i->{calls.add("preflight");return null;});
            identity.when(()->StaticTargetIdentity.identify(jdbc,f.table,7L,"generation-7")).thenAnswer(i->{calls.add("identity");return ID;});
            assertEquals(ID,f.identity(jdbc,quest));
            assertEquals(f==Family.WEIGHT?List.of("preflight","metadata","identity"):List.of("metadata","identity"),calls);
        }
        verifyNoInteractions(quest);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void badIdentityKeepsItsFamilyMessageAndNeverComputesAnIdentity(Family f) {
        var jdbc=mock(JdbcTemplate.class);var quest=mock(QuestDB.class);
        var valid=Map.<String,Object>of("id",7L,"directoryName","generation-7");
        try(var checks=mockStatic(QuestDbWriteChecks.class);var identity=mockStatic(StaticTargetIdentity.class)) {
            for(var rows:List.of(List.<Map<String,Object>>of(),List.of(valid,valid),List.<Map<String,Object>>of(Map.of("id","7","directoryName","g")),List.<Map<String,Object>>of(Map.of("id",7)))) {
                when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?",f.table)).thenReturn(rows);
                assertEquals(f.identityError,assertThrows(IllegalStateException.class,()->f.identity(jdbc,quest)).getMessage());
            }
            identity.verifyNoInteractions();
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void rangeRetainsExactSqlPrecisionAndNullSemantics(Family f) throws Exception {
        var jdbc=mock(JdbcTemplate.class);var quest=mock(QuestDB.class);var ds=mock(DataSource.class);
        when(jdbc.getDataSource()).thenReturn(ds);
        try(var clones=mockConstruction(JdbcTemplate.class);var identity=mockStatic(StaticTargetIdentity.class)) {
            var writer=f.writer(jdbc,quest);var clone=clones.constructed().getFirst();
            when(clone.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?",f.table)).thenReturn(List.of(Map.of("id",7L,"directoryName","g")));
            identity.when(()->StaticTargetIdentity.identify(clone,f.table,7L,"g")).thenReturn(ID);
            var rs=mock(ResultSet.class);when(rs.next()).thenReturn(true);
            if(f==Family.WEIGHT) when(clone.query(eq(f.rangeSql()),any(ResultSetExtractor.class))).thenAnswer(i->((ResultSetExtractor<?>)i.getArgument(1)).extractData(rs));
            else when(clone.query(eq(f.rangeSql()),any(ResultSetExtractor.class),eq("000300.SH"))).thenAnswer(i->((ResultSetExtractor<?>)i.getArgument(1)).extractData(rs));
            assertEquals(f.expected(null,null),f.range(writer));
            LocalDate lo=LocalDate.of(1969,12,31),hi=LocalDate.of(2026,10,8);
            when(rs.getObject("min_micros")).thenReturn(micros(lo));when(rs.getObject("max_micros")).thenReturn(micros(hi));
            assertEquals(f.expected(lo,hi),f.range(writer));
            when(rs.getObject("min_micros")).thenReturn(null);
            assertThrows(SQLException.class,()->f.range(writer));
            when(rs.getObject("min_micros")).thenReturn("0");assertThrows(SQLException.class,()->f.range(writer));
            when(rs.getObject("min_micros")).thenReturn(micros(hi));when(rs.getObject("max_micros")).thenReturn(micros(lo));
            assertThrows(IllegalArgumentException.class,()->f.range(writer));
            when(rs.getObject("min_micros")).thenReturn(1L);when(rs.getObject("max_micros")).thenReturn(1L);
            if(f==Family.WEIGHT) assertEquals(f.expected(LocalDate.of(1970,1,1),LocalDate.of(1970,1,1)),f.range(writer));
            else assertThrows(SQLException.class,()->f.range(writer));
            when(rs.next()).thenReturn(false);assertThrows(IllegalStateException.class,()->f.range(writer));
        }
        verifyNoInteractions(ds,quest);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void rangesKeepIndependentMessagesAndJson(Family f) throws Exception {
        LocalDate day=LocalDate.of(2020,1,1);
        String expected=switch(f){case MARKET->"Invalid D019 target range";case BASIC->"Invalid D020 physical range";case WEIGHT->"Invalid D021 target date range";};
        assertEquals(expected,assertThrows(IllegalArgumentException.class,()->f.expected(null,day)).getMessage());
        assertEquals(expected,assertThrows(IllegalArgumentException.class,()->f.expected(day,null)).getMessage());
        assertEquals(expected,assertThrows(IllegalArgumentException.class,()->f.expected(day.plusDays(1),day)).getMessage());
        assertEquals("{\"min\":\"2020-01-01\",\"max\":\"2020-01-01\"}",JobDefinitionJson.mapper().writeValueAsString(f.expected(day,day)));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void formalTablesRemainRejectedByTheExistingIsolatedPolicy(Family f) {
        var jdbc=mock(JdbcTemplate.class);var quest=mock(QuestDB.class);
        for(String table:List.of("index_daily_market","index_daily_basic","index_weight","unrelated"))
            assertThrows(RuntimeException.class,()->f.target(table,jdbc,quest));
        assertThrows(IllegalArgumentException.class,()->f.target(f.table+";drop",jdbc,quest));
        verifyNoInteractions(jdbc,quest);
    }

    @Test void basicCodeAdmissionRetainsWhitespaceCaseAndError() {
        assertEquals("000300.SH",IndexDailyBasicUniverse.requireCode(" 000300.sh "));
        assertEquals("D020 code must be one of the frozen Python CORE_INDICES",assertThrows(IllegalArgumentException.class,()->IndexDailyBasicUniverse.requireCode(null)).getMessage());
        assertThrows(IllegalArgumentException.class,()->IndexDailyBasicUniverse.requireCode("000001.SH"));
    }
    private static long micros(LocalDate date){return Math.multiplyExact(date.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),1_000_000L);}
    private static JdbcTemplate jdbc(Object writer)throws Exception{var field=writer.getClass().getDeclaredField("jdbc");field.setAccessible(true);return (JdbcTemplate)field.get(writer);}
}
