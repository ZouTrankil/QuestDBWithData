package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.DcIndexMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import com.zoutrankil.data.service.DcIndexSource;

/** Single-send typed WAL port for DEDUP=false D023 targets; retries require a newly built stage. */
public final class DcIndexWritePort implements VerifiedBatchExecutor.Port<DcIndex,DcIndexKey> {
    public static final int MAX_BATCH_ROWS=250,MAX_BATCH_BYTES=1024*1024;
    private static final Duration ACK_TIMEOUT=Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<DcIndex,DcIndexKey> CODEC=new VerifiedBatchExecutor.Codec<>() {
        @Override public DcIndexKey key(DcIndex row){if(row==null)return null;return row.key();}
        @Override public byte[] canonicalBytes(DcIndex row) {
            try{return JobDefinitionJson.mapper().writeValueAsBytes(new DcIndexMapper().values(row).asMap());}
            catch(Exception e){throw new IllegalArgumentException("Cannot canonicalize dc_index row",e);}
        }
        @Override public int estimatedTransportBytes(DcIndex row,byte[] canonical){
            int text=0;for(String v:List.of(row.tsCode(),row.name()==null?"":row.name(),row.leading()==null?"":row.leading(),row.leadingCode()==null?"":row.leadingCode()))
                text=Math.addExact(text,v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length*4);
            long nanos=row.tradeDate().atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()*1_000_000_000L;
            return Math.toIntExact(Math.max(canonical.length,canonical.length+text+Long.toString(nanos).length()+256L));
        }
    };
    private final String table,expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private volatile boolean senderStopped;
    public DcIndexWritePort(String table,String expectedTargetId,JdbcTemplate jdbc,QuestDB questdb){
        DatasetDefinition.identifier(table);if(expectedTargetId==null||!expectedTargetId.matches("static-v2-[0-9a-f]{64}"))throw new IllegalArgumentException("Frozen dc_index target id required");
        this.table=table;this.expectedTargetId=expectedTargetId;this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(10001);this.questdb=Objects.requireNonNull(questdb);
    }
    public DcIndexWritePort forTarget(String stage,String physicalId){return new DcIndexWritePort(stage,physicalId,jdbc,questdb);}
    @Override public void preflight(){
        QuestDbWriteChecks.preflight(jdbc,table,DcIndexDataset.definition(table));requireExpectedTarget();
    }
    @Override public void send(List<DcIndex> rows)throws Exception{
        if(!table.matches("java_dc_index_stage_[0-9a-f]{32}"))
            throw new IllegalStateException("D023 non-DEDUP writes require an isolated source-verified publication stage");
        if(rows==null||rows.isEmpty()||rows.size()>MAX_BATCH_ROWS)throw new IllegalArgumentException("Nonempty dc_index batch of at most 250 rows required");
        preflight();senderStopped=false;var keys=new HashSet<DcIndexKey>();long bytes=0;
        for(var row:rows){if(row==null||!keys.add(row.key()))throw new IllegalArgumentException("Null or duplicate dc_index full key in batch");
            bytes=Math.addExact(bytes,CODEC.estimatedTransportBytes(row,CODEC.canonicalBytes(row)));}
        if(bytes>MAX_BATCH_BYTES)throw new IllegalArgumentException("dc_index batch exceeds 1 MiB");
        boolean attempted=false;
        try(Sender sender=questdb.borrowSender()){
            for(var row:rows){var line=sender.table(table).symbol("ts_code",row.tsCode());
                if(row.name()!=null)line.stringColumn("name",row.name());if(row.leading()!=null)line.stringColumn("leading",row.leading());
                if(row.leadingCode()!=null)line.stringColumn("leading_code",row.leadingCode());
                if(row.pctChange()!=null)line.doubleColumn("pct_change",row.pctChange());if(row.leadingPct()!=null)line.doubleColumn("leading_pct",row.leadingPct());
                if(row.totalMv()!=null)line.doubleColumn("total_mv",row.totalMv());if(row.turnoverRate()!=null)line.doubleColumn("turnover_rate",row.turnoverRate());
                if(row.upNum()!=null)line.intColumn("up_num",row.upNum());if(row.downNum()!=null)line.intColumn("down_num",row.downNum());
                line.at(new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());}
            long seq=sender.flushAndGetSequence();attempted=true;
            if(seq<0||!sender.awaitAckedFsn(seq,ACK_TIMEOUT.toMillis()))throw new IllegalStateException("dc_index stage QWP acknowledgement unknown");
        }catch(Exception e){attempted=true;throw e;}finally{senderStopped=attempted;}
    }
    @Override public List<DcIndex> readback(List<DcIndexKey> keys){
        if(keys==null||keys.isEmpty())return List.of();if(keys.size()>MAX_BATCH_ROWS||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("At most 250 unique dc_index readback keys required");
        requireExpectedTarget();var clauses=new ArrayList<String>();var args=new ArrayList<Object>();
        for(var key:keys){clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");args.add(key.tsCode());args.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));}
        return jdbc.query(select()+" WHERE "+String.join(" OR ",clauses)+" ORDER BY trade_date,ts_code LIMIT "+(keys.size()+1),this::physical,args.toArray());
    }
    @Override public boolean walSettled(){requireExpectedTarget();return QuestDbWriteChecks.walSettled(jdbc,table);}
    @Override public boolean uncertainSenderStopped(){return senderStopped;}
    public String table(){return table;}
    public List<DcIndex> readDate(java.time.LocalDate date){requireExpectedTarget();long micros=new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);
        var rows=jdbc.query(select()+" WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT "+(DcIndexSource.SOURCE_ROW_CAP+1),this::physical,micros);
        if(rows.size()>DcIndexSource.SOURCE_ROW_CAP)throw new IllegalStateException("dc_index physical date exceeds source row bound");return List.copyOf(rows);}
    public List<LocalDate> readExistingDates(){requireExpectedTarget();var rows=jdbc.query("SELECT DISTINCT cast(trade_date AS long) AS trade_date_micros FROM \""+table+"\" ORDER BY trade_date_micros LIMIT 10001",
            (rs,i)->TemporalValues.CalendarTimestamp.fromStorageEpoch(rs.getLong("trade_date_micros"),TemporalValues.EpochUnit.MICROS).date());
        if(rows.size()>10_000)throw new IllegalStateException("dc_index physical distinct-date inventory exceeds 10000");return List.copyOf(rows);}
    private void requireExpectedTarget(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String dir)
                ||!expectedTargetId.equals(StaticTargetIdentity.identify(jdbc,table,id.longValue(),dir)))throw new IllegalStateException("dc_index physical table generation changed");}
    private String select(){return "SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,name,leading,leading_code,pct_change,leading_pct,total_mv,turnover_rate,up_num,down_num FROM \""+table+"\"";}
    private DcIndex physical(ResultSet rs,int n)throws SQLException{
        Object raw=rs.getObject("trade_date_micros");if(!(raw instanceof Number micros))throw new SQLException("dc_index trade_date required");
        try{return new DcIndex(rs.getString("ts_code"),TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(),TemporalValues.EpochUnit.MICROS).date(),
                rs.getString("name"),rs.getString("leading"),rs.getString("leading_code"),nullableDouble(rs,"pct_change"),nullableDouble(rs,"leading_pct"),
                nullableDouble(rs,"total_mv"),nullableDouble(rs,"turnover_rate"),nullableInt(rs,"up_num"),nullableInt(rs,"down_num"));}
        catch(RuntimeException e){throw new SQLException("Invalid physical dc_index row",e);}
    }
    private static Double nullableDouble(ResultSet rs,String field)throws SQLException{double v=rs.getDouble(field);return rs.wasNull()?null:v;}
    private static Integer nullableInt(ResultSet rs,String field)throws SQLException{int v=rs.getInt(field);return rs.wasNull()?null:v;}
}
