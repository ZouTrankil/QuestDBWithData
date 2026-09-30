package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real QuestDB protocol test only, not evidence of a business source producer migration. */
@EnabledIfEnvironmentVariable(named="JDB_QUESTDB_TEST_URL",matches="https?://.+")
class QuestDbProtocolTest {
    @TempDir Path archive;
    final HttpClient http=HttpClient.newBuilder().proxy(ProxySelector.of(null)).version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    String base=System.getenv("JDB_QUESTDB_TEST_URL");
    com.fasterxml.jackson.databind.JsonNode sql(String sql) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create(base+"/exec?query="+URLEncoder.encode(sql,StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        var json=Json.MAPPER.readTree(response.body()); assertFalse(json.has("error"),response.body()); return json;
    }
    @Test void exactBatchReadbackAndLostAckWithoutReplay() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");
        String table="jdb_test_protocol_"+suffix;
        // Only a fresh, uniquely named jdb_test_ table is created. It is retained as evidence.
        sql("CREATE TABLE "+table+" (timestamp TIMESTAMP,batch_id SYMBOL,entity SYMBOL,payload STRING) TIMESTAMP(timestamp) PARTITION BY DAY WAL DEDUP UPSERT KEYS(timestamp,batch_id,entity)");
        String line=table+",batch_id=receipt1,entity=AAA payload=\"frozen-value\" 1790683200000000000\n";
        Path artifact=archive.resolve("batch.ilp"); Files.writeString(artifact,line);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
        var sqlite=new org.sqlite.SQLiteDataSource(); sqlite.setUrl("jdbc:sqlite:"+archive.resolve("metadata.sqlite").toAbsolutePath());
        {
            var ds=sqlite;
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration/batch").cleanDisabled(true).load(); flyway.migrate();
            var ledger=new SqliteLedger(ds); var request=ContractsTest.request("questdb-protocol"); ledger.register(request);
            var writer=new DurableWriter(ledger);
            var intent=new DurableWriter.Intent("live-"+suffix,request.instanceId(),table,"protocol-test",hash,artifact.toString(),1);
            var sends=new AtomicInteger();
            var port=new DurableWriter.Port() {
                public void preflight(DurableWriter.Intent i) { assertTrue(i.target().startsWith("jdb_test_")); }
                public void send(DurableWriter.Intent i) throws Exception {
                    sends.incrementAndGet();
                    var response=http.send(HttpRequest.newBuilder(URI.create(base+"/write?precision=n"))
                            .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(line)).build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(204,response.statusCode(),response.body());
                    throw new java.io.IOException("Injected lost ACK after accepted HTTP write");
                }
                public DurableWriter.Proof inspect(DurableWriter.Intent i) throws Exception {
                    var rows=sql("SELECT entity,payload FROM "+table+" WHERE batch_id='receipt1'").get("dataset");
                    boolean exact=rows.size()==1 && rows.get(0).get(0).asText().equals("AAA") && rows.get(0).get(1).asText().equals("frozen-value");
                    // Sender above is synchronous and known stopped; exact batch contents are directly visible.
                    return new DurableWriter.Proof(exact,exact,true,false,"questdb://"+table+"/receipt1");
                }
            };
            assertEquals(DurableWriter.Delivery.UNKNOWN,writer.execute(intent,port));
            var delivery=DurableWriter.Delivery.UNKNOWN;
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(delivery!=DurableWriter.Delivery.VERIFIED && System.nanoTime()<deadline) {
                delivery=writer.execute(intent,port); if(delivery!=DurableWriter.Delivery.VERIFIED) Thread.sleep(100);
            }
            assertEquals(DurableWriter.Delivery.VERIFIED,delivery); assertEquals(1,sends.get());
            assertEquals(DurableWriter.Delivery.VERIFIED,writer.execute(intent,port)); assertEquals(1,sends.get());
            Path report=Path.of("build/reports/jdb-questdb-protocol.json"); Files.createDirectories(report.getParent());
            Files.writeString(report,Json.write(Map.of("table",table,"questdbVersion",sql("SELECT build()").get("dataset"),
                    "sourceRows",1,"visibleRows",1,"sendAttempts",sends.get(),"delivery",delivery,
                    "ackLoss","injected-after-real-http-acceptance","metadata","temporary-sqlite-file",
                    "businessProducerAcceptance",false)));
        }
    }
}
