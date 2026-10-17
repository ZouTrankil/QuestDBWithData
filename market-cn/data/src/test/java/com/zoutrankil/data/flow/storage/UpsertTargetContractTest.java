package com.zoutrankil.data.flow.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.flow.mapper.*;
import com.zoutrankil.data.margin.mapper.MarginDetailMapper;
import com.zoutrankil.data.margin.storage.*;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The SQL and sender contracts remain family-specific across the new target/session boundary. */
@SuppressWarnings({"rawtypes","unchecked"})
class UpsertTargetContractTest {
    static final LocalDate DAY=LocalDate.of(1969,12,31);
    static final String ID="static-v2-"+"a".repeat(64);
    static final String ID_SQL="SELECT id,directoryName FROM tables() WHERE table_name=?";
    enum Family {
        MONEYFLOW("java_d024_moneyflow_contract",30001,6000),
        DC("java_d026_moneyflow_dc_contract",50001,10000),
        THS("java_d025_moneyflow_ths_contract",6001,6000),
        DETAIL("java_d029_margin_detail_contract",84001,6000);
        final String table;final int queryCap,dateCap;
        Family(String table,int queryCap,int dateCap){this.table=table;this.queryCap=queryCap;this.dateCap=dateCap;}
        View target(String table,JdbcTemplate jdbc,QuestDB questdb){return switch(this){
            case MONEYFLOW->{var t=new QuestDbMoneyflowTarget(table,jdbc,questdb);yield new View(t.tableName(),t::targetId,t::newWriter);}
            case DC->{var t=new QuestDbMoneyflowDcTarget(table,jdbc,questdb);yield new View(t.tableName(),t::targetId,t::newWriter);}
            case THS->{var t=new QuestDbMoneyflowThsTarget(table,jdbc,questdb);yield new View(t.tableName(),t::targetId,t::newWriter);}
            case DETAIL->{var t=new QuestDbMarginDetailTarget(table,jdbc,questdb);yield new View(t.tableName(),t::targetId,t::newWriter);}
        };}
        DatasetDefinition definition(){return switch(this){case MONEYFLOW->MoneyflowDataset.definition(table);case DC->MoneyflowDcDataset.definition(table);case THS->MoneyflowThsDataset.definition(table);case DETAIL->MarginDetailDataset.definition(table);};}
        Object row(){
            var values=new LinkedHashMap<String,Object>();definition().columns().forEach(c->values.put(c.logicalName(),1D));
            values.put("ts_code","000001.SZ");values.put("trade_date",DAY);if(values.containsKey("name"))values.put("name","测试");
            if(this==MONEYFLOW)values.keySet().stream().filter(k->k.endsWith("_vol")).toList().forEach(k->values.put(k,1L));
            var v=new DatasetValues(values);return switch(this){case MONEYFLOW->new MoneyflowMapper().fromValues(v);case DC->new MoneyflowDcMapper().fromValues(v);case THS->new MoneyflowThsMapper().fromValues(v);case DETAIL->new MarginDetailMapper().fromValues(v);};
        }
        Object key(String code,LocalDate date){return switch(this){case MONEYFLOW->new MoneyflowKey(code,date);case DC->new MoneyflowDcKey(code,date);case THS->new MoneyflowThsKey(code,date);case DETAIL->new MarginDetailKey(code,date);};}
    }
    record View(String table,Callable<String> identity,Function<String,? extends VerifiedWriteSession<?,?>> writers){}

