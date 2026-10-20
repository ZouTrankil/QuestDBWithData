package com.zoutrankil.data.etf.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.fund.EtfAdj;
import com.zoutrankil.data.etf.port.EtfAdjWriteSession;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

class EtfAdjVersionedStorageTest {
    private static final String TABLE = "java_d015_etf_adj_layout_test";
    private static final String TARGET = "static-v2-" + "a".repeat(64);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    private static final EtfAdj ROW = new EtfAdj("510300.SH", DAY, 1.2345);

    @Test
    void targetSelectsARequestedWriterVersionWithoutChangingTheRowCodecOrDoingIo() {
        var jdbc = mock(JdbcTemplate.class);
        var dataSource = mock(DataSource.class);
        var qdb = mock(QuestDB.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        var target = new QuestDbEtfAdjTarget(TABLE, jdbc, qdb, CnTradeDateLayout.TRADE_DATE_V2);
        assertEquals(2, target.datasetVersion());
        assertEquals(2, ((EtfAdjWriteSession.Versioned) target.newWriter(TARGET)).datasetVersion());
        assertEquals(
                1, ((EtfAdjWriteSession.Versioned) target.newWriter(TARGET, 1)).datasetVersion());
        assertSame(EtfAdjWritePort.CODEC, target.newWriter(TARGET, 2).codec());
        assertThrows(IllegalArgumentException.class, () -> target.newWriter(TARGET, 3));
        verifyNoInteractions(dataSource, qdb);
    }

    @Test
    void typedReaderBindsV2StorageButKeepsTheOriginalLogicalFieldsAndDateBounds() {
        var reader = mock(QuestDbBoundedReader.class);
        var repository = new EtfAdjReadRepository(reader, TABLE, CnTradeDateLayout.TRADE_DATE_V2);
        repository.findRange(DAY, DAY.plusDays(1), 2, null);
        var definition = ArgumentCaptor.forClass(DatasetDefinition.class);
        var query = ArgumentCaptor.forClass(DatasetReadQuery.class);
        verify(reader).read(definition.capture(), query.capture(), eq(null), any());
        assertEquals(2, definition.getValue().schemaVersion());
        assertEquals("trade_date", definition.getValue().designatedTimestamp());
        assertEquals(List.of("ts_code", "trade_date", "adj_factor"), query.getValue().columns());
        assertEquals(DAY, query.getValue().fromInclusive());
        assertEquals(DAY.plusDays(1), query.getValue().toExclusive());
    }

    @Test
    void v2SenderAndEveryReadbackUseTheDeclaredDateColumnAndRetainUtcDateValues() throws Exception {
        var input = mock(JdbcTemplate.class);
        when(input.getDataSource()).thenReturn(mock(DataSource.class));
        var qdb = mock(QuestDB.class);
        var sender = mock(Sender.class, RETURNS_SELF);
        when(qdb.borrowSender()).thenReturn(sender);
        when(sender.flushAndGetSequence()).thenReturn(7L);
        when(sender.awaitAckedFsn(7L, 10_000L)).thenReturn(true);
        try (var constructions =
                        mockConstruction(
                                JdbcTemplate.class,
                                withSettings()
                                        .defaultAnswer(
                                                invocation -> {
                                                    if (invocation
                                                            .getMethod()
                                                            .getName()
                                                            .equals("queryForList"))
                                                        return metadata(
                                                                invocation.getArgument(0),
                                                                "trade_date");
                                                    if (invocation
                                                            .getMethod()
                                                            .getName()
                                                            .equals("query")) {
                                                        RowMapper<?> mapper =
                                                                invocation.getArgument(1);
                                                        return List.of(mapper.mapRow(row(), 0));
                                                    }
                                                    return RETURNS_DEFAULTS.answer(invocation);
                                                }));
                var identity = mockStatic(StaticTargetIdentity.class)) {
            identity.when(
                            () ->
                                    StaticTargetIdentity.identify(
                                            any(JdbcTemplate.class),
                                            eq(TABLE),
                                            eq(7L),
                                            eq("generation-7")))
                    .thenReturn(TARGET);
            var writer =
                    new EtfAdjWritePort(TABLE, TARGET, input, qdb, CnTradeDateLayout.TRADE_DATE_V2);
            writer.send(List.of(ROW));
            assertEquals(List.of(ROW), writer.readDate(DAY));
            assertEquals(List.of(DAY), writer.readExistingDates());
            assertEquals(List.of(ROW), writer.readback(List.of(ROW.key())));
            verify(sender).at(DAY.atStartOfDay(ZoneOffset.UTC).toInstant());
            verify(sender).doubleColumn("adj_factor", ROW.adjFactor());
            var sqls =
                    mockingDetails(constructions.constructed().getFirst()).getInvocations().stream()
                            .filter(invocation -> invocation.getMethod().getName().equals("query"))
                            .map(invocation -> invocation.getArgument(0).toString())
                            .toList();
            assertEquals(3, sqls.size());
            assertTrue(
                    sqls.stream()
                            .allMatch(
                                    sql ->
                                            sql.contains("cast(trade_date as long)")
                                                    && !sql.contains("timestamp")));
            assertTrue(
                    sqls.getLast().contains("(ts_code = ? AND trade_date = cast(? as TIMESTAMP))"));
        }
    }

    @Test
    void oldPhysicalLayoutAndChangedIdentityAreRejectedBeforeAnySenderIsBorrowed() {
        var input = mock(JdbcTemplate.class);
        when(input.getDataSource()).thenReturn(mock(DataSource.class));
        var qdb = mock(QuestDB.class);
        try (var constructions =
                        mockConstruction(
                                JdbcTemplate.class,
                                withSettings()
                                        .defaultAnswer(
                                                invocation -> {
                                                    if (invocation
                                                            .getMethod()
                                                            .getName()
                                                            .equals("queryForList"))
                                                        return metadata(
                                                                invocation.getArgument(0),
                                                                "timestamp");
                                                    return RETURNS_DEFAULTS.answer(invocation);
                                                }));
                var identity = mockStatic(StaticTargetIdentity.class)) {
            identity.when(
                            () ->
                                    StaticTargetIdentity.identify(
                                            any(JdbcTemplate.class),
                                            eq(TABLE),
                                            eq(7L),
                                            eq("generation-7")))
                    .thenReturn(TARGET);
            var writer =
                    new EtfAdjWritePort(TABLE, TARGET, input, qdb, CnTradeDateLayout.TRADE_DATE_V2);
            assertThrows(IllegalStateException.class, () -> writer.send(List.of(ROW)));
            identity.when(
                            () ->
                                    StaticTargetIdentity.identify(
                                            any(JdbcTemplate.class),
                                            eq(TABLE),
                                            eq(7L),
                                            eq("generation-7")))
                    .thenReturn("static-v2-new-generation");
            assertThrows(IllegalStateException.class, writer::preflight);
            verifyNoInteractions(qdb);
        }
    }

    private static List<Map<String, Object>> metadata(String sql, String dateColumn) {
        if (sql.contains("table_columns"))
            return List.of(
                    Map.of("column", "ts_code", "type", "SYMBOL", "upsertKey", true),
                    Map.of("column", dateColumn, "type", "TIMESTAMP", "upsertKey", true),
                    Map.of("column", "adj_factor", "type", "DOUBLE", "upsertKey", false));
        if (sql.startsWith("SELECT id,"))
            return List.of(Map.of("id", 7, "directoryName", "generation-7"));
        if (sql.contains("designatedTimestamp"))
            return List.of(Map.of("designatedTimestamp", dateColumn, "partitionBy", "YEAR"));
        if (sql.contains("walEnabled"))
            return List.of(
                    Map.of(
                            "walEnabled",
                            true,
                            "table_suspended",
                            false,
                            "wal_pending_row_count",
                            0,
                            "table_txn",
                            7,
                            "wal_txn",
                            7));
        if (sql.contains("wal_tables"))
            return List.of(
                    Map.of(
                            "suspended",
                            false,
                            "writerTxn",
                            7,
                            "sequencerTxn",
                            7,
                            "bufferedTxnSize",
                            0));
        throw new AssertionError(sql);
    }

    private static ResultSet row() throws Exception {
        var row = mock(ResultSet.class);
        when(row.getString("ts_code")).thenReturn(ROW.tsCode());
        when(row.getObject("adj_factor")).thenReturn(ROW.adjFactor());
        when(row.getObject("trade_date_micros"))
                .thenReturn(DAY.atStartOfDay(ZoneOffset.UTC).toEpochSecond() * 1_000_000);
        return row;
    }
}
