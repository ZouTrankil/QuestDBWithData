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

/** 当前挂牌和供应商历史目录共同规划；逐证券保留不可得、退市和映射缺口。 */
final class UsBroadDailyCollector {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Environment env;private final Path root;private final VerifiedMarketDataStore store;
    private record Task(String id,String kind,String symbol,String yahoo,String phase,String raw,String sha,String observed,String proof,String bundle) {}
    UsBroadDailyCollector(Environment env,Path root,VerifiedMarketDataStore store){this.env=env;this.root=root.toAbsolutePath().normalize();this.store=store;}
    void reconcileTask(String taskId,String run,LocalDate through)throws Exception {
        if(!taskId.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原队列任务标识非法");
        try(var channel=FileChannel.open(root.resolve("us-broad.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock();var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("broad-queue.sqlite3"))) {
            if(lock==null)throw new IllegalStateException("挂牌采集器仍在运行");var selected=tasks(db,"id="+VerifiedMarketDataStore.literal(taskId));if(selected.size()!=1||!selected.getFirst().phase().equals("PUBLISHING")||!selected.getFirst().kind().equals("DAILY"))throw new IllegalArgumentException("需要唯一的原挂牌日线发布任务");
            var task=selected.getFirst();if(task.bundle()!=null)throw new IllegalArgumentException("该任务属于冻结来源组，请使用 reconcile-broad-bundle");Path raw=Path.of(task.raw());byte[] bytes=Files.readAllBytes(raw);Instant observed=Instant.parse(task.observed());
            if(!VerifiedMarketDataStore.sha(("v1|DAILY|"+task.symbol()+"|"+through+"|").getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(taskId))throw new IllegalArgumentException("必须保持原队列请求截止日期");
            var rows=YahooPriceMapper.daily(bytes,task.symbol(),task.yahoo(),task.sha(),observed,LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01")),through);
            var proof=store.verifyExisting(run,UsMarketDatasets.DAILY,rows,raw,task.sha(),taskId);var response=new FrozenHttpSource.Response(raw,task.sha(),observed,task.proof(),bytes.length);
            update(db,task,"VERIFIED",response,JSON.writeValueAsString(Map.of("reconciledRun",run,"sourceUrl",task.proof(),"fullReadbackProof",proof)));System.out.println(JSON.writeValueAsString(Map.of("task",taskId,"phase","VERIFIED","proof",proof)));
        }
    }
    void reconcileBundle(String bundle,String run)throws Exception {
        if(!bundle.matches("broad-[a-f0-9-]{36}"))throw new IllegalArgumentException("日线来源组标识非法");
        try(var channel=FileChannel.open(root.resolve("us-broad.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock();var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("broad-queue.sqlite3"))) {
            if(lock==null)throw new IllegalStateException("挂牌采集器仍在运行");String raw,sha;
            try(var s=db.prepareStatement("SELECT raw,sha FROM broad_bundles WHERE id=? AND phase='PUBLISHING'")){s.setString(1,bundle);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("需要原始未推进检查点的来源组");raw=r.getString(1);sha=r.getString(2);}}
            byte[] bytes=Files.readAllBytes(Path.of(raw));if(!VerifiedMarketDataStore.sha(bytes).equals(sha))throw new IllegalStateException("日线组冻结文件变化");if(UsYahooAnnualBundle.annual(bytes))throw new IllegalArgumentException("年度组请用 reconcile-broad-window 指定原窗口及运行");var parsed=UsYahooDailyBundle.parse(bytes);if(!parsed.bundle().equals(bundle))throw new IllegalStateException("日线组身份不符");
            var proof=store.verifyExistingBundle(run,UsMarketDatasets.DAILY,parsed.rows(),Path.of(raw),sha,bundle);finishBundle(db,parsed,Map.of("verifiedRun",run,"fullReadbackProof",proof));System.out.println(JSON.writeValueAsString(Map.of("bundle",bundle,"phase","VERIFIED","proof",proof)));
        }
    }
    void reconcileWindow(String bundle,int window,String run)throws Exception {
        if(!bundle.matches("broad-[a-f0-9-]{36}"))throw new IllegalArgumentException("原年度组标识非法");
        try(var channel=FileChannel.open(root.resolve("us-broad.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock();var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("broad-queue.sqlite3"))) {
            if(lock==null)throw new IllegalStateException("挂牌采集器仍在运行");
            Path raw;String sha;
            try(var s=db.prepareStatement("SELECT raw,sha FROM broad_bundles WHERE id=? AND phase='PUBLISHING'")) {
                s.setString(1,bundle);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("需要原发布态年度组");raw=Path.of(r.getString(1));sha=r.getString(2);}
            }
            byte[] frozen=Files.readAllBytes(raw);if(!VerifiedMarketDataStore.sha(frozen).equals(sha))throw new IllegalStateException("原年度 ZIP 变化");var parsed=UsYahooAnnualBundle.parse(frozen);if(!parsed.bundle().equals(bundle))throw new IllegalStateException("原年度组身份不符");new UsAnnualDailyPublication(root,store).reconcile(db,parsed,window,run,raw,sha);
            System.out.println(JSON.writeValueAsString(Map.of("bundle",bundle,"window",window,"phase","VERIFIED","run",run,"scope","仅核对原窗口；collect-broad 将继续未发送的其余窗口")));
        }
    }
    void run(LocalDate through)throws Exception {
        Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("us-broad.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("已有挂牌日线采集器持有队列");
            Files.deleteIfExists(root.resolve("us-broad-stop.request"));
            var policy=new PersistentMarketApiBudget.Policy(env.getProperty("app.market-data.yahoo-requests-per-hour",Integer.class,1800),env.getProperty("app.market-data.yahoo-requests-per-day",Integer.class,20000),100000,
                    env.getProperty("app.market-data.yahoo-monthly-bytes",Long.class,20L*1024*1024*1024),env.getProperty("app.market-data.yahoo-request-spacing-ms",Long.class,2000L));
            var referencePolicy=new PersistentMarketApiBudget.Policy(30,100,1000,128L*1024*1024,2000);
            try(var budget=new PersistentMarketApiBudget(root.resolve("api-budget.sqlite3"),Map.of("yahoo",policy,"reference",referencePolicy));var source=new FrozenHttpSource(root,budget,env.getProperty("app.market-data.proxy-url",""));
                var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("broad-queue.sqlite3"))) {
                try(var s=db.createStatement()){s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=5000");s.execute("CREATE TABLE IF NOT EXISTS broad_tasks(id TEXT PRIMARY KEY,kind TEXT,symbol TEXT,yahoo TEXT,phase TEXT,raw TEXT,sha TEXT,observed TEXT,proof TEXT,updated_at TEXT)");}
                boolean hasPriority=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(broad_tasks)")){while(r.next())if(r.getString("name").equals("priority"))hasPriority=true;}
                if(!hasPriority)try(var s=db.createStatement()){s.execute("ALTER TABLE broad_tasks ADD COLUMN priority INT NOT NULL DEFAULT 10");}
                boolean hasBundle=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(broad_tasks)")){while(r.next())if(r.getString("name").equals("bundle_id"))hasBundle=true;}
                if(!hasBundle)try(var s=db.createStatement()){s.execute("ALTER TABLE broad_tasks ADD COLUMN bundle_id TEXT");}
                // 范围复核按证券反复查原任务；加索引避免数千次扫描整个历史队列。
                try(var s=db.createStatement()){s.execute("CREATE INDEX IF NOT EXISTS broad_tasks_security_phase ON broad_tasks(kind,symbol,phase)");}
                try(var s=db.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS broad_bundles(id TEXT PRIMARY KEY,raw TEXT NOT NULL,sha TEXT NOT NULL,phase TEXT NOT NULL,proof TEXT,updated_at TEXT NOT NULL)");}
                UsAnnualDailyPublication.createTables(db);
                var references=references(source,budget,through);
                status(db,budget,"PLANNING",null);
                db.setAutoCommit(false);try {plan(db,through,references);db.commit();}catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
                if(!resumeAnnual(db,budget,through))return;
                if(!tasks(db,"phase='PUBLISHING'").isEmpty())throw new IllegalStateException("挂牌来源原发布未核对，禁止另起运行重发");
                // 只有持有原队列锁且规划完成后才启动单一生产者；正式写入仍只有当前线程。
                boolean pipelined=env.getProperty("app.market-data.broad-prefetch-enabled",Boolean.class,true);
                try(var prefetch=pipelined?new UsBroadDailyPrefetch(env,root,through):null) {
                while(true) {
                    if(prefetch!=null)prefetch.check();
                    if(Files.exists(root.resolve("us-broad-stop.request"))){status(db,budget,"STOPPED",null);return;}
                    var pending=tasks(db,pipelined?"((kind!='DAILY' AND phase IN ('PLANNED','FETCHED')) OR (kind='DAILY' AND phase='FETCHED'))":"phase IN ('PLANNED','FETCHED')");
                    if(pending.isEmpty()) {
                        if(prefetch!=null&&!prefetch.finished()){status(db,budget,"WAITING_PREFETCH",null);Thread.sleep(1000);continue;}
                        if(prefetch!=null)prefetch.check();
                        // 生产者可能刚在本次查询后保存最后一个来源，不能漏掉尾组便宣布结束。
                        if(pipelined&&!tasks(db,"kind='DAILY' AND phase='FETCHED'").isEmpty())continue;
                        status(db,budget,tasks(db,"phase!='VERIFIED'").isEmpty()?"COMPLETE_PLANNED_SOURCE_SCOPE":"COMPLETE_WITH_GAPS",null);return;
                    }
                    var task=pending.getFirst();status(db,budget,"WORKING",task.id());
                    // 与核心队列共用正式账本；先让核心日线结束，之后可与分钟采集并行。
                    if(task.kind().equals("DAILY")&&coreDailyPending()){status(db,budget,"WAITING_CORE_DAILY",null);Thread.sleep(10000);continue;}
                    if(task.kind().equals("DAILY")) {
                        boolean annual=pipelined&&env.getProperty("app.market-data.broad-annual-enabled",Boolean.class,true);
                        int groupSize=annual?annualSymbols(env):publicationSymbols(env);
                        if(prefetch!=null&&!UsBroadDailyPrefetch.groupReady(db,groupSize,prefetch.finished())){status(db,budget,"WAITING_PREFETCH_GROUP",null);Thread.sleep(1000);continue;}
                        if(annual){if(!publishAnnualGroup(db,budget,through))return;continue;}
                        if(!publishDailyGroup(db,source,budget,through,pipelined))return;continue;
                    }
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
                    try {rows=switch(task.kind()) {
                        case "DAILY" -> YahooPriceMapper.daily(bytes,task.symbol(),task.yahoo(),response.sha256(),response.observedAt(),LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01")),through);
                        case "HISTORY_CATALOG" -> UsHistoricalSecurityCatalog.rows(bytes,response.sha256(),response.observedAt(),LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01")),through);
                        case "UNIVERSE_NASDAQ","UNIVERSE_OTHER" -> UsUniverseSnapshot.rows(bytes,task.kind().equals("UNIVERSE_NASDAQ"),response.sha256(),response.observedAt());
                        default -> throw new IllegalArgumentException("未知证券范围任务");};}
                    catch(IllegalArgumentException malformed){update(db,task,"FAILED_SOURCE",response,malformed.getMessage());continue;}
                    update(db,task,"PUBLISHING",response,response.sourceUrl());
                    try {
                        var definition=task.kind().equals("DAILY")?UsMarketDatasets.DAILY:task.kind().equals("HISTORY_CATALOG")?UsHistoricalSecurityCatalog.SECURITIES:UsUniverseSnapshot.SECURITIES;
                        var result=store.publish(definition,rows,response.path(),response.sha256(),Map.of("task",task.id(),"sourceUrl",response.sourceUrl(),"requestedThrough",through.toString(),"scope","当前挂牌与供应商历史目录候选；逐项保存价格不可得或代码映射缺口，不代表完整官方历史指数成分"));
                        update(db,task,"VERIFIED",response,JSON.writeValueAsString(Map.of("result",result)));status(db,budget,"VERIFIED_TASK",task.id());
                    }catch(Exception failure){status(db,budget,"PUBLICATION_REQUIRES_RECONCILIATION",task.id());throw failure;}
                }
                }
            }
        }
    }
    static int annualSymbols(Environment environment) {
        int symbols=environment.getProperty("app.market-data.broad-annual-symbols",Integer.class,128);
        if(symbols<1||symbols>UsYahooAnnualBundle.MAX_MEMBERS||symbols>UsBroadDailyPrefetch.capacity(environment))throw new IllegalArgumentException("年度组须为 1 至 128 个来源，且不得超过预取缓存");return symbols;
    }
    private boolean publishAnnualGroup(Connection db,PersistentMarketApiBudget budget,LocalDate through)throws Exception {
        var page=publicationPage(env);var sources=new ArrayList<UsYahooDailyBundle.Source>();int bytes=UsYahooAnnualBundle.MANIFEST_BYTES;
        for(var task:tasks(db,"kind='DAILY' AND phase='FETCHED'",annualSymbols(env))) {
            if(Files.exists(root.resolve("us-broad-stop.request"))){status(db,budget,"STOPPED",null);return false;}
            byte[] body=Files.readAllBytes(Path.of(task.raw()));if(!VerifiedMarketDataStore.sha(body).equals(task.sha()))throw new IllegalStateException("原年度成员摘要变化");
            if(bytes+body.length>UsYahooAnnualBundle.MAX_BYTES)break;
            sources.add(new UsYahooDailyBundle.Source(task.id(),task.symbol(),task.yahoo(),task.proof(),task.sha(),Instant.parse(task.observed()),body));bytes+=body.length;
        }
        if(sources.isEmpty())throw new IllegalStateException("年度组没有可发布的有界来源");
        String bundle="broad-"+UUID.randomUUID();var from=LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01"));
        byte[] frozen=UsYahooAnnualBundle.freeze(bundle,from,through,new UsYahooAnnualBundle.Page(page.rows(),page.bytes()),sources);var parsed=UsYahooAnnualBundle.parse(frozen);
        Path folder=root.resolve("broad-bundles");Files.createDirectories(folder);Path raw=folder.resolve(bundle+".zip");Files.write(raw,frozen,StandardOpenOption.CREATE_NEW);try(var file=FileChannel.open(raw,StandardOpenOption.WRITE)){file.force(true);}String sha=VerifiedMarketDataStore.sha(frozen);
        UsAnnualDailyPublication.begin(db,parsed,raw,sha);
        return publishAnnual(db,budget,parsed,raw,sha);
    }
    private boolean resumeAnnual(Connection db,PersistentMarketApiBudget budget,LocalDate through)throws Exception {
        var pending=new ArrayList<String[]>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,raw,sha FROM broad_bundles WHERE phase='PUBLISHING' ORDER BY updated_at")){while(r.next())pending.add(new String[]{r.getString(1),r.getString(2),r.getString(3)});}
        for(var old:pending) {
            Path raw=Path.of(old[1]);byte[] frozen=Files.readAllBytes(raw);if(!VerifiedMarketDataStore.sha(frozen).equals(old[2]))throw new IllegalStateException("原来源组 ZIP 变化");
            if(!UsYahooAnnualBundle.annual(frozen))throw new IllegalStateException("原非年度来源组尚未核对，禁止自动重发");var parsed=UsYahooAnnualBundle.parse(frozen);
            if(!parsed.bundle().equals(old[0])||!parsed.through().equals(through)||!parsed.from().equals(LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01"))))throw new IllegalStateException("年度恢复须保持原组身份和原请求日期");
            if(!publishAnnual(db,budget,parsed,raw,old[2]))return false;
        }return true;
    }
    private boolean publishAnnual(Connection db,PersistentMarketApiBudget budget,UsYahooAnnualBundle.Parsed parsed,Path raw,String sha)throws Exception {
        boolean completed=new UsAnnualDailyPublication(root,store).publishRemaining(db,parsed,raw,sha,()->Files.exists(root.resolve("us-broad-stop.request")),(phase,detail)->status(db,budget,phase,detail));
        if(!completed)status(db,budget,"STOPPED_BETWEEN_WINDOWS",parsed.bundle());return completed;
    }
    private boolean publishDailyGroup(Connection db,FrozenHttpSource source,PersistentMarketApiBudget budget,LocalDate through,boolean prefetchedOnly)throws Exception {
        int capacity=publicationSymbols(env);
        var page=publicationPage(env);
        var selected=tasks(db,prefetchedOnly?"kind='DAILY' AND phase='FETCHED'":"kind='DAILY' AND phase IN ('PLANNED','FETCHED')",capacity);var sources=new ArrayList<UsYahooDailyBundle.Source>();LocalDate from=LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01"));
        for(var task:selected) {
            if(Files.exists(root.resolve("us-broad-stop.request"))){status(db,budget,"STOPPED",null);return false;}
            status(db,budget,"FETCHING_GROUP",task.id());FrozenHttpSource.Response response;
            if(task.phase().equals("FETCHED"))response=new FrozenHttpSource.Response(Path.of(task.raw()),task.sha(),Instant.parse(task.observed()),task.proof(),(int)Files.size(Path.of(task.raw())));
            else {
                long start=from.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),end=LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                var uri=URI.create("https://query1.finance.yahoo.com/v8/finance/chart/"+task.yahoo()+"?period1="+start+"&period2="+end+"&interval=1d&events=div,splits");
                response=null;
                // 等配额时保留组内已取得的原文，避免反复解析前面的证券，也不持有发布锁。
                while(true) {
                    if(Files.exists(root.resolve("us-broad-stop.request"))){status(db,budget,"STOPPED",null);return false;}
                    try {response=source.get("yahoo","public",task.yahoo(),uri,Map.of(),4*1024*1024);break;}
                    catch(PersistentMarketApiBudget.Exhausted wait){status(db,budget,"WAITING_QUOTA",wait.nextAllowed.toString());Thread.sleep(Math.max(1,Math.min(10000,Duration.between(Instant.now(),wait.nextAllowed).toMillis())));}
                    catch(FrozenHttpSource.SourceStatusException failure){if(failure.status!=429){update(db,task,"FAILED_SOURCE",null,failure.getMessage());break;}}
                    catch(Exception failure){update(db,task,"FAILED_SOURCE",null,failure.getClass().getSimpleName());break;}
                }
                if(response==null)continue;
                update(db,task,"FETCHED",response,response.sourceUrl());
            }
            byte[] bytes=Files.readAllBytes(response.path());if(!VerifiedMarketDataStore.sha(bytes).equals(response.sha256()))throw new IllegalStateException("股票日线冻结来源变化");
            try {YahooPriceMapper.daily(bytes,task.symbol(),task.yahoo(),response.sha256(),response.observedAt(),from,through);}
            catch(IllegalArgumentException malformed){update(db,task,"FAILED_SOURCE",response,malformed.getMessage());continue;}
            sources.add(new UsYahooDailyBundle.Source(task.id(),task.symbol(),task.yahoo(),response.sourceUrl(),response.sha256(),response.observedAt(),bytes));
        }
        if(sources.isEmpty())return true;
        String bundle="broad-"+UUID.randomUUID();byte[] frozen=UsYahooDailyBundle.freeze(bundle,from,through,sources);var parsed=UsYahooDailyBundle.parse(frozen);
        Path folder=root.resolve("broad-bundles");Files.createDirectories(folder);Path raw=folder.resolve(bundle+".zip");Files.write(raw,frozen,StandardOpenOption.CREATE_NEW);try(var f=FileChannel.open(raw,StandardOpenOption.WRITE)){f.force(true);}String sha=VerifiedMarketDataStore.sha(frozen);
        // 组和全部成员一起进入发布态；网络调用始终在 SQLite 事务外，崩溃后不会自动另起发送。
        beginBundle(db,parsed,raw,sha);
        status(db,budget,"PUBLISHING_GROUP",bundle);
        var result=store.publish(UsMarketDatasets.DAILY,parsed.rows(),raw,sha,Map.of("bundle",bundle,"memberTasks",parsed.members().stream().map(UsYahooDailyBundle.Member::task).toList(),"requestedFrom",from.toString(),"requestedThrough",through.toString(),"scope","完整原始响应及清单冻结在同一 ZIP；按日期排序发布，全部字段与 WAL 验收后一起推进成员"),page);
        finishBundle(db,parsed,Map.of("result",result));status(db,budget,"VERIFIED_GROUP",bundle);return true;
    }
    static VerifiedMarketDataStore.PublicationPage publicationPage(Environment environment) {
        return new VerifiedMarketDataStore.PublicationPage(environment.getProperty("app.market-data.broad-publication-page-rows",Integer.class,10000),environment.getProperty("app.market-data.broad-publication-page-bytes",Integer.class,8*1024*1024));
    }
    private static int publicationSymbols(Environment environment) {
        int capacity=environment.getProperty("app.market-data.broad-publication-symbols",Integer.class,12);if(capacity<1||capacity>16)throw new IllegalArgumentException("股票日线来源组容量须为 1 至 16");return capacity;
    }
    static void beginBundle(Connection db,UsYahooDailyBundle.Parsed parsed,Path raw,String sha)throws Exception {
        db.setAutoCommit(false);try {
            try(var s=db.prepareStatement("INSERT INTO broad_bundles VALUES(?,?,?,'PUBLISHING',NULL,?)")){s.setString(1,parsed.bundle());s.setString(2,raw.toString());s.setString(3,sha);s.setString(4,Instant.now().toString());s.executeUpdate();}
            try(var s=db.prepareStatement("UPDATE broad_tasks SET phase='PUBLISHING',bundle_id=?,updated_at=? WHERE id=? AND phase='FETCHED' AND sha=? AND bundle_id IS NULL")) {
                for(var member:parsed.members()){s.setString(1,parsed.bundle());s.setString(2,Instant.now().toString());s.setString(3,member.task());s.setString(4,member.sha256());if(s.executeUpdate()!=1)throw new IllegalStateException("来源组成员队列状态变化");}
            }db.commit();
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    static void finishBundle(Connection db,UsYahooDailyBundle.Parsed parsed,Map<String,Object> proof)throws Exception {
        String evidence=JSON.writeValueAsString(Map.of("bundle",parsed.bundle(),"members",parsed.members().size(),"rows",parsed.rows().size(),"verification",proof));db.setAutoCommit(false);
        try {
            try(var s=db.prepareStatement("UPDATE broad_bundles SET phase='VERIFIED',proof=?,updated_at=? WHERE id=? AND phase='PUBLISHING'")){s.setString(1,evidence);s.setString(2,Instant.now().toString());s.setString(3,parsed.bundle());if(s.executeUpdate()!=1)throw new IllegalStateException("来源组原状态变化");}
            try(var s=db.prepareStatement("UPDATE broad_tasks SET phase='VERIFIED',proof=?,updated_at=? WHERE id=? AND bundle_id=? AND phase='PUBLISHING' AND sha=?")) {
                for(var m:parsed.members()){s.setString(1,evidence);s.setString(2,Instant.now().toString());s.setString(3,m.task());s.setString(4,parsed.bundle());s.setString(5,m.sha256());if(s.executeUpdate()!=1)throw new IllegalStateException("来源组成员原状态变化");}
            }db.commit();
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    private void plan(Connection db,LocalDate through,Map<String,FrozenHttpSource.Response> references)throws Exception {
        var candidates=new TreeMap<String,String>();var nonCommon=new TreeMap<String,Map<String,Object>>();
        for(boolean nasdaq:new boolean[]{true,false}) {
            var reference=references.get(nasdaq?"nasdaq-listed":"nasdaq-other");byte[] bytes=Files.readAllBytes(reference.path());
            var rows=UsUniverseSnapshot.rows(bytes,nasdaq,reference.sha256(),reference.observedAt());String kind=nasdaq?"UNIVERSE_NASDAQ":"UNIVERSE_OTHER";
            insert(db,kind,kind,"",through,reference);
            for(var row:rows)if(Boolean.TRUE.equals(row.get("daily_candidate"))) {String original=row.get("symbol").toString(),mapped=row.get("yahoo_symbol").toString();var previous=candidates.putIfAbsent(original,mapped);if(previous!=null&&!previous.equals(mapped))throw new IllegalStateException("挂牌目录之间来源代码冲突");}
            for(var row:rows)if("NON_COMMON_SECURITY".equals(row.get("scope_reason")))nonCommon.put(row.get("symbol").toString(),row);
        }
        for(var candidate:candidates.entrySet())insert(db,"DAILY",candidate.getKey(),candidate.getValue(),through,null);
        // 发行人股票持仓只用于当前覆盖优先级；不把基金快照注册为官方历史指数成分。
        for(String fund:List.of("SPY","QQQ")) {
            Path file=root.resolve("probes/"+(fund.equals("SPY")?"spy-holdings.xlsx":"qqq-full-holdings.response"));if(!Files.isRegularFile(file))continue;
            byte[] bytes=Files.readAllBytes(file);String sha=VerifiedMarketDataStore.sha(bytes);Instant observed=Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.MICROS);
            var members=fund.equals("SPY")?UsFundHoldings.spy(bytes,sha,observed):UsFundHoldings.qqq(bytes,sha,observed);
            for(var member:members){String symbol=member.get("symbol").toString();if(!candidates.containsKey(symbol))insert(db,"DAILY",symbol,symbol.replace('.','-'),through,null);}
            try(var q=db.prepareStatement("UPDATE broad_tasks SET priority=0 WHERE kind='DAILY' AND symbol=?")){for(var member:members){q.setString(1,member.get("symbol").toString());q.executeUpdate();}}
        }
        var history=references.get("tiingo-symbols");var historical=UsHistoricalSecurityCatalog.rows(Files.readAllBytes(history.path()),history.sha256(),history.observedAt(),LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01")),through);
        if(historical.size()<1000)throw new IllegalArgumentException("供应商历史美国证券目录异常偏小");
        insert(db,"HISTORY_CATALOG","TIINGO_HISTORY","",through,history);
        try(var priority=db.prepareStatement("UPDATE broad_tasks SET priority=20 WHERE id=?")) {
            for(var row:historical)if(Boolean.TRUE.equals(row.get("daily_candidate"))) {
                String symbol=row.get("symbol").toString();if(insert(db,"DAILY",symbol,row.get("yahoo_symbol").toString(),through,null)) {
                    priority.setString(1,taskId("DAILY",symbol,through,null));priority.executeUpdate();
                }
            }
        }
        holdNonCommonCandidates(db,nonCommon.values());
    }
    /** 当前明确是非普通股票，但历史 Stock 标签不能排除代码复用；保留任务待分段复核，不删历史范围。 */
    static void holdNonCommonCandidates(Connection db,Collection<Map<String,Object>> officialRows)throws Exception {
        for(var row:officialRows) {
            if(!Boolean.FALSE.equals(row.get("daily_candidate"))||!"NON_COMMON_SECURITY".equals(row.get("scope_reason")))throw new IllegalArgumentException("范围复核必须有官方非普通股票记录");
            var selected=new ArrayList<String[]>();
            try(var s=db.prepareStatement("SELECT id,phase,proof FROM broad_tasks WHERE kind='DAILY' AND symbol=? AND phase IN ('PLANNED','FETCHED','FAILED_SOURCE') AND bundle_id IS NULL")){s.setString(1,row.get("symbol").toString());try(var r=s.executeQuery()){while(r.next())selected.add(new String[]{r.getString(1),r.getString(2),r.getString(3)});}}
            for(var task:selected) {
                String proof=JSON.writeValueAsString(Map.of("reason","CURRENT_NON_COMMON_REQUIRES_HISTORY_REVIEW","officialSnapshot",row,"sourcePhaseBeforeReview",task[1],"priorProof",Objects.toString(task[2],""),"scope","保留原文及历史目录窗口；先确认历史证券类别和代码复用，不能当作普通股票价格缺失或已完成"));
                try(var s=db.prepareStatement("UPDATE broad_tasks SET phase='SCOPE_REVIEW_REQUIRED',proof=?,updated_at=? WHERE id=? AND phase=? AND bundle_id IS NULL")){s.setString(1,proof);s.setString(2,Instant.now().toString());s.setString(3,task[0]);s.setString(4,task[1]);if(s.executeUpdate()!=1)throw new IllegalStateException("范围复核期间来源任务状态变化");}
            }
        }
    }
    private static String taskId(String kind,String symbol,LocalDate through,String sha){return VerifiedMarketDataStore.sha(("v1|"+kind+"|"+symbol+"|"+through+"|"+Objects.toString(sha,"")).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static boolean insert(Connection db,String kind,String symbol,String yahoo,LocalDate through,FrozenHttpSource.Response reference)throws Exception {
        String id=taskId(kind,symbol,through,reference==null?null:reference.sha256());
        try(var q=db.prepareStatement("INSERT OR IGNORE INTO broad_tasks(id,kind,symbol,yahoo,phase,raw,sha,observed,proof,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")){q.setString(1,id);q.setString(2,kind);q.setString(3,symbol);q.setString(4,yahoo);q.setString(5,reference==null?"PLANNED":"FETCHED");q.setString(6,reference==null?null:reference.path().toAbsolutePath().toString());q.setString(7,reference==null?null:reference.sha256());q.setString(8,reference==null?null:reference.observedAt().toString());q.setString(9,reference==null?null:reference.sourceUrl());q.setString(10,Instant.now().toString());return q.executeUpdate()==1;}
    }
    private Map<String,FrozenHttpSource.Response> references(FrozenHttpSource source,PersistentMarketApiBudget budget,LocalDate through)throws Exception {
        var specs=Map.of("nasdaq-listed","https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt","nasdaq-other","https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt","tiingo-symbols","https://apimedia.tiingo.com/docs/tiingo/daily/supported_tickers.zip");
        var result=new HashMap<String,FrozenHttpSource.Response>();Path folder=root.resolve("reference-sources");Files.createDirectories(folder);
        for(var entry:specs.entrySet()) {
            if(Files.exists(root.resolve("us-broad-stop.request")))throw new InterruptedException("已收到停止请求，未启动新的发布");
            String key=entry.getKey();Path manifest=folder.resolve(key+"-"+through+".json");FrozenHttpSource.Response response;
            if(Files.isRegularFile(manifest)) {var card=JSON.readTree(Files.readAllBytes(manifest));Path raw=Path.of(card.path("rawPath").asText());response=new FrozenHttpSource.Response(raw,card.path("sha256").asText(),Instant.parse(card.path("observedAt").asText()),card.path("sourceUrl").asText(),card.path("bytes").asInt());}
            else {
                Path retained=root.resolve("probes/"+key+".response");
                if(Files.isRegularFile(retained)&&!Files.getLastModifiedTime(retained).toInstant().atZone(ZoneId.of("America/New_York")).toLocalDate().isBefore(through)) {
                    byte[] bytes=Files.readAllBytes(retained);response=new FrozenHttpSource.Response(retained,VerifiedMarketDataStore.sha(bytes),Files.getLastModifiedTime(retained).toInstant().truncatedTo(ChronoUnit.MICROS),entry.getValue(),bytes.length);
                    budget.seed("reference-"+response.sha256(),"reference","public",key,response.observedAt(),bytes.length);
                } else {
                    while(true)try {response=source.get("reference","public",key,URI.create(entry.getValue()),Map.of(),4*1024*1024);break;}
                    catch(PersistentMarketApiBudget.Exhausted wait){if(Files.exists(root.resolve("us-broad-stop.request")))throw new InterruptedException("已收到停止请求，未启动新的发布");Thread.sleep(Math.max(1,Math.min(10000,Duration.between(Instant.now(),wait.nextAllowed).toMillis())));}
                }
                Files.write(manifest,JSON.writeValueAsBytes(Map.of("rawPath",response.path().toString(),"sha256",response.sha256(),"observedAt",response.observedAt().toString(),"sourceUrl",response.sourceUrl(),"bytes",response.bytes())),StandardOpenOption.CREATE_NEW);
            }
            if(!VerifiedMarketDataStore.sha(Files.readAllBytes(response.path())).equals(response.sha256()))throw new IllegalStateException("证券参考快照变化，禁止重新解释原队列");result.put(key,response);
        }return result;
    }
    private boolean coreDailyPending()throws Exception {
        Path file=root.resolve("collection-queue.sqlite3");if(!Files.isRegularFile(file))return false;
        try(var db=DriverManager.getConnection("jdbc:sqlite:file:"+file.toString().replace('\\','/')+"?mode=ro");var s=db.createStatement();var r=s.executeQuery("SELECT count() FROM us_collection_tasks WHERE kind='DAILY' AND phase IN ('PLANNED','FETCHED','PUBLISHING')")){return r.next()&&r.getLong(1)>0;}
    }
    private static List<Task> tasks(Connection db,String predicate)throws Exception {
        return tasks(db,predicate,1);
    }
    private static List<Task> tasks(Connection db,String predicate,int maximum)throws Exception {
        if(maximum<1||maximum>UsYahooAnnualBundle.MAX_MEMBERS)throw new IllegalArgumentException("队列读取容量超限");
        // 核对旧队列时只读旧结构，不要求先启动新采集器做结构迁移。
        boolean hasBundle=false;try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info(broad_tasks)")){while(r.next())if(r.getString("name").equals("bundle_id"))hasBundle=true;}
        var out=new ArrayList<Task>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,kind,symbol,yahoo,phase,raw,sha,observed,proof,"+(hasBundle?"bundle_id":"NULL")+" FROM broad_tasks WHERE "+predicate+" ORDER BY CASE WHEN kind='DAILY' THEN 1 ELSE 0 END,priority,rowid LIMIT "+maximum)){while(r.next())out.add(new Task(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10)));}return out;
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
