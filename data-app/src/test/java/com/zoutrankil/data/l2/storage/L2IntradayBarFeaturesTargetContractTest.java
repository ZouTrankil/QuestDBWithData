package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.port.L2IntradayBarFeaturesWriteSession;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Physical D087 contracts; JDBC and QWP are simulated and no server is contacted. */
class L2IntradayBarFeaturesTargetContractTest {
    static final String TABLE="java_d087_l2_intraday_bar_features_contract";
    static final String ID_SQL="SELECT id,directoryName FROM tables() WHERE table_name=?";
    static final LocalDate DAY=LocalDate.of(1969,12,31);

    @Test void constructorDefersAdmissionAndEveryWriterIsIndependent()throws Exception{
        var jdbc=mock(JdbcTemplate.class);var qdb=mock(QuestDB.class);var properties=new QuestDbProperties();
        var blank=new QuestDbL2IntradayBarFeaturesTarget(null,jdbc,qdb,properties);
        assertEquals("",blank.tableName());verifyNoInteractions(jdbc,qdb);
        assertThrows(IllegalArgumentException.class,blank::targetId);assertThrows(IllegalArgumentException.class,blank::newWriter);
        verifyNoInteractions(jdbc,qdb);
        var ds=mock(DataSource.class);when(jdbc.getDataSource()).thenReturn(ds);
        var target=new QuestDbL2IntradayBarFeaturesTarget(" "+TABLE+" ",jdbc,qdb,properties);
        var a=target.newWriter();var b=target.newWriter();assertNotSame(a,b);assertSame(a.codec(),b.codec());
        assertSame(L2IntradayBarFeaturesWriteSession.CODEC,a.codec());assertTrue(a.uncertainSenderStopped());assertTrue(b.uncertainSenderStopped());
        var field=a.getClass().getDeclaredField("jdbc");field.setAccessible(true);var aj=(JdbcTemplate)field.get(a);var bj=(JdbcTemplate)field.get(b);
        assertNotSame(jdbc,aj);assertNotSame(aj,bj);assertEquals(20,aj.getQueryTimeout());assertEquals(-1,aj.getMaxRows());assertEquals(-1,aj.getFetchSize());
        verifyNoInteractions(ds,qdb);
    }

