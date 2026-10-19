package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.derived.BacktestDailyViewDataset;
import com.zoutrankil.data.domain.JobDefinitionJson;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** 只读核验数据库维护的物化视图；不写入结果表，也不主动发送刷新命令。 */
public final class BacktestDailyMaterializedViewPort {
    public static final String NAME="mv_backtest_daily", BASE="stk_limit";
    public static final List<String> SOURCES=List.of("stk_factor","stk_limit","stk_suspend","stk_st_daily");
    public static final String CREATE_SQL=loadSql();
    public static final String QUERY_SQL=querySql(CREATE_SQL);
    private final JdbcTemplate jdbc;
    public record State(Map<String,Object> view,Map<String,Map<String,Object>> tables) {}
    public BacktestDailyMaterializedViewPort(JdbcTemplate jdbc){
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(120);
    }
    public static String normalized(String sql){return sql.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT).replaceAll("\\s*([(),=<>])\\s*","$1");}
    static String querySql(String sql){
        String lines=sql.replace("\r\n","\n").replace('\r','\n');
        int start=lines.indexOf("AS (\n"),end=lines.lastIndexOf("\n)\nPARTITION");
        if(start<0||end<=start+5)throw new IllegalStateException("Canonical backtest MV SQL query boundary absent");
        return lines.substring(start+5,end);
    }
    private static String loadSql(){
        try(var in=BacktestDailyMaterializedViewPort.class.getResourceAsStream("/sql/mv_backtest_daily.sql")){
            if(in==null)throw new IllegalStateException("Canonical backtest MV SQL resource absent");
            return new String(in.readAllBytes(),StandardCharsets.UTF_8).strip().replaceFirst(";\\s*$","");
        }catch(IOException e){throw new IllegalStateException("Cannot load backtest MV SQL",e);}
    }
    private static long number(Map<String,Object> row,String key){
        if(!(row.get(key) instanceof Number n))throw new IllegalStateException("Backtest metadata counter absent: "+key);
        return n.longValue();
    }
    private static Map<String,Object> immutable(Map<String,Object> row){return Collections.unmodifiableMap(new LinkedHashMap<>(row));}
    private Map<String,Object> view(){
        var rows=jdbc.queryForList("SELECT view_name,refresh_type,base_table_name,view_status,invalidation_reason,view_sql,view_table_dir_name,"
                +"refresh_base_table_txn,base_table_txn,cast(last_refresh_start_timestamp AS LONG) AS started,"
                +"cast(last_refresh_finish_timestamp AS LONG) AS finished FROM materialized_views() WHERE view_name=?",NAME);
        if(rows.size()!=1)throw new IllegalStateException("Required native materialized view absent: "+NAME);
        var v=rows.getFirst();
        if(!BASE.equals(v.get("base_table_name"))||!"immediate".equals(v.get("refresh_type"))
                ||!normalized(QUERY_SQL).equals(normalized(Objects.toString(v.get("view_sql"),""))))
            throw new IllegalStateException("Backtest MV must use the canonical raw-table query and REFRESH IMMEDIATE");
        if("invalid".equals(v.get("view_status"))||v.get("invalidation_reason")!=null)
            throw new IllegalStateException("Backtest MV invalid: "+v.get("invalidation_reason"));
        if(!"valid".equals(v.get("view_status"))||number(v,"refresh_base_table_txn")<0
                ||number(v,"refresh_base_table_txn")!=number(v,"base_table_txn")||v.get("started")==null||v.get("finished")==null)
            throw pending("QuestDB has not completed automatic backtest MV refresh");
        return immutable(v);
    }
    private Map<String,Map<String,Object>> clocks(){
        var rows=jdbc.queryForList("SELECT t.table_name,t.id,t.directoryName,t.table_type,t.walEnabled,t.table_suspended,t.dedup,"
                +"w.writerTxn,w.sequencerTxn,w.bufferedTxnSize,w.suspended FROM tables() t JOIN wal_tables() w ON w.name=t.table_name "
                +"WHERE t.table_name IN ('mv_backtest_daily','stk_factor','stk_limit','stk_suspend','stk_st_daily') ORDER BY t.table_name");
        if(rows.size()!=5)throw new IllegalStateException("Backtest MV and its four raw tables are required");
        var result=new TreeMap<String,Map<String,Object>>();
        for(var row:rows){
            String name=Objects.toString(row.get("table_name"));
            if(!(SOURCES.contains(name)||NAME.equals(name))||result.containsKey(name))throw new IllegalStateException("Unexpected backtest object identity");
            if(!Boolean.TRUE.equals(row.get("walEnabled"))||!Boolean.FALSE.equals(row.get("table_suspended"))
                    ||!Boolean.FALSE.equals(row.get("suspended"))||number(row,"bufferedTxnSize")!=0||number(row,"writerTxn")!=number(row,"sequencerTxn"))
                throw pending("Backtest source/MV WAL is not settled: "+name);
            if(!(NAME.equals(name)?"M":"T").equals(row.get("table_type")))throw new IllegalStateException("Backtest requires a physical native MV and original raw tables");
            if(!NAME.equals(name)&&!Boolean.TRUE.equals(row.get("dedup")))throw new IllegalStateException("Backtest raw sources require unique date/stock keys: "+name);
            number(row,"id");result.put(name,immutable(row));
        }
        return Collections.unmodifiableMap(result);
    }
    private void requireSchema(){
        var actual=jdbc.queryForList("SELECT \"column\",\"type\",designated,upsertKey FROM table_columns('mv_backtest_daily') LIMIT 14");
        var expected=BacktestDailyViewDataset.DEFINITION.columns();
        if(actual.size()!=expected.size())throw new IllegalStateException("Exact 13-column backtest MV schema required");
        for(int i=0;i<expected.size();i++){
            var c=expected.get(i);var row=actual.get(i);
            if(!c.storageName().equals(row.get("column"))||!c.storageType().name().equals(row.get("type"))
                    ||Boolean.TRUE.equals(row.get("designated"))!=(i==0)||Boolean.TRUE.equals(row.get("upsertKey")))
                throw new IllegalStateException("Backtest MV schema differs: "+c.storageName());
        }
    }
    public State sourceState(){
        var before=clocks();var view=view();requireSchema();
        if(number(before.get(BASE),"sequencerTxn")!=number(view,"refresh_base_table_txn"))throw pending("Backtest base transaction changed during refresh admission");
        for(String name:SOURCES){
            var keys=jdbc.queryForList("SELECT \"column\" FROM table_columns('"+name+"') WHERE upsertKey=true LIMIT 4");
            var actual=new HashSet<String>();for(var key:keys)actual.add(Objects.toString(key.get("column")));
            if(!actual.equals(Set.of(name.equals("stk_suspend")||name.equals("stk_st_daily")?"timestamp":"trade_date","ts_code")))
                throw new IllegalStateException("Backtest raw source date/stock uniqueness changed: "+name);
        }
        // QuestDB 仅监听一个基表；关联表若在刷新后才提交，须拒绝读取，避免返回过期的补充字段。
        for(String name:SOURCES){
            var tx=jdbc.queryForList("SELECT sequencerTxn,cast(timestamp AS LONG) AS committed FROM wal_transactions('"+name+"') LIMIT -1");
            if(tx.size()!=1||number(tx.getFirst(),"sequencerTxn")!=number(before.get(name),"sequencerTxn"))throw pending("Backtest raw source transaction changed: "+name);
            if(!BASE.equals(name)&&number(tx.getFirst(),"committed")>number(view,"started"))
                throw new BacktestDailyMaterializationReadGuard.NotReadyException(BacktestDailyMaterializationReadGuard.NotReadyException.Reason.SOURCES_CHANGED,
                        "Raw source changed after MV refresh; write stk_limit last after all raw sources: "+name);
        }
        if(!before.equals(clocks())||!view.equals(view()))throw pending("Backtest MV/raw sources changed while observing the snapshot");
        return new State(view,before);
    }
    public State awaitCurrent(Duration timeout){
        long end=System.nanoTime()+timeout.toNanos();
        while(true){
            try{return sourceState();}catch(BacktestDailyMaterializationReadGuard.NotReadyException waiting){
                if(System.nanoTime()>=end)throw waiting;
                try{Thread.sleep(250);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Backtest MV wait interrupted");}
            }
        }
    }
    public String sourceVersion(){
        try{return "questdb-mv:"+NAME+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JobDefinitionJson.mapper().writeValueAsBytes(sourceState())));}
        catch(IllegalStateException e){throw e;}catch(Exception e){throw new IllegalStateException("Cannot fingerprint backtest MV",e);}
    }
    private static BacktestDailyMaterializationReadGuard.NotReadyException pending(String message){
        return new BacktestDailyMaterializationReadGuard.NotReadyException(BacktestDailyMaterializationReadGuard.NotReadyException.Reason.REFRESH_PENDING,message);
    }
}
