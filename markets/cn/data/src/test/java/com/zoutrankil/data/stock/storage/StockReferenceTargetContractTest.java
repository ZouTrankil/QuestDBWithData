package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.data.stock.domain.StockDetailState;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import java.sql.ResultSet;
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
import static org.mockito.Mockito.*;

/** Characterizes the extracted targets without opening a database connection. */
class StockReferenceTargetContractTest {
    private static final String ID = "static-v2-" + "a".repeat(64);
    private enum Family { FACTOR, LIMIT, ST }

    @ParameterizedTest @EnumSource(Family.class)
    void writersAreFreshAndKeepTheirOwnBoundedJdbcState(Family family) throws Exception {
        var jdbc = mock(JdbcTemplate.class); var source = mock(DataSource.class); var qdb = mock(QuestDB.class);
        when(jdbc.getDataSource()).thenReturn(source);
        var first = writer(family, jdbc, qdb); var second = writer(family, jdbc, qdb);
        assertNotSame(first, second); assertSame(codec(family), first.codec()); assertSame(first.codec(), second.codec());
        var a = jdbcOf(first); var b = jdbcOf(second);
        assertNotSame(a, b); assertNotSame(jdbc, a); assertSame(source, a.getDataSource());
        assertEquals(20, a.getQueryTimeout()); assertEquals(10001, a.getMaxRows());
        assertEquals(20, b.getQueryTimeout()); assertEquals(10001, b.getMaxRows());
        assertFalse(first.uncertainSenderStopped()); assertFalse(second.uncertainSenderStopped());
        verifyNoInteractions(source, qdb);
    }

    @ParameterizedTest @EnumSource(Family.class)
    @SuppressWarnings("unchecked")
    void rangesKeepColumnSqlMicrosecondsAndMalformedAggregateBehavior(Family family) throws Exception {
        var jdbc = mock(JdbcTemplate.class); var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        if (family == Family.FACTOR) {
            when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                    .thenAnswer(i -> ((ResultSetExtractor<?>) i.getArgument(1)).extractData(rs));
        } else {
            when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
                    .thenAnswer(i -> ((ResultSetExtractor<?>) i.getArgument(1)).extractData(rs));
        }
        assertEquals(new StockTargetRange(null, null), range(family, jdbc, null));
        var from = LocalDate.of(1969, 12, 31); var to = LocalDate.of(2020, 1, 4);
        when(rs.getObject("min_micros")).thenReturn(micros(from));
        when(rs.getObject("max_micros")).thenReturn(micros(to));
        assertEquals(new StockTargetRange(from, to), range(family, jdbc, null));
        String column = family == Family.ST ? "timestamp" : "trade_date";
        String separator = family == Family.FACTOR ? "," : ", ";
        String sql = "SELECT cast(min(" + column + ") AS long) AS min_micros," + separator.substring(1)
                + "cast(max(" + column + ") AS long) AS max_micros FROM \"" + table(family) + "\"";
        if (family == Family.FACTOR) {
            verify(jdbc, times(2)).query(eq(sql), any(ResultSetExtractor.class), eq(new Object[]{}));
            assertEquals(new StockTargetRange(from, to), range(family, jdbc, "000001.SZ"));
            verify(jdbc).query(eq(sql + " WHERE ts_code=?"), any(ResultSetExtractor.class), eq(new Object[]{"000001.SZ"}));
        } else verify(jdbc, times(2)).query(eq(sql), any(ResultSetExtractor.class));
        for (Object[] bad : new Object[][]{{null, 0L}, {0L, null}, {"0", 0L}, {1L, 0L}}) {
            when(rs.getObject("min_micros")).thenReturn(bad[0]); when(rs.getObject("max_micros")).thenReturn(bad[1]);
            assertThrows(RuntimeException.class, () -> range(family, jdbc, null));
        }
        when(rs.getObject("min_micros")).thenReturn(micros(to)); when(rs.getObject("max_micros")).thenReturn(micros(from));
        assertThrows(IllegalStateException.class, () -> range(family, jdbc, null));
        when(rs.next()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> range(family, jdbc, null));
    }

