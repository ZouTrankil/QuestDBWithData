package com.zoutrankil.batch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class BaiduShareClientTest {
    @TempDir Path temp;
    private static Map<String,String> cookies(){return Map.of("BDUSS","fixture-session","STOKEN","fixture-stoken");}

    @Test void normalizesBothSupportedLinkFormsAndRejectsCredentialExfiltrationHosts(){
        var direct=BaiduShareClient.normalizeShare("https://pan.baidu.com/s/1abc_DEF?pwd=9xyz","");
        assertEquals("https://pan.baidu.com/s/1abc_DEF",direct.url().toString());assertEquals("9xyz",direct.password());
        var init=BaiduShareClient.normalizeShare("https://pan.baidu.com/share/init?surl=abc_DEF",null);
        assertEquals("https://pan.baidu.com/s/1abc_DEF",init.url().toString());
        assertThrows(IllegalArgumentException.class,()->BaiduShareClient.normalizeShare("https://evil.example/s/1abc", ""));
        assertThrows(IllegalArgumentException.class,()->BaiduShareClient.normalizeShare("http://pan.baidu.com/s/1abc", ""));
        assertThrows(IllegalArgumentException.class,()->BaiduShareClient.normalizeShare("https://pan.baidu.com/share/init?surl=../../x", ""));
    }

    @Test void rejectsTruncatedShareRoot() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/gettemplatevariable",exchange->respond(exchange,"{\"errno\":0,\"result\":{\"bdstoken\":\"safe-token\"}}"));
        server.createContext("/s/1abc",exchange->respond(exchange,"<script>locals.mset({\"uk\":12,\"shareid\":34,\"file_list\":{\"has_more\":true,\"list\":[]}});</script>"));
        server.start();
        try(var ignored=new AutoCloseable(){public void close(){server.stop(0);}}){
            int port=server.getAddress().getPort();var client=new BaiduShareClient(URI.create("http://127.0.0.1:"+port),cookies());
            var share=new BaiduShareClient.Share(URI.create("http://127.0.0.1:"+port+"/s/1abc"),"");
            assertThrows(IllegalStateException.class,()->client.sharedRoot(share));
        }
    }

    @Test void readsCompleteRootAndPaginatesSubdirectoriesWithBoundedPages() throws Exception {
        var tokenSeen=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/gettemplatevariable",exchange->{tokenSeen.set(exchange.getRequestHeaders().getFirst("Cookie"));respond(exchange,"{\"errno\":0,\"result\":{\"bdstoken\":\"safe-token\"}}");});
        server.createContext("/s/1abc",exchange->respond(exchange,"<script>yunData.setData({\"share_uk\":12,\"shareid\":34,\"file_list\":[{\"path\":\"/2026\",\"isdir\":1,\"size\":0,\"fs_id\":99}]});</script>"));
        server.createContext("/share/list",exchange->{String q=exchange.getRequestURI().getRawQuery();int page=q.contains("page=1")?1:2;if(page==1){StringBuilder json=new StringBuilder("{\"errno\":0,\"list\":[");for(int i=0;i<100;i++){if(i>0)json.append(',');json.append("{\"path\":\"/2026/09/f").append(i).append("\",\"isdir\":0,\"size\":10,\"fs_id\":").append(100+i).append('}');}respond(exchange,json.append("]}").toString());}else respond(exchange,"{\"errno\":0,\"list\":[{\"path\":\"/2026/09/20260930.7z\",\"isdir\":0,\"size\":1200000000,\"fs_id\":101}]}");});
        server.start();
        try(var ignored=new AutoCloseable(){public void close(){server.stop(0);}}){
            var client=new BaiduShareClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),cookies());
            var share=new BaiduShareClient.Share(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/s/1abc"),"");
            var root=client.sharedRoot(share);assertEquals(1,root.size());assertEquals(12,root.getFirst().uk());assertEquals("safe-token",root.getFirst().token());
            var children=client.allSharedChildren(root.getFirst());assertEquals(101,children.size());assertEquals("/2026/09/20260930.7z",children.getLast().path());
            assertNotNull(tokenSeen.get());assertTrue(tokenSeen.get().contains("BDUSS=fixture-session"));
        }
    }

    @Test void requiresLocalSessionCookiesAndCannotUseRemoteHttpOrigin(){
        assertThrows(IllegalArgumentException.class,()->new BaiduShareClient(Map.of("BDUSS","only")));
        assertThrows(IllegalArgumentException.class,()->new BaiduShareClient(URI.create("http://example.com"),cookies()));
    }

    @Test void readsOnlyPrivateCookieFileAndRequiresBothSessionCookies() throws Exception {
        Path file=temp.resolve("cookies.txt");Files.writeString(file,"BDUSS=fixture-session; STOKEN=fixture-stoken");
        try { Files.setPosixFilePermissions(file,EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)); }
        catch(UnsupportedOperationException ignored) { }
        assertEquals("fixture-session",BaiduShareClient.readPrivateCookieFile(file).get("BDUSS"));
        Files.writeString(file,"BDUSS=only");assertThrows(IllegalArgumentException.class,()->BaiduShareClient.readPrivateCookieFile(file));
        Files.delete(file);Files.createSymbolicLink(file,temp.resolve("target"));
        assertThrows(IllegalArgumentException.class,()->BaiduShareClient.readPrivateCookieFile(file));
    }

    @Test void plansShareArchiveAndDryRunDoesNotCreateOrTransfer() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var transferCalls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/filemetas",exchange->respond(exchange,"{\"errno\":12}"));
        server.createContext("/api/gettemplatevariable",exchange->respond(exchange,"{\"errno\":0,\"result\":{\"bdstoken\":\"safe-token\"}}"));
        server.createContext("/s/1abc",exchange->respond(exchange,"<script>yunData.setData({\"share_uk\":12,\"shareid\":34,\"file_list\":[{\"path\":\"/20260930.7z\",\"isdir\":0,\"size\":1234,\"fs_id\":99}]});</script>"));
        server.createContext("/share/transfer",exchange->{transferCalls.incrementAndGet();respond(exchange,"{\"errno\":0,\"info\":[]}");});
        server.createContext("/api/create",exchange->{transferCalls.incrementAndGet();respond(exchange,"{\"errno\":0}");});server.start();
        try(var ignored=new AutoCloseable(){public void close(){server.stop(0);}}){
            var client=new BaiduShareClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),cookies());
            var settings=new BaiduL2Subscription.Settings("https://pan.baidu.com/s/1abc","","",false,"/isolated",1000);
            var subscription=new BaiduL2Subscription(client,settings,transferStore());
            var result=subscription.transfer(java.time.LocalDate.of(2026,9,30),true);
            assertEquals("PLANNED",result.status());assertEquals("/isolated/2026/09/20260930.7z",result.remotePath());assertEquals(1234,result.expectedBytes());
            assertEquals(0,transferCalls.get());
        }
    }

    @Test void unknownTransferIntentSurvivesStoreRecreationAndCannotBeReclaimed() {
        var store=transferStore();var intent=store.reserve("20260930","/archive/20260930.7z",99,1234);
        assertEquals(BaiduTransferIntentStore.State.INTENT,intent.state());store.unknown("20260930");
        var restarted=transferStore();assertEquals(BaiduTransferIntentStore.State.UNKNOWN,restarted.get("20260930").orElseThrow().state());
        assertEquals(BaiduTransferIntentStore.State.UNKNOWN,restarted.reserve("20260930","/archive/20260930.7z",99,1234).state());
        assertThrows(IllegalStateException.class,()->restarted.reserve("20260930","/archive/20260930.7z",100,1234));
        restarted.verified("20260930");assertEquals(BaiduTransferIntentStore.State.VERIFIED,restarted.get("20260930").orElseThrow().state());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,String text) throws java.io.IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
    }
    private BaiduTransferIntentStore transferStore() {
        var source=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("baidu-transfer.sqlite"));
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS baidu_transfer_intent(logical_date varchar(8) PRIMARY KEY,remote_path text NOT NULL,source_fs_id bigint NOT NULL,source_size_bytes bigint NOT NULL,state varchar(16) NOT NULL CHECK(state IN ('INTENT','UNKNOWN','VERIFIED')),created_at timestamp NOT NULL DEFAULT current_timestamp,updated_at timestamp NOT NULL DEFAULT current_timestamp)");
        return new BaiduTransferIntentStore(jdbc);
    }
}
