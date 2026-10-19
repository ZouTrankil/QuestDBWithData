package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.marketdata.*;
import com.zoutrankil.market.us.marketdata.*;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 当前挂牌股票日线队列；逐证券记录空响应/退市/代码映射问题，不用成功子集冒充全市场。 */
final class UsBroadDailyCollector {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Environment env;private final Path root;private final VerifiedMarketDataStore store;
    private record Task(String id,String kind,String symbol,String yahoo,String phase,String raw,String sha,String observed,String proof) {}
    UsBroadDailyCollector(Environment env,Path root,VerifiedMarketDataStore store){this.env=env;this.root=root.toAbsolutePath().normalize();this.store=store;}
    void reconcileTask(String taskId,String run,LocalDate through)throws Exception {
        if(!taskId.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原队列任务标识非法");
        try(var channel=FileChannel.open(root.resolve("us-broad.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock();var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("broad-queue.sqlite3"))) {
            if(lock==null)throw new IllegalStateException("挂牌采集器仍在运行");var selected=tasks(db,"id="+VerifiedMarketDataStore.literal(taskId));if(selected.size()!=1||!selected.getFirst().phase().equals("PUBLISHING")||!selected.getFirst().kind().equals("DAILY"))throw new IllegalArgumentException("需要唯一的原挂牌日线发布任务");
            var task=selected.getFirst();Path raw=Path.of(task.raw());byte[] bytes=Files.readAllBytes(raw);Instant observed=Instant.parse(task.observed());
            if(!VerifiedMarketDataStore.sha(("v1|DAILY|"+task.symbol()+"|"+through+"|").getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(taskId))throw new IllegalArgumentException("必须保持原队列请求截止日期");
            var rows=YahooPriceMapper.daily(bytes,task.symbol(),task.yahoo(),task.sha(),observed,LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01")),through);
            var proof=store.verifyExisting(run,UsMarketDatasets.DAILY,rows,raw,task.sha(),taskId);var response=new FrozenHttpSource.Response(raw,task.sha(),observed,task.proof(),bytes.length);
            update(db,task,"VERIFIED",response,JSON.writeValueAsString(Map.of("reconciledRun",run,"sourceUrl",task.proof(),"fullReadbackProof",proof)));System.out.println(JSON.writeValueAsString(Map.of("task",taskId,"phase","VERIFIED","proof",proof)));
        }
    }
    void run(LocalDate through)throws Exception {
        Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("us-broad.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("已有挂牌日线采集器持有队列");
            var policy=new PersistentMarketApiBudget.Policy(env.getProperty("app.market-data.yahoo-requests-per-hour",Integer.class,1800),env.getProperty("app.market-data.yahoo-requests-per-day",Integer.class,20000),100000,
                    env.getProperty("app.market-data.yahoo-monthly-bytes",Long.class,20L*1024*1024*1024),env.getProperty("app.market-data.yahoo-request-spacing-ms",Long.class,2000L));
            try(var budget=new PersistentMarketApiBudget(root.resolve("api-budget.sqlite3"),Map.of("yahoo",policy));var source=new FrozenHttpSource(root,budget,env.getProperty("app.market-data.proxy-url",""));
                var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("broad-queue.sqlite3"))) {
                try(var s=db.createStatement()){s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=5000");s.execute("CREATE TABLE IF NOT EXISTS broad_tasks(id TEXT PRIMARY KEY,kind TEXT,symbol TEXT,yahoo TEXT,phase TEXT,raw TEXT,sha TEXT,observed TEXT,proof TEXT,updated_at TEXT)");}
                boolean hasPriority=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(broad_tasks)")){while(r.next())if(r.getString("name").equals("priority"))hasPriority=true;}
                if(!hasPriority)try(var s=db.createStatement()){s.execute("ALTER TABLE broad_tasks ADD COLUMN priority INT NOT NULL DEFAULT 10");}
                db.setAutoCommit(false);try {plan(db,through);db.commit();}catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
                if(!tasks(db,"phase='PUBLISHING'").isEmpty())throw new IllegalStateException("挂牌来源原发布未核对，禁止另起运行重发");
                Files.deleteIfExists(root.resolve("us-broad-stop.request"));
                while(true) {
                    if(Files.exists(root.resolve("us-broad-stop.request"))){status(db,budget,"STOPPED",null);return;}
                    var pending=tasks(db,"phase IN ('PLANNED','FETCHED')");if(pending.isEmpty()){status(db,budget,tasks(db,"phase!='VERIFIED'").isEmpty()?"COMPLETE_CURRENT_LISTED_SCOPE":"COMPLETE_WITH_GAPS",null);return;}
                    var task=pending.getFirst();status(db,budget,"WORKING",task.id());
                    // 与核心队列共用正式账本；先让核心日线结束，之后可与分钟采集并行。
                    if(task.kind().equals("DAILY")&&coreDailyPending()){status(db,budget,"WAITING_CORE_DAILY",null);Thread.sleep(10000);continue;}
                    FrozenHttpSource.Response response;
                    if(task.phase().equals("FETCHED"))response=new FrozenHttpSource.Response(Path.of(task.raw()),task.sha(),Instant.parse(task.observed()),task.proof(),(int)Files.size(Path.of(task.raw())));
                    else {
                        long start=LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01")).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                        // 查询到实际取得时刻后的 UTC 零点，避免漏掉调整基准中发生在 through 后的拆股。
                        long end=LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                        var uri=URI.create("https://query1.finance.yahoo.com/v8/finance/chart/"+task.yahoo()+"?period1="+start+"&period2="+end+"&interval=1d&events=div,splits");
                        try {response=source.get("yahoo","public",task.yahoo(),uri,Map.of(),4*1024*1024);}
                        catch(PersistentMarketApiBudget.Exhausted wait) {status(db,budget,"WAITING_QUOTA",wait.nextAllowed.toString());Thread.sleep(Math.max(1,Math.min(10000,Duration.between(Instant.now(),wait.nextAllowed).toMillis())));continue;}
                        catch(FrozenHttpSource.SourceStatusException failed){if(failed.status==429)continue;update(db,task,"FAILED_SOURCE",null,failed.getMessage());continue;}
                        catch(Exception failed){update(db,task,"FAILED_SOURCE",null,failed.getClass().getSimpleName());continue;}
                        update(db,task,"FETCHED",response,response.sourceUrl());
                    }
                    byte[] bytes=Files.readAllBytes(response.path());if(!VerifiedMarketDataStore.sha(bytes).equals(response.sha256()))throw new IllegalStateException("挂牌来源冻结文件变化");
                    List<Map<String,Object>> rows;
                    try {rows=task.kind().equals("DAILY")?YahooPriceMapper.daily(bytes,task.symbol(),task.yahoo(),response.sha256(),response.observedAt(),LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01")),through):UsUniverseSnapshot.rows(bytes,task.kind().equals("UNIVERSE_NASDAQ"),response.sha256(),response.observedAt());}
                    catch(IllegalArgumentException malformed){update(db,task,"FAILED_SOURCE",response,malformed.getMessage());continue;}
                    update(db,task,"PUBLISHING",response,response.sourceUrl());
                    try {
                        var result=store.publish(task.kind().equals("DAILY")?UsMarketDatasets.DAILY:UsUniverseSnapshot.SECURITIES,rows,response.path(),response.sha256(),Map.of("task",task.id(),"sourceUrl",response.sourceUrl(),"requestedThrough",through.toString(),"scope","当前挂牌范围；历史退市证券和历史指数成分仍需单独核对"));
                        update(db,task,"VERIFIED",response,JSON.writeValueAsString(Map.of("result",result)));status(db,budget,"VERIFIED_TASK",task.id());
                    }catch(Exception failure){status(db,budget,"PUBLICATION_REQUIRES_RECONCILIATION",task.id());throw failure;}
                }
            }
        }
    }
    private void plan(Connection db,LocalDate through)throws Exception {
        var candidates=new TreeMap<String,String>();
        for(boolean nasdaq:new boolean[]{true,false}) {
            Path path=root.resolve("probes/"+(nasdaq?"nasdaq-listed":"nasdaq-other")+".response");byte[] bytes=Files.readAllBytes(path);String sha=VerifiedMarketDataStore.sha(bytes);Instant observed=Files.getLastModifiedTime(path).toInstant().truncatedTo(ChronoUnit.MICROS);
            var rows=UsUniverseSnapshot.rows(bytes,nasdaq,sha,observed);String kind=nasdaq?"UNIVERSE_NASDAQ":"UNIVERSE_OTHER";
            insert(db,kind,kind,"",through,path,sha,observed);
            for(var row:rows)if(Boolean.TRUE.equals(row.get("daily_candidate"))) {String original=row.get("symbol").toString(),mapped=row.get("yahoo_symbol").toString();var previous=candidates.putIfAbsent(original,mapped);if(previous!=null&&!previous.equals(mapped))throw new IllegalStateException("挂牌目录之间来源代码冲突");}
        }
        for(var candidate:candidates.entrySet())insert(db,"DAILY",candidate.getKey(),candidate.getValue(),through,null,null,null);
        // 发行人股票持仓只用于当前覆盖优先级；不把基金快照注册为官方历史指数成分。
        for(String fund:List.of("SPY","QQQ")) {
            Path file=root.resolve("probes/"+(fund.equals("SPY")?"spy-holdings.xlsx":"qqq-full-holdings.response"));if(!Files.isRegularFile(file))continue;
            byte[] bytes=Files.readAllBytes(file);String sha=VerifiedMarketDataStore.sha(bytes);Instant observed=Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.MICROS);
            var members=fund.equals("SPY")?UsFundHoldings.spy(bytes,sha,observed):UsFundHoldings.qqq(bytes,sha,observed);
            for(var member:members){String symbol=member.get("symbol").toString();if(!candidates.containsKey(symbol))insert(db,"DAILY",symbol,symbol.replace('.','-'),through,null,null,null);}
            try(var q=db.prepareStatement("UPDATE broad_tasks SET priority=0 WHERE kind='DAILY' AND symbol=?")){for(var member:members){q.setString(1,member.get("symbol").toString());q.executeUpdate();}}
        }
    }
    private static void insert(Connection db,String kind,String symbol,String yahoo,LocalDate through,Path raw,String sha,Instant observed)throws Exception {
        String id=VerifiedMarketDataStore.sha(("v1|"+kind+"|"+symbol+"|"+through+"|"+Objects.toString(sha,"")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try(var q=db.prepareStatement("INSERT OR IGNORE INTO broad_tasks(id,kind,symbol,yahoo,phase,raw,sha,observed,proof,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")){q.setString(1,id);q.setString(2,kind);q.setString(3,symbol);q.setString(4,yahoo);q.setString(5,raw==null?"PLANNED":"FETCHED");q.setString(6,raw==null?null:raw.toAbsolutePath().toString());q.setString(7,sha);q.setString(8,observed==null?null:observed.toString());q.setString(9,raw==null?null:"https://www.nasdaqtrader.com/dynamic/SymDir/"+(kind.equals("UNIVERSE_NASDAQ")?"nasdaqlisted.txt":"otherlisted.txt"));q.setString(10,Instant.now().toString());q.executeUpdate();}
    }
    private boolean coreDailyPending()throws Exception {
        Path file=root.resolve("collection-queue.sqlite3");if(!Files.isRegularFile(file))return false;
        try(var db=DriverManager.getConnection("jdbc:sqlite:file:"+file.toString().replace('\\','/')+"?mode=ro");var s=db.createStatement();var r=s.executeQuery("SELECT count() FROM us_collection_tasks WHERE kind='DAILY' AND phase IN ('PLANNED','FETCHED','PUBLISHING')")){return r.next()&&r.getLong(1)>0;}
    }
    private static List<Task> tasks(Connection db,String predicate)throws Exception {
        var out=new ArrayList<Task>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,kind,symbol,yahoo,phase,raw,sha,observed,proof FROM broad_tasks WHERE "+predicate+" ORDER BY CASE WHEN kind='DAILY' THEN 1 ELSE 0 END,priority,rowid LIMIT 1")){while(r.next())out.add(new Task(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9)));}return out;
    }
    private static void update(Connection db,Task task,String phase,FrozenHttpSource.Response response,String proof)throws Exception {
        try(var q=db.prepareStatement("UPDATE broad_tasks SET phase=?,raw=coalesce(?,raw),sha=coalesce(?,sha),observed=coalesce(?,observed),proof=?,updated_at=? WHERE id=?")){q.setString(1,phase);q.setString(2,response==null?null:response.path().toString());q.setString(3,response==null?null:response.sha256());q.setString(4,response==null?null:response.observedAt().toString());q.setString(5,proof);q.setString(6,Instant.now().toString());q.setString(7,task.id());if(q.executeUpdate()!=1)throw new IllegalStateException("挂牌来源任务缺失");}
    }
    private void status(Connection db,PersistentMarketApiBudget budget,String phase,String detail)throws Exception {
        var counts=new TreeMap<String,Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT phase,count() FROM broad_tasks GROUP BY phase")){while(r.next())counts.put(r.getString(1),r.getLong(2));}
        var card=new LinkedHashMap<String,Object>();card.put("phase",phase);card.put("detail",detail);card.put("pid",ProcessHandle.current().pid());card.put("heartbeatAt",Instant.now().toString());card.put("tasks",counts);card.put("apiBudget",budget.usage());
        if(!AtomicMarketStatus.write(root.resolve("us-broad-status.json"),JSON.writeValueAsBytes(card)))System.err.println("状态页暂被读取占用；正式结果以 SQLite 为准");System.out.println(JSON.writeValueAsString(card));
    }
}
