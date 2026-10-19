package com.zoutrankil.data.marketdata;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FrozenHttpSourceTest {
    @TempDir Path root;
    private static final class Budget implements MarketRequestBudget {
        int calls,status,bytes;
        public String reserve(String p,String a,String s,int maximum){calls++;return "request-"+calls;}
        public void complete(String id,int status,int bytes,Instant retry){this.status=status;this.bytes=bytes;}
    }
    @Test void freezesSuccessfulBodyButNeverWritesQueryCredentialsAndRejectsOversize()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/data",exchange->{var body="0123456789".getBytes();exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});server.start();
        var budget=new Budget();var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/data?api_key=private-value&symbol=X");
        try(var source=new FrozenHttpSource(root,budget,"")) {
            var result=source.get("test","account","X",uri,Map.of(),20);
            assertEquals("0123456789",Files.readString(result.path()));assertFalse(result.sourceUrl().contains("private-value"));
            assertFalse(Files.readString(result.path().resolveSibling("request-1.json")).contains("private-value"));
            assertThrows(IllegalStateException.class,()->source.get("test","account","X",uri,Map.of(),5));
            assertFalse(Files.exists(root.resolve("raw/test/request-2.response")));assertEquals(6,budget.bytes);
        } finally{server.stop(0);}
    }
}
