package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Streams immutable semantic L2 artifacts to archive-scoped QuestDB tables through the durable writer protocol. */
public final class L2ArchiveQuestDbIngestor {
    private static final int BATCH_ROWS=500;
    private final Path root;private final URI endpoint;private final String authorization;private final SqliteLedger ledger;private final DurableWriter writer;private final Duration visibilityTimeout,pollInterval;
    public L2ArchiveQuestDbIngestor(Path archiveRoot,URI endpoint,String authorization,SqliteLedger ledger,Duration visibilityTimeout,Duration pollInterval)throws IOException {
        this.root=archiveRoot.toAbsolutePath().normalize();this.endpoint=endpoint;this.authorization=authorization;this.ledger=Objects.requireNonNull(ledger);this.writer=new DurableWriter(ledger);this.visibilityTimeout=Objects.requireNonNull(visibilityTimeout);this.pollInterval=Objects.requireNonNull(pollInterval);
        if(visibilityTimeout.isNegative()||visibilityTimeout.isZero()||pollInterval.toMillis()<1||pollInterval.compareTo(visibilityTimeout)>0)throw new IllegalArgumentException("Positive bounded QuestDB visibility timeout and poll interval required");
        if(endpoint!=null&&(!Set.of("http","https").contains(endpoint.getScheme())||endpoint.getHost()==null))throw new IllegalArgumentException("QuestDB HTTP endpoint must be an HTTP origin");
    }
    public boolean configured(){return endpoint!=null;}
    public Result ingest(L2ArchiveMaterializer.Materialization materialization)throws Exception {
        if(!configured())throw new IllegalStateException("L2 QuestDB writer is not configured; semantic artifacts remain staged and are not Ready");
        var manifest=materialization.manifest();Path manifestFile=materialization.manifestPath().toAbsolutePath().normalize();
        if(!manifestFile.startsWith(root.resolve("parsed"))||!manifestFile.getFileName().toString().equals("manifest.json"))throw new IllegalArgumentException("L2 manifest must be inside the configured parsed archive root");
        var request=businessRequest(manifest);ledger.register(request);Path archiveDir=manifestFile.getParent();Path outbox=archiveDir.resolve("outbox");Files.createDirectories(outbox);if(Files.isSymbolicLink(outbox))throw new IOException("L2 outbox cannot be a symbolic link");
        var sent=new LinkedHashMap<String,Long>();var products=new LinkedHashMap<String,ProductCoverage>();for(var product:L2QuestDbTablePort.Product.values()){
            String productName=product.name().toLowerCase(Locale.ROOT);var artifact=manifest.artifacts().get(productName);if(artifact==null)throw new IOException("L2 manifest is missing "+productName);
            Path source=archiveDir.resolve(artifact.file()).normalize();if(!source.getParent().equals(archiveDir)||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.size(source)!=artifact.bytes()||!sha256(source).equals(artifact.sha256()))throw new IOException("L2 materialized source artifact failed hash verification");
            String table="jdb_test_l2_"+manifest.archiveSha256().substring(0,16)+"_"+productName;
            var batches=new ArrayList<String>();long count=streamProduct(source,product,table,manifest.archiveSha256(),request,outbox,batches);long expected=switch(product){case DEALS->manifest.dealRows();case ORDERS->manifest.orderRows();case QUOTES->manifest.quoteRows();};
            if(count!=expected)throw new IOException("L2 parsed row count differs from manifest for "+productName);sent.put(productName,count);
            products.put(productName,new ProductCoverage(artifact.sha256(),count,table,List.copyOf(batches),true));
        }
        var certificate=new CoverageCertificate(1,"VERIFIED",request.instanceId(),manifest.tradeDate(),manifest.parserVersion(),manifest.archiveSha256(),manifest.members(),manifest.symbols(),Map.copyOf(sent),Map.copyOf(products));
        Path certificatePath=archiveDir.resolve("ingest-certificate.json");publishCertificate(certificatePath,certificate);
        return new Result(request.instanceId(),Map.copyOf(sent),products.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,e->e.getValue().target())),certificatePath);
    }
    private long streamProduct(Path source,L2QuestDbTablePort.Product product,String table,String archiveHash,RunRequest request,Path outbox,List<String> batches)throws Exception {
        long total=0;int chunk=0;var rows=new ArrayList<Map<String,Object>>(BATCH_ROWS);
        try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(source)),java.nio.charset.StandardCharsets.UTF_8),64*1024)){
            for(String line;(line=in.readLine())!=null;){if(line.isBlank())throw new IOException("Blank row in L2 JSONL artifact");rows.add(map(Json.MAPPER.readTree(line),product,total+1));total++;if(rows.size()==BATCH_ROWS){batches.add(writeChunk(rows,product,table,archiveHash,request,outbox,chunk++));rows.clear();}}
        }
        if(!rows.isEmpty()||chunk==0)batches.add(writeChunk(rows,product,table,archiveHash,request,outbox,chunk));
        return total;
    }
    private String writeChunk(List<Map<String,Object>> rows,L2QuestDbTablePort.Product product,String table,String archiveHash,RunRequest request,Path outbox,int chunk)throws Exception {
        byte[] bytes=L2QuestDbTablePort.encode(product,table,rows);String fileName=product.name().toLowerCase(Locale.ROOT)+"-"+String.format(Locale.ROOT,"%06d",chunk)+".ilp";Path artifact=outbox.resolve(fileName);
        if(Files.exists(artifact,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(artifact,LinkOption.NOFOLLOW_LINKS)||!Arrays.equals(bytes,Files.readAllBytes(artifact)))throw new IOException("Existing L2 retained batch conflicts with the immutable parsed source");}
        else {Path tmp=Files.createTempFile(outbox,".l2-batch-",".partial");try{Files.write(tmp,bytes,StandardOpenOption.TRUNCATE_EXISTING);try{Files.move(tmp,artifact,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){throw new IOException("Atomic publication of L2 write batch required",e);}}finally{Files.deleteIfExists(tmp);}}
        String fp=sha256(artifact);String batchId=archiveHash+"-"+product.name().toLowerCase(Locale.ROOT)+"-"+String.format(Locale.ROOT,"%06d",chunk);var intent=new DurableWriter.Intent(batchId,request.instanceId(),table,"java-l2-archive:"+product.name().toLowerCase(Locale.ROOT),fp,artifact.toString(),rows.size());
        var port=new L2QuestDbTablePort(endpoint,authorization,product,table,rows);var result=writer.execute(intent,port);long deadline=System.nanoTime()+visibilityTimeout.toNanos();
        while(result!=DurableWriter.Delivery.VERIFIED&&result!=DurableWriter.Delivery.BLOCKED&&System.nanoTime()<deadline){Thread.sleep(pollInterval.toMillis());result=writer.execute(intent,port);}
        if(result!=DurableWriter.Delivery.VERIFIED)throw new IllegalStateException("L2 batch did not reach VERIFIED within configured visibility bound: "+batchId+" state="+result);return batchId;
    }
    private static void publishCertificate(Path path,CoverageCertificate certificate)throws IOException {
        String json=Json.write(certificate);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("L2 ingestion certificate path is not a regular file");try{var old=Json.MAPPER.readValue(Files.readAllBytes(path),CoverageCertificate.class);if(!old.equals(certificate))throw new IOException("Existing L2 ingestion certificate conflicts with verified batches");return;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IOException("Existing L2 ingestion certificate is invalid",e);}}
        Path tmp=Files.createTempFile(path.getParent(),".l2-certificate-",".partial");try{Files.writeString(tmp,json,java.nio.charset.StandardCharsets.UTF_8,StandardOpenOption.TRUNCATE_EXISTING);try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){throw new IOException("Atomic L2 certificate publication required",e);}}finally{Files.deleteIfExists(tmp);}
    }
    private static RunRequest businessRequest(L2ArchiveMaterializer.Manifest m){String version=L2ArchiveMaterializer.PARSER_VERSION+"-"+m.archiveSha256();Instant now=Instant.now();return new RunRequest("l2-"+m.archiveSha256(),"l2_archive_integrity",m.tradeDate(),m.tradeDate(),m.tradeDate(),version,"0",null,null,m.archiveSha256(),"l2-content-addressed-v1","Asia/Shanghai",now,now);}
    private static Map<String,Object> map(JsonNode node,L2QuestDbTablePort.Product product,long sourceRowNumber)throws IOException {
        String symbol=text(node,"symbol"),day=text(node,"tradeDate");int rawTime=integer(node,"time");var row=new LinkedHashMap<String,Object>();row.put("event_ts",eventTime(day,rawTime).toString());row.put("ts_code",symbol);row.put("source_row_number",sourceRowNumber);row.put("trade_date",day);row.put("raw_time",rawTime);
        switch(product){case DEALS->{row.put("deal_id",text(node,"dealId"));row.put("bs_flag",text(node,"bsFlag"));row.put("buy_order_id",text(node,"buyOrderId"));row.put("sell_order_id",text(node,"sellOrderId"));row.put("price_cny",number(node,"priceCny"));row.put("volume",longNumber(node,"volume"));row.put("side",text(node,"side"));}
            case ORDERS->{row.put("order_id",text(node,"orderId"));row.put("vendor_type",text(node,"vendorType"));row.put("vendor_code",text(node,"vendorCode"));row.put("price_cny",number(node,"priceCny"));row.put("volume",longNumber(node,"volume"));row.put("kuake_order_type",node.get("kuakeOrderType")==null||node.get("kuakeOrderType").isNull()?null:node.get("kuakeOrderType").intValue());row.put("kind",text(node,"kind"));row.put("submission",node.path("submission").asBoolean());}
            case QUOTES->{row.put("tick_time_diff",integer(node,"tickTimeDiff"));nullableNumber(row,"price_cny",node.get("priceCny"));row.put("volume",longNumber(node,"volume"));row.put("source_turnover",number(node,"sourceTurnover"));row.put("total_volume",longNumber(node,"totalVolume"));row.put("source_total_turnover",number(node,"sourceTotalTurnover"));row.put("total_bid_volume",longNumber(node,"totalBidVolume"));row.put("total_ask_volume",longNumber(node,"totalAskVolume"));nullableNumber(row,"weighted_bid_price_cny",node.get("weightedBidPriceCny"));nullableNumber(row,"weighted_ask_price_cny",node.get("weightedAskPriceCny"));JsonNode levels=node.path("levels");if(!levels.isArray()||levels.size()!=10)throw new IOException("L2 quote must contain ten book levels");for(int i=0;i<10;i++){JsonNode level=levels.get(i);nullableNumber(row,"bid_price_"+(i+1),level.get("bidPriceCny"));row.put("bid_volume_"+(i+1),level.path("bidVolume").longValue());nullableNumber(row,"ask_price_"+(i+1),level.get("askPriceCny"));row.put("ask_volume_"+(i+1),level.path("askVolume").longValue());}}
        }
        return Collections.unmodifiableMap(row);
    }
    private static Instant eventTime(String date,int raw)throws IOException {try{if(raw<0||raw>235959999)throw new DateTimeException("range");String time=String.format(Locale.ROOT,"%09d",raw);int hour=Integer.parseInt(time.substring(0,2)),minute=Integer.parseInt(time.substring(2,4)),second=Integer.parseInt(time.substring(4,6)),milli=Integer.parseInt(time.substring(6,9));return LocalDate.parse(date).atTime(LocalTime.of(hour,minute,second,milli*1_000_000)).atZone(ZoneId.of("Asia/Shanghai")).toInstant();}catch(RuntimeException e){throw new IOException("Invalid L2 event timestamp",e);}}
    private static String text(JsonNode n,String k)throws IOException{var v=n.get(k);if(v==null||!v.isTextual())throw new IOException("Missing L2 text field "+k);return v.asText();}
    private static int integer(JsonNode n,String k)throws IOException{var v=n.get(k);if(v==null||!v.canConvertToInt())throw new IOException("Invalid L2 integer field "+k);return v.intValue();}
    private static long longNumber(JsonNode n,String k)throws IOException{var v=n.get(k);if(v==null||!v.canConvertToLong())throw new IOException("Invalid L2 long field "+k);return v.longValue();}
    private static double number(JsonNode n,String k)throws IOException{var v=n.get(k);if(v==null||v.isNull()||!v.isNumber()||!Double.isFinite(v.doubleValue()))throw new IOException("Invalid L2 number field "+k);return v.doubleValue();}
    private static void nullableNumber(Map<String,Object> row,String k,JsonNode v)throws IOException{if(v==null||v.isNull()){row.put(k,null);return;}if(!v.isNumber()||!Double.isFinite(v.doubleValue()))throw new IOException("Invalid optional L2 number "+k);row.put(k,v.doubleValue());}
    private static String sha256(Path file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[64*1024];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
    public record ProductCoverage(String sourceArtifactSha256,long rows,String target,List<String> verifiedBatches,boolean exactPhysicalReadback){}
    public record CoverageCertificate(int schemaVersion,String status,String businessInstanceId,LocalDate tradeDate,String parserVersion,
                                      String archiveSha256,int archiveMembers,int symbols,Map<String,Long> sourceRows,Map<String,ProductCoverage> products){}
    public record Result(String instanceId,Map<String,Long> rows,Map<String,String> targets,Path certificatePath){}
}
