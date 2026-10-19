package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.temporal.TemporalValues;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.zoutrankil.data.l2.application.L2EventAndT0Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class L2EventAndT0TargetContractTest {
    @ParameterizedTest @EnumSource(Family.class)
    void constructorIsLazyAndEveryWriterOwnsItsSenderState(Family family) throws Exception {
        var data=mock(DataSource.class);var jdbc=new JdbcTemplate(data);var questdb=mock(QuestDB.class);
        Object blank=family.target(null,jdbc,questdb,new QuestDbProperties());
        assertEquals("",invoke(blank,"tableName"));verifyNoInteractions(data,questdb);
        Object target=family.target("  "+family.table()+"  ",jdbc,questdb,new QuestDbProperties());
        var first=(VerifiedWriteSession<?,?>)invoke(target,"newWriter");
        var second=(VerifiedWriteSession<?,?>)invoke(target,"newWriter");
        assertNotSame(first,second);assertSame(first.codec(),second.codec());assertSame(family.codec(),first.codec());
        assertEquals(family.table(),invoke(first,"tableName"));
        var field=first.getClass().getDeclaredField("jdbc");field.setAccessible(true);
        var clone=(JdbcTemplate)field.get(first);assertNotSame(jdbc,clone);assertEquals(20,clone.getQueryTimeout());assertEquals(-1,jdbc.getQueryTimeout());
        var state=first.getClass().getDeclaredField("senderActive");state.setAccessible(true);
        assertNotSame(state.get(first),state.get(second));verifyNoInteractions(data,questdb);
        assertThrows(IllegalArgumentException.class,()->invoke(blank,"newWriter"));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void identityKeepsExactMetadataSqlAndColonFrameWithoutNormalization(Family family) throws Exception {
        var jdbc=mock(JdbcTemplate.class);var questdb=mock(QuestDB.class);var properties=new QuestDbProperties();
        properties.setHost("Host.EXAMPLE");properties.setPgPort(18812);properties.setQwpPort(19000);properties.setDatabase("custom");
        when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",family.table())).thenReturn(List.of(Map.of("id",17L,"directoryName","physical~17")));
        Object target=family.target(family.table(),jdbc,questdb,properties);verifyNoInteractions(jdbc,questdb);
        String frame="Host.EXAMPLE:18812:19000:custom:"+family.table()+":17:physical~17";
        assertEquals("questdb-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame.getBytes(StandardCharsets.UTF_8))),invoke(target,"targetId"));
        verify(jdbc).queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",family.table());verifyNoMoreInteractions(jdbc);verifyNoInteractions(questdb);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void malformedIdentityAndExplicitWrongTableRejectBeforeWrites(Family family) throws Exception {
        var jdbc=mock(JdbcTemplate.class);var questdb=mock(QuestDB.class);
        Object target=family.target(family.table(),jdbc,questdb,new QuestDbProperties());
        for(List<Map<String,Object>> rows:List.<List<Map<String,Object>>>of(List.of(),List.of(Map.of("id","17","directoryName","x")),List.of(Map.of("id",17L)),List.of(Map.of("id",17L,"directoryName","a"),Map.of("id",18L,"directoryName","b")))) {
            when(jdbc.queryForList(anyString(),eq(family.table()))).thenReturn(rows);
            var failure=assertThrows(IllegalStateException.class,()->invoke(target,"targetId"));
            assertEquals("Exact "+family.code+" isolated QuestDB table identity required",failure.getMessage());
        }
        clearInvocations(jdbc);
        assertThrows(IllegalStateException.class,()->call(target.getClass(),target,"createIsolatedTarget",new Class<?>[]{String.class},family.dataset));
        verifyNoInteractions(jdbc,questdb);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void readbackUsesCompleteKeyMicrosAndBoundedStatement(Family family) throws Exception {
        var data=mock(DataSource.class);var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);var result=mock(ResultSet.class);
        when(data.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(false);
        var writer=family.writer(new JdbcTemplate(data),mock(QuestDB.class));Object row=family.row(family.input());Object key=family.codec().key(row);
        assertEquals(List.of(),writer.readback(List.of(key)));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
        String keyClause=family==Family.EVENT?"(symbol=? AND minute=cast(? as TIMESTAMP) AND event_type=?)":"(symbol=? AND minute=cast(? as TIMESTAMP))";
        assertTrue(sql.getValue().contains("cast(minute as long) AS minute_micros"));assertTrue(sql.getValue().contains("WHERE "+keyClause));
        assertTrue(sql.getValue().endsWith("ORDER BY symbol,minute"+(family==Family.EVENT?",event_type":"")+" LIMIT 2"));
        var order=inOrder(statement);order.verify(statement).setQueryTimeout(20);order.verify(statement).setMaxRows(2);order.verify(statement).setFetchSize(2);order.verify(statement).setString(1,SYMBOL);
        order.verify(statement).setLong(2,TemporalValues.epochValue(DAY.atTime(9,15).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),TemporalValues.EpochUnit.MICROS));
        if(family==Family.EVENT)order.verify(statement).setString(3,family.input().path("event_type").asText());
        order.verify(statement).executeQuery();verify(result).close();verify(statement).close();verify(connection).close();
    }

    @ParameterizedTest @EnumSource(Family.class)
    void invalidBatchAndDuplicateReadbackRejectBeforeBorrowing(Family family) throws Exception {
        var data=mock(DataSource.class);var questdb=mock(QuestDB.class);var writer=family.writer(new JdbcTemplate(data),questdb);Object row=family.row(family.input());Object key=family.codec().key(row);
        assertThrows(IllegalArgumentException.class,()->writer.send(List.of()));
        assertThrows(IllegalArgumentException.class,()->writer.send(Collections.nCopies(201,row)));
        assertThrows(IllegalArgumentException.class,()->writer.send(List.of(row,row)));
        assertThrows(IllegalArgumentException.class,()->writer.readback(List.of(key,key)));
        verifyNoInteractions(data,questdb);assertTrue(writer.uncertainSenderStopped());
    }

    @ParameterizedTest @EnumSource(Family.class)
    void sendKeepsOrderedAckCloseAndNullOmission(Family family) throws Exception {
        var questdb=mock(QuestDB.class);var sender=mock(Sender.class,RETURNS_SELF);when(questdb.borrowSender()).thenReturn(sender);when(sender.flushAndGetSequence()).thenReturn(23L);when(sender.awaitAckedFsn(23L,10_000L)).thenReturn(true);
        var writer=family.writer(new JdbcTemplate(mock(DataSource.class)),questdb);Object row=family.row(family.input());
        doAnswer(call->{assertFalse(writer.uncertainSenderStopped());return sender;}).when(sender).table(family.table());
        writer.send(List.of(row));assertTrue(writer.uncertainSenderStopped());
        var order=inOrder(sender);order.verify(sender).table(family.table());order.verify(sender).symbol("symbol",SYMBOL);order.verify(sender).stringColumn("trade_date","20260921");order.verify(sender).stringColumn("market","SZ");order.verify(sender).stringColumn("board","MAIN");
        if(family==Family.EVENT)order.verify(sender).stringColumn("event_type",family.input().path("event_type").asText());
        order.verify(sender).at(DAY.atTime(9,15).atZone(ZoneId.of("Asia/Shanghai")).toInstant());order.verify(sender).flushAndGetSequence();order.verify(sender).awaitAckedFsn(23L,10_000L);order.verify(sender).close();
        String nullable=family==Family.EVENT?"bid_depth_1":"sell_first_gross_alpha_30m";assertTrue(family.input().path(nullable).isNull());verify(sender,never()).doubleColumn(eq(nullable),anyDouble());
    }

    @ParameterizedTest @EnumSource(Family.class)
    void uncertainAckPreservesCauseSuppressionAndResetsOnlyItsSession(Family family) throws Exception {
        var questdb=mock(QuestDB.class);var sender=mock(Sender.class,RETURNS_SELF);when(questdb.borrowSender()).thenReturn(sender);when(sender.flushAndGetSequence()).thenReturn(-1L);
        var closeFailure=new IllegalStateException("close-failed");doThrow(closeFailure).when(sender).close();
        var writer=family.writer(new JdbcTemplate(mock(DataSource.class)),questdb);var other=family.writer(new JdbcTemplate(mock(DataSource.class)),questdb);Object row=family.row(family.input());
        var failure=assertThrows(IllegalStateException.class,()->writer.send(List.of(row)));
        assertEquals(family.code+" QWP acknowledgement is unknown; reconcile the complete key batch before replay",failure.getMessage());assertArrayEquals(new Throwable[]{closeFailure},failure.getSuppressed());
        verify(sender,never()).awaitAckedFsn(anyLong(),anyLong());assertTrue(writer.uncertainSenderStopped());assertTrue(other.uncertainSenderStopped());
    }

    @ParameterizedTest @EnumSource(Family.class)
    void physicalReadbackTypeFailureKeepsSqlExceptionCause(Family family) throws Exception {
        var writer=family.writer(new JdbcTemplate(mock(DataSource.class)),mock(QuestDB.class));var result=mock(ResultSet.class);
        when(result.getLong("minute_micros")).thenReturn(1L);when(result.wasNull()).thenReturn(false);
        String field=family==Family.EVENT?"open":"executability_label";when(result.getObject(field)).thenReturn("wrong-type");
        var failure=assertThrows(SQLException.class,()->call(writer.getClass(),writer,"readValues",new Class<?>[]{ResultSet.class},result));
        assertEquals(family.code+" readback type mismatch: "+field,failure.getMessage());assertInstanceOf(IllegalArgumentException.class,failure.getCause());
    }
    private static Object invoke(Object object,String name) throws Exception { return call(object.getClass(),object,name,new Class<?>[0]); }
}