    @Test void factorIdentityPreflightsItsWriterBeforeReadingExactGeneration() throws Exception {
        var jdbc = mock(JdbcTemplate.class); when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        var qdb = mock(QuestDB.class); var calls = new ArrayList<String>();
        when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", "stk_factor"))
                .thenAnswer(i -> { calls.add("identity-query"); return List.of(Map.of("id", 7L, "directoryName", "generation")); });
        try (var checks = mockStatic(QuestDbWriteChecks.class); var identity = mockStatic(StaticTargetIdentity.class)) {
            checks.when(() -> QuestDbWriteChecks.preflight(any(JdbcTemplate.class), eq("stk_factor"), eq(StockFactorDataset.DEFINITION)))
                    .thenAnswer(i -> { calls.add("preflight"); return null; });
            identity.when(() -> StaticTargetIdentity.identify(jdbc, "stk_factor", 7L, "generation")).thenReturn(ID);
            assertEquals(ID, new QuestDbStockFactorTarget("stk_factor", jdbc, qdb).targetId());
            assertEquals(List.of("preflight", "identity-query"), calls);
            calls.clear(); var failure = new IllegalStateException("schema");
            checks.when(() -> QuestDbWriteChecks.preflight(any(JdbcTemplate.class), eq("stk_factor"), eq(StockFactorDataset.DEFINITION)))
                    .thenThrow(failure);
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> new QuestDbStockFactorTarget("stk_factor", jdbc, qdb).targetId()));
            assertTrue(calls.isEmpty());
        }
        verifyNoInteractions(qdb);
    }

    @Test void limitIdentityDoesNotAddSchemaPreflightAndRejectsAmbiguousMetadata() {
        var jdbc = mock(JdbcTemplate.class); var qdb = mock(QuestDB.class);
        var target = new QuestDbStockLimitTarget("stk_limit", jdbc, qdb);
        String sql = "SELECT id,directoryName FROM tables() WHERE table_name = ?";
        try (var checks = mockStatic(QuestDbWriteChecks.class); var identity = mockStatic(StaticTargetIdentity.class)) {
            when(jdbc.queryForList(sql, "stk_limit")).thenReturn(List.of(Map.of("id", 7L, "directoryName", "generation")));
            identity.when(() -> StaticTargetIdentity.identify(jdbc, "stk_limit", 7L, "generation")).thenReturn(ID);
            assertEquals(ID, target.targetId()); checks.verifyNoInteractions();
            for (List<Map<String,Object>> rows : List.<List<Map<String,Object>>>of(List.of(),
                    List.of(Map.of("id", "7", "directoryName", "generation")),
                    List.of(Map.of("id", 7L, "directoryName", "a"), Map.of("id", 8L, "directoryName", "b")))) {
                when(jdbc.queryForList(sql, "stk_limit")).thenReturn(rows);
                assertEquals("Exact stk_limit QuestDB target identity required",
                        assertThrows(IllegalStateException.class, target::targetId).getMessage());
            }
        }
    }

    @Test void stLogicalIdentityRetainsItsIndependentGenerationNamespace() {
        var jdbc = mock(JdbcTemplate.class); var qdb = mock(QuestDB.class);
        try (var identity = mockStatic(StaticTargetIdentity.class)) {
            identity.when(() -> StaticTargetIdentity.identify(jdbc, "stk_st_daily", 0L, "d012-logical-target-v1")).thenReturn(ID);
            assertEquals("d012-logical-v1-" + "a".repeat(64),
                    new QuestDbStockStDailyTarget("stk_st_daily", jdbc, qdb).targetId());
            identity.verify(() -> StaticTargetIdentity.identify(jdbc, "stk_st_daily", 0L, "d012-logical-target-v1"));
        }
        verifyNoInteractions(jdbc, qdb);
    }

    @Test void formalAndIsolatedLimitAndStTargetsRemainAdmittedWithoutIo() {
        var jdbc = mock(JdbcTemplate.class); var qdb = mock(QuestDB.class);
        for (String table : List.of("stk_limit", "java_d010_stk_limit_contract"))
            assertEquals(table, new QuestDbStockLimitTarget(table, jdbc, qdb).tableName());
        for (String table : List.of("stk_st_daily", "java_d012_stk_st_daily_contract"))
            assertEquals(table, new QuestDbStockStDailyTarget(table, jdbc, qdb).tableName());
        assertThrows(IllegalStateException.class, () -> new QuestDbStockLimitTarget("other", jdbc, qdb));
        assertThrows(IllegalStateException.class, () -> new QuestDbStockStDailyTarget("other", jdbc, qdb));
        assertThrows(IllegalArgumentException.class, () -> new QuestDbStockFactorTarget("bad;name", jdbc, qdb));
        verifyNoInteractions(jdbc, qdb);
    }

    @Test void detailTableOperationsPreserveSqlIdentityAndFreshBoundedFactories() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var source = mock(DataSource.class);
        when(jdbc.getDataSource()).thenReturn(source);
        var target = new QuestDbStockDetailTarget(jdbc, "stock_detail_info");
        assertEquals("stock_detail_info", target.tableName());
        when(jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", "owned_stage")).thenReturn(List.of(Map.of("id", 7)));
        assertTrue(target.exists("owned_stage")); assertFalse(target.exists("missing_stage"));
        target.rename("owned_stage", "stock_detail_info");
        verify(jdbc).execute("RENAME TABLE owned_stage TO stock_detail_info");
        try (var identities = mockStatic(StaticTargetIdentity.class)) {
            identities.when(() -> StaticTargetIdentity.identify(jdbc, "stock_detail_info", 7L, "generation")).thenReturn(ID);
            assertEquals(ID, target.identify("stock_detail_info", new StockDetailState.Identity(7, "generation")));
        }
        var first = target.open("owned_stage"); var second = target.open("owned_stage");
        assertNotSame(first, second); assertNotSame(jdbcOf(first), jdbcOf(second));
        assertEquals(20, jdbcOf(first).getQueryTimeout()); assertEquals(10001, jdbcOf(first).getMaxRows());
        var a = target.newStaging(); var b = target.newStaging();
        assertNotSame(a, b); assertNotSame(jdbcOf(a), jdbcOf(b)); assertEquals(20, jdbcOf(a).getQueryTimeout());
        var publication = target.publicationTables(); assertNotSame(target, publication);
        assertEquals(20, jdbcOf(publication).getQueryTimeout());
        verifyNoInteractions(source);
    }

    @Test void stPublicationTablesKeepMissingTableAndRenameSemantics() throws Exception {
        var external = mock(JdbcTemplate.class); when(external.getDataSource()).thenReturn(mock(DataSource.class));
        try (var copies = mockConstruction(JdbcTemplate.class)) {
            var tables = new QuestDbStockStDailyTables(external); var jdbc = copies.constructed().getFirst();
            verify(jdbc).setQueryTimeout(120);
            assertNull(tables.snapshotIfPresent("java_d012_stk_st_daily_missing"));
            verify(jdbc).queryForList("SELECT id FROM tables() WHERE table_name=?", "java_d012_stk_st_daily_missing");
            tables.rename("stage_table", "stk_st_daily");
            verify(jdbc).execute("RENAME TABLE \"stage_table\" TO \"stk_st_daily\"");
            assertThrows(IllegalArgumentException.class, () -> tables.rename("bad;table", "stk_st_daily"));
        }
    }

    private static String table(Family family) { return switch (family) { case FACTOR -> "stk_factor"; case LIMIT -> "stk_limit"; case ST -> "stk_st_daily"; }; }
    private static VerifiedWriteSession<?, ?> writer(Family family, JdbcTemplate jdbc, QuestDB qdb) {
        return switch (family) {
            case FACTOR -> new QuestDbStockFactorTarget(table(family), jdbc, qdb).newWriter(ID);
            case LIMIT -> new QuestDbStockLimitTarget(table(family), jdbc, qdb).newWriter(ID);
            case ST -> new QuestDbStockStDailyTarget(table(family), jdbc, qdb).newWriter(ID);
        };
    }
    private static Object codec(Family family) { return switch (family) { case FACTOR -> StockFactorWritePort.CODEC; case LIMIT -> StockLimitWritePort.CODEC; case ST -> StockStDailyWritePort.CODEC; }; }
    private static StockTargetRange range(Family family, JdbcTemplate jdbc, String code) {
        var qdb = mock(QuestDB.class);
        return switch (family) {
            case FACTOR -> new QuestDbStockFactorTarget(table(family), jdbc, qdb).range(code);
            case LIMIT -> new QuestDbStockLimitTarget(table(family), jdbc, qdb).range();
            case ST -> new QuestDbStockStDailyTarget(table(family), jdbc, qdb).range();
        };
    }
    private static JdbcTemplate jdbcOf(Object value) throws Exception {
        var field = value.getClass().getDeclaredField("jdbc"); field.setAccessible(true); return (JdbcTemplate) field.get(value);
    }
    private static long micros(LocalDate date) { return date.atStartOfDay(ZoneOffset.UTC).toEpochSecond() * 1_000_000L; }
}