    @Test void identityUsesOriginalColonFrameAndAcceptsAnyNonnullDirectoryValue()throws Exception{
        var jdbc=mock(JdbcTemplate.class);var qdb=mock(QuestDB.class);var p=new QuestDbProperties();
        p.setHost("HOST.example");p.setPgPort(18812);p.setQwpPort(19000);p.setDatabase("fixture");
        var target=new QuestDbL2IntradayBarFeaturesTarget(TABLE,jdbc,qdb,p);
        when(jdbc.queryForList(ID_SQL,TABLE)).thenReturn(List.of(Map.of("id",7L,"directoryName",19L)));
        String frame="HOST.example:18812:19000:fixture:"+TABLE+":7:19";
        assertEquals("questdb-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame.getBytes(StandardCharsets.UTF_8))),target.targetId());
        verify(jdbc).queryForList(ID_SQL,TABLE);verifyNoInteractions(qdb);
        when(jdbc.queryForList(ID_SQL,TABLE)).thenReturn(List.of(Map.of("id","7","directoryName","g")));
        assertEquals("Exact D087 isolated QuestDB table identity required",assertThrows(IllegalStateException.class,target::targetId).getMessage());
        when(jdbc.queryForList(ID_SQL,TABLE)).thenReturn(List.of(Map.of("id",7L)));
        assertThrows(IllegalStateException.class,target::targetId);
        when(jdbc.queryForList(ID_SQL,TABLE)).thenReturn(List.of());assertThrows(IllegalStateException.class,target::targetId);
    }

    @Test void createKeepsExactDdlThenPreflightAndUsesItsExplicitTable(){
        try(var h=new Harness();var checks=mockStatic(QuestDbWriteChecks.class)){
            var target=new QuestDbL2IntradayBarFeaturesTarget("",h.original,h.qdb,new QuestDbProperties());
            String selected=TABLE+"_new";target.createIsolatedTarget(selected);var created=h.copies.constructed().get(1);
            String columns=String.join(", ",Arrays.stream(L2IntradayBarFeatureField.values()).map(f->f.fieldName()+" "+f.storageType().name()).toList());
            verify(created).execute("CREATE TABLE IF NOT EXISTS \""+selected+"\" ("+columns+") TIMESTAMP(minute) PARTITION BY DAY WAL DEDUP UPSERT KEYS(symbol,minute)");
            checks.verify(()->QuestDbWriteChecks.preflight(created,selected,L2IntradayBarFeaturesDataset.DEFINITION));
            verifyNoInteractions(h.qdb);assertThrows(IllegalStateException.class,()->target.createIsolatedTarget("l2_intraday_bar_features"));
        }
    }

    @Test void sendRejectsInvalidBatchesBeforeBorrowAndDoesNotPerformAnExtraPreflight()throws Exception{
        try(var h=new Harness();var checks=mockStatic(QuestDbWriteChecks.class)){
            assertThrows(IllegalArgumentException.class,()->h.writer.send(null));assertThrows(IllegalArgumentException.class,()->h.writer.send(List.of()));
            assertThrows(IllegalArgumentException.class,()->h.writer.send(Collections.nCopies(201,row(0))));
            assertThrows(IllegalArgumentException.class,()->h.writer.send(List.of(row(0),row(0))));
            verifyNoInteractions(h.qdb,h.sender);assertTrue(h.writer.uncertainSenderStopped());
            h.ack();h.writer.send(List.of(row(0)));checks.verifyNoInteractions();
            verify(h.sender).symbol("symbol","000001.SZ");verify(h.sender).stringColumn("trade_date","19691231");
            verify(h.sender).longColumn("tick_count",9007199254740993L);verify(h.sender).doubleColumn("volume",-0.0);
            verify(h.sender,never()).doubleColumn(eq("open"),anyDouble());verify(h.sender).at(row(0).minute());
            var order=inOrder(h.sender);order.verify(h.sender).flushAndGetSequence();order.verify(h.sender).awaitAckedFsn(17L,10000L);order.verify(h.sender).close();
            assertTrue(h.writer.uncertainSenderStopped());
        }
    }

    @Test void transportBudgetRejectsDistinctRowsBeforeBorrow(){
        try(var h=new Harness()){
            var rows=new ArrayList<L2IntradayBarFeatures>();for(int i=0;i<200;i++)rows.add(row(i));
            assertThrows(IllegalArgumentException.class,()->h.writer.send(rows));verifyNoInteractions(h.qdb,h.sender);
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void negativeSequenceOrUnackedSequenceStillClosesAndResetsSession(boolean negative)throws Exception{
        try(var h=new Harness()){
            when(h.sender.flushAndGetSequence()).thenReturn(negative?-1L:17L);
            assertEquals("D087 QWP acknowledgement is unknown; reconcile the complete key batch before replay",assertThrows(IllegalStateException.class,()->h.writer.send(List.of(row(0)))).getMessage());
            if(negative)verify(h.sender,never()).awaitAckedFsn(anyLong(),anyLong());else verify(h.sender).awaitAckedFsn(17L,10000L);
            verify(h.sender).close();assertTrue(h.writer.uncertainSenderStopped());
        }
    }

    @Test void flushFailureRetainsCloseSuppressionAndRejectsReentrantSend()throws Exception{
        try(var h=new Harness()){
            when(h.qdb.borrowSender()).thenAnswer(a->{assertFalse(h.writer.uncertainSenderStopped());
                assertEquals("D087 writer is already active",assertThrows(IllegalStateException.class,()->h.writer.send(List.of(row(1)))).getMessage());return h.sender;});
            var flush=new IllegalStateException("flush");var close=new IllegalStateException("close");
            when(h.sender.flushAndGetSequence()).thenThrow(flush);doThrow(close).when(h.sender).close();
            assertSame(flush,assertThrows(IllegalStateException.class,()->h.writer.send(List.of(row(0)))));
            assertArrayEquals(new Throwable[]{close},flush.getSuppressed());assertTrue(h.writer.uncertainSenderStopped());verify(h.qdb).borrowSender();
        }
    }

    @Test void preflightAndWalRemainSeparatePhysicalChecks(){
        try(var h=new Harness();var checks=mockStatic(QuestDbWriteChecks.class)){
            var fail=new IllegalStateException("schema");checks.when(()->QuestDbWriteChecks.preflight(h.jdbc,TABLE,L2IntradayBarFeaturesDataset.DEFINITION)).thenThrow(fail);
            assertSame(fail,assertThrows(IllegalStateException.class,h.writer::preflight));verifyNoInteractions(h.qdb,h.sender);
            checks.when(()->QuestDbWriteChecks.walSettled(h.jdbc,TABLE)).thenReturn(true);assertTrue(h.writer.walSettled());
        }
    }

    @Test void countAndFrontierKeepOriginalLimitsAndShanghaiMicros(){
        try(var h=new Harness()){
            String count="SELECT count() FROM \""+TABLE+"\"";
            when(h.jdbc.queryForObject(count,Long.class)).thenReturn(null,300000L,300001L,-1L);
            assertEquals(0,h.writer.rowCount());assertEquals(300000,h.writer.rowCount());assertThrows(IllegalStateException.class,h.writer::rowCount);assertEquals(-1,h.writer.rowCount());
            String latest="SELECT cast(max(minute) as long) AS minute_micros FROM \""+TABLE+"\"";
            when(h.jdbc.queryForList(latest)).thenReturn(List.of(),List.of(Collections.singletonMap("minute_micros",null)),List.of(Map.of("minute_micros",-1L)),List.of(Map.of("minute_micros","1")));
            assertNull(h.writer.readLatestTradeDate());assertNull(h.writer.readLatestTradeDate());assertEquals(LocalDate.of(1970,1,1),h.writer.readLatestTradeDate());assertThrows(IllegalStateException.class,h.writer::readLatestTradeDate);
            String dateCount=count+" WHERE minute >= cast(? as TIMESTAMP) AND minute < cast(? as TIMESTAMP)";
            long from=DAY.atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond()*1_000_000L,to=from+86400_000000L;
            when(h.jdbc.queryForObject(dateCount,Long.class,from,to)).thenReturn(7L,null,2147483648L);
            assertEquals(7,h.writer.countRows(DAY));assertEquals(0,h.writer.countRows(DAY));assertThrows(IllegalStateException.class,()->h.writer.countRows(DAY));
        }
    }

    @Test void readbackUsesRealJdbcTemplateWithExactPredicateBoundsMicrosAndTypedNulls()throws Exception{
        var ds=mock(DataSource.class);var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);var rs=mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true,false);stubRow(rs,row(0));
        var writer=new L2IntradayBarFeaturesWritePort(TABLE,new JdbcTemplate(ds),mock(QuestDB.class));
        var keys=List.of(row(1).key(),row(0).key());assertEquals(List.of(row(0)),writer.readback(keys));
        String projection=String.join(", ",Arrays.stream(L2IntradayBarFeatureField.values()).map(f->f==L2IntradayBarFeatureField.MINUTE?"cast(minute as long) AS minute_micros":f.fieldName()).toList());
        verify(connection).prepareStatement("SELECT "+projection+" FROM \""+TABLE+"\" WHERE (symbol=? AND minute=cast(? as TIMESTAMP)) OR (symbol=? AND minute=cast(? as TIMESTAMP)) ORDER BY symbol,minute LIMIT 3");
        verify(statement,atLeastOnce()).setQueryTimeout(20);verify(statement).setMaxRows(3);verify(statement).setFetchSize(3);
        verify(statement).setString(1,"000001.SZ");verify(statement).setLong(2,micros(row(1).minute()));verify(statement).setString(3,"000001.SZ");verify(statement).setLong(4,micros(row(0).minute()));
        verify(rs).close();verify(statement).close();verify(connection).close();
    }

    @ParameterizedTest @ValueSource(strings={"minute","long","required"})
    void readbackRetainsSqlExceptionTypeChecksBeforeMapping(String broken)throws Exception{
        var original=mock(JdbcTemplate.class);when(original.getDataSource()).thenReturn(mock(DataSource.class));
        try(var copies=mockConstruction(JdbcTemplate.class)){
            var writer=new L2IntradayBarFeaturesWritePort(TABLE,original,mock(QuestDB.class));var jdbc=copies.constructed().getFirst();var rs=mock(ResultSet.class);stubRow(rs,row(0));
            if(broken.equals("minute"))when(rs.wasNull()).thenReturn(true);
            if(broken.equals("long"))when(rs.getObject("tick_count")).thenReturn(1.5);
            if(broken.equals("required"))when(rs.getObject("amount")).thenReturn(null);
            when(jdbc.query(any(org.springframework.jdbc.core.PreparedStatementCreator.class),any(org.springframework.jdbc.core.RowMapper.class)))
                .thenAnswer(a->List.of(((org.springframework.jdbc.core.RowMapper<?>)a.getArgument(1)).mapRow(rs,0)));
            Throwable failure=assertThrows(Throwable.class,()->writer.readback(List.of(row(0).key())));
            if(broken.equals("required"))assertInstanceOf(IllegalArgumentException.class,failure);else assertInstanceOf(SQLException.class,failure);
            assertThrows(IllegalArgumentException.class,()->writer.readback(List.of(row(0).key(),row(0).key())));
        }
    }

    static long micros(Instant value){return Math.addExact(Math.multiplyExact(value.getEpochSecond(),1_000_000L),value.getNano()/1000);}
    static L2IntradayBarFeatures row(int offset){
        var fields=new EnumMap<L2IntradayBarFeatureField,Object>(L2IntradayBarFeatureField.class);
        for(var field:L2IntradayBarFeatureField.values())if(field.metric())fields.put(field,null);
        fields.put(L2IntradayBarFeatureField.VOLUME,-0.0);fields.put(L2IntradayBarFeatureField.AMOUNT,2.5);
        fields.put(L2IntradayBarFeatureField.TICK_COUNT,9007199254740993L);fields.put(L2IntradayBarFeatureField.HAS_TRADE_1M,1L);
        return new L2IntradayBarFeatures(DAY,"000001.SZ","SZ","MAIN",DAY.atTime(9,15).atZone(ZoneId.of("Asia/Shanghai")).toInstant().plusSeconds(offset*60L),fields);
    }
    static void stubRow(ResultSet rs,L2IntradayBarFeatures row)throws Exception{
        when(rs.getLong("minute_micros")).thenReturn(micros(row.minute()));when(rs.getString("trade_date")).thenReturn("19691231");
        when(rs.getString("symbol")).thenReturn(row.symbol());when(rs.getString("market")).thenReturn(row.market());when(rs.getString("board")).thenReturn(row.board());
        for(var field:L2IntradayBarFeatureField.values())if(field.metric())when(rs.getObject(field.fieldName())).thenReturn(row.features().get(field));
    }
    static final class Harness implements AutoCloseable{
        final JdbcTemplate original=mock(JdbcTemplate.class),jdbc;final QuestDB qdb=mock(QuestDB.class);final Sender sender=mock(Sender.class,RETURNS_SELF);
        final MockedConstruction<JdbcTemplate> copies;final L2IntradayBarFeaturesWritePort writer;
        Harness(){when(original.getDataSource()).thenReturn(mock(DataSource.class));copies=mockConstruction(JdbcTemplate.class);writer=new L2IntradayBarFeaturesWritePort(TABLE,original,qdb);jdbc=copies.constructed().getFirst();when(qdb.borrowSender()).thenReturn(sender);}
        void ack(){when(sender.flushAndGetSequence()).thenReturn(17L);when(sender.awaitAckedFsn(17L,10000L)).thenReturn(true);}
        public void close(){copies.close();}
    }
}
