package com.zoutrankil.data.l2.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesDataset;
import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

class L2DailyFeaturesLayoutTest {
    private static final String TABLE = "java_d086_l2_daily_features_layout";
    private static final LocalDate DAY = LocalDate.of(1969, 12, 31);

    static L2DailyFeatures row(boolean nulls) {
        var fields = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
        for (var f : L2DailyFeatureField.values()) {
            if (f.identity()) continue;
            fields.put(
                    f,
                    nulls
                            ? null
                            : switch (f.storageType()) {
                                case BOOLEAN -> true;
                                case LONG -> 17L;
                                case DOUBLE -> 1.25;
                                case STRING, SYMBOL -> "来源值";
                                default -> throw new AssertionError(f);
                            });
        }
        return new L2DailyFeatures(DAY, "000001.SZ", fields);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void fullRowMappingContractAndReadSqlStayInTheSelectedLayout(int version) {
        var layout = CnTradeDateLayout.fromVersion(version);
        String date = version == 1 ? "ts" : "trade_date";
        var definition = L2DailyFeaturesDataset.definition(TABLE, layout);
        assertEquals(version, definition.schemaVersion());
        assertEquals(110, definition.columns().size());
        assertEquals(List.of(date, "symbol"), definition.businessKey());
        assertEquals(definition.businessKey(), definition.dedupKey());
        var column = definition.columns().getFirst();
        assertEquals("ts", column.sourceName());
        assertEquals(date, column.logicalName());
        assertEquals(date, column.storageName());
        var mapper = new L2DailyFeaturesMapper();
        for (boolean nulls : new boolean[] {false, true}) {
            var row = row(nulls);
            var values = mapper.values(row, layout);
            assertEquals(110, values.asMap().size());
            assertEquals(row, mapper.fromValues(values, layout));
            assertFalse(values.asMap().containsKey(version == 1 ? "trade_date" : "ts"));
        }
        var reader = new QuestDbBoundedReader(mock(JdbcTemplate.class));
        var query =
                new DatasetReadQuery(
                        L2DailyFeaturesMapper.columns(layout),
                        Map.of(date, DAY),
                        null,
                        null,
                        null,
                        2,
                        null);
        var prepared = reader.prepare(definition, query, null);
        assertTrue(prepared.sql().contains(date));
        assertFalse(prepared.sql().contains(version == 1 ? "trade_date" : " ts"));
        var repository = new L2DailyFeaturesReadRepository(reader, TABLE, layout);
        assertEquals(definition, repository.definition());
    }

    @Test
    void encodingsDoNotBorrowEachOthersDateKeyAndLegacyBytesRemainExact() {
        var mapper = new L2DailyFeaturesMapper();
        var original = L2ManifestDailyTargetContractTest.row(true);
        var row = (L2DailyFeatures) original;
        var legacy = L2DailyFeaturesWritePort.CODEC.canonicalBytes(row);
        var newer =
                L2DailyFeaturesWriteSession.codec(CnTradeDateLayout.TRADE_DATE_V2)
                        .canonicalBytes(row);
        assertTrue(
                new String(legacy, StandardCharsets.UTF_8)
                        .startsWith("{\"ts\":\"1969-12-31\",\"symbol\":\"000001.SZ\""));
        assertArrayEquals(
                legacy,
                new String(newer, StandardCharsets.UTF_8)
                        .replaceFirst("trade_date", "ts")
                        .getBytes(StandardCharsets.UTF_8));
        assertThrows(
                RuntimeException.class,
                () -> mapper.fromValues(mapper.values(row), CnTradeDateLayout.TRADE_DATE_V2));
        assertThrows(
                RuntimeException.class,
                () -> mapper.fromValues(mapper.values(row, CnTradeDateLayout.TRADE_DATE_V2)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void v2JdbcReadbackBindsTheFullKeyAndDecodesEveryField(boolean nulls) throws Exception {
        var row = row(nulls);
        var raw = L2ManifestDailyTargetContractTest.raw(true);
        for (var field : L2DailyFeatureField.values())
            if (!field.identity()) raw.put(field.fieldName(), row.features().get(field));
        var f = new L2ManifestDailyTargetContractTest.Fixture(List.of(raw));
        var writer =
                new L2DailyFeaturesWritePort(
                        TABLE, f.jdbc, mock(QuestDB.class), CnTradeDateLayout.TRADE_DATE_V2);
        assertEquals(2, writer.datasetVersion());
        assertEquals(List.of(row), writer.readback(List.of(row.key())));
        String sql = f.sql.getFirst();
        assertTrue(sql.startsWith("SELECT cast(trade_date as long) AS ts_micros, symbol"));
        assertTrue(
                sql.endsWith(
                        "WHERE trade_date=cast(? as TIMESTAMP) AND symbol IN (?) ORDER BY trade_date,symbol LIMIT 2"));
        verify(f.statements.getFirst()).setLong(1, -86_400_000_000L);
        verify(f.statements.getFirst()).setString(2, "000001.SZ");
        verify(f.statements.getFirst()).setMaxRows(2);
    }

    @Test
    void v2RejectsNonMidnightCarrierInsteadOfTruncatingIt() throws Exception {
        var raw = L2ManifestDailyTargetContractTest.raw(true);
        raw.put("ts_micros", -86_399_999_999L);
        var f = new L2ManifestDailyTargetContractTest.Fixture(List.of(raw));
        var writer =
                new L2DailyFeaturesWritePort(
                        TABLE, f.jdbc, mock(QuestDB.class), CnTradeDateLayout.TRADE_DATE_V2);
        assertThrows(RuntimeException.class, () -> writer.readback(List.of(row(true).key())));
    }

    @Test
    void v2MapperRejectsNonMidnightAndKeepsPlainBusinessDates() {
        var mapper = new L2DailyFeaturesMapper();
        var values =
                new LinkedHashMap<String, Object>(
                        mapper.values(row(true), CnTradeDateLayout.TRADE_DATE_V2).asMap());
        for (Object carrier :
                List.of(
                        Instant.parse("1969-12-31T00:00:00Z"),
                        LocalDateTime.parse("1969-12-31T00:00:00"),
                        "1969-12-31")) {
            values.put("trade_date", carrier);
            assertEquals(
                    row(true),
                    mapper.fromValues(new DatasetValues(values), CnTradeDateLayout.TRADE_DATE_V2));
        }
        values.put("trade_date", Instant.parse("1969-12-31T00:00:00.000001Z"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mapper.fromValues(
                                new DatasetValues(values), CnTradeDateLayout.TRADE_DATE_V2));
    }

    @Test
    void v2IsolatedDdlKeepsAll110ColumnsAndDayWalDedup() throws Exception {
        var ds = mock(DataSource.class);
        var connection = mock(Connection.class);
        var st = mock(Statement.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(st);
        var writer =
                spy(
                        new L2DailyFeaturesWritePort(
                                TABLE,
                                new JdbcTemplate(ds),
                                mock(QuestDB.class),
                                CnTradeDateLayout.TRADE_DATE_V2));
        doNothing().when(writer).preflight();
        writer.createIsolatedTargetIfMissing();
        var sql = ArgumentCaptor.forClass(String.class);
        verify(st).execute(sql.capture());
        assertTrue(sql.getValue().contains("trade_date TIMESTAMP, symbol SYMBOL"));
        assertFalse(sql.getValue().contains("ts TIMESTAMP"));
        assertTrue(
                sql.getValue()
                        .endsWith(
                                "TIMESTAMP(trade_date) PARTITION BY DAY WAL DEDUP UPSERT KEYS(trade_date,symbol)"));
        verify(writer).preflight();
    }

    @Test
    void v2SenderPreservesUtcCarrierAllMetricsAndAckBoundary() throws Exception {
        var ds = mock(DataSource.class);
        var qdb = mock(QuestDB.class);
        var sender = mock(Sender.class, RETURNS_SELF);
        when(qdb.borrowSender()).thenReturn(sender);
        when(sender.flushAndGetSequence()).thenReturn(7L);
        when(sender.awaitAckedFsn(7L, 10000)).thenReturn(true);
        var writer =
                new L2DailyFeaturesWritePort(
                        TABLE, new JdbcTemplate(ds), qdb, CnTradeDateLayout.TRADE_DATE_V2);
        writer.send(List.of(row(false)));
        verify(sender).at(Instant.parse("1969-12-31T00:00:00Z"));
        verify(sender).symbol("symbol", "000001.SZ");
        for (var f : L2DailyFeatureField.values()) {
            if (f.identity()) continue;
            switch (f.storageType()) {
                case BOOLEAN -> verify(sender).boolColumn(f.fieldName(), true);
                case LONG -> verify(sender).longColumn(f.fieldName(), 17L);
                case DOUBLE -> verify(sender).doubleColumn(f.fieldName(), 1.25);
                case STRING -> verify(sender).stringColumn(f.fieldName(), "来源值");
                default -> throw new AssertionError(f);
            }
        }
        verify(sender).awaitAckedFsn(7L, 10000);
        verify(sender).close();
        verifyNoInteractions(ds);
    }

    @Test
    void v2DateEnumerationAndCountUseTheSamePhysicalColumn() throws Exception {
        var f = new L2ManifestDailyTargetContractTest.Fixture(List.of(Map.of("count", 2L)));
        var statement = mock(Statement.class);
        var rs = mock(ResultSet.class);
        when(f.connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        var writer =
                new L2DailyFeaturesWritePort(
                        TABLE, f.jdbc, mock(QuestDB.class), CnTradeDateLayout.TRADE_DATE_V2);
        assertTrue(writer.readExistingDates().isEmpty());
        verify(statement)
                .executeQuery(
                        "SELECT trade_date FROM \""
                                + TABLE
                                + "\" GROUP BY trade_date ORDER BY trade_date LIMIT 32");
        // COUNT 的 JDBC 标量回读由独立代理返回，只检查原有界日期绑定。
        when(f.connection.prepareStatement(anyString()))
                .thenAnswer(
                        call -> {
                            var st = mock(PreparedStatement.class);
                            var result = mock(ResultSet.class);
                            when(result.next()).thenReturn(true, false);
                            when(result.getObject(1, Long.class)).thenReturn(2L);
                            when(result.getObject(1)).thenReturn(2L);
                            var metadata = mock(ResultSetMetaData.class);
                            when(metadata.getColumnCount()).thenReturn(1);
                            when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);
                            when(result.getMetaData()).thenReturn(metadata);
                            when(result.getLong(1)).thenReturn(2L);
                            when(st.executeQuery()).thenReturn(result);
                            return st;
                        });
        assertEquals(2, writer.countRows(DAY));
        verify(f.connection)
                .prepareStatement(
                        "SELECT count() FROM \""
                                + TABLE
                                + "\" WHERE trade_date=cast(? as TIMESTAMP)");
    }
}
