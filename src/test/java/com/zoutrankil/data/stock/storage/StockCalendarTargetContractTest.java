package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarQuestDbTarget;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarWritePort;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.StockBasicDataset;
import com.zoutrankil.data.stock.domain.DailyBasicTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockCalendarTargetContractTest {
    private record TargetView(String table, Callable<String> identity,
                              Supplier<VerifiedWriteSession<?, ?>> writer) {}

    private enum Family {
        DAILY("java_d007_daily_t15", "SELECT id,directoryName FROM tables() WHERE table_name=?",
                "Exact QuestDB physical identity required for daily", -1),
        DAILY_BASIC("java_daily_basic_t15", "SELECT id,directoryName FROM tables() WHERE table_name = ?",
                "Exact daily_basic QuestDB target identity required", 10_001),
        STOCK_BASIC(StockBasicDataset.DEFINITION.objectName(),
                "SELECT id, directoryName FROM tables() WHERE table_name = ?",
                "Exact physical QuestDB target identity required", 10_001),
        CALENDAR("java_calendar_t15", "SELECT id,directoryName FROM tables() WHERE table_name=?",
                "Exact calendar physical identity required", 367);

        final String table, sql, missingIdentity;
        final int maxRows;
        Family(String table, String sql, String missingIdentity, int maxRows) {
            this.table = table; this.sql = sql; this.missingIdentity = missingIdentity; this.maxRows = maxRows;
        }
        TargetView target(JdbcTemplate jdbc, QuestDB questdb, QuestDbProperties properties) {
            return switch (this) {
                case DAILY -> {
                    var target = new DailyQuestDbTarget(table, jdbc, questdb, properties);
                    yield new TargetView(target.tableName(), target::targetId, target::newWriter);
                }
                case DAILY_BASIC -> {
                    var target = new DailyBasicQuestDbTarget(table, jdbc, questdb, properties);
                    yield new TargetView(target.tableName(), target::targetId, target::newWriter);
                }
                case STOCK_BASIC -> {
                    var target = new StockBasicQuestDbTarget(table, jdbc, questdb, properties);
                    yield new TargetView(target.tableName(), target::targetId, target::newWriter);
                }
                case CALENDAR -> {
                    var target = new ExchangeCalendarQuestDbTarget(table, jdbc, questdb, properties);
                    yield new TargetView(target.tableName(), target::targetId, target::newWriter);
                }
            };
        }
        Object codec() {
            return switch (this) {
                case DAILY -> DailyWritePort.CODEC;
                case DAILY_BASIC -> DailyBasicWritePort.CODEC;
                case STOCK_BASIC -> StockBasicWritePort.CODEC;
                case CALENDAR -> ExchangeCalendarWritePort.CODEC;
            };
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void constructionAndFreshSessionsDoNotOpenConnections(Family family) throws Exception {
        var jdbc = mock(JdbcTemplate.class); var source = mock(DataSource.class); var questdb = mock(QuestDB.class);
        var target = family.target(jdbc, questdb, properties());
        assertEquals(family.table, target.table());
        verifyNoInteractions(jdbc, source, questdb);
        when(jdbc.getDataSource()).thenReturn(source);
        var first = target.writer().get(); var second = target.writer().get();
        assertNotSame(first, second); assertSame(family.codec(), first.codec()); assertSame(first.codec(), second.codec());
        var field = first.getClass().getDeclaredField("jdbc"); field.setAccessible(true);
        var firstJdbc = (JdbcTemplate) field.get(first); var secondJdbc = (JdbcTemplate) field.get(second);
        assertNotSame(jdbc, firstJdbc); assertNotSame(firstJdbc, secondJdbc);
        assertSame(source, firstJdbc.getDataSource()); assertSame(source, secondJdbc.getDataSource());
        assertEquals(20, firstJdbc.getQueryTimeout()); assertEquals(family.maxRows, firstJdbc.getMaxRows());
        assertEquals(20, secondJdbc.getQueryTimeout()); assertEquals(family.maxRows, secondJdbc.getMaxRows());
        verify(jdbc, times(2)).getDataSource(); verifyNoInteractions(source, questdb);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void identityKeepsExactSqlHashInputsAndPhysicalGeneration(Family family) throws Exception {
        var jdbc = mock(JdbcTemplate.class); var questdb = mock(QuestDB.class); var properties = properties();
        when(jdbc.queryForList(family.sql, family.table)).thenReturn(List.of(Map.of("id", 37L, "directoryName", "g-37")));
        var target = family.target(jdbc, questdb, properties);
        assertEquals(hash("host.example:18812:19000:database:" + family.table + ":37:g-37"), target.identity().call());
        when(jdbc.queryForList(family.sql, family.table)).thenReturn(List.of(Map.of("id", 38, "directoryName", "g-38")));
        assertEquals(hash("host.example:18812:19000:database:" + family.table + ":38:g-38"), target.identity().call());
        verify(jdbc, times(2)).queryForList(family.sql, family.table); verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(questdb);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void malformedMetadataKeepsEachFamilyFailure(Family family) {
        var jdbc = mock(JdbcTemplate.class); var target = family.target(jdbc, mock(QuestDB.class), properties());
        var valid = Map.<String, Object>of("id", 37, "directoryName", "g-37");
        for (var rows : List.of(List.<Map<String, Object>>of(), List.of(valid, valid),
                List.of(Map.<String, Object>of("id", "37", "directoryName", "g-37")),
                List.of(Map.<String, Object>of("id", 37)))) {
            when(jdbc.queryForList(family.sql, family.table)).thenReturn(rows);
            assertEquals(family.missingIdentity, assertThrows(IllegalStateException.class, target.identity()::call).getMessage());
        }
    }

    @Test void dailyRejectsUnownedTargetBeforeMetadataAccess() {
        var jdbc = mock(JdbcTemplate.class); var questdb = mock(QuestDB.class);
        var target = new DailyQuestDbTarget("daily", jdbc, questdb, properties());
        assertEquals("D007 execution requires a dedicated java_d007_daily_<suffix> isolated target",
                assertThrows(IllegalStateException.class, target::targetId).getMessage());
        verifyNoInteractions(jdbc, questdb);
    }

    @Test void basicRangeKeepsQuotedTradeDateSqlAndMicrosecondPrecision() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var rs = rangeRows(jdbc); var target = basicTarget(jdbc);
        var min = LocalDate.of(1969, 12, 31); var max = LocalDate.of(2026, 10, 8);
        when(rs.getObject("min_micros")).thenReturn(micros(min));
        when(rs.getObject("max_micros")).thenReturn(micros(max));
        assertEquals(new DailyBasicTargetRange(min, max), target.range());
        verify(jdbc).query(eq("SELECT cast(min(trade_date) AS long) AS min_micros, cast(max(trade_date) AS long) AS max_micros FROM \"java_daily_basic_t15\""),
                any(ResultSetExtractor.class));
    }

    @Test void basicEmptyPartialAndNonNumericRangesKeepTheirMeaning() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var rs = rangeRows(jdbc); var target = basicTarget(jdbc);
        assertEquals(new DailyBasicTargetRange(null, null), target.range());
        for (var bounds : new Object[][]{{null, 0L}, {0L, null}, {"0", 0L}, {0L, "0"}}) {
            when(rs.getObject("min_micros")).thenReturn(bounds[0]); when(rs.getObject("max_micros")).thenReturn(bounds[1]);
            assertEquals("QuestDB daily_basic date range is not a timestamp epoch",
                    assertThrows(IllegalStateException.class, target::range).getMessage());
        }
    }

    @Test void basicMissingReversedAndSubdayRangesFailAtOriginalBoundaries() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var rs = rangeRows(jdbc); var target = basicTarget(jdbc);
        when(rs.next()).thenReturn(false);
        assertEquals("QuestDB did not return the daily_basic date range aggregate",
                assertThrows(IllegalStateException.class, target::range).getMessage());
        when(rs.next()).thenReturn(true); long day = micros(LocalDate.of(2026, 10, 8));
        when(rs.getObject("min_micros")).thenReturn(day + 86_400_000_000L); when(rs.getObject("max_micros")).thenReturn(day);
        assertEquals("Invalid daily_basic QuestDB target range", assertThrows(IllegalArgumentException.class, target::range).getMessage());
        when(rs.getObject("min_micros")).thenReturn(day + 1); when(rs.getObject("max_micros")).thenReturn(day + 1);
        assertThrows(IllegalArgumentException.class, target::range);
    }

    @Test void configuredStockWriterKeepsBudgetsAndRejectsInvalidBudgetsBeforeIo() throws Exception {
        var jdbc = mock(JdbcTemplate.class); var source = mock(DataSource.class); var questdb = mock(QuestDB.class);
        var target = new StockBasicQuestDbTarget(StockBasicDataset.DEFINITION.objectName(), jdbc, questdb, properties());
        assertThrows(IllegalArgumentException.class, () -> target.newConfiguredWriter(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> target.newConfiguredWriter(4096, Duration.ZERO));
        verifyNoInteractions(jdbc, source, questdb); when(jdbc.getDataSource()).thenReturn(source);
        var writer = target.newConfiguredWriter(8192, Duration.ofSeconds(3));
        var bytes = writer.getClass().getDeclaredField("maxBatchBytes"); bytes.setAccessible(true);
        var timeout = writer.getClass().getDeclaredField("acknowledgementTimeout"); timeout.setAccessible(true);
        assertEquals(8192, bytes.get(writer)); assertEquals(Duration.ofSeconds(3), timeout.get(writer));
        assertSame(StockBasicWritePort.CODEC, writer.codec()); verifyNoInteractions(source, questdb);
    }

    @Test void stockConnectionProbeRejectsNullAndUnexpectedResults() {
        var jdbc = mock(JdbcTemplate.class); var target = new StockBasicQuestDbTarget(StockBasicDataset.DEFINITION.objectName(), jdbc,
                mock(QuestDB.class), properties());
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(null, 0, 1);
        assertEquals("QuestDB JDBC probe returned an unexpected result", assertThrows(IllegalStateException.class, target::verifyConnection).getMessage());
        assertThrows(IllegalStateException.class, target::verifyConnection); assertDoesNotThrow(target::verifyConnection);
        verify(jdbc, times(3)).queryForObject("SELECT 1", Integer.class); verifyNoMoreInteractions(jdbc);
    }

    private static DailyBasicQuestDbTarget basicTarget(JdbcTemplate jdbc) {
        return new DailyBasicQuestDbTarget("java_daily_basic_t15", jdbc, mock(QuestDB.class), properties());
    }
    @SuppressWarnings("unchecked")
    private static ResultSet rangeRows(JdbcTemplate jdbc) throws Exception {
        var rs = mock(ResultSet.class); when(rs.next()).thenReturn(true);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(invocation ->
                ((ResultSetExtractor<?>) invocation.getArgument(1)).extractData(rs));
        return rs;
    }
    private static QuestDbProperties properties() {
        var properties = new QuestDbProperties(); properties.setHost("host.example"); properties.setPgPort(18812);
        properties.setQwpPort(19000); properties.setDatabase("database"); return properties;
    }
    private static String hash(String identity) throws Exception {
        return "questdb-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
    private static long micros(LocalDate date) {
        return Math.multiplyExact(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC), 1_000_000L);
    }
}
