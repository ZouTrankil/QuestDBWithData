package com.zoutrankil.market.btc.app;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.marketdata.*;
import com.zoutrankil.market.btc.marketdata.*;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Binance 校验归档和 Bybit 月窗口逐个落库；每项仅在小时、日线全部回读后推进。 */
final class CryptoHistoryCollector {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Environment env;private final Path root;private final VerifiedMarketDataStore store;
    private record Task(String id,String source,String symbol,String instrument,LocalDate from,LocalDate through,String phase,String raw,String sha,String observed,String proof) {}
    CryptoHistoryCollector(Environment env,Path root,VerifiedMarketDataStore store){this.env=env;this.root=root.toAbsolutePath().normalize();this.store=store;}
    void run(LocalDate through)throws Exception {
        Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("collector.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("已有 BTC 采集器持有队列");
            var policy=new PersistentMarketApiBudget.Policy(env.getProperty("app.crypto-data.requests-per-hour",Integer.class,2000),env.getProperty("app.crypto-data.requests-per-day",Integer.class,20000),10000,
                    env.getProperty("app.crypto-data.bytes-per-31-days",Long.class,5368709120L),env.getProperty("app.crypto-data.request-spacing-ms",Long.class,400L));
            try(var budget=new PersistentMarketApiBudget(root.resolve("api-budget.sqlite3"),Map.of("binance",policy,"bybit",policy));
                var source=new FrozenHttpSource(root,budget,env.getProperty("app.crypto-data.proxy-url",""));var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("queue.sqlite3"))) {
                try(var s=db.createStatement()) {s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=5000");
                    s.execute("CREATE TABLE IF NOT EXISTS crypto_tasks(id TEXT PRIMARY KEY,source TEXT,symbol TEXT,instrument TEXT,from_date TEXT,through_date TEXT,phase TEXT,raw TEXT,sha TEXT,observed TEXT,proof TEXT,updated_at TEXT)");}
                boolean failuresColumn=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(crypto_tasks)")){while(r.next())if(r.getString("name").equals("source_failures"))failuresColumn=true;}
                if(!failuresColumn)try(var s=db.createStatement()){s.execute("ALTER TABLE crypto_tasks ADD COLUMN source_failures INT NOT NULL DEFAULT 0");}
                plan(db,LocalDate.parse(env.getRequiredProperty("app.crypto-data.history-start")),through);
                if(!tasks(db,"phase='PUBLISHING'").isEmpty())throw new IllegalStateException("BTC 原发布未核对，禁止自动重发");
                Files.deleteIfExists(root.resolve("stop.request"));
                while(true) {
                    if(Files.exists(root.resolve("stop.request"))){status(db,budget,"STOPPED",null);return;}
                    var work=tasks(db,"phase IN ('PLANNED','FETCHED')");if(work.isEmpty()){status(db,budget,tasks(db,"phase!='VERIFIED'").isEmpty()?"COMPLETE":"COMPLETE_WITH_GAPS",null);return;}
                    var task=work.getFirst();status(db,budget,"WORKING",task.id());FrozenHttpSource.Response response;String proof;
                    if(task.phase().equals("FETCHED")){response=new FrozenHttpSource.Response(Path.of(task.raw()),task.sha(),Instant.parse(task.observed()),"retained",0);proof=task.proof();}
                    else {
                        try {
                            if(task.source().startsWith("BINANCE")) {
                                String url=binanceUrl(task);var checksum=get(source,"binance",URI.create(url+".CHECKSUM"),task.symbol(),1024,budget,db);
                                String expected=Files.readString(checksum.path()).trim().split("\\s+")[0];if(!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Binance 校验和非法");
                                response=get(source,"binance",URI.create(url),task.symbol(),2*1024*1024,budget,db);
                                if(!expected.equals(response.sha256()))throw new IllegalStateException("Binance 归档与官方 CHECKSUM 不符");
                                proof=JSON.writeValueAsString(Map.of("sourceUrl",url,"checksumPath",checksum.path().toString(),"checksumSha256",checksum.sha256(),"officialArchiveSha256",expected));
                            } else {
                                long start=task.from().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),end=task.through().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()-1;
                                String url="https://api.bybit.com/v5/market/kline?category="+(task.instrument().equals("SPOT")?"spot":"linear")+"&symbol="+task.symbol()+"&interval=60&limit=1000&start="+start+"&end="+end;
                                response=get(source,"bybit",URI.create(url),task.symbol(),2*1024*1024,budget,db);proof=JSON.writeValueAsString(Map.of("sourceUrl",url,"requestedFrom",task.from().toString(),"requestedThrough",task.through().toString()));
                            }
                        } catch(Exception failure) {update(db,task,"FAILED_SOURCE",null,failure.getClass().getSimpleName()+": "+failure.getMessage());status(db,budget,"SOURCE_GAP",task.id());continue;}
                        update(db,task,"FETCHED",response,proof);
                    }
                    if(task.source().equals("BYBIT_MONTH")) {
                        var body=JSON.readTree(Files.readAllBytes(response.path()));int code=body.path("retCode").asInt(-1);
                        if(code!=0) {
                            int failures;try(var q=db.prepareStatement("UPDATE crypto_tasks SET source_failures=source_failures+1 WHERE id=?")){q.setString(1,task.id());q.executeUpdate();}
                            try(var q=db.prepareStatement("SELECT source_failures FROM crypto_tasks WHERE id=?")){q.setString(1,task.id());try(var r=q.executeQuery()){r.next();failures=r.getInt(1);}}
                            boolean retry=retryableBybit(code)&&failures<3;var next=Instant.now().plusSeconds(code==10006||code==10018?60:10);
                            String requestId=response.path().getFileName().toString().replaceFirst("\\.response$","");
                            if(retry)budget.complete(requestId,code==10006||code==10018?429:503,(int)Files.size(response.path()),next);
                            var error=Map.of("httpStatus",200,"providerCode",code,"attempt",failures,"retry",retry,"nextAllowed",next.toString(),"rawPath",response.path().toString(),"sourceSha256",response.sha256());
                            Path errors=root.resolve("source-errors");Files.createDirectories(errors);Files.write(errors.resolve(UUID.randomUUID()+".json"),JSON.writeValueAsBytes(error),StandardOpenOption.CREATE_NEW);
                            update(db,task,retry?"PLANNED":"FAILED_SOURCE",response,JSON.writeValueAsString(error));status(db,budget,retry?"SOURCE_RETRY":"SOURCE_GAP",task.id());continue;
                        }
                    }
                    try {
                        byte[] bytes=Files.readAllBytes(response.path());if(!VerifiedMarketDataStore.sha(bytes).equals(response.sha256()))throw new IllegalStateException("BTC 冻结响应摘要变化");
                        var rows=task.source().startsWith("BINANCE")?CryptoPriceMapper.binance(bytes,task.symbol(),!task.instrument().equals("SPOT"),response.sha256(),response.observedAt(),task.from(),task.through()):bybit(bytes,task,response);
                        var daily=CryptoPriceMapper.daily(rows);var all=new ArrayList<>(rows);all.addAll(daily);
                        update(db,task,"PUBLISHING",response,proof);
                        var result=store.publish(CryptoMarketDataset.BARS,all,response.path(),response.sha256(),Map.of("task",task.id(),"sourceProof",JSON.readTree(proof),"dailyConstruction","每个 UTC 日必须有连续 24 根真实小时线；OHLC 和成交量由这些小时线合成"));
                        update(db,task,"VERIFIED",response,JSON.writeValueAsString(Map.of("result",result,"sourceProof",JSON.readTree(proof),"hourlyRows",rows.size(),"dailyRows",daily.size())));status(db,budget,"VERIFIED_TASK",task.id());
                    } catch(Exception failed){status(db,budget,"PUBLICATION_REQUIRES_RECONCILIATION",task.id());throw failed;}
                }
            }
        }
    }
    static boolean retryableBybit(int code){return Set.of(10000,10006,10016,10018).contains(code);}
    private FrozenHttpSource.Response get(FrozenHttpSource source,String provider,URI uri,String symbol,int max,PersistentMarketApiBudget budget,Connection db)throws Exception {
        while(true) {
            try{return source.get(provider,"public",symbol,uri,Map.of(),max);}
            catch(PersistentMarketApiBudget.Exhausted wait) {
                while(Instant.now().isBefore(wait.nextAllowed)) {status(db,budget,"WAITING_QUOTA",wait.nextAllowed.toString());if(Files.exists(root.resolve("stop.request")))throw new InterruptedException("已收到停止请求");Thread.sleep(Math.max(1,Math.min(10000,Duration.between(Instant.now(),wait.nextAllowed).toMillis())));}
            } catch(FrozenHttpSource.SourceStatusException failure){if(failure.status!=429)throw failure;}
        }
    }
    private static List<Map<String,Object>> bybit(byte[] bytes,Task task,FrozenHttpSource.Response source)throws Exception {
        var document=JSON.readTree(bytes);if(document.path("retCode").asInt(-1)!=0||!document.path("result").path("symbol").asText().equals(task.symbol()))throw new IllegalArgumentException("Bybit 未返回所请求币对的成功响应");
        String category=task.instrument().equals("SPOT")?"spot":"linear";if(!document.path("result").path("category").asText().equals(category))throw new IllegalArgumentException("Bybit 产品类别不符");
        var output=new ArrayList<Map<String,Object>>();var list=document.path("result").path("list");if(!list.isArray()||list.size()>=1000)throw new IllegalArgumentException("Bybit 来源为空或达到截断边界");
        for(var c:list) {if(c.size()!=7)throw new IllegalArgumentException("Bybit 字段数量不符");var values=new ArrayList<String>();for(int i=1;i<7;i++)values.add(c.get(i).asText());output.add(CryptoPriceMapper.row(Instant.ofEpochMilli(Long.parseLong(c.get(0).asText())),"BYBIT",task.symbol(),task.instrument(),60,values,source.sha256(),source.observedAt()));}
        CryptoPriceMapper.requireContinuous(output,task.from(),task.through(),60);return output;
    }
    private static String binanceUrl(Task task) {
        String interval=task.source().equals("BINANCE_MONTH")?"monthly":"daily",path=task.instrument().equals("SPOT")?"spot":"futures/um";
        String period=interval.equals("monthly")?YearMonth.from(task.from()).toString():task.from().toString();
        return "https://data.binance.vision/data/"+path+"/"+interval+"/klines/"+task.symbol()+"/1h/"+task.symbol()+"-1h-"+period+".zip";
    }
    private static void plan(Connection db,LocalDate from,LocalDate through)throws Exception {
        if(!from.equals(from.withDayOfMonth(1))||through.isBefore(from))throw new IllegalArgumentException("BTC 历史从整月第一天开始");
        var current=YearMonth.now(ZoneOffset.UTC);
        for(var month=YearMonth.from(through);!month.isBefore(YearMonth.from(from));month=month.minusMonths(1))for(String symbol:List.of("BTCUSDT","ETHUSDT"))for(String instrument:List.of("SPOT","USDT_PERPETUAL")) {
            var start=month.atDay(1);var end=month.atEndOfMonth();if(end.isAfter(through))end=through;
            insert(db,"BYBIT_MONTH",symbol,instrument,start,end);
            if(month.isBefore(current)&&end.equals(month.atEndOfMonth()))insert(db,"BINANCE_MONTH",symbol,instrument,start,end);
            else for(var day=start;!day.isAfter(end);day=day.plusDays(1))insert(db,"BINANCE_DAY",symbol,instrument,day,day);
        }
    }
    private static void insert(Connection db,String source,String symbol,String instrument,LocalDate from,LocalDate through)throws Exception {
        String id=VerifiedMarketDataStore.sha(("v1|"+source+"|"+symbol+"|"+instrument+"|"+from+"|"+through).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try(var q=db.prepareStatement("INSERT OR IGNORE INTO crypto_tasks(id,source,symbol,instrument,from_date,through_date,phase,updated_at) VALUES(?,?,?,?,?,?,'PLANNED',?)")){q.setString(1,id);q.setString(2,source);q.setString(3,symbol);q.setString(4,instrument);q.setString(5,from.toString());q.setString(6,through.toString());q.setString(7,Instant.now().toString());q.executeUpdate();}
    }
    private static List<Task> tasks(Connection db,String predicate)throws Exception {
        var tasks=new ArrayList<Task>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,source,symbol,instrument,from_date,through_date,phase,raw,sha,observed,proof FROM crypto_tasks WHERE "+predicate+" ORDER BY rowid")){while(r.next())tasks.add(new Task(r.getString(1),r.getString(2),r.getString(3),r.getString(4),LocalDate.parse(r.getString(5)),LocalDate.parse(r.getString(6)),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11)));}return tasks;
    }
    private static void update(Connection db,Task task,String phase,FrozenHttpSource.Response response,String proof)throws Exception {
        try(var q=db.prepareStatement("UPDATE crypto_tasks SET phase=?,raw=coalesce(?,raw),sha=coalesce(?,sha),observed=coalesce(?,observed),proof=?,updated_at=? WHERE id=?")){q.setString(1,phase);q.setString(2,response==null?null:response.path().toString());q.setString(3,response==null?null:response.sha256());q.setString(4,response==null?null:response.observedAt().toString());q.setString(5,proof);q.setString(6,Instant.now().toString());q.setString(7,task.id());if(q.executeUpdate()!=1)throw new IllegalStateException("BTC 任务缺失");}
    }
    private void status(Connection db,PersistentMarketApiBudget budget,String phase,String detail)throws Exception {
        var counts=new TreeMap<String,Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT phase,count() FROM crypto_tasks GROUP BY phase")){while(r.next())counts.put(r.getString(1),r.getLong(2));}
        var card=new LinkedHashMap<String,Object>();card.put("phase",phase);card.put("detail",detail);card.put("pid",ProcessHandle.current().pid());card.put("heartbeatAt",Instant.now().toString());card.put("tasks",counts);card.put("apiBudget",budget.usage());
        if(!AtomicMarketStatus.write(root.resolve("status.json"),JSON.writeValueAsBytes(card)))System.err.println("状态页暂被读取占用；正式结果以 SQLite 为准");System.out.println(JSON.writeValueAsString(card));
    }
}
