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
}
