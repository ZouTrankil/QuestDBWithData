package com.zoutrankil.data.derived.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.application.EtfMarketOverviewCacheTestFixtures;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;
import static com.zoutrankil.data.derived.application.EtfMarketOverviewCacheTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtfMarketOverviewPublicationTargetContractTest {
    @TempDir Path temporary;

    @Test void constructionIsPureAndReadbackKeepsPhysicalCheckAndReadOrder() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var expected = EtfMarketOverviewCacheTestFixtures.envelope(temporary.resolve("preview.json"), true, true);
        var order = new ArrayList<String>();
        var target = new QuestDbEtfMarketOverviewPublicationTarget(jdbc) {
            @Override void requireJdbcTarget() { order.add("endpoint"); }
            @Override void verifySchemas(EtfMarketOverviewCachePublicationEnvelope row) {
                assertSame(expected, row); order.add("schemas");
            }
            @Override Map<String, JsonNode> readSnapshots(EtfMarketOverviewCachePublicationEnvelope row) {
                assertSame(expected, row); order.add("snapshot"); return row.targets();
            }
            @Override List<EtfMarketOverviewDailyCache> readCache(EtfMarketOverviewDailyCacheKey key) {
                assertEquals(expected.key(), key); order.add("cache"); return List.of(expected.cache(), expected.cache());
            }
            @Override List<MarketBarometerCacheCoverage> readReceipt(EtfMarketOverviewDailyCacheKey key) {
                assertEquals(expected.key(), key); order.add("receipt"); return List.of(expected.receipt());
            }
        };
        verifyNoInteractions(jdbc);
        target.preflight(expected);
        assertEquals(List.of("endpoint", "schemas", "snapshot"), order);
        order.clear();
        var observed = target.readback(expected, expected.key());
        assertEquals(List.of("endpoint", "schemas", "snapshot", "cache", "receipt", "snapshot"), order);
        assertEquals(2, observed.cache().size(), "duplicates survive transport for application rejection");
        assertThrows(UnsupportedOperationException.class, () -> observed.cache().clear());
        order.clear();
        assertTrue(target.walSettled(expected));
        assertEquals(List.of("endpoint", "snapshot"), order);
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://localhost:18832/qdb", "jdbc:postgresql://127.0.0.1:8812/qdb",
            "jdbc:postgresql://192.168.1.1:18832/qdb"})
    void endpointMismatchStopsBeforeAnyQuery(String url) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(url);
        var target = new QuestDbEtfMarketOverviewPublicationTarget(jdbc);
        var expected = EtfMarketOverviewCacheTestFixtures.envelope(temporary.resolve("preview.json"), true, true);
        assertEquals("D101 exact private PGWire endpoint required", assertThrows(IllegalStateException.class,
                () -> target.preflight(expected)).getMessage());
        verify(connection).close();
        verify(jdbc, never()).query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class));
    }

    @Test void cacheSqlKeepsFullKeyLimitsStatementBudgetsMicrosAndRawSignedZero() throws Exception {
        var result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getLong("trade_date")).thenReturn(-86_400_000_000L);
        when(result.getLong("etf_count")).thenReturn(2L);
        when(result.getObject("total_share")).thenReturn(-0.0d);
        when(result.getObject("total_size_yi")).thenReturn(null);
        when(result.getString("source_version")).thenReturn(SHA);
        var fixture = new QueryFixture(result);
        var key = new EtfMarketOverviewDailyCacheKey(LocalDate.of(1969, 12, 31), SHA);
        var rows = fixture.target.readCache(key);
        assertEquals("SELECT cast(trade_date as long) AS trade_date,etf_count,total_share,total_size_yi,source_version FROM etf_market_overview_daily_cache"
                + " WHERE trade_date='1969-12-31' AND source_version='" + SHA + "' LIMIT 2", fixture.sql);
        assertEquals(key, rows.getFirst().key());
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(rows.getFirst().totalShare()));
        assertNull(rows.getFirst().totalSizeYi());
        fixture.verifyBudgets();
    }

    @Test void receiptSqlKeepsDatasetDateAndSourceVersionCompleteKey() throws Exception {
        var result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getLong("trade_date")).thenReturn(DAY.toEpochDay() * 86_400_000_000L);
        when(result.getLong("row_count")).thenReturn(0L);
        when(result.getString("dataset_id")).thenReturn("etf_market_overview_daily");
        when(result.getString("source_version")).thenReturn(SHA);
        when(result.getString("content_digest")).thenReturn(DIGEST);
        var fixture = new QueryFixture(result);
        var rows = fixture.target.readReceipt(new EtfMarketOverviewDailyCacheKey(DAY, SHA));
        assertEquals("SELECT cast(trade_date as long) AS trade_date,dataset_id,source_version,row_count,content_digest FROM market_barometer_cache_coverage"
                + " WHERE trade_date='" + DAY + "' AND dataset_id='etf_market_overview_daily' AND source_version='" + SHA + "' LIMIT 2", fixture.sql);
        assertEquals(0L, rows.getFirst().rowCount());
        assertEquals(DIGEST, rows.getFirst().contentDigest());
        fixture.verifyBudgets();
    }

    @Test void exactPhysicalDecodingRejectsNonDoubleNonfiniteAndNonMidnight() throws Exception {
        for (Object invalid : List.of(1.0f, 1L, Double.NaN, Double.POSITIVE_INFINITY)) {
            var result = mock(ResultSet.class);
            when(result.next()).thenReturn(true, false);
            when(result.getLong("trade_date")).thenReturn(DAY.toEpochDay() * 86_400_000_000L);
            when(result.getObject("total_share")).thenReturn(invalid);
            var fixture = new QueryFixture(result);
            assertEquals("Exact finite PG binary64 required", assertThrows(IllegalStateException.class,
                    () -> fixture.target.readCache(new EtfMarketOverviewDailyCacheKey(DAY, SHA))).getMessage());
        }
        var result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getLong("trade_date")).thenReturn(-1L);
        var fixture = new QueryFixture(result);
        assertEquals("Exact UTC midnight TIMESTAMP required", assertThrows(IllegalStateException.class,
                () -> fixture.target.readCache(new EtfMarketOverviewDailyCacheKey(DAY, SHA))).getMessage());
    }

    private static final class QueryFixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final QuestDbEtfMarketOverviewPublicationTarget target;
        String sql;

        @SuppressWarnings({"rawtypes", "unchecked"})
        QueryFixture(ResultSet result) throws Exception {
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                sql = call.getArgument(0); return statement;
            });
            when(jdbc.query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class))).thenAnswer(call -> {
                ((PreparedStatementCreator) call.getArgument(0)).createPreparedStatement(connection);
                return ((ResultSetExtractor) call.getArgument(1)).extractData(result);
            });
            target = new QuestDbEtfMarketOverviewPublicationTarget(jdbc);
        }

        void verifyBudgets() throws Exception {
            verify(statement).setQueryTimeout(20);
            verify(statement).setMaxRows(257);
            verify(statement).setFetchSize(257);
            verify(jdbc, never()).setQueryTimeout(anyInt());
            verify(jdbc, never()).setMaxRows(anyInt());
            verify(jdbc, never()).setFetchSize(anyInt());
        }
    }
}
