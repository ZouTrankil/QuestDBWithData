package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.port.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real JdbcTemplate SQL/bind/decode paths; no physical database or sender connection. */
@SuppressWarnings({"rawtypes", "unchecked"})
class L2ManifestDailyTargetContractTest {
    static final LocalDate DAY = LocalDate.of(1969, 12, 31);
    static final long MICROS = -86_400_000_000L;
    static String table(boolean daily) { return daily ? "java_d086_l2_daily_features_contract" : "java_d085_l2_dataset_manifest_contract"; }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void identityUsesExactMetadataFrameAndEachFactoryCallCreatesAnIndependentSession(boolean daily) throws Exception {
        var ds = mock(DataSource.class); var jdbc = mock(JdbcTemplate.class); when(jdbc.getDataSource()).thenReturn(ds);
        when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table(daily)))
                .thenReturn(List.of(Map.of("id", 37L, "directoryName", "partition~37")));
        var qdb = mock(QuestDB.class); var properties = properties();
        Object target = target(daily, jdbc, qdb, properties);
        String expected = "questdb-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("db-host:18812:18813:research:" + table(daily) + ":37:partition~37").getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, identity(daily, target));
        VerifiedWriteSession first = writer(daily, target), second = writer(daily, target);
        assertNotSame(first, second); assertSame(first.codec(), second.codec());
        assertSame(daily ? L2DailyFeaturesWritePort.CODEC : L2DatasetManifestWritePort.CODEC, first.codec());
        var field = first.getClass().getDeclaredField("jdbc"); field.setAccessible(true);
        assertEquals(20, ((JdbcTemplate)field.get(first)).getQueryTimeout());
        assertNotSame(jdbc, field.get(first)); verifyNoInteractions(ds, qdb);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void absentOrAmbiguousIdentityAndFormalTargetsFailClosed(boolean daily) throws Exception {
        var jdbc = mock(JdbcTemplate.class); var qdb = mock(QuestDB.class); var target = target(daily, jdbc, qdb, properties());
        when(jdbc.queryForList(anyString(), eq(table(daily)))).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> identity(daily, target));
        when(jdbc.queryForList(anyString(), eq(table(daily)))).thenReturn(List.of(Map.of("id", 1L, "directoryName", "a"), Map.of("id", 2L, "directoryName", "b")));
        assertThrows(IllegalStateException.class, () -> identity(daily, target));
        when(jdbc.queryForList(anyString(), eq(table(daily)))).thenReturn(List.of(Map.of("id", "1", "directoryName", "a")));
        assertThrows(IllegalStateException.class, () -> identity(daily, target));
        assertThrows(IllegalStateException.class, () -> {
            if (daily) new L2DailyFeaturesWritePort("l2_daily_features", jdbc, qdb);
            else new L2DatasetManifestWritePort("l2_dataset_manifest", jdbc, qdb);
        }); verifyNoInteractions(qdb);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void readbackKeepsCompleteKeysNegativeEpochNullableValuesAndTimeouts(boolean daily) throws Exception {
        var raw = raw(daily); var fixture = new Fixture(List.of(raw));
        var session = session(daily, fixture.jdbc, mock(QuestDB.class)); Object row = row(daily);
        var result = session.readback(List.of(session.codec().key(row)));
        assertEquals(List.of(row), result);
        String sql = fixture.sql.getFirst();
        assertTrue(sql.startsWith("SELECT " + (daily ? "cast(ts as long) AS ts_micros, symbol" : "trade_date, symbol")));
        assertTrue(sql.endsWith(daily ? "WHERE (ts=cast(? as TIMESTAMP) AND symbol=?) ORDER BY ts,symbol LIMIT 2"
                : "WHERE (trade_date=? AND symbol=? AND batch_id=? AND trade_date_ts=cast(? as TIMESTAMP)) ORDER BY trade_date,symbol,batch_id LIMIT 2"));
        var st = fixture.statements.getFirst(); verify(st, times(2)).setQueryTimeout(20); verify(st).setMaxRows(2); verify(st).setFetchSize(2);
        if (daily) { verify(st).setLong(1, MICROS); verify(st).setString(2, "000001.SZ"); }
        else { verify(st).setString(1, "19691231"); verify(st).setString(2, "000001.SZ"); verify(st).setLong(3, 7L); verify(st).setLong(4, MICROS); }
        verify(st).close(); verify(fixture.connection).close();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void malformedTimestampAndDuplicateVerificationKeysAreRejected(boolean daily) throws Exception {
        var data = raw(daily); data.put(daily ? "ts_micros" : "trade_date_ts_micros", null);
        var fixture = new Fixture(List.of(data)); var session = session(daily, fixture.jdbc, mock(QuestDB.class));
        Object key = session.codec().key(row(daily));
        assertThrows(RuntimeException.class, () -> session.readback(List.of(key)));
        clearInvocations(fixture.ds);
        assertThrows(IllegalArgumentException.class, () -> session.readback(List.of(key, key)));
        assertThrows(IllegalArgumentException.class, () -> session.readback(List.of()));
        assertThrows(IllegalArgumentException.class, () -> session.readback(Collections.nCopies(201, key)));
        verifyNoInteractions(fixture.ds);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sendKeepsAckBoundaryAndReleasesOnlyItsOwnSenderState(boolean daily) throws Exception {
        var qdb = mock(QuestDB.class); var sender = mock(Sender.class, RETURNS_SELF);
        when(qdb.borrowSender()).thenReturn(sender); when(sender.flushAndGetSequence()).thenReturn(11L); when(sender.awaitAckedFsn(11L, 10_000)).thenReturn(true);
        var ds = mock(DataSource.class); var session = session(daily, new JdbcTemplate(ds), qdb);
        doAnswer(i -> { assertFalse(session.uncertainSenderStopped()); return 11L; }).when(sender).flushAndGetSequence();
        session.send(List.of(row(daily))); assertTrue(session.uncertainSenderStopped());
        var order = inOrder(sender); order.verify(sender).table(table(daily)); order.verify(sender).at(Instant.parse("1969-12-31T00:00:00Z"));
        order.verify(sender).flushAndGetSequence(); order.verify(sender).awaitAckedFsn(11L, 10_000); order.verify(sender).close();
        verify(sender).symbol("symbol", "000001.SZ");
        if (!daily) { verify(sender).longColumn("batch_id", 7L); verify(sender).stringColumn("trade_date", "19691231"); }
        verifyNoInteractions(ds);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unknownAckPreservesCloseFailureAsSuppressedAndResetsSenderState(boolean daily) throws Exception {
        var qdb = mock(QuestDB.class); var sender = mock(Sender.class, RETURNS_SELF);
        when(qdb.borrowSender()).thenReturn(sender); when(sender.flushAndGetSequence()).thenReturn(11L);
        var close = new IllegalStateException("close failed"); doThrow(close).when(sender).close();
        var session = session(daily, new JdbcTemplate(mock(DataSource.class)), qdb);
        var failure = assertThrows(IllegalStateException.class, () -> session.send(List.of(row(daily))));
        assertTrue(failure.getMessage().contains("QWP acknowledgement unknown")); assertArrayEquals(new Throwable[]{close}, failure.getSuppressed());
        assertTrue(session.uncertainSenderStopped()); verify(sender).awaitAckedFsn(11L, 10_000);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invalidBatchesDoNotBorrowSenderAndOriginalDateScanLimitsRemainDistinct(boolean daily) throws Exception {
        var fixture = new Fixture(List.of()); var qdb = mock(QuestDB.class); var session = session(daily, fixture.jdbc, qdb);
        assertThrows(IllegalArgumentException.class, () -> session.send(List.of()));
        assertThrows(IllegalArgumentException.class, () -> session.send(Collections.nCopies(201, row(daily)))); verifyNoInteractions(qdb);
        var statement = mock(Statement.class); var rs = mock(ResultSet.class);
        when(fixture.connection.createStatement()).thenReturn(statement); when(statement.executeQuery(anyString())).thenReturn(rs);
        if (daily) assertTrue(((L2DailyFeaturesWriteSession)session).readExistingDates().isEmpty());
        else assertTrue(((L2DatasetManifestWriteSession)session).readExistingDates().isEmpty());
        verify(statement).executeQuery("SELECT " + (daily ? "ts" : "trade_date") + " FROM \"" + table(daily) + "\" GROUP BY "
                + (daily ? "ts ORDER BY ts LIMIT 32" : "trade_date ORDER BY trade_date LIMIT 33"));
    }

    static QuestDbProperties properties() { var p = new QuestDbProperties(); p.setHost("db-host"); p.setPgPort(18812); p.setQwpPort(18813); p.setDatabase("research"); return p; }
    static Object target(boolean daily, JdbcTemplate jdbc, QuestDB qdb, QuestDbProperties p) {
        return daily ? new QuestDbL2DailyFeaturesTarget(table(true), jdbc, qdb, p) : new QuestDbL2DatasetManifestTarget(table(false), jdbc, qdb, p);
    }
    static String identity(boolean daily, Object target) throws Exception { return daily ? ((L2DailyFeaturesTarget)target).targetId() : ((L2DatasetManifestTarget)target).targetId(); }
    static VerifiedWriteSession writer(boolean daily, Object target) { return daily ? ((L2DailyFeaturesTarget)target).newWriter() : ((L2DatasetManifestTarget)target).newWriter(); }
    static VerifiedWriteSession session(boolean daily, JdbcTemplate jdbc, QuestDB qdb) { return daily ? new L2DailyFeaturesWritePort(table(true), jdbc, qdb) : new L2DatasetManifestWritePort(table(false), jdbc, qdb); }
    static Object row(boolean daily) {
        if (daily) { var fields = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
            for (var field : L2DailyFeatureField.values()) if (!field.identity()) fields.put(field, null);
            return new L2DailyFeatures(DAY, "000001.SZ", fields); }
        return new L2DatasetManifest(DAY, "000001.SZ", null, null, null, null, null, null, null, null, null, null, null, null, 7L, DAY);
    }
    static Map<String, Object> raw(boolean daily) {
        var values = new HashMap<String, Object>(); values.put("symbol", "000001.SZ");
        values.put(daily ? "ts_micros" : "trade_date_ts_micros", MICROS);
        if (!daily) { values.put("trade_date", "19691231"); values.put("batch_id", 7L); }
        return values;
    }
    static final class Fixture {
        final DataSource ds = mock(DataSource.class); final JdbcTemplate jdbc = new JdbcTemplate(ds); final Connection connection = mock(Connection.class);
        final List<String> sql = new ArrayList<>(); final List<PreparedStatement> statements = new ArrayList<>();
        Fixture(List<Map<String, Object>> rows) throws Exception {
            when(ds.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                sql.add(call.getArgument(0)); var st = mock(PreparedStatement.class); statements.add(st); var rs = mock(ResultSet.class);
                int[] at = {-1}; boolean[] wasNull = {false}; when(rs.next()).thenAnswer(i -> ++at[0] < rows.size());
                when(rs.getObject(anyString())).thenAnswer(i -> rows.get(at[0]).get(i.getArgument(0)));
                when(rs.getObject(anyString(), any(Class.class))).thenAnswer(i -> rows.get(at[0]).get(i.getArgument(0)));
                when(rs.getString(anyString())).thenAnswer(i -> (String) rows.get(at[0]).get(i.getArgument(0)));
                when(rs.getLong(anyString())).thenAnswer(i -> { Object v = rows.get(at[0]).get(i.getArgument(0)); wasNull[0] = v == null; return v == null ? 0L : ((Number)v).longValue(); });
                when(rs.wasNull()).thenAnswer(i -> wasNull[0]); when(st.executeQuery()).thenReturn(rs); return st;
            });
        }
    }
}
