package com.zoutrankil.batch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EmptySnapshotPortTest {
    @TempDir Path root;
    @Test void emptyIntentNeverPostsDataAndExistingRowsPreventEmptyProof() throws Exception {
        var writes=new AtomicInteger();var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            if(exchange.getRequestMethod().equals("POST")) writes.incrementAndGet();
            String query=URLDecoder.decode(Objects.toString(exchange.getRequestURI().getRawQuery(),""),java.nio.charset.StandardCharsets.UTF_8);
            Object value=query.contains("wal_tables")?false:count.get();
            byte[] body=Json.write(Map.of("dataset",List.of(List.of(value)))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()) { out.write(body); }
        });server.start();
        try {
            var contract=SourceContract.load("stk_suspend");var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            var port=new QuestDbTablePort(endpoint,"",contract,"jdb_test_empty_port",List.of());
            Path artifact=root.resolve("empty.ilp");Files.write(artifact,new byte[0]);
            String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[0]));
            var intent=new DurableWriter.Intent("empty-batch","empty-instance","jdb_test_empty_port","test",sha,artifact.toString(),0);
            port.send(intent);assertEquals(0,writes.get());
            assertTrue(port.inspect(intent).verified(true));
            count.set(1);assertFalse(port.inspect(intent).verified(true));
            assertThrows(IllegalArgumentException.class,() -> new QuestDbTablePort(endpoint,"",SourceContract.load("daily"),"jdb_test_empty_daily",List.of()));
        } finally { server.stop(0); }
    }
}
