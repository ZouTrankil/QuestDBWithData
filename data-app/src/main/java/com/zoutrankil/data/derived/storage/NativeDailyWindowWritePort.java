package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.repository.*;


import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.NativeDailyWindowSnapshot;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Shared typed daily-window staging for MONTH/WAL/non-DEDUP derived record tables.
 * The formal table is never appended, deleted, dropped or altered by this port.
 */
public final class NativeDailyWindowWritePort<R extends Record> implements NativeDailyWindowSession<R> {
    private final JdbcTemplate jdbc;
    private final String formal,exactFormal,stagePrefix;
    private final Class<R> rowType;
    private final List<String> columns;
    private final RecordComponent[] components;
    private final NativeDailyProjection<R> projection;
    private final VerifiedBatchExecutor.Codec<R,Instant> codec;
    private String writeTable,writeId;

    public NativeDailyWindowWritePort(JdbcTemplate source,String target,String exactFormalTable,String stagePrefix,Class<R> rowType,List<String> columns) {
        DatasetDefinition.identifier(exactFormalTable);DatasetDefinition.identifier(stagePrefix);
        if(!stagePrefix.startsWith("java_"))throw new IllegalArgumentException("Explicit native staging prefix required");
        this.exactFormal=exactFormalTable;this.stagePrefix=stagePrefix;requireTarget(target,exactFormalTable,stagePrefix);
        this.formal=target;this.rowType=Objects.requireNonNull(rowType);this.columns=List.copyOf(columns);
        projection=new NativeDailyProjection<>(rowType,columns);components=rowType.getRecordComponents();
        jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(120);jdbc.setFetchSize(256);
        codec=new NativeDailyCodec<>(projection);
    }
    public static void requireTarget(String table,String exactFormal,String prefix) {NativeDailyWindowRules.requireTarget(table,exactFormal,prefix);}
    public String table(){return formal;}
    public String stage(){return Objects.requireNonNull(writeTable,"Stage not prepared");}
    public String stagePrefix(){return stagePrefix;}
    public Class<R> rowType(){return rowType;}
    public List<String> columns(){return columns;}
    public VerifiedBatchExecutor.Codec<R,Instant> codec(){return codec;}
    public NativeDailyWindowSnapshot<R> snapshot(String table) {
        requireTarget(table,exactFormal,stagePrefix);
        var metadata=jdbc.queryForList("SELECT id,directoryName,walEnabled,partitionBy,dedup,designatedTimestamp FROM tables() WHERE table_name=?",table);
        if(metadata.size()!=1)throw new IllegalStateException("Exact typed daily table identity required: "+table);
        var item=metadata.getFirst();
        if(!(item.get("id") instanceof Number id)||!(item.get("directoryName") instanceof String directory)||!(item.get("walEnabled") instanceof Boolean wal))
            throw new IllegalStateException("Incomplete typed daily metadata");
        if(!wal||!"MONTH".equals(item.get("partitionBy"))||!Boolean.FALSE.equals(item.get("dedup"))||!"trade_date".equals(item.get("designatedTimestamp")))
            throw new IllegalStateException("Expected MONTH WAL non-DEDUP daily layout");
        requireSchema(table);if(!walSettled(table,wal))throw new IllegalStateException("Typed daily WAL unsettled: "+table);
        var rows=readAll(table);if(!walSettled(table,wal))throw new IllegalStateException("Typed daily WAL changed during snapshot");
        return new NativeDailyWindowSnapshot<>(StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory),id.longValue(),directory,wal,rows,digest(rows));
    }
    public NativeDailyWindowSnapshot<R> formalSnapshot(){return snapshot(formal);}
    public void requireSame(NativeDailyWindowSnapshot<R> expected) {
        var current=formalSnapshot();if(!expected.targetId().equals(current.targetId())||!expected.fingerprint().equals(current.fingerprint()))
            throw new IllegalStateException("Typed daily target changed since planning");
    }
    public NativeDailyWindowSnapshot<R> prepare(NativeDailyWindowSnapshot<R> before,LocalDate from,LocalDate to)throws Exception {
        requireWindow(from,to);requireSame(before);writeTable=stagePrefix+"_stage_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE TABLE \""+writeTable+"\" AS (SELECT "+quotedColumns()+" FROM \""+formal+"\" WHERE trade_date<cast('"+from+"T00:00:00.000000Z' AS TIMESTAMP) OR trade_date>=cast('"+to.plusDays(1)+"T00:00:00.000000Z' AS TIMESTAMP)) TIMESTAMP(trade_date) PARTITION BY MONTH "+(before.wal()?"WAL":"BYPASS WAL"));
        awaitWal(writeTable,before.wal());var stage=snapshot(writeTable);writeId=stage.targetId();
        var outside=before.rows().stream().filter(r->outside(r,from,to)).toList();
        if(!digest(outside).equals(stage.fingerprint()))throw new IllegalStateException("Stage failed to preserve outside-window rows");
        return stage;
    }
    public static void requireWindow(LocalDate from,LocalDate to) {NativeDailyWindowRules.requireWindow(from,to);}
    public boolean outside(R row,LocalDate from,LocalDate to){return projection.outside(row,from,to);}
    private Instant businessDate(R row){return projection.key(row);}
    @Override public void preflight() {
        if(writeTable==null){formalSnapshot();return;}
        var current=snapshot(writeTable);if(!current.targetId().equals(writeId))throw new IllegalStateException("Stage physical identity changed");
    }
    @Override public void send(List<R> rows)throws Exception {
        if(rows==null||rows.isEmpty()||rows.size()>250)throw new IllegalArgumentException("Typed daily batch must contain 1..250 rows");
        preflight();String placeholders=String.join(",",columns.stream().map(c->isTimestamp(c)?"cast(? AS TIMESTAMP)":"?").toList());
        for(var row:rows) {
            Object[] args=values(row).values().toArray();for(int i=0;i<args.length;i++)if(args[i] instanceof Instant instant)args[i]=micros(instant);
            jdbc.update("INSERT INTO \""+stage()+"\" ("+quotedColumns()+") VALUES ("+placeholders+")",args);
        }
    }
    @Override public List<R> readback(List<Instant> keys) {
        if(keys==null||keys.isEmpty()||keys.size()>250||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("Unique bounded date keys required");
        return jdbc.query("SELECT "+readProjection()+" FROM \""+stage()+"\" WHERE "+String.join(" OR ",keys.stream().map(k->"trade_date=cast(? AS TIMESTAMP)").toList())+" ORDER BY trade_date LIMIT "+(keys.size()+1),this::physical,keys.stream().map(NativeDailyWindowWritePort::micros).toArray());
    }
    @Override public boolean walSettled() {
        var metadata=jdbc.queryForList("SELECT walEnabled FROM tables() WHERE table_name=?",stage());
        return metadata.size()==1&&metadata.getFirst().get("walEnabled") instanceof Boolean wal&&walSettled(stage(),wal);
    }
    @Override public boolean uncertainSenderStopped(){return false;}
    public List<R> readAll(String table) {
        requireTarget(table,exactFormal,stagePrefix);var rows=jdbc.query("SELECT "+readProjection()+" FROM \""+table+"\" ORDER BY trade_date LIMIT 10001",this::physical);
        if(rows.size()>10000)throw new IllegalStateException("Typed daily full snapshot exceeds 10000-row budget");
        var seen=new HashSet<Instant>();for(var row:rows)if(!seen.add(businessDate(row)))throw new IllegalStateException("Typed daily duplicate business date");return List.copyOf(rows);
    }
    private R physical(ResultSet rs,int ignored)throws SQLException {
        var map=new LinkedHashMap<String,Object>();for(int i=0;i<columns.size();i++) {
            String column=columns.get(i);Class<?> type=components[i].getType();Object value;
            if(type==Instant.class){Object timestamp=rs.getObject(column+"_micros");value=timestamp==null?null:fromMicros(((Number)timestamp).longValue());}
            else if(type==Double.class){double number=rs.getDouble(column);value=rs.wasNull()||Double.isNaN(number)?null:number;if(value!=null&&!Double.isFinite(number))throw new SQLException("Nonfinite typed daily field "+column);}
            else if(type==Boolean.class){boolean flag=rs.getBoolean(column);value=rs.wasNull()?null:flag;}
            else value=rs.getString(column);map.put(column,value);
        }
        return row(map);
    }
    public R row(Map<String,?> map){return projection.row(map);}
    public Map<String,Object> values(R row){return projection.values(row);}
    public void requireSchema(String table) {
        requireTarget(table,exactFormal,stagePrefix);var schema=jdbc.queryForList("SELECT \"column\",type,designated,upsertKey FROM table_columns('"+table+"')");
        if(schema.size()!=columns.size())throw new IllegalStateException("Expected exact "+columns.size()+"-column daily schema");
        for(int i=0;i<columns.size();i++) {
            var item=schema.get(i);Class<?> type=components[i].getType();String expected=type==Instant.class?"TIMESTAMP":type==Double.class?"DOUBLE":type==Boolean.class?"BOOLEAN":"SYMBOL";
            if(!columns.get(i).equals(item.get("column"))||!expected.equals(item.get("type"))||!Boolean.valueOf(i==0).equals(item.get("designated"))||!Boolean.FALSE.equals(item.get("upsertKey")))
                throw new IllegalStateException("Typed daily schema mismatch: "+columns.get(i));
        }
    }
    private boolean walSettled(String table,boolean wal){return !wal||QuestDbWriteChecks.walSettled(jdbc,table);}
    private void awaitWal(String table,boolean wal)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(!walSettled(table,wal)) {
            if(System.nanoTime()>=deadline)throw new IllegalStateException("Stage WAL did not settle");Thread.sleep(100);
        }
    }
    public static long micros(Instant time){return NativeDailyWindowRules.micros(time);}
    public static Instant fromMicros(long time){return NativeDailyWindowRules.fromMicros(time);}
    public String quotedColumns(){return projection.quotedColumns();}
    private boolean isTimestamp(String column){return components[columns.indexOf(column)].getType()==Instant.class;}
    private String readProjection(){return String.join(",",columns.stream().map(c->isTimestamp(c)?"cast(\""+c+"\" AS long) AS "+c+"_micros":"\""+c+"\"").toList());}
    public String digest(List<R> rows){return projection.digest(rows);}

    /** Pure typed projection shared by wrappers without constructing a JDBC client. */

}
