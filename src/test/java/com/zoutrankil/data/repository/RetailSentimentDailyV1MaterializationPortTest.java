package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.RetailSentimentDailyV1;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;

/** Independent bounded source census, nullable native counts, and exact physical contract guards. */
class RetailSentimentDailyV1MaterializationPortTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 17);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class Census {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final RetailSentimentDailyV1MaterializationPort port =
                spy(new RetailSentimentDailyV1MaterializationPort(jdbc, new QuestDbProperties(), false));
        final List<String> sql = new ArrayList<>();

        Census(List<Map<String, Object>> rows) throws Exception {
            var connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                sql.add(call.getArgument(0));
                return statement;
            });
            when(jdbc.query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class))).thenAnswer(call -> {
                ((PreparedStatementCreator) call.getArgument(0)).createPreparedStatement(connection);
                return ((ResultSetExtractor<?>) call.getArgument(1)).extractData(resultSet(rows));
            });
        }

        void verify(long count, RetailSentimentDailyV1 expected) {
            port.requireSourceCoverage(DATE, DATE, count, List.of(expected));
        }
    }

    private static Map<String, Object> source(Long q1, Long q3, Long spoof, Long support, Long pressure) {
        var result = new LinkedHashMap<String, Object>();
        result.put("ts", Timestamp.from(DATE.atStartOfDay().toInstant(ZoneOffset.UTC)));
        result.put("q1_count", q1);
        result.put("q3_count", q3);
        result.put("spoof_count", spoof);
        result.put("fake_support_count", support);
        result.put("fake_pressure_count", pressure);
        return result;
    }

    private static RetailSentimentDailyV1 counts(Long q1, Long q3, Long spoof, Long manipulation) {
        return new RetailSentimentDailyV1(DATE, null, null, null, null, null,
                q1, q3, null, spoof, manipulation, null, null);
    }

    @Test void allNullSourceCountsRemainNullWithoutZeroFill() throws Exception {
        var census = new Census(List.of(source(null, null, null, null, null), source(null, null, null, null, null)));
        assertDoesNotThrow(() -> census.verify(2, counts(null, null, null, null)));
        assertEquals(1, census.sql.size());
        assertTrue(census.sql.getFirst().contains("SELECT ts,q1_count,q3_count,spoof_count,fake_support_count,fake_pressure_count"));
        assertTrue(census.sql.getFirst().contains("WHERE ts >= ? AND ts < ? LIMIT 200001"));
        verify(census.statement).setMaxRows(200001);
        verify(census.statement).setFetchSize(1000);
        verify(census.statement).setQueryTimeout(20);
        verify(census.statement).setTimestamp(eq(1), eq(Timestamp.from(DATE.atStartOfDay().toInstant(ZoneOffset.UTC))), any(Calendar.class));
        verify(census.statement).setTimestamp(eq(2), eq(Timestamp.from(DATE.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))), any(Calendar.class));
    }

    @Test void manipulationUsesNullablePairBeforeSumRatherThanSeparateCoalescedSums() throws Exception {
        var census = new Census(List.of(source(1L, null, 0L, 5L, 1L),
                source(2L, 4L, null, 100L, null), source(null, null, null, null, 90L)));
        assertDoesNotThrow(() -> census.verify(3, counts(3L, 4L, 0L, 6L)));
        assertThrows(IllegalStateException.class, () -> census.verify(3, counts(3L, 4L, 0L, 196L)));
    }

    @Test void pairWithMissingOperandDoesNotOverflowOrBecomeZero() throws Exception {
        var census = new Census(List.of(source(null, null, null, Long.MAX_VALUE, null)));
        assertDoesNotThrow(() -> census.verify(1, counts(null, null, null, null)));
    }

    @Test void negativeSupportCannotBeHiddenByPositivePressure() throws Exception {
        var census = new Census(List.of(source(0L, 0L, 0L, -1L, 1L)));
        var error = assertThrows(IllegalStateException.class,
                () -> census.verify(1, counts(0L, 0L, 0L, 0L)));
        assertTrue(error.getMessage().contains("fake_support_count"));
    }

    @Test void negativePressureCannotBeHiddenByPositiveSupport() throws Exception {
        var census = new Census(List.of(source(0L, 0L, 0L, 1L, -1L)));
        var error = assertThrows(IllegalStateException.class,
                () -> census.verify(1, counts(0L, 0L, 0L, 0L)));
        assertTrue(error.getMessage().contains("fake_pressure_count"));
    }

    @Test void negativeSupportIsRejectedBeforeNullPressureExcludesThePair() throws Exception {
        var census = new Census(List.of(source(0L, 0L, 0L, -1L, null)));
        var error = assertThrows(IllegalStateException.class,
                () -> census.verify(1, counts(0L, 0L, 0L, null)));
        assertTrue(error.getMessage().contains("fake_support_count"));
    }

    @Test void negativePressureIsRejectedBeforeNullSupportExcludesThePair() throws Exception {
        var census = new Census(List.of(source(0L, 0L, 0L, null, -1L)));
        var error = assertThrows(IllegalStateException.class,
                () -> census.verify(1, counts(0L, 0L, 0L, null)));
        assertTrue(error.getMessage().contains("fake_pressure_count"));
    }
    @Test void longMaxIsPreservedExactly() throws Exception {
        var census = new Census(List.of(source(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0L)));
        assertDoesNotThrow(() -> census.verify(1, counts(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)));
    }

    @Test void overflowingPerRowPairFailsBeforeNativeSumCouldWrap() throws Exception {
        var census = new Census(List.of(source(0L, 0L, 0L, Long.MAX_VALUE, 1L)));
        var error = assertThrows(IllegalStateException.class, () -> census.verify(1, counts(0L, 0L, 0L, 0L)));
        assertTrue(error.getMessage().contains("per-row manipulation LONG addition overflows"));
    }

    @Test void overflowingPositiveDailySumsFailEvenWhenNativeValueCouldWrapToPositive() throws Exception {
        // 2*MAX + 3 wraps to 1; a native nonnegative DTO or SQL parity check would accept the wrapped value.
        var census = new Census(List.of(source(Long.MAX_VALUE, 0L, 0L, 0L, 0L),
                source(Long.MAX_VALUE, 0L, 0L, 0L, 0L), source(3L, 0L, 0L, 0L, 0L)));
        var error = assertThrows(IllegalStateException.class, () -> census.verify(3, counts(1L, 0L, 0L, 0L)));
        assertTrue(error.getMessage().contains("complete daily LONG sum overflows"));
    }

    @Test void overflowingManipulationDailySumAlsoFails() throws Exception {
        var census = new Census(List.of(source(0L, 0L, 0L, Long.MAX_VALUE, 0L),
                source(0L, 0L, 0L, Long.MAX_VALUE, 0L), source(0L, 0L, 0L, 3L, 0L)));
        assertThrows(IllegalStateException.class, () -> census.verify(3, counts(0L, 0L, 0L, 1L)));
    }

    @Test void nullAndZeroAggregateAreDifferent() throws Exception {
        var census = new Census(List.of(source(null, null, null, null, null)));
        assertThrows(IllegalStateException.class, () -> census.verify(1, counts(0L, null, null, null)));
    }

    @Test void missingRowsAndBucketsCannotClaimCompleteSource() throws Exception {
        var census = new Census(List.of(source(1L, 2L, 3L, 4L, 5L)));
        assertThrows(IllegalStateException.class, () -> census.verify(2, counts(1L, 2L, 3L, 9L)));
        assertThrows(IllegalStateException.class, () -> census.port.requireSourceCoverage(DATE, DATE, 1, List.of()));
        assertThrows(IllegalStateException.class, () -> census.verify(1, counts(1L, 2L, 3L, 8L)));
    }

    @Test void negativeCountsAndNonMidnightSourceTimestampsAreRejected() throws Exception {
        var census = new Census(List.of(source(-1L, 0L, 0L, 0L, 0L)));
        assertThrows(IllegalStateException.class, () -> census.verify(1, counts(0L, 0L, 0L, 0L)));
        var row = source(1L, 2L, 3L, 4L, 5L);
        row.put("ts", Timestamp.from(DATE.atStartOfDay().plusSeconds(1).toInstant(ZoneOffset.UTC)));
        var offMidnight = new Census(List.of(row));
        assertThrows(IllegalStateException.class, () -> offMidnight.verify(1, counts(1L, 2L, 3L, 9L)));
    }

    @Test void censusBudgetAndCancellationFailBeforeAnyAcceptedEvidence() throws Exception {
        var census = new Census(List.of(source(1L, 2L, 3L, 4L, 5L)));
        assertThrows(IllegalStateException.class, () -> census.verify(200001, counts(1L, 2L, 3L, 9L)));
        assertTrue(census.sql.isEmpty());
        census.port.cancellationProbe(() -> true);
        assertThrows(java.util.concurrent.CancellationException.class, () -> census.verify(1, counts(1L, 2L, 3L, 9L)));
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void actualReadbackPreservesAllNullableFieldsAndExplicitThirteenColumnProjection() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var sql = new ArrayList<String>();
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            sql.add(call.getArgument(0));
            return statement;
        });
        var storage = new LinkedHashMap<String, Object>();
        storage.put("trade_date", Timestamp.from(DATE.atStartOfDay().toInstant(ZoneOffset.UTC)));
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class))).thenAnswer(call -> {
            ((PreparedStatementCreator) call.getArgument(0)).createPreparedStatement(connection);
            var rows = resultSet(List.of(storage));
            assertTrue(rows.next());
            return List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rows, 0));
        });
        var port = spy(new RetailSentimentDailyV1MaterializationPort(jdbc, new QuestDbProperties(), false));
        assertEquals(List.of(counts(null, null, null, null)), port.actual(DATE, DATE));
        assertTrue(sql.getFirst().startsWith("SELECT trade_date,avg_retail_ratio,avg_retail_entropy,"));
        assertTrue(sql.getFirst().contains("total_q1,total_q3,avg_wash_trade_ratio,total_spoof_count,total_manipulation_count,"));
        assertTrue(sql.getFirst().contains("FROM mv_retail_sentiment_daily_v1 WHERE trade_date >= ? AND trade_date < ?"));
        verify(statement).setMaxRows(32);
        storage.put("total_q1", 0L);
        assertEquals(0L, port.actual(DATE, DATE).getFirst().totalQ1().longValue());
        storage.put("total_q1", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, port.actual(DATE, DATE).getFirst().totalQ1().longValue());
        storage.put("avg_mfi_score", Double.POSITIVE_INFINITY);
        assertThrows(IllegalStateException.class, () -> port.actual(DATE, DATE));
    }
    @Test void authoritativeSqlRetainsAllThirteenExpressionsAndYuanConversion() {
        String sql = RetailSentimentDailyV1MaterializationPort.DEFINITION_SQL.toLowerCase(Locale.ROOT);
        assertTrue(sql.startsWith("select ts as trade_date, avg(gmm_retail_ratio)"));
        assertTrue(sql.contains("sum(fake_support_count + fake_pressure_count) as total_manipulation_count"));
        assertFalse(sql.contains("coalesce"));
        assertTrue(sql.contains("sum(retail_total_amount) / 100000000.0"));
        assertTrue(sql.contains("sum(retail_funds_net_inflow) / 100000000.0"));
        assertTrue(sql.contains("sum(main_net_inflow) / 100000000.0"));
        assertTrue(sql.endsWith("from l2_daily_features sample by 1d align to calendar"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class Metadata {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final RetailSentimentDailyV1MaterializationPort port =
                spy(new RetailSentimentDailyV1MaterializationPort(jdbc, new QuestDbProperties(), false));
        final Map<String, String> sourceTypes = new LinkedHashMap<>();
        final Map<String, String> outputTypes = new LinkedHashMap<>();
        final Map<String, Object> source = physical(11, "l2_daily_features~11", "DAY", true);
        final Map<String, Object> output = physical(12, "mv_retail_sentiment_daily_v1~12", "MONTH", false);
        final Map<String, Object> refresh = new LinkedHashMap<>();
        final Map<String, Object> sourceWal = wal(7);
        final Map<String, Object> outputWal = wal(4);

        Metadata() throws Exception {
            sourceTypes.put("ts", "TIMESTAMP"); sourceTypes.put("symbol", "SYMBOL");
            for (String column : List.of("gmm_retail_ratio", "mean_retail_entropy", "retail_total_amount",
                    "retail_funds_net_inflow", "mean_rel_aggro", "wash_trade_ratio", "mfi_score", "main_net_inflow"))
                sourceTypes.put(column, "DOUBLE");
            for (String column : List.of("q1_count", "q3_count", "spoof_count", "fake_support_count", "fake_pressure_count"))
                sourceTypes.put(column, "LONG");
            com.zoutrankil.data.domain.RetailSentimentDailyV1Dataset.DEFINITION.columns()
                    .forEach(column -> outputTypes.put(column.storageName(), column.storageType().name()));
            refresh.put("view_status", "valid");
            refresh.put("invalidation_reason", null);
            refresh.put("base_table_name", RetailSentimentDailyV1MaterializationPort.SOURCE);
            refresh.put("view_sql", RetailSentimentDailyV1MaterializationPort.DEFINITION_SQL);
            refresh.put("refresh_type", "timer"); refresh.put("timer_interval", 1L);
            refresh.put("timer_interval_unit", "MINUTE"); refresh.put("refresh_base_table_txn", 7L);
            refresh.put("base_table_txn", 7L);
            refresh.put("last_refresh_start_timestamp", "2026-10-06T00:00:00Z");
            refresh.put("last_refresh_finish_timestamp", "2026-10-06T00:00:01Z");

            doAnswer(call -> {
                String sql = call.getArgument(0);
                var types = sql.contains("('l2_daily_features')") ? sourceTypes : outputTypes;
                var rs = mock(ResultSet.class);
                for (var column : types.entrySet()) {
                    when(rs.getString(1)).thenReturn(column.getKey());
                    when(rs.getString(2)).thenReturn(column.getValue());
                    ((RowCallbackHandler) call.getArgument(1)).processRow(rs);
                }
                return null;
            }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
            when(jdbc.queryForList(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                if (sql.contains("('l2_daily_features')"))
                    return List.of(Map.of("column", "ts", "designated", true, "upsertKey", true),
                            Map.of("column", "symbol", "designated", false, "upsertKey", true));
                return List.of(Map.of("column", "trade_date", "designated", true, "upsertKey", false));
            });
            when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                Map<String, Object> row;
                if (sql.contains("FROM materialized_views()")) row = refresh;
                else if (sql.contains("FROM wal_tables()")) row = sql.contains("name='l2_daily_features'") ? sourceWal : outputWal;
                else row = sql.contains("table_name='l2_daily_features'") ? source : output;
                return ((ResultSetExtractor<?>) call.getArgument(1)).extractData(resultSet(List.of(row)));
            });
        }

        private static Map<String, Object> physical(long id, String directory, String partition, boolean dedup) {
            return new LinkedHashMap<>(Map.of("id", id, "directoryName", directory, "table_txn", 10L,
                    "table_suspended", false, "wal_pending_row_count", 0L, "partitionBy", partition,
                    "walEnabled", true, "dedup", dedup));
        }

        private static Map<String, Object> wal(long txn) {
            return new LinkedHashMap<>(Map.of("sequencerTxn", txn, "writerTxn", txn,
                    "bufferedTxnSize", 0L, "suspended", false));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"partition", "non-wal", "non-dedup", "table-suspended", "pending-rows",
            "wal-suspended", "buffered-wal", "writer-behind"})
    void installationRejectsWrongPhysicalSourceBeforeAnyCreate(String fault) throws Exception {
        var fixture = new Metadata();
        // Mock only process admission; the installation and its schema/physical/WAL checks remain real.
        doNothing().when(fixture.port).verifyPrivateInstance();
        when(fixture.jdbc.queryForList("SELECT view_name FROM materialized_views() WHERE view_name='"
                + RetailSentimentDailyV1MaterializationPort.OUTPUT + "'")).thenReturn(List.of());
        switch (fault) {
            case "partition" -> fixture.source.put("partitionBy", "MONTH");
            case "non-wal" -> fixture.source.put("walEnabled", false);
            case "non-dedup" -> fixture.source.put("dedup", false);
            case "table-suspended" -> fixture.source.put("table_suspended", true);
            case "pending-rows" -> fixture.source.put("wal_pending_row_count", 1L);
            case "wal-suspended" -> fixture.sourceWal.put("suspended", true);
            case "buffered-wal" -> fixture.sourceWal.put("bufferedTxnSize", 1L);
            case "writer-behind" -> fixture.sourceWal.put("writerTxn", 6L);
            default -> throw new AssertionError(fault);
        }
        assertThrows(IllegalStateException.class, fixture.port::createIsolatedTarget);
        verify(fixture.jdbc, never()).execute(anyString());
        verify(fixture.jdbc, never()).queryForList(
                "SELECT view_name FROM materialized_views() WHERE view_name='" + RetailSentimentDailyV1MaterializationPort.OUTPUT + "'");
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void installationRejectsAChangedSourcePhysicalVersionBeforeCreate() throws Exception {
        var fixture = new Metadata();
        doNothing().when(fixture.port).verifyPrivateInstance();
        var reads = new AtomicInteger();
        when(fixture.jdbc.query(contains("FROM tables() WHERE table_name='l2_daily_features'"),
                any(ResultSetExtractor.class))).thenAnswer(call -> {
            var source = new LinkedHashMap<>(fixture.source);
            if (reads.incrementAndGet() > 1) source.put("table_txn", 11L);
            return ((ResultSetExtractor<?>) call.getArgument(1)).extractData(resultSet(List.of(source)));
        });
        assertThrows(IllegalStateException.class, fixture.port::createIsolatedTarget);
        assertEquals(2, reads.get());
        verify(fixture.jdbc, never()).execute(anyString());
    }
    @Test void installationChecksStableSourceIdentityWithoutAnExistingMv() throws Exception {
        var fixture = new Metadata();
        doNothing().when(fixture.port).verifyPrivateInstance();
        when(fixture.jdbc.queryForList("SELECT view_name FROM materialized_views() WHERE view_name='"
                + RetailSentimentDailyV1MaterializationPort.OUTPUT + "'")).thenReturn(List.of());
        assertDoesNotThrow(fixture.port::createIsolatedTarget);
        verify(fixture.jdbc).execute("CREATE MATERIALIZED VIEW " + RetailSentimentDailyV1MaterializationPort.OUTPUT
                + " REFRESH EVERY 1m AS (" + RetailSentimentDailyV1MaterializationPort.DEFINITION_SQL + ") PARTITION BY MONTH");
    }
    @Test void actualLongTypesAndPhysicalTransactionsAreIndependentFromWalFrontiers() throws Exception {
        var fixture = new Metadata();
        var snapshot = fixture.port.snapshot();
        assertTrue(snapshot.valid()); assertTrue(snapshot.caughtUp());
        assertEquals(7, snapshot.sourceSeqTxn()); assertEquals(10, snapshot.sourceTableTxn());
        assertEquals(4, snapshot.mvSeqTxn()); assertEquals(10, snapshot.mvTxn());
    }

    @Test void wrongBaseColumnTypesPartitionAndDefinitionAreRejected() throws Exception {
        var fixture = new Metadata();
        fixture.sourceTypes.put("fake_pressure_count", "INT");
        assertThrows(IllegalStateException.class, fixture.port::snapshot);
        fixture.sourceTypes.put("fake_pressure_count", "LONG");
        fixture.source.put("partitionBy", "MONTH");
        assertThrows(IllegalStateException.class, fixture.port::snapshot);
        fixture.source.put("partitionBy", "DAY");
        fixture.refresh.put("view_sql", RetailSentimentDailyV1MaterializationPort.DEFINITION_SQL.replace(
                "sum(fake_support_count + fake_pressure_count)", "sum(fake_support_count) + sum(fake_pressure_count)"));
        assertThrows(IllegalStateException.class, fixture.port::snapshot);
    }

    @Test void outputThirteenColumnTypesMustMatchExactly() throws Exception {
        var fixture = new Metadata();
        fixture.outputTypes.put("total_q1", "DOUBLE");
        assertThrows(IllegalStateException.class, fixture.port::snapshot);
        fixture.outputTypes.put("total_q1", "LONG");
        fixture.outputTypes.put("extra", "LONG");
        assertThrows(IllegalStateException.class, fixture.port::snapshot);
    }

    @Test void invalidLaggingAndSuspendedMetadataCannotCertifyCaughtUpValues() throws Exception {
        var fixture = new Metadata();
        fixture.refresh.put("view_status", "invalid");
        assertFalse(fixture.port.snapshot().valid());
        fixture.refresh.put("view_status", "valid");
        fixture.refresh.put("refresh_base_table_txn", 6L);
        assertFalse(fixture.port.snapshot().caughtUp());
        fixture.refresh.put("refresh_base_table_txn", 7L);
        fixture.sourceWal.put("bufferedTxnSize", 1L);
        assertFalse(fixture.port.snapshot().sourceSettled());
        fixture.sourceWal.put("bufferedTxnSize", 0L);
        fixture.outputWal.put("suspended", true);
        assertFalse(fixture.port.snapshot().mvSettled());
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) throws Exception {
        var rs = mock(ResultSet.class);
        var index = new AtomicInteger(-1);
        var wasNull = new AtomicBoolean();
        when(rs.next()).thenAnswer(call -> index.incrementAndGet() < rows.size());
        when(rs.getString(anyString())).thenAnswer(call -> {
            Object value = rows.get(index.get()).get(call.getArgument(0));
            wasNull.set(value == null);
            return value == null ? null : value.toString();
        });
        when(rs.getLong(anyString())).thenAnswer(call -> {
            Object value = rows.get(index.get()).get(call.getArgument(0));
            wasNull.set(value == null);
            return value == null ? 0L : ((Number) value).longValue();
        });
        when(rs.getDouble(anyString())).thenAnswer(call -> {
            Object value = rows.get(index.get()).get(call.getArgument(0));
            wasNull.set(value == null);
            return value == null ? 0.0 : ((Number) value).doubleValue();
        });
        when(rs.getBoolean(anyString())).thenAnswer(call -> {
            Object value = rows.get(index.get()).get(call.getArgument(0));
            wasNull.set(value == null);
            return Boolean.TRUE.equals(value);
        });
        when(rs.getTimestamp(anyString(), any(Calendar.class))).thenAnswer(call -> {
            Object value = rows.get(index.get()).get(call.getArgument(0));
            wasNull.set(value == null);
            return value;
        });
        when(rs.wasNull()).thenAnswer(call -> wasNull.get());
        return rs;
    }
}