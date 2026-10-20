package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.zoutrankil.data.marketdata.FrozenHttpSource;
import org.springframework.core.env.Environment;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;

/** 未取得原文的来源重试单独留证；不改变任务身份，也不接触正式发布账本。 */
final class UsBroadSourceRetry {
    private static final ObjectMapper JSON=new ObjectMapper();
    record Policy(int maxAttempts,long delayMillis) {
        Policy {if(maxAttempts<1||maxAttempts>5||delayMillis<1000||delayMillis>60000)throw new IllegalArgumentException("来源重试须为 1 至 5 次，初始等待须为 1000 至 60000 毫秒");}
    }
    record Candidate(UsBroadDailyPrefetch.Task task,int failures,String lastError) {}
    private UsBroadSourceRetry() {}
    static Policy policy(Environment env) {return new Policy(env.getProperty("app.market-data.broad-source-max-attempts",Integer.class,3),env.getProperty("app.market-data.broad-source-retry-delay-ms",Long.class,5000L));}
    static void initialize(Connection db)throws SQLException {
        if(!db.getAutoCommit())throw new IllegalStateException("来源重试初始化不能提交调用方事务");
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS broad_source_retries(task_id TEXT PRIMARY KEY,failures INT NOT NULL CHECK(failures BETWEEN 1 AND 5),next_attempt_ms INTEGER NOT NULL CHECK(next_attempt_ms>=0),last_error TEXT NOT NULL,history TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS broad_tasks_prefetch_pending ON broad_tasks(kind,phase,priority)");
        }
    }
    static Candidate next(Connection db,Policy policy,Instant now)throws SQLException {
        // 等待重试的证券不挡住其他候选；下调上限时，原待重试任务按已发生次数结束，不再联网。
        try(var s=db.prepareStatement("SELECT t.id,t.symbol,t.yahoo,t.raw,t.sha,t.observed,coalesce(r.failures,0) failures,r.last_error FROM broad_tasks t LEFT JOIN broad_source_retries r ON r.task_id=t.id WHERE t.kind='DAILY' AND t.phase='PLANNED' AND t.bundle_id IS NULL AND (r.task_id IS NULL OR r.next_attempt_ms<=? OR r.failures>=?) ORDER BY t.priority,t.rowid LIMIT 1")) {
            s.setLong(1,now.toEpochMilli());s.setInt(2,policy.maxAttempts());
            try(var r=s.executeQuery()) {
                if(!r.next())return null;
                if(r.getObject("raw")!=null||r.getObject("sha")!=null||r.getObject("observed")!=null)throw new IllegalStateException("PLANNED 来源已有冻结痕迹，须核对原状态，不能重新请求");
                return new Candidate(new UsBroadDailyPrefetch.Task(r.getString("id"),r.getString("symbol"),r.getString("yahoo")),r.getInt("failures"),r.getString("last_error"));
            }
        }
    }
    static boolean pending(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM broad_tasks WHERE kind='DAILY' AND phase='PLANNED' AND bundle_id IS NULL LIMIT 1")){return r.next();}
    }
    static boolean retryable(Exception error) {
        return error instanceof FrozenHttpSource.SourceStatusException http?http.status>=500&&http.status<600:error instanceof IOException;
    }
    static String display(Exception error) {return error instanceof FrozenHttpSource.SourceStatusException?error.getMessage():error.getClass().getSimpleName();}
    static boolean record(Connection db,UsBroadDailyPrefetch.Task task,Exception error,Policy policy,Instant now)throws Exception {
        if(!db.getAutoCommit()||!retryable(error))throw new IllegalArgumentException("只能记录无外层事务的临时来源错误");
        // 先取得 SQLite 写锁，再同时保存尝试记录和原任务状态；网络请求始终在此事务外。
        try(var transaction=db.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                String priorProof;
                try(var s=db.prepareStatement("SELECT phase,raw,sha,observed,bundle_id,proof FROM broad_tasks WHERE id=? AND kind='DAILY'")) {
                    s.setString(1,task.id());try(var r=s.executeQuery()){if(!r.next()||!"PLANNED".equals(r.getString("phase"))||r.getObject("raw")!=null||r.getObject("sha")!=null||r.getObject("observed")!=null||r.getObject("bundle_id")!=null)throw new IllegalStateException("来源状态已改变，不能覆盖冻结、复核或正式发布");priorProof=r.getString("proof");}
                }
                int failures=0;ArrayNode history=JSON.createArrayNode();
                try(var s=db.prepareStatement("SELECT failures,history FROM broad_source_retries WHERE task_id=?")) {
                    s.setString(1,task.id());try(var r=s.executeQuery()){if(r.next()){failures=r.getInt(1);var saved=JSON.readTree(r.getString(2));if(!saved.isArray()||saved.size()!=failures)throw new IllegalStateException("来源重试次数与原记录不符");history=(ArrayNode)saved;}}
                }
                if(failures>=policy.maxAttempts())throw new IllegalStateException("来源重试已到上限，禁止另记请求");
                failures++;boolean retry=failures<policy.maxAttempts();long next=0;
                if(retry){next=now.plusMillis(Math.min(60000,policy.delayMillis()*(1L<<(failures-1)))).toEpochMilli();if(error instanceof FrozenHttpSource.SourceStatusException http&&http.retryAfter!=null)next=Math.max(next,http.retryAfter.toEpochMilli());}
                String message=display(error);var entry=history.addObject();entry.put("attempt",failures);entry.put("failedAt",now.toString());entry.put("error",message);entry.put("maxAttempts",policy.maxAttempts());if(priorProof==null)entry.putNull("priorProof");else entry.put("priorProof",priorProof);if(retry)entry.put("nextAttemptAt",Instant.ofEpochMilli(next).toString());else entry.putNull("nextAttemptAt");
                try(var s=db.prepareStatement("INSERT INTO broad_source_retries(task_id,failures,next_attempt_ms,last_error,history) VALUES(?,?,?,?,?) ON CONFLICT(task_id) DO UPDATE SET failures=excluded.failures,next_attempt_ms=excluded.next_attempt_ms,last_error=excluded.last_error,history=excluded.history")){s.setString(1,task.id());s.setInt(2,failures);s.setLong(3,next);s.setString(4,message);s.setString(5,JSON.writeValueAsString(history));s.executeUpdate();}
                try(var s=db.prepareStatement("UPDATE broad_tasks SET phase=?,proof=?,updated_at=? WHERE id=? AND kind='DAILY' AND phase='PLANNED' AND raw IS NULL AND sha IS NULL AND observed IS NULL AND bundle_id IS NULL")){s.setString(1,retry?"PLANNED":"FAILED_SOURCE");s.setString(2,message);s.setString(3,now.toString());s.setString(4,task.id());if(s.executeUpdate()!=1)throw new IllegalStateException("来源重试期间原状态变化");}
                transaction.execute("COMMIT");return retry;
            }catch(Exception failure){try{transaction.execute("ROLLBACK");}catch(Exception rollback){failure.addSuppressed(rollback);}throw failure;}
        }
    }
}
