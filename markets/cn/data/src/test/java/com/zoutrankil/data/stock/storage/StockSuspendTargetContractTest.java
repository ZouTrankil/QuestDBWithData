package com.zoutrankil.data.stock.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.stock.market.StockSuspendDataset;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.stock.domain.StockSuspendState.Identity;
import com.zoutrankil.data.stock.domain.StockSuspendState.Range;
import com.zoutrankil.data.stock.domain.StockSuspendState.Snapshot;
import com.zoutrankil.data.stock.port.StockSuspendTables;
import com.zoutrankil.data.stock.port.StockSuspendTarget;
import com.zoutrankil.data.stock.port.StockSuspendWriteSession;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

class StockSuspendTargetContractTest {
    private static final String TABLE = "stk_suspend_d011_contract",
            ID = "static-v2-" + "a".repeat(64);
    private static final String STAGE = "java_stk_suspend_stage_0123456789abcdef0123456789abcdef";

    @Test
    void constructorsAndFreshFactoriesDoNoIoAndRetainPrivateWriterConfiguration() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var quest = mock(QuestDB.class);
        var ds = mock(DataSource.class);
        var target = new QuestDbStockSuspendTarget(TABLE, jdbc, quest);
        assertEquals(TABLE, target.tableName());
        verifyNoInteractions(jdbc, quest);
        when(jdbc.getDataSource()).thenReturn(ds);
        var first = target.newWriter(ID);
        var second = target.newWriter(ID);
        var stage = first.forTarget(STAGE, ID);
        assertNotSame(first, second);
        assertNotSame(first, stage);
        assertSame(StockSuspendWritePort.CODEC, first.codec());
        assertSame(first.codec(), stage.codec());
        for (var writer : List.of(first, second, stage)) {
            var own = (JdbcTemplate) field(writer, "jdbc");
            assertNotSame(jdbc, own);
            assertSame(ds, own.getDataSource());
            assertEquals(20, own.getQueryTimeout());
            assertEquals(251, own.getMaxRows());
        }
        assertNotSame(field(first, "jdbc"), field(stage, "jdbc"));
        assertNotSame(target.newPublicationTables(), target.newPublicationTables());
        assertNotSame(target.newStaging(), target.newStaging());
        verifyNoInteractions(ds, quest);
    }

    @Test
    void explicitScopeKeepsWriterTablesStagingRangeAndLogicalIdentityOnOneVersion()
            throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var source = mock(DataSource.class);
        var quest = mock(QuestDB.class);
        when(jdbc.getDataSource()).thenReturn(source);
        var v1 = new QuestDbStockSuspendTarget(TABLE, jdbc, quest);
        var v2 = StockSuspendTarget.forFrozenVersion(v1, 2);
        assertEquals(TABLE, v2.tableName());
        assertEquals(2, StockSuspendTarget.versionOf(v2));
        assertSame(v2, StockSuspendTarget.forFrozenVersion(v2, 2));
        assertEquals(1, StockSuspendTarget.versionOf(StockSuspendTarget.forFrozenVersion(v2, 1)));
        assertEquals(2, ((StockSuspendWriteSession.Versioned) v2.newWriter(ID)).datasetVersion());
        assertEquals(
                2, ((StockSuspendTables.Versioned) v2.newPublicationTables()).datasetVersion());
        assertEquals(CnTradeDateLayout.TRADE_DATE_V2, field(v2.newStaging(), "layout"));
        when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
                .thenReturn(new Range(null, null));
        assertEquals(new Range(null, null), v2.range());
        verify(jdbc)
                .query(
                        eq(
                                "SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros FROM \""
                                        + TABLE
                                        + "\""),
                        any(ResultSetExtractor.class));
        when(jdbc.execute(any(ConnectionCallback.class)))
                .thenReturn("jdbc:postgresql://HOST.example/db%20name?user=ignored");
        String text = "stk_suspend-logical-v2\nhost.example:5432/db%20name\n" + TABLE;
        assertEquals(
                "static-v2-"
                        + HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(text.getBytes(StandardCharsets.UTF_8))),
                v2.targetId());
        assertNotEquals(v1.targetId(), v2.targetId());
        var old = mock(StockSuspendTarget.class);
        assertThrows(
                IllegalArgumentException.class, () -> StockSuspendTarget.forFrozenVersion(old, 2));
        verifyNoInteractions(old, source, quest);
    }

    @Test
    void publicTargetAdmissionDoesNotPermitAnInternalStage() {
        var jdbc = mock(JdbcTemplate.class);
        var quest = mock(QuestDB.class);
        for (String name :
                List.of("stk_suspend", "stk_suspend_d011_one", "java_d011_stk_suspend_two"))
            assertEquals(name, new QuestDbStockSuspendTarget(name, jdbc, quest).tableName());
        for (String name :
                List.of(STAGE, "stk_suspend_other", "java_d011_stk_suspend_", "stk_suspend;drop"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new QuestDbStockSuspendTarget(name, jdbc, quest));
        verifyNoInteractions(jdbc, quest);
    }

    @Test
    void logicalIdentityRetainsExactEndpointNormalizationAndDigestBytes() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var target = new QuestDbStockSuspendTarget(TABLE, jdbc, mock(QuestDB.class));
        when(jdbc.execute(any(ConnectionCallback.class)))
                .thenReturn("jdbc:postgresql://HOST.example/db%20name?user=ignored");
        String value = "stk_suspend-logical-v1\nhost.example:5432/db%20name\n" + TABLE;
        assertEquals(
                "static-v2-"
                        + HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(value.getBytes(StandardCharsets.UTF_8))),
                target.targetId());
        verify(jdbc).execute(any(ConnectionCallback.class));
    }

    @Test
    void logicalIdentityKeepsMetadataFailureOutsideItsEndpointValidationCatch() {
        var jdbc = mock(JdbcTemplate.class);
        var target = new QuestDbStockSuspendTarget(TABLE, jdbc, mock(QuestDB.class));
        var queryFailure = new IllegalStateException("metadata unavailable");
        when(jdbc.execute(any(ConnectionCallback.class))).thenThrow(queryFailure);
        assertSame(queryFailure, assertThrows(IllegalStateException.class, target::targetId));
        for (String url :
                Arrays.asList(
                        null, "jdbc:h2:mem:x", "jdbc:postgresql://host/", "jdbc:postgresql:/db")) {
            doReturn(url).when(jdbc).execute(any(ConnectionCallback.class));
            var invalid = assertThrows(IllegalArgumentException.class, target::targetId);
            assertEquals(
                    "Cannot bind D011 logical target to an explicit PGWire endpoint",
                    invalid.getMessage());
            assertNull(invalid.getCause());
        }
    }

    @Test
    void physicalIdentityPreflightsBeforeMetadataAndUsesTheExactGeneration() {
        var jdbc = mock(JdbcTemplate.class);
        var target = new QuestDbStockSuspendTarget(TABLE, jdbc, mock(QuestDB.class));
        var calls = new ArrayList<String>();
        when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", TABLE))
                .thenAnswer(
                        call -> {
                            calls.add("metadata");
                            return List.of(Map.of("id", 27L, "directoryName", "generation-27"));
                        });
        try (var checks = mockStatic(QuestDbWriteChecks.class);
                var identity = mockStatic(StaticTargetIdentity.class)) {
            checks.when(
                            () ->
                                    QuestDbWriteChecks.preflight(
                                            jdbc, TABLE, StockSuspendDataset.definition(TABLE)))
                    .thenAnswer(
                            call -> {
                                calls.add("preflight");
                                return null;
                            });
            identity.when(() -> StaticTargetIdentity.identify(jdbc, TABLE, 27L, "generation-27"))
                    .thenAnswer(
                            call -> {
                                calls.add("identity");
                                return ID;
                            });
            assertEquals(ID, target.physicalTargetId());
            assertEquals(List.of("preflight", "metadata", "identity"), calls);
        }
    }

    @Test
    void malformedPhysicalIdentityKeepsItsOriginalMessage() {
        var jdbc = mock(JdbcTemplate.class);
        var target = new QuestDbStockSuspendTarget(TABLE, jdbc, mock(QuestDB.class));
        try (var checks = mockStatic(QuestDbWriteChecks.class);
                var identity = mockStatic(StaticTargetIdentity.class)) {
            for (var rows :
                    List.of(
                            List.<Map<String, Object>>of(),
                            List.of(Map.<String, Object>of("id", "1", "directoryName", "dir")),
                            List.of(Map.<String, Object>of("id", 1, "directoryName", 2)))) {
                when(jdbc.queryForList(
                                "SELECT id,directoryName FROM tables() WHERE table_name=?", TABLE))
                        .thenReturn(rows);
                assertEquals(
                        "Exact isolated stk_suspend QuestDB target identity required",
                        assertThrows(IllegalStateException.class, target::physicalTargetId)
                                .getMessage());
            }
            identity.verifyNoInteractions();
        }
    }

    @Test
    void rangeKeepsSqlMicrosAndAllInvalidBoundaries() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
                .thenAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(rs));
        var target = new QuestDbStockSuspendTarget(TABLE, jdbc, mock(QuestDB.class));
        assertEquals(new Range(null, null), target.range());
        var min = LocalDate.of(1969, 12, 31);
        var max = LocalDate.of(2026, 9, 28);
        when(rs.getObject("min_micros")).thenReturn(epoch(min));
        when(rs.getObject("max_micros")).thenReturn(epoch(max));
        assertEquals(new Range(min, max), target.range());
        verify(jdbc, times(2))
                .query(
                        eq(
                                "SELECT cast(min(timestamp) AS long) AS min_micros,cast(max(timestamp) AS long) AS max_micros FROM \""
                                        + TABLE
                                        + "\""),
                        any(ResultSetExtractor.class));
        for (Object[] bounds : new Object[][] {{null, 1L}, {1L, null}, {"1", 1L}, {1L, "1"}}) {
            when(rs.getObject("min_micros")).thenReturn(bounds[0]);
            when(rs.getObject("max_micros")).thenReturn(bounds[1]);
            assertEquals(
                    "QuestDB stk_suspend range is not a timestamp epoch",
                    assertThrows(IllegalStateException.class, target::range).getMessage());
        }
        when(rs.getObject("min_micros")).thenReturn(epoch(max));
        when(rs.getObject("max_micros")).thenReturn(epoch(min));
        assertEquals(
                "Invalid stk_suspend physical date range",
                assertThrows(IllegalStateException.class, target::range).getMessage());
        when(rs.getObject("min_micros")).thenReturn(epoch(min) + 1);
        when(rs.getObject("max_micros")).thenReturn(epoch(max));
        assertThrows(IllegalArgumentException.class, target::range);
        when(rs.next()).thenReturn(false);
        assertEquals(
                "QuestDB did not return stk_suspend date range aggregate",
                assertThrows(IllegalStateException.class, target::range).getMessage());
    }

    @Test
    void optionalSnapshotChecksExistenceThenWalBeforeOpeningTableAndRenameSqlIsExact()
            throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        var tables = new QuestDbStockSuspendTables(jdbc);
        var own = mock(JdbcTemplate.class);
        setField(tables, "jdbc", own);
        when(own.queryForList("SELECT id FROM tables() WHERE table_name=?", TABLE))
                .thenReturn(List.of());
        try (var checks = mockStatic(QuestDbWriteChecks.class);
                var opened = mockConstruction(StockSuspendStorage.class)) {
            assertNull(tables.snapshotIfPresent(TABLE));
            checks.verifyNoInteractions();
            assertTrue(opened.constructed().isEmpty());
        }
        var order = new ArrayList<String>();
        when(own.queryForList("SELECT id FROM tables() WHERE table_name=?", TABLE))
                .thenAnswer(
                        c -> {
                            order.add("exists");
                            return List.of(Map.of("id", 1));
                        });
        var expected = new Snapshot(new Identity(1, "dir"), List.of(), "fingerprint", 0);
        try (var checks = mockStatic(QuestDbWriteChecks.class);
                var opened =
                        mockConstruction(
                                StockSuspendStorage.class,
                                (mock, context) -> {
                                    order.add("open");
                                    assertEquals(List.of(own, TABLE), context.arguments());
                                    when(mock.snapshot())
                                            .thenAnswer(
                                                    c -> {
                                                        order.add("snapshot");
                                                        return expected;
                                                    });
                                })) {
            checks.when(() -> QuestDbWriteChecks.walSettled(own, TABLE))
                    .thenAnswer(
                            c -> {
                                order.add("wal");
                                return true;
                            });
            assertSame(expected, tables.snapshotIfPresent(TABLE));
            assertEquals(List.of("exists", "wal", "open", "snapshot"), order);
        }
        tables.rename(TABLE, "backup");
        verify(own).execute("RENAME TABLE \"" + TABLE + "\" TO \"backup\"");
    }

    private static long epoch(LocalDate date) {
        return Math.multiplyExact(date.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), 1_000_000L);
    }

    private static Object field(Object value, String name) throws Exception {
        var f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(value);
    }

    private static void setField(Object value, String name, Object replacement) throws Exception {
        var f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(value, replacement);
    }
}
