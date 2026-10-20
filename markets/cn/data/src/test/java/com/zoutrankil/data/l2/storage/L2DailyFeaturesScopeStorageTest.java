package com.zoutrankil.data.l2.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import javax.sql.DataSource;

class L2DailyFeaturesScopeStorageTest {
    private static final String TABLE = "java_d086_l2_daily_features_scope";

    @Test
    void filteredCountBindsDateAndEverySymbolAndRejectsUnknownCount() throws Exception {
        var f = new Fixture();
        f.count = 2L;
        var writer = f.writer();
        var day = LocalDate.of(1969, 12, 31);
        assertEquals(2, writer.countRows(day, List.of("000001.SZ", "000002.SZ")));
        assertEquals(
                "SELECT count() FROM \""
                        + TABLE
                        + "\" WHERE trade_date=cast(? as TIMESTAMP) AND symbol IN (?, ?)",
                f.sql.getFirst());
        var statement = f.statements.getFirst();
        verify(statement).setObject(1, -86400000000L);
        verify(statement).setString(2, "000001.SZ");
        verify(statement).setString(3, "000002.SZ");
        f.count = null;
        assertThrows(IllegalStateException.class, () -> writer.countRows(day, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> writer.countRows(day, List.of("000001.SZ", "000001.SZ")));
    }

    @Test
    void walVersionMustBeSettledIntegralAndPresent() throws Exception {
        var f = new Fixture();
        var writer = f.writer();
        assertEquals("wal:5", writer.stableVersion());
        f.transaction = 5.0;
        assertThrows(IllegalStateException.class, writer::stableVersion);
        f.transaction = null;
        assertThrows(IllegalStateException.class, writer::stableVersion);
        f.transaction = 5L;
        f.suspended = true;
        assertThrows(IllegalStateException.class, writer::stableVersion);
    }

    @Test
    void dateScanReadsOneExtraRowToDetectOverflow() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        int[] row = {-1};
        when(result.next()).thenAnswer(i -> ++row[0] < 33);
        when(result.getString("trade_date"))
                .thenAnswer(i -> LocalDate.of(2026, 9, 1).plusDays(row[0]).toString());
        var writer =
                new L2DailyFeaturesWritePort(
                        TABLE,
                        new JdbcTemplate(source),
                        mock(QuestDB.class),
                        CnTradeDateLayout.TRADE_DATE_V2);
        assertThrows(IllegalStateException.class, writer::readExistingDates);
        verify(statement)
                .executeQuery(
                        "SELECT trade_date FROM \""
                                + TABLE
                                + "\" GROUP BY trade_date ORDER BY trade_date LIMIT 33");
    }

    // 用实际 JdbcTemplate 跑 SQL、参数和类型回读，外部连接只返回当前测试的有限行。
    private static final class Fixture {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final List<String> sql = new ArrayList<>();
        final List<PreparedStatement> statements = new ArrayList<>();
        Long count = 0L;
        Object transaction = 5L;
        boolean suspended;

        Fixture() throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString()))
                    .thenAnswer(
                            call -> {
                                String query = call.getArgument(0);
                                sql.add(query);
                                var statement = mock(PreparedStatement.class);
                                statements.add(statement);
                                var values = new LinkedHashMap<String, Object>();
                                if (query.startsWith("SELECT count()")) values.put("count", count);
                                else if (query.contains("FROM tables()")) {
                                    values.put("walEnabled", true);
                                    values.put("table_suspended", false);
                                    values.put("wal_pending_row_count", 0L);
                                    values.put("table_txn", 5L);
                                    values.put("wal_txn", 5L);
                                } else {
                                    values.put("suspended", suspended);
                                    values.put("writerTxn", transaction);
                                    values.put("sequencerTxn", transaction);
                                    values.put("bufferedTxnSize", 0L);
                                }
                                var columns = List.copyOf(values.keySet());
                                var result = mock(ResultSet.class);
                                var metadata = mock(ResultSetMetaData.class);
                                when(result.getMetaData()).thenReturn(metadata);
                                when(metadata.getColumnCount()).thenReturn(columns.size());
                                when(metadata.getColumnLabel(anyInt()))
                                        .thenAnswer(i -> columns.get((int) i.getArgument(0) - 1));
                                when(metadata.getColumnName(anyInt()))
                                        .thenAnswer(i -> columns.get((int) i.getArgument(0) - 1));
                                when(metadata.getColumnType(anyInt())).thenReturn(Types.BIGINT);
                                when(result.next()).thenReturn(true, false);
                                when(result.getObject(anyInt()))
                                        .thenAnswer(
                                                i ->
                                                        values.get(
                                                                columns.get(
                                                                        (int) i.getArgument(0)
                                                                                - 1)));
                                when(result.getObject(anyInt(), eq(Long.class)))
                                        .thenAnswer(
                                                i ->
                                                        values.get(
                                                                columns.get(
                                                                        (int) i.getArgument(0)
                                                                                - 1)));
                                boolean[] wasNull = {false};
                                when(result.getLong(anyInt()))
                                        .thenAnswer(
                                                i -> {
                                                    Object v =
                                                            values.get(
                                                                    columns.get(
                                                                            (int) i.getArgument(0)
                                                                                    - 1));
                                                    wasNull[0] = v == null;
                                                    return v == null
                                                            ? 0L
                                                            : ((Number) v).longValue();
                                                });
                                when(result.wasNull()).thenAnswer(i -> wasNull[0]);
                                when(statement.executeQuery()).thenReturn(result);
                                return statement;
                            });
        }

        L2DailyFeaturesWritePort writer() {
            return new L2DailyFeaturesWritePort(
                    TABLE,
                    new JdbcTemplate(source),
                    mock(QuestDB.class),
                    CnTradeDateLayout.TRADE_DATE_V2);
        }
    }
}
