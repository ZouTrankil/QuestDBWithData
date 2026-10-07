package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtfTargetTest {
    private enum Family {
        DAILY("java_d014_etf_daily_t14", "timestamp", "nanos", 1_000_000_000L,
                "Exact isolated etf_daily QuestDB target identity required", "QuestDB did not return etf_daily date range",
                "QuestDB etf_daily date range has invalid types", "Invalid etf_daily physical range"),
        ADJ("java_d015_etf_adj_t14", "timestamp", "micros", 1_000_000L,
                "Exact isolated etf_adj QuestDB target identity required", "QuestDB did not return etf_adj date range",
                "QuestDB etf_adj date range has invalid types", "Invalid etf_adj physical range"),
        FACTOR("java_d017_etf_factor_t14", "trade_date", "micros", 1_000_000L,
                "Exact isolated etf_factor QuestDB table required", "QuestDB did not return the etf_factor date range",
                "QuestDB etf_factor timestamp range has invalid types", "Invalid etf_factor physical date range");
        final String table, column, unit, identityError, missingError, typeError, orderError;
        final long unitsPerSecond;
        Family(String table, String column, String unit, long unitsPerSecond, String identityError,
               String missingError, String typeError, String orderError) {
            this.table=table; this.column=column; this.unit=unit; this.unitsPerSecond=unitsPerSecond;
            this.identityError=identityError; this.missingError=missingError; this.typeError=typeError; this.orderError=orderError;
        }
        EtfTarget<?,?> target(JdbcTemplate jdbc, QuestDB quest) { return target(table, jdbc, quest); }
        EtfTarget<?,?> target(String table, JdbcTemplate jdbc, QuestDB quest) {
            return switch (this) {
                case DAILY -> new QuestDbEtfDailyTarget(table, jdbc, quest);
                case ADJ -> new QuestDbEtfAdjTarget(table, jdbc, quest);
                case FACTOR -> new QuestDbEtfFactorTarget(table, jdbc, quest);
            };
        }
        long epoch(LocalDate date) { return Math.multiplyExact(date.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), unitsPerSecond); }
        String sql() { return "SELECT cast(min("+column+") AS long) AS min_"+unit+", cast(max("+column+") AS long) AS max_"+unit+" FROM \""+table+"\""; }
        Object codec() { return switch (this) { case DAILY -> EtfDailyWritePort.CODEC; case ADJ -> EtfAdjWritePort.CODEC; case FACTOR -> EtfFactorWritePort.CODEC; }; }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void constructionDoesNoIoAndEachWriterIsNewWithTheOriginalCodec(Family family) throws Exception {
        var jdbc=mock(JdbcTemplate.class); var quest=mock(QuestDB.class); var dataSource=mock(DataSource.class);
        var target=family.target(jdbc,quest);
        assertEquals(family.table,target.tableName());
        verifyNoInteractions(jdbc,quest,dataSource);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        var first=target.newWriter("static-v2-frozen"); var second=target.newWriter("static-v2-frozen");
        assertNotSame(first,second);
        assertSame(family.codec(),first.codec()); assertSame(first.codec(),second.codec());
        assertThrows(IllegalArgumentException.class,()->target.newWriter("unfrozen"));
        verifyNoInteractions(quest,dataSource);
        verify(jdbc,times(2)).getDataSource();
    }

    @ParameterizedTest @EnumSource(Family.class)
    void identityUsesTheOriginalBoundQueryAndPhysicalGeneration(Family family) {
        var jdbc=mock(JdbcTemplate.class); var quest=mock(QuestDB.class);
        when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?",family.table))
                .thenReturn(List.of(Map.of("id",37L,"directoryName","generation-37")));
        try(var identity=mockStatic(StaticTargetIdentity.class)) {
            identity.when(()->StaticTargetIdentity.identify(jdbc,family.table,37L,"generation-37")).thenReturn("static-v2-proof");
            assertEquals("static-v2-proof",family.target(jdbc,quest).targetId());
            identity.verify(()->StaticTargetIdentity.identify(jdbc,family.table,37L,"generation-37"));
        }
        verify(jdbc).queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?",family.table);
        verifyNoInteractions(quest);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void ambiguousOrMalformedIdentityKeepsItsFamilyError(Family family) {
        var jdbc=mock(JdbcTemplate.class); var target=family.target(jdbc,mock(QuestDB.class));
        var valid=Map.<String,Object>of("id",37,"directoryName","generation-37");
        List<List<Map<String,Object>>> cases=List.of(List.of(),List.of(valid,valid),
                List.of(Map.of("id","37","directoryName","generation-37")),List.of(Map.of("id",37,"directoryName",42)),
                List.of(Map.of("id",37)));
        try(var identity=mockStatic(StaticTargetIdentity.class)) {
            for(var rows:cases) {
                when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?",family.table)).thenReturn(rows);
                var failure=assertThrows(IllegalStateException.class,target::targetId);
                assertEquals(family.identityError,failure.getMessage());
            }
            identity.verifyNoInteractions();
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void rangeUsesTheOriginalSqlAndExplicitPrecisionIncludingPreEpochDates(Family family) throws Exception {
        var jdbc=mock(JdbcTemplate.class); var rs=rangeRows(jdbc);
        var min=LocalDate.of(1969,12,31);var max=LocalDate.of(2026,9,28);
        when(rs.getObject("min_"+family.unit)).thenReturn(family.epoch(min));
        when(rs.getObject("max_"+family.unit)).thenReturn(family.epoch(max));
        assertEquals(new EtfTargetRange(min,max),family.target(jdbc,mock(QuestDB.class)).range());
        verify(jdbc).query(eq(family.sql()),any(ResultSetExtractor.class));
        verify(rs).getObject("min_"+family.unit); verify(rs).getObject("max_"+family.unit);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void nullRangeIsEmptyButPartialAndNonNumericBoundsFail(Family family) throws Exception {
        var jdbc=mock(JdbcTemplate.class);var rs=rangeRows(jdbc);var target=family.target(jdbc,mock(QuestDB.class));
        assertEquals(new EtfTargetRange(null,null),target.range());
        Object[][] bad={{null,0L},{0L,null},{"0",0L},{0L,"0"}};
        for(var bounds:bad) {
            when(rs.getObject("min_"+family.unit)).thenReturn(bounds[0]);
            when(rs.getObject("max_"+family.unit)).thenReturn(bounds[1]);
            assertEquals(family.typeError,assertThrows(IllegalStateException.class,target::range).getMessage());
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void missingReversedAndNonMidnightRangesFailAtTheirOriginalBoundary(Family family) throws Exception {
        var jdbc=mock(JdbcTemplate.class);var rs=rangeRows(jdbc);var target=family.target(jdbc,mock(QuestDB.class));
        when(rs.next()).thenReturn(false);
        assertEquals(family.missingError,assertThrows(IllegalStateException.class,target::range).getMessage());
        when(rs.next()).thenReturn(true);
        long start=family.epoch(LocalDate.of(2026,9,28));
        when(rs.getObject("min_"+family.unit)).thenReturn(start+86400L*family.unitsPerSecond);
        when(rs.getObject("max_"+family.unit)).thenReturn(start);
        assertEquals(family.orderError,assertThrows(IllegalStateException.class,target::range).getMessage());
        when(rs.getObject("min_"+family.unit)).thenReturn(start+1);
        when(rs.getObject("max_"+family.unit)).thenReturn(start+1);
        assertThrows(IllegalArgumentException.class,target::range);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void admitsOnlyItsExactFormalNameAndRejectsInjectedNamesWithoutConsultingTheDatabase(Family family) {
        var jdbc=mock(JdbcTemplate.class);var quest=mock(QuestDB.class);
        String admitted = "etf_" + family.name().toLowerCase(java.util.Locale.ROOT);
        for(String table:List.of("etf_daily","etf_adj","etf_factor", "unrelated")) {
            if (table.equals(admitted)) assertEquals(table, family.target(table,jdbc,quest).tableName());
            else assertThrows(IllegalStateException.class,()->family.target(table,jdbc,quest));
        }
        assertThrows(IllegalArgumentException.class,()->family.target(family.table+"\";DROP TABLE x",jdbc,quest));
        verifyNoInteractions(jdbc,quest);
    }

    @SuppressWarnings("unchecked")
    private static ResultSet rangeRows(JdbcTemplate jdbc) throws Exception {
        var rs=mock(ResultSet.class);when(rs.next()).thenReturn(true);
        when(jdbc.query(anyString(),any(ResultSetExtractor.class))).thenAnswer(call ->
                ((ResultSetExtractor<?>)call.getArgument(1)).extractData(rs));
        return rs;
    }
}
