package com.zoutrankil.data.margin.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.margin.domain.*;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import io.questdb.client.QuestDB;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarginTargetContractTest {
    static final String PHYSICAL="static-v2-"+"a".repeat(64);
    static final LocalDate DAY=LocalDate.of(2025,7,1);

    @ParameterizedTest @ValueSource(booleans={false,true})
    void constructionHasNoIoAndEveryWriterAndProtocolClientIsIndependent(boolean zrz){
        var jdbc=mock(JdbcTemplate.class);when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));var quest=mock(QuestDB.class);
        Object target=zrz?new QuestDbMarginZrzTarget("java_d031_margin_zrz_contract",jdbc,quest):new QuestDbMarginAllTarget("java_d028_margin_all_contract",jdbc,quest);verifyNoInteractions(quest);verify(jdbc,never()).getDataSource();
        try(var clients=mockConstruction(JdbcTemplate.class)){
            if(zrz){var t=(QuestDbMarginZrzTarget)target;var a=t.newWriter(PHYSICAL);var b=t.newWriter(PHYSICAL);assertNotSame(a,b);assertSame(MarginZrzWritePort.CODEC,a.codec());assertNotSame(t.newStaging(),t.newPublicationTables());}
            else{var t=(QuestDbMarginAllTarget)target;var a=t.newWriter(PHYSICAL);var b=t.newWriter(PHYSICAL);assertNotSame(a,b);assertSame(MarginAllWritePort.CODEC,a.codec());assertNotSame(t.newStaging(),t.newPublicationTables());}
            assertEquals(4,clients.constructed().size());for(int i=0;i<2;i++){verify(clients.constructed().get(i)).setQueryTimeout(20);verify(clients.constructed().get(i)).setMaxRows(100001);}for(int i=2;i<4;i++){verify(clients.constructed().get(i)).setQueryTimeout(120);verify(clients.constructed().get(i),never()).setMaxRows(anyInt());}
        }
        verifyNoInteractions(quest);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void targetRejectsFormalTablesAndWriterRejectsDirectFormalSendBeforeIo(boolean zrz)throws Exception {
        var jdbc=mock(JdbcTemplate.class);var quest=mock(QuestDB.class);when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        assertThrows(IllegalArgumentException.class,()->{if(zrz)new QuestDbMarginZrzTarget("margin_zrz",jdbc,quest);else new QuestDbMarginAllTarget("margin_all",jdbc,quest);});
        try(var clients=mockConstruction(JdbcTemplate.class)){
            if(zrz){var writer=new QuestDbMarginZrzTarget("java_d031_margin_zrz_contract",jdbc,quest).newWriter(PHYSICAL);assertThrows(IllegalStateException.class,()->writer.send(List.of(new MarginZrz(new MarginZrzKey(DAY),1d,null,2d,null,3d))));}
            else{var writer=new QuestDbMarginAllTarget("java_d028_margin_all_contract",jdbc,quest).newWriter(PHYSICAL);assertThrows(IllegalStateException.class,()->writer.send(List.of(new MarginAll(new MarginAllKey(DAY,"SSE"),1d,2d,3d,4d,5d,6d,7d))));}
            verify(clients.constructed().getFirst(),never()).queryForList(anyString(),any(Object[].class));
        }
        verifyNoInteractions(quest);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void storageKeepsMicrosecondWindowSqlAndSnapshotSettings(boolean zrz)throws Exception {
        var source=mock(JdbcTemplate.class);when(source.getDataSource()).thenReturn(mock(DataSource.class));String table=zrz?"java_d031_margin_zrz_contract":"java_d028_margin_all_contract";var sql=new AtomicReference<String>();var args=new AtomicReference<Object[]>();
        try(var checks=mockStatic(QuestDbWriteChecks.class);var clients=mockConstruction(JdbcTemplate.class,(jdbc,ctx)->{
            when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table)).thenReturn(List.of(Map.of("id",1,"directoryName","generation")));
            when(jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?",table)).thenReturn(List.of(Map.of("writerTxn",7)));
            doAnswer(call->{sql.set(call.getArgument(0));args.set(Arrays.copyOfRange(call.getArguments(),2,call.getArguments().length));return List.of();}).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        })){
            if(zrz)assertTrue(new MarginZrzStorage(source,table).window(DAY,DAY).rows().isEmpty());else assertTrue(new MarginAllStorage(source,table).window(DAY,DAY).rows().isEmpty());
            assertTrue(sql.get().contains(" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP)"));assertTrue(sql.get().endsWith("LIMIT 100001"));
            assertArrayEquals(new Object[]{1751328000000000L,1751414400000000L},args.get());verify(clients.constructed().getFirst()).setQueryTimeout(120);verify(clients.constructed().getFirst()).setMaxRows(100001);
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void tableDdlUsesExactYearWalOutsideWindowAndDefaultDiscardClient(boolean zrz){
        var source=mock(JdbcTemplate.class);when(source.getDataSource()).thenReturn(mock(DataSource.class));String prefix=zrz?"java_d031_margin_zrz_":"java_d028_margin_all_",stage=prefix+"stage_"+"1".repeat(32),target=prefix+"contract";
        try(var clients=mockConstruction(JdbcTemplate.class)){
            var tables=zrz?new QuestDbMarginZrzTables(source):new QuestDbMarginAllTables(source);
            if(tables instanceof QuestDbMarginZrzTables t){t.createOutsideStage(stage,target,"2025-07-01T00:00:00.000000Z","2025-07-02T00:00:00.000000Z");var discard=t.newDiscardSession();discard.namedStageCount(stage);discard.dropStage(stage);discard.stageExists(stage);t.rename(stage,target);}
            else{var t=(QuestDbMarginAllTables)tables;t.createOutsideStage(stage,target,"2025-07-01T00:00:00.000000Z","2025-07-02T00:00:00.000000Z");var discard=t.newDiscardSession();discard.namedStageCount(stage);discard.dropStage(stage);discard.stageExists(stage);t.rename(stage,target);}
            String fields=zrz?"trade_date,ob,auc_amount,repo_amount,repay_amount,cb":"trade_date,exchange_id,rzye,rzmre,rzche,rqye,rqmcl,rzrqye,rqyl";
            verify(clients.constructed().getFirst()).execute("CREATE TABLE \""+stage+"\" AS (SELECT "+fields+" FROM \""+target+"\" WHERE trade_date<cast('2025-07-01T00:00:00.000000Z' AS TIMESTAMP) OR trade_date>=cast('2025-07-02T00:00:00.000000Z' AS TIMESTAMP)) TIMESTAMP(trade_date) PARTITION BY YEAR WAL");
            verify(clients.constructed().getFirst()).execute("RENAME TABLE \""+stage+"\" TO \""+target+"\"");var discard=clients.constructed().get(1);verify(discard,never()).setQueryTimeout(anyInt());verify(discard,never()).setMaxRows(anyInt());verify(discard).execute("DROP TABLE \""+stage+"\"");
        }
    }
    @Test void domainRowBytesKeepNullsDeclaredFieldOrderAndEndpointAwareIdentity()throws Exception {
        var all=new MarginAll(new MarginAllKey(DAY,"SSE"),1d,2d,3d,4d,5d,6d,7d);var zrz=new MarginZrz(new MarginZrzKey(DAY),1d,null,2d,null,3d);
        String a="{\"trade_date\":\"2025-07-01\",\"exchange_id\":\"SSE\",\"rzye\":1.0,\"rzmre\":2.0,\"rzche\":3.0,\"rqye\":4.0,\"rqmcl\":5.0,\"rzrqye\":6.0,\"rqyl\":7.0}";
        String z="{\"trade_date\":\"2025-07-01\",\"ob\":1.0,\"auc_amount\":null,\"repo_amount\":2.0,\"repay_amount\":null,\"cb\":3.0}";
        assertArrayEquals(a.getBytes(StandardCharsets.UTF_8),MarginAllRows.canonicalBytes(all));assertArrayEquals(z.getBytes(StandardCharsets.UTF_8),MarginZrzRows.canonicalBytes(zrz));assertArrayEquals(MarginAllRows.canonicalBytes(all),MarginAllWritePort.CODEC.canonicalBytes(all));assertArrayEquals(MarginZrzRows.canonicalBytes(zrz),MarginZrzWritePort.CODEC.canonicalBytes(zrz));
        String endpoint="jdbc:postgresql://HOST:8812/qdb?user=secret";assertEquals(MarginAllTargetIdentity.logical(endpoint,"java_d028_margin_all_contract"),MarginAllTargetIdentity.logical("jdbc:postgresql://host:8812/qdb?user=other","java_d028_margin_all_contract"));
        String raw="host:8812/qdb\njava_d028_margin_all_contract\n0\nd028-logical-target-v1";assertEquals("static-v2-"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))),MarginAllTargetIdentity.logical(endpoint,"java_d028_margin_all_contract"));
        assertNotEquals(MarginAllTargetIdentity.logical(endpoint,"java_d028_margin_all_contract"),MarginAllTargetIdentity.logical("jdbc:postgresql://other:8812/qdb","java_d028_margin_all_contract"));
    }
}