    @ParameterizedTest @EnumSource(Family.class)
    void freshSessionsKeepIndependentMutableSenderStateAndBoundedJdbcCopies(Family f)throws Exception {
        var jdbc=mock(JdbcTemplate.class);var ds=mock(DataSource.class);var qdb=mock(QuestDB.class);
        var target=f.target(f.table,jdbc,qdb);assertEquals(f.table,target.table());verifyNoInteractions(jdbc,ds,qdb);
        when(jdbc.getDataSource()).thenReturn(ds);var a=target.writers().apply(ID);var b=target.writers().apply(ID);
        assertNotSame(a,b);assertSame(a.codec(),b.codec());assertFalse(a.uncertainSenderStopped());assertFalse(b.uncertainSenderStopped());
        var field=a.getClass().getDeclaredField("jdbc");field.setAccessible(true);var aj=(JdbcTemplate)field.get(a);var bj=(JdbcTemplate)field.get(b);
        assertNotSame(jdbc,aj);assertNotSame(aj,bj);assertEquals(20,aj.getQueryTimeout());assertEquals(f.queryCap,aj.getMaxRows());
        assertEquals(20,bj.getQueryTimeout());assertEquals(f.queryCap,bj.getMaxRows());verifyNoInteractions(ds,qdb);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void targetIdentityPreservesExactMetadataSqlAndNewlineFraming(Family f)throws Exception {
        var jdbc=mock(JdbcTemplate.class);var qdb=mock(QuestDB.class);var target=f.target(f.table,jdbc,qdb);
        when(jdbc.queryForList(ID_SQL,f.table)).thenReturn(List.of(Map.of("id",7L,"directoryName","generation")));
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn("jdbc:postgresql://HOST.example:18812/db?user=ignored");
        String frame="host.example:18812/db\n"+f.table+"\n7\ngeneration";
        assertEquals("static-v2-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame.getBytes(StandardCharsets.UTF_8))),target.identity().call());
        verify(jdbc).queryForList(ID_SQL,f.table);verify(jdbc).execute(any(ConnectionCallback.class));verifyNoInteractions(qdb);
        when(jdbc.queryForList(ID_SQL,f.table)).thenReturn(List.of(Map.of("id","7","directoryName","generation")));
        assertThrows(IllegalStateException.class,target.identity()::call);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void preflightFailureNeverBorrowsSenderAndUnknownFlushClosesWithSuppressedFailure(Family f)throws Exception {
        try(var h=new Harness(f)) {
            var schemaFailure=new IllegalStateException("schema");
            h.checks.when(()->QuestDbWriteChecks.preflight(eq(h.jdbc),eq(f.table),any(DatasetDefinition.class))).thenThrow(schemaFailure);
            assertSame(schemaFailure,assertThrows(IllegalStateException.class,h::send));verifyNoInteractions(h.qdb,h.sender);assertFalse(h.writer.uncertainSenderStopped());
            h.checks.reset();var sendFailure=new IllegalStateException("flush");var closeFailure=new IllegalStateException("close");
            when(h.sender.flushAndGetSequence()).thenThrow(sendFailure);doThrow(closeFailure).when(h.sender).close();
            assertSame(sendFailure,assertThrows(IllegalStateException.class,h::send));assertArrayEquals(new Throwable[]{closeFailure},sendFailure.getSuppressed());
            assertTrue(h.writer.uncertainSenderStopped());verify(h.sender).close();verify(h.sender,never()).awaitAckedFsn(anyLong(),anyLong());
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void acknowledgedSendKeepsTenSecondAckAndIdentityDriftBlocksEveryOperation(Family f)throws Exception {
        try(var h=new Harness(f)) {
            when(h.sender.flushAndGetSequence()).thenReturn(29L);when(h.sender.awaitAckedFsn(29L,10000L)).thenReturn(true);
            h.send();var order=inOrder(h.sender);order.verify(h.sender).flushAndGetSequence();order.verify(h.sender).awaitAckedFsn(29L,10000L);order.verify(h.sender).close();
            assertTrue(h.writer.uncertainSenderStopped());clearInvocations(h.qdb,h.sender,h.jdbc);
            h.identities.when(()->StaticTargetIdentity.identify(h.jdbc,f.table,7L,"generation")).thenReturn("changed");
            assertThrows(IllegalStateException.class,h::send);assertThrows(IllegalStateException.class,h::readback);assertThrows(IllegalStateException.class,h.writer::walSettled);
            verifyNoInteractions(h.qdb,h.sender);assertTrue(mockingDetails(h.jdbc).getInvocations().stream().noneMatch(i->i.getMethod().getName().equals("query")));
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void mixedDateReadbackKeepsFamilyCodeOrderDateBoundsAndExactPairOrder(Family f)throws Exception {
        try(var h=new Harness(f)) {
            h.readback();var call=mockingDetails(h.jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("query")).findFirst().orElseThrow();
            String sql=call.getArgument(0);String pairs="(ts_code=? AND trade_date=cast(? AS TIMESTAMP)) OR (ts_code=? AND trade_date=cast(? AS TIMESTAMP))";
            assertTrue(sql.startsWith("SELECT ts_code,cast(trade_date AS long) AS "));assertTrue(sql.contains(" FROM \""+f.table+"\" WHERE "));
            if(f==Family.MONEYFLOW||f==Family.DETAIL)assertTrue(sql.endsWith(" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) AND ts_code IN (?,?) AND ("+pairs+") ORDER BY trade_date,ts_code LIMIT 3"));
            else assertTrue(sql.endsWith(" WHERE "+pairs+" ORDER BY trade_date,ts_code LIMIT 3"));
            Object[] params=f==Family.MONEYFLOW?new Object[]{micros(DAY),micros(DAY.plusDays(2)),"000001.SZ","600000.SH","600000.SH",micros(DAY.plusDays(1)),"000001.SZ",micros(DAY)}
                    :f==Family.DETAIL?new Object[]{micros(DAY),micros(DAY.plusDays(2)),"600000.SH","000001.SZ","600000.SH",micros(DAY.plusDays(1)),"000001.SZ",micros(DAY)}
                    :new Object[]{"600000.SH",micros(DAY.plusDays(1)),"000001.SZ",micros(DAY)};
            assertArrayEquals(params,(Object[])call.getRawArguments()[2]);verifyNoInteractions(h.qdb,h.sender);
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void rangeUsesExactMicrosAndCountShapeWhileDateCapKeepsItsBoundary(Family f)throws Exception {
        try(var h=new Harness(f)) {
            var rs=mock(ResultSet.class);when(rs.next()).thenReturn(true);
            when(rs.getObject(1)).thenReturn(micros(DAY));when(rs.getObject(2)).thenReturn(micros(DAY.plusDays(1)));when(rs.getObject(3)).thenReturn(2L);
            when(rs.getObject("min_micros")).thenReturn(micros(DAY));when(rs.getObject("max_micros")).thenReturn(micros(DAY.plusDays(1)));
            when(h.jdbc.query(anyString(),any(ResultSetExtractor.class))).thenAnswer(i->((ResultSetExtractor<?>)i.getArgument(1)).extractData(rs));
            Object range=h.writer.getClass().getMethod("readTargetRange").invoke(h.writer);
            assertEquals(DAY,range.getClass().getMethod("min").invoke(range));assertEquals(DAY.plusDays(1),range.getClass().getMethod("max").invoke(range));
            String sql="SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros"+(f==Family.THS?"":",count(*) AS row_count")+" FROM \""+f.table+"\"";
            verify(h.jdbc).query(eq(sql),any(ResultSetExtractor.class));
            when(h.jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(Collections.nCopies(f.dateCap,f.row()));
            var dates=(DateSliceReadPort)h.writer;
            if(f==Family.DETAIL)assertThrows(IllegalStateException.class,()->dates.readDate(DAY));else assertEquals(f.dateCap,dates.readDate(DAY).size());
            when(h.jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(Collections.nCopies(f.dateCap+1,f.row()));
            assertThrows(IllegalStateException.class,()->dates.readDate(DAY));
        }
    }

    @Test void formalTableAdmissionIsLimitedToMoneyflowAndDetail() {
        var jdbc=mock(JdbcTemplate.class);var qdb=mock(QuestDB.class);
        assertEquals("moneyflow",Family.MONEYFLOW.target("moneyflow",jdbc,qdb).table());assertEquals("margin_detail",Family.DETAIL.target("margin_detail",jdbc,qdb).table());
        assertThrows(IllegalStateException.class,()->Family.DC.target("moneyflow_dc",jdbc,qdb));assertThrows(IllegalStateException.class,()->Family.THS.target("moneyflow_ths",jdbc,qdb));verifyNoInteractions(jdbc,qdb);
    }

    static long micros(LocalDate day){return day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()*1_000_000L;}
    static final class Harness implements AutoCloseable {
        final Family family;final QuestDB qdb=mock(QuestDB.class);final Sender sender=mock(Sender.class,RETURNS_SELF);
        final MockedStatic<QuestDbWriteChecks> checks=mockStatic(QuestDbWriteChecks.class);
        final MockedStatic<StaticTargetIdentity> identities=mockStatic(StaticTargetIdentity.class);
        final MockedConstruction<JdbcTemplate> copies;final JdbcTemplate jdbc;final VerifiedWriteSession writer;
        Harness(Family f){family=f;var original=mock(JdbcTemplate.class);when(original.getDataSource()).thenReturn(mock(DataSource.class));var target=f.target(f.table,original,qdb);
            copies=mockConstruction(JdbcTemplate.class);writer=target.writers().apply(ID);jdbc=copies.constructed().getFirst();
            when(jdbc.queryForList(ID_SQL,f.table)).thenReturn(List.of(Map.of("id",7L,"directoryName","generation")));
            identities.when(()->StaticTargetIdentity.identify(jdbc,f.table,7L,"generation")).thenReturn(ID);when(qdb.borrowSender()).thenReturn(sender);
        }
        void send()throws Exception{writer.send(List.of(family.row()));}
        void readback()throws Exception{writer.readback(List.of(family.key("600000.SH",DAY.plusDays(1)),family.key("000001.SZ",DAY)));}
        @Override public void close(){copies.close();identities.close();checks.close();}
    }
}
