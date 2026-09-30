package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit integration gate: writes only fresh archive-scoped jdb_test_l2_* tables. */
@EnabledIfEnvironmentVariable(named="JDB_QUESTDB_TEST_URL",matches="https?://.+")
class L2QuestDbLiveTest {
    @TempDir Path root;
    @Test void streamsSemanticArchiveArtifactsThroughSQLiteLedgerIntoThreeIsolatedTables()throws Exception {
        URI endpoint=URI.create(System.getenv("JDB_QUESTDB_TEST_URL"));var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+root.resolve("ingest-metadata.sqlite"));
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration/batch").cleanDisabled(true).load().migrate();
        String archiveHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("synthetic-l2-"+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        LocalDate date=LocalDate.of(2026,9,29);Path directory=root.resolve("parsed").resolve(archiveHash);Files.createDirectories(directory);
        var artifacts=new LinkedHashMap<String,L2ArchiveMaterializer.Artifact>();
        var deal=new DfcfCsvParser.Deal("000001.SZ",date,93000123,"100","B","200","",new java.math.BigDecimal("12.3456"),100,DfcfCsvParser.DealSide.BUY);
        var order=new DfcfCsvParser.Order("000001.SZ",date,93000124,"300","A","B",new java.math.BigDecimal("12.3456"),200,0,DfcfCsvParser.OrderKind.ADD_BUY,true);
        var levels=new ArrayList<DfcfCsvParser.BookLevel>();for(int i=0;i<10;i++)levels.add(new DfcfCsvParser.BookLevel(new java.math.BigDecimal("12.34").subtract(new java.math.BigDecimal("0.01").multiply(java.math.BigDecimal.valueOf(i))),i+1,new java.math.BigDecimal("12.35").add(new java.math.BigDecimal("0.01").multiply(java.math.BigDecimal.valueOf(i))),i+11));
        var quote=new DfcfCsvParser.Quote("000001.SZ",date,93000125,1,new java.math.BigDecimal("12.3456"),300,new java.math.BigDecimal("3703.68"),500,new java.math.BigDecimal("6000"),55,66,new java.math.BigDecimal("12.34"),new java.math.BigDecimal("12.35"),levels);
        for(var entry:Map.of("deals",deal,"orders",order,"quotes",quote).entrySet()){Path path=directory.resolve(entry.getKey()+".ndjson.gz");try(var gzip=new GZIPOutputStream(Files.newOutputStream(path))){gzip.write((Json.write(entry.getValue())+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}artifacts.put(entry.getKey(),new L2ArchiveMaterializer.Artifact(path.getFileName().toString(),Files.size(path),sha256(path)));}
        var manifest=new L2ArchiveMaterializer.Manifest(1,L2ArchiveMaterializer.PARSER_VERSION,archiveHash,date,3,1,1,1,1,Map.of("BUY",1L),Map.of("ADD_BUY",1L),artifacts);
        Path manifestFile=directory.resolve("manifest.json");Files.writeString(manifestFile,Json.write(manifest));
        var materialized=new L2ArchiveMaterializer.Materialization(manifest,manifestFile,false);
        var ingestor=new L2ArchiveQuestDbIngestor(root,endpoint,null,new SqliteLedger(ds),Duration.ofSeconds(10),Duration.ofMillis(100));
        var result=ingestor.ingest(materialized);
        assertEquals(Map.of("deals",1L,"orders",1L,"quotes",1L),result.rows());
        assertTrue(result.targets().values().stream().allMatch(t->t.startsWith("jdb_test_l2_"+archiveHash.substring(0,16))));
        assertTrue(Files.isRegularFile(result.certificatePath()));var certificate=Json.read(Files.readString(result.certificatePath()),L2ArchiveQuestDbIngestor.CoverageCertificate.class);
        assertEquals("VERIFIED",certificate.status());assertEquals(Map.of("deals",1L,"orders",1L,"quotes",1L),certificate.sourceRows());
        assertTrue(certificate.products().values().stream().allMatch(L2ArchiveQuestDbIngestor.ProductCoverage::exactPhysicalReadback));
        Path report=Path.of("build/reports/jdb-l2-archive-ingest-live.json"),certificateCopy=Path.of("build/reports/jdb-l2-coverage-certificate.json");Files.createDirectories(report.getParent());Files.copy(result.certificatePath(),certificateCopy,StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(report,Json.write(Map.of("archiveSha256",archiveHash,"tradeDate",date,"rows",result.rows(),"targets",result.targets(),"certificate",certificateCopy.toString(),"status","VERIFIED","metadata","temporary-sqlite-file","archiveSource","synthetic-semantic-fixture")));
    }

    @Test void allThreeTypedL2TablesPassDurableWriteAndExactReadback()throws Exception {
        String url=System.getenv("JDB_QUESTDB_TEST_URL"),salt=UUID.randomUUID().toString().replace("-","").substring(0,16);
        URI endpoint=URI.create(url);var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+root.resolve("metadata.sqlite"));
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration/batch").cleanDisabled(true).load().migrate();
        var ledger=new SqliteLedger(ds);LocalDate date=LocalDate.of(2026,9,29);Instant now=Instant.now();
        var request=new RunRequest("l2-live-"+salt,"l2_archive_integrity",date,date,date,"dfcf-csv-mapper-v1-"+salt,"0",null,null,
                salt.repeat(4),"l2-content-addressed-v1","Asia/Shanghai",now,now);ledger.register(request);var durable=new DurableWriter(ledger);
        var visible=new LinkedHashMap<String,String>();
        for(var product:L2QuestDbTablePort.Product.values()) {
            String suffix=product.name().toLowerCase(Locale.ROOT),table="jdb_test_l2_"+salt+"_"+suffix;
            var rows=List.of(row(product));byte[] ilp=L2QuestDbTablePort.encode(product,table,rows);Path retained=root.resolve(suffix+".ilp");Files.write(retained,ilp);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ilp));
            var intent=new DurableWriter.Intent("l2-live-"+salt+"-"+suffix,request.instanceId(),table,"l2-live-test",hash,retained.toString(),rows.size());
            var port=new L2QuestDbTablePort(endpoint,null,product,table,rows);
            var state=durable.execute(intent,port);long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(state!=DurableWriter.Delivery.VERIFIED&&System.nanoTime()<deadline){Thread.sleep(100);state=durable.execute(intent,port);}
            assertEquals(DurableWriter.Delivery.VERIFIED,state,product.name());
            assertEquals(DurableWriter.Delivery.VERIFIED,durable.execute(intent,port),product.name());
            visible.put(suffix,table);
        }
        Path report=Path.of("build/reports/jdb-l2-questdb-live.json");Files.createDirectories(report.getParent());
        Files.writeString(report,Json.write(Map.of("tables",visible,"rowsPerTable",1,"delivery","VERIFIED","metadata","temporary-sqlite-file","archiveRows",false)));
    }
    private static Map<String,Object> row(L2QuestDbTablePort.Product product) {
        var row=new LinkedHashMap<String,Object>();row.put("event_ts","2026-09-29T01:30:00.123Z");row.put("ts_code","000001.SZ");row.put("source_row_number",1L);row.put("trade_date","2026-09-29");row.put("raw_time",93000123);
        if(product==L2QuestDbTablePort.Product.DEALS){row.put("deal_id","100");row.put("bs_flag","B");row.put("buy_order_id","200");row.put("sell_order_id","");row.put("price_cny",12.3456d);row.put("volume",100L);row.put("side","BUY");}
        else if(product==L2QuestDbTablePort.Product.ORDERS){row.put("order_id","300");row.put("vendor_type","A");row.put("vendor_code","B");row.put("price_cny",12.3456d);row.put("volume",200L);row.put("kuake_order_type",0);row.put("kind","ADD_BUY");row.put("submission",true);}
        else {row.put("tick_time_diff",1);row.put("price_cny",12.3456d);row.put("volume",300L);row.put("source_turnover",3703.68d);row.put("total_volume",500L);row.put("source_total_turnover",6000d);row.put("total_bid_volume",10L);row.put("total_ask_volume",20L);row.put("weighted_bid_price_cny",12.34d);row.put("weighted_ask_price_cny",12.35d);for(int i=1;i<=10;i++){row.put("bid_price_"+i,12.34d-i*.01);row.put("bid_volume_"+i,(long)i);row.put("ask_price_"+i,12.35d+i*.01);row.put("ask_volume_"+i,(long)i+10);}}
        return Collections.unmodifiableMap(row);
    }
    private static String sha256(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
}
