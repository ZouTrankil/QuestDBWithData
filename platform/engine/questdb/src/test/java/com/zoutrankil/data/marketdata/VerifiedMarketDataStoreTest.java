package com.zoutrankil.data.marketdata;

import com.zoutrankil.data.domain.DatasetDefinition;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

class VerifiedMarketDataStoreTest {
    @TempDir Path root;
    private final DatasetDefinition definition=new DatasetDefinition("market_test",1,"test","test","market_test",ObjectKind.TABLE,List.of(
            new Column("ts","ts","ts",StorageType.TIMESTAMP,false,"真实 UTC 时刻",new TemporalContract(TemporalKind.INSTANT,"ISO-8601","UTC","MICROS","真实事件时刻")),
            new Column("symbol","symbol","symbol",StorageType.SYMBOL,false,"证券业务键",null),
            new Column("price","price","price",StorageType.DOUBLE,true,"未知价格保持空值",null)),
            List.of("ts","symbol"),List.of("ts","symbol"),"ts",Partition.MONTH,true,Set.of(Capability.READ,Capability.WRITE),List.of(),"测试完整键值和空值");
    @Test void normalizesTimestampRepresentationWithoutLosingPrecisionAndPreservesNull() {
        var row=new LinkedHashMap<String,Object>();row.put("ts","2026-01-01T00:00:00.000000Z");row.put("symbol","X");row.put("price",null);
        var actual=VerifiedMarketDataStore.normalize(definition,row);assertEquals("2026-01-01T00:00:00Z",actual.get("ts"));assertNull(actual.get("price"));
        row.put("ts","2026-01-01T00:00:00.000000001Z");assertThrows(IllegalArgumentException.class,()->VerifiedMarketDataStore.normalize(definition,row));
    }
    @Test void modifiedSourceAndDuplicateBusinessKeysAreRejectedBeforeConnectingToDatabase()throws Exception {
        var file=Files.writeString(root.resolve("source.json"),"[]");var store=new VerifiedMarketDataStore(URI.create("http://127.0.0.1:1"),"","","",root);
        var rows=List.<Map<String,Object>>of(Map.of("ts","2026-01-01T00:00:00Z","symbol","X","price",1.));
        assertThrows(IllegalStateException.class,()->store.publish(definition,rows,file,"a".repeat(64),Map.of()));
        assertThrows(IllegalArgumentException.class,()->store.publish(definition,List.of(rows.getFirst(),rows.getFirst()),file,VerifiedMarketDataStore.sha(Files.readAllBytes(file)),Map.of()));
    }
    @Test void aClaimedStoppedWriterCannotReconcileWhileTheOriginalProcessIsStillAlive()throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();String run="market-00000000-0000-0000-0000-000000000001";
        var folder=Files.createDirectories(root.resolve("prepared"));var receipts=Files.createDirectories(root.resolve("receipts"));
        byte[] prepared=json.writeValueAsBytes(Map.of("writerPid",ProcessHandle.current().pid(),"writerStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString()));
        String sha=VerifiedMarketDataStore.sha(prepared);Files.write(folder.resolve(run+".json"),prepared);Files.write(receipts.resolve(run+".json"),json.writeValueAsBytes(Map.of("preparedSha256",sha)));
        var proof=root.resolve("stopped.json");Files.write(proof,json.writeValueAsBytes(Map.of("runId",run,"writerStopped",true,"inspectedAt",java.time.Instant.now().toString(),"evidence","错误的停止声明必须拒绝")));
        var store=new VerifiedMarketDataStore(URI.create("http://127.0.0.1:1"),"","","",root);
        var failure=assertThrows(IllegalStateException.class,()->store.reconcileUndelivered(run,sha,proof));assertTrue(failure.getMessage().contains("仍在运行"));
        Files.writeString(folder.resolve(run+".json"),"{}");assertThrows(IllegalArgumentException.class,()->store.reconcileUndelivered(run,sha,proof));
    }
    @Test void fillOnlyRejectsAKeyThatAppearedBeforeSendAndRetainsTheUnresolvedRun()throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var writes=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            if(!exchange.getRequestURI().getPath().equals("/exec")){writes.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();return;}
            String sql=java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery().split("&query=",2)[1],java.nio.charset.StandardCharsets.UTF_8);List<String> names;List<List<Object>> values;
            if(sql.startsWith("CREATE TABLE")){names=List.of();values=List.of();}
            else if(sql.contains("table_columns(")){names=List.of("column","type","designated","upsertKey");values=List.of(List.of("ts","TIMESTAMP",true,true),List.of("symbol","SYMBOL",false,true),List.of("price","DOUBLE",false,false));}
            else if(sql.startsWith("SELECT partitionBy")){names=List.of("partitionBy","walEnabled","table_type");values=List.of(List.of("MONTH",true,"T"));}
            else if(sql.startsWith("SELECT id,directoryName")){names=List.of("id","directoryName");values=List.of(List.of(1,"market_test~1"));}
            else if(sql.contains("wal_tables()")){names=List.of("walEnabled","table_suspended","writerTxn","sequencerTxn","suspended","bufferedTxnSize");values=List.of(List.of(true,false,1,1,false,0));}
            else {names=List.of("ts","symbol","price");values=List.of(List.of("2026-01-01T00:00:00Z","X",99.));}
            byte[] body=json.writeValueAsBytes(Map.of("columns",names.stream().map(n->Map.of("name",n)).toList(),"dataset",values));exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });server.start();
        try {
            var store=new VerifiedMarketDataStore(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"","","",root);var raw=Files.writeString(root.resolve("source.json"),"frozen");
            var rows=List.<Map<String,Object>>of(Map.of("ts","2026-01-01T00:00:00Z","symbol","X","price",1.));
            assertThrows(IllegalStateException.class,()->store.publishMissing(definition,rows,raw,VerifiedMarketDataStore.sha(Files.readAllBytes(raw)),Map.of()));
            assertEquals(0,writes.get(),"发现已存在的键时，不能连接正式发送端口");
            assertThrows(IllegalStateException.class,()->store.requireNoUnresolved(definition.datasetId()));
        } finally{server.stop(0);}
    }
}
