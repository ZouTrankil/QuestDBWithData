package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.*;
import static org.junit.jupiter.api.Assertions.*;

class UsBroadSourceRetryTest {
    @TempDir Path root;
    private static final LocalDate FROM=LocalDate.of(2026,1,1),THROUGH=LocalDate.of(2026,1,5);
    private static final Instant NOW=Instant.parse("2026-10-20T00:00:00Z");
    private static final UsBroadSourceRetry.Policy POLICY=new UsBroadSourceRetry.Policy(3,5000);
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test void persistsBackoffAcrossRestartAndLetsOtherSourcesRunBeforeTheRetry()throws Exception {
        String original;
        try(var db=database()) {
            original=insert(db,"X");insert(db,"Y");
            try(var s=db.createStatement()){s.executeUpdate("UPDATE broad_tasks SET proof='original-evidence' WHERE symbol='X'");}
            assertEquals(UsBroadDailyPrefetch.Step.RETRY_WAIT,step(db,NOW,t->{
                assertEquals("X",t.symbol());assertTrue(db.getAutoCommit());
                // 来源出错前，其他连接仍能提交队列进度。
                try(var other=DriverManager.getConnection(url());var s=other.createStatement()){s.executeUpdate("UPDATE broad_tasks SET proof='concurrent-progress' WHERE symbol='Y'");}
                throw new IOException("temporary");
            }));
            assertEquals("PLANNED",value(db,"SELECT phase FROM broad_tasks WHERE symbol='X'"));
            assertEquals("1",value(db,"SELECT failures FROM broad_source_retries"));
            var history=JSON.readTree(value(db,"SELECT history FROM broad_source_retries"));
            assertEquals("original-evidence",history.get(0).get("priorProof").textValue());
            assertEquals(NOW.plusSeconds(5).toString(),history.get(0).get("nextAttemptAt").textValue());
        }
        try(var db=DriverManager.getConnection(url())) {
            UsBroadSourceRetry.initialize(db);
            assertEquals(UsBroadDailyPrefetch.Step.FETCHED,step(db,NOW.plusSeconds(1),t->{assertEquals("Y",t.symbol());return response("Y");}));
            assertEquals(UsBroadDailyPrefetch.Step.RETRY_WAIT,step(db,NOW.plusSeconds(4),t->{fail("未到重试时刻");return null;}));
            var frozen=response("X");
            assertEquals(UsBroadDailyPrefetch.Step.FETCHED,step(db,NOW.plusSeconds(5),t->{assertEquals(original,t.id());return frozen;}));
            assertEquals(frozen.sha256(),value(db,"SELECT sha FROM broad_tasks WHERE symbol='X'"));
            assertEquals(frozen.path().toString(),value(db,"SELECT raw FROM broad_tasks WHERE symbol='X'"));
            assertEquals("1",value(db,"SELECT count(*) FROM broad_source_retries"));
            assertEquals(UsBroadDailyPrefetch.Step.COMPLETE,step(db,NOW.plusSeconds(6),t->{fail("冻结后不能再请求");return null;}));
        }
    }
    @Test void stopsAtTheTotalAttemptLimitAndPreservesEveryFailure()throws Exception {
        try(var db=database()) {
            insert(db,"X");
            assertEquals(UsBroadDailyPrefetch.Step.RETRY_WAIT,step(db,NOW,t->{throw new IOException();}));
            assertEquals(UsBroadDailyPrefetch.Step.RETRY_WAIT,step(db,NOW.plusSeconds(5),t->{throw new IOException();}));
            assertEquals(UsBroadDailyPrefetch.Step.RETRY_WAIT,step(db,NOW.plusSeconds(14),t->{fail("第二次须等待十秒");return null;}));
            assertEquals(UsBroadDailyPrefetch.Step.FAILED_SOURCE,step(db,NOW.plusSeconds(15),t->{throw new IOException();}));
            assertEquals("FAILED_SOURCE",value(db,"SELECT phase FROM broad_tasks"));
            var history=JSON.readTree(value(db,"SELECT history FROM broad_source_retries"));
            assertEquals(3,history.size());assertEquals(3,history.get(2).get("attempt").intValue());assertTrue(history.get(2).get("nextAttemptAt").isNull());
            assertEquals(UsBroadDailyPrefetch.Step.COMPLETE,step(db,NOW.plusSeconds(100),t->{fail("不能进行第四次请求");return null;}));
        }
    }
    @Test void loweringTheConfiguredLimitNeverAddsAnotherNetworkRequest()throws Exception {
        try(var db=database()) {
            insert(db,"X");assertEquals(UsBroadDailyPrefetch.Step.RETRY_WAIT,step(db,NOW,t->{throw new IOException();}));
            assertEquals(UsBroadDailyPrefetch.Step.FAILED_SOURCE,UsBroadDailyPrefetch.step(db,16,FROM,THROUGH,t->{fail("已到新上限");return null;},()->false,new UsBroadSourceRetry.Policy(1,5000),Clock.fixed(NOW,ZoneOffset.UTC)));
            assertEquals("1",value(db,"SELECT failures FROM broad_source_retries"));assertEquals("FAILED_SOURCE",value(db,"SELECT phase FROM broad_tasks"));
        }
    }
    @Test void quotaCancellationAnd429DoNotConsumeTheTransientRetryLimit()throws Exception {
        try(var db=database()) {
            insert(db,"X");
            assertThrows(PersistentMarketApiBudget.Exhausted.class,()->step(db,NOW,t->{throw new PersistentMarketApiBudget.Exhausted("yahoo","hour",NOW.plusSeconds(3600));}));
            var limited=status(429,NOW.plusSeconds(60));assertSame(limited,assertThrows(FrozenHttpSource.SourceStatusException.class,()->step(db,NOW,t->{throw limited;})));
            assertThrows(InterruptedException.class,()->step(db,NOW,t->{throw new InterruptedException();}));
            var stopped=new AtomicBoolean();
            assertEquals(UsBroadDailyPrefetch.Step.STOPPED,UsBroadDailyPrefetch.step(db,16,FROM,THROUGH,t->{stopped.set(true);throw new IOException();},stopped::get,POLICY,Clock.fixed(NOW,ZoneOffset.UTC)));
            assertEquals("0",value(db,"SELECT count(*) FROM broad_source_retries"));assertEquals("PLANNED",value(db,"SELECT phase FROM broad_tasks"));
        }
    }
    @Test void serverErrorsHonorRetryAfterWhile404AndResponseLimitsAreTerminal()throws Exception {
        try(var db=database()) {
            insert(db,"X");
            assertEquals(UsBroadDailyPrefetch.Step.RETRY_WAIT,step(db,NOW,t->{throw status(503,NOW.plusSeconds(120));}));
            assertEquals(UsBroadDailyPrefetch.Step.RETRY_WAIT,step(db,NOW.plusSeconds(119),t->{fail("须遵守服务端等待时间");return null;}));
            assertEquals(UsBroadDailyPrefetch.Step.FAILED_SOURCE,step(db,NOW.plusSeconds(120),t->{throw status(404,null);}));
            assertEquals("1",value(db,"SELECT failures FROM broad_source_retries"));
            insert(db,"Y");assertEquals(UsBroadDailyPrefetch.Step.FAILED_SOURCE,step(db,NOW,t->{throw new IllegalStateException("response limit");}));
            assertEquals("1",value(db,"SELECT count(*) FROM broad_source_retries"));
        }
    }
    @Test void scopeChangeDuringFailedFetchRollsBackTheRetryRecord()throws Exception {
        try(var db=database()) {
            insert(db,"X");
            assertThrows(IllegalStateException.class,()->step(db,NOW,t->{
                try(var other=DriverManager.getConnection(url());var s=other.createStatement()){s.executeUpdate("UPDATE broad_tasks SET phase='SCOPE_REVIEW_REQUIRED',proof='scope-proof' WHERE symbol='X'");}
                throw new IOException();
            }));
            assertEquals("SCOPE_REVIEW_REQUIRED",value(db,"SELECT phase FROM broad_tasks"));assertEquals("scope-proof",value(db,"SELECT proof FROM broad_tasks"));assertEquals("0",value(db,"SELECT count(*) FROM broad_source_retries"));
            assertTrue(db.getAutoCommit());try(var s=db.createStatement()){s.executeUpdate("UPDATE broad_tasks SET proof='still-writable'");}
        }
    }
    @Test void refusesToRefetchAPlannedTaskWithAnyFrozenRemnant()throws Exception {
        try(var db=database()) {
            insert(db,"X");
            for(String column:List.of("raw","sha","observed")) {
                try(var s=db.createStatement()){s.executeUpdate("UPDATE broad_tasks SET raw=NULL,sha=NULL,observed=NULL,"+column+"='original-evidence'");}
                assertThrows(IllegalStateException.class,()->step(db,NOW,t->{fail("已有冻结痕迹须先核对");return null;}));
                assertEquals("original-evidence",value(db,"SELECT "+column+" FROM broad_tasks"));
            }
            assertEquals("0",value(db,"SELECT count(*) FROM broad_source_retries"));
        }
    }
    @Test void measuresBackoffFromFailureInsteadOfTheStartOfASlowRequest()throws Exception {
        try(var db=database()) {
            insert(db,"X");
            var clock=new Clock(){int calls;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return calls++==0?NOW:NOW.plusSeconds(60);}};
            assertEquals(UsBroadDailyPrefetch.Step.RETRY_WAIT,UsBroadDailyPrefetch.step(db,16,FROM,THROUGH,t->{throw new IOException();},()->false,POLICY,clock));
            assertEquals(Long.toString(NOW.plusSeconds(65).toEpochMilli()),value(db,"SELECT next_attempt_ms FROM broad_source_retries"));
        }
    }
    @Test void validatesPublicDefaultsAndStandardConfigurationOverrides() {
        assertEquals(POLICY,UsBroadSourceRetry.policy(new StandardEnvironment()));
        for(var invalid:List.of(new int[]{0,5000},new int[]{6,5000},new int[]{3,0},new int[]{3,60001}))assertThrows(IllegalArgumentException.class,()->new UsBroadSourceRetry.Policy(invalid[0],invalid[1]));
        var env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("override",Map.of("app.market-data.broad-source-max-attempts",5,"app.market-data.broad-source-retry-delay-ms",60000)));
        assertEquals(new UsBroadSourceRetry.Policy(5,60000),UsBroadSourceRetry.policy(env));
    }
    private UsBroadDailyPrefetch.Step step(Connection db,Instant now,UsBroadDailyPrefetch.Fetch fetch)throws Exception {return UsBroadDailyPrefetch.step(db,16,FROM,THROUGH,fetch,()->false,POLICY,Clock.fixed(now,ZoneOffset.UTC));}
    private String url(){return "jdbc:sqlite:"+root.resolve("queue.sqlite3");}
    private Connection database()throws Exception {
        var db=DriverManager.getConnection(url());try(var s=db.createStatement()){s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000");s.execute("CREATE TABLE broad_tasks(id TEXT PRIMARY KEY,kind TEXT,symbol TEXT,yahoo TEXT,phase TEXT,raw TEXT,sha TEXT,observed TEXT,proof TEXT,updated_at TEXT,priority INT,bundle_id TEXT)");}UsBroadSourceRetry.initialize(db);return db;
    }
    private static String insert(Connection db,String symbol)throws Exception {
        String id=VerifiedMarketDataStore.sha(("v1|DAILY|"+symbol+"|"+THROUGH+"|").getBytes(StandardCharsets.UTF_8));
        try(var s=db.prepareStatement("INSERT INTO broad_tasks(id,kind,symbol,yahoo,phase,priority) VALUES(?,'DAILY',?,?,'PLANNED',0)")){s.setString(1,id);s.setString(2,symbol);s.setString(3,symbol);s.executeUpdate();}return id;
    }
    private static String value(Connection db,String sql)throws Exception {try(var s=db.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    private FrozenHttpSource.Response response(String symbol)throws Exception {
        String json="""
                {"chart":{"error":null,"result":[{"meta":{"symbol":"%s","currency":"USD","exchangeTimezoneName":"America/New_York","instrumentType":"EQUITY"},"timestamp":[1767378600],"indicators":{"quote":[{"open":[10],"high":[11],"low":[9],"close":[10],"volume":[100]}],"adjclose":[{"adjclose":[10]}]}}]}}
                """.formatted(symbol);
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);Path path=root.resolve(UUID.randomUUID()+".response");Files.write(path,bytes);return new FrozenHttpSource.Response(path,VerifiedMarketDataStore.sha(bytes),NOW,"https://query1.finance.yahoo.com/v8/finance/chart/"+symbol,bytes.length);
    }
    private static FrozenHttpSource.SourceStatusException status(int code,Instant retry)throws Exception {
        var constructor=FrozenHttpSource.SourceStatusException.class.getDeclaredConstructor(int.class,String.class,Instant.class);constructor.setAccessible(true);return constructor.newInstance(code,"https://query1.finance.yahoo.com/v8/finance/chart/X",retry);
    }
}
