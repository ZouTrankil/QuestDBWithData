package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginZrz;
import com.zoutrankil.data.domain.MarginZrzDataset;
import com.zoutrankil.data.domain.MarginZrzKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.MarginZrzMapper;
import com.zoutrankil.data.service.MarginZrzSource;
import com.zoutrankil.data.service.MarginZrzSyncJobOwner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** Writes only a journal-owned D031 stage; formal/non-DEDUP direct writes are prohibited. */
public final class MarginZrzWritePort implements VerifiedBatchExecutor.Port<MarginZrz,MarginZrzKey> {
    public static final int MAX_BATCH_ROWS=250,MAX_BATCH_BYTES=1024*1024;
    private static final Duration ACK_TIMEOUT=Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<MarginZrz,MarginZrzKey> CODEC=new VerifiedBatchExecutor.Codec<>(){
        @Override public MarginZrzKey key(MarginZrz row){return row==null?null:row.key();}
        @Override public byte[] canonicalBytes(MarginZrz row){try{return JobDefinitionJson.mapper().writeValueAsBytes(new MarginZrzMapper().values(row).asMap());}catch(Exception e){throw new IllegalArgumentException("Cannot canonicalize D031 row",e);}}
        @Override public int estimatedTransportBytes(MarginZrz row,byte[] canonical){return Math.addExact(Math.multiplyExact(canonical.length,4),128);}};
    private final String formalTable,frozenTargetId;private volatile String writeTable,writeTargetId;private final JdbcTemplate jdbc;private final QuestDB questdb;private final MarginZrzMapper mapper=new MarginZrzMapper();private volatile boolean senderStopped;
    public MarginZrzWritePort(String formalTable,String frozenTargetId,JdbcTemplate jdbc,QuestDB questdb){MarginZrzDataset.requireIsolatedTable(formalTable);if(frozenTargetId==null||!frozenTargetId.matches("static-v2-[0-9a-f]{64}"))throw new IllegalArgumentException("Frozen D031 isolated target identity required");this.formalTable=formalTable;this.frozenTargetId=frozenTargetId;writeTable=formalTable;writeTargetId=frozenTargetId;this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(MarginZrzStorage.MAX_ROWS+1);this.questdb=Objects.requireNonNull(questdb);}
    public synchronized void useStage(String stage,String stageId){MarginZrzDataset.requireIsolatedTable(stage);if(!stage.contains("_stage_")||stageId==null||!stageId.matches("static-v2-[0-9a-f]{64}")||stageId.equals(frozenTargetId))throw new IllegalArgumentException("D031 journal-owned stage identity required");writeTable=stage;writeTargetId=stageId;}
    public String formalTable(){return formalTable;}public String stageTable(){return writeTable;}
    @Override public void preflight(){requireWriteTarget();QuestDbWriteChecks.preflight(jdbc,writeTable,MarginZrzDataset.isolatedWriteDefinition(writeTable));requireWriteTarget();}
    @Override public void send(List<MarginZrz> rows)throws Exception {
        if(rows==null||rows.isEmpty()||rows.size()>MAX_BATCH_ROWS)throw new IllegalArgumentException("D031 requires 1..250 row batches");if(writeTable.equals(formalTable))throw new IllegalStateException("D031 direct writes forbidden; bind stage first");preflight();senderStopped=false;
        DatasetWritePreparation.prepareWalReplace(MarginZrzDataset.isolatedWriteDefinition(writeTable),rows,mapper::values,new DatasetWritePreparation.Limits(MAX_BATCH_ROWS,MAX_BATCH_BYTES));long bytes=0;
        for(var row:rows){bytes=Math.addExact(bytes,CODEC.estimatedTransportBytes(row,CODEC.canonicalBytes(row)));if(bytes>MAX_BATCH_BYTES)throw new IllegalArgumentException("D031 batch exceeds 1 MiB before send");}
        boolean attempted=false;try(Sender sender=questdb.borrowSender()){
            for(var row:rows){var line=sender.table(writeTable);if(row.ob()!=null)line.doubleColumn("ob",row.ob());if(row.aucAmount()!=null)line.doubleColumn("auc_amount",row.aucAmount());
                if(row.repoAmount()!=null)line.doubleColumn("repo_amount",row.repoAmount());if(row.repayAmount()!=null)line.doubleColumn("repay_amount",row.repayAmount());if(row.cb()!=null)line.doubleColumn("cb",row.cb());
                line.at(new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());}
            long sequence=sender.flushAndGetSequence();attempted=true;if(sequence<0||!sender.awaitAckedFsn(sequence,ACK_TIMEOUT.toMillis()))throw new IllegalStateException("D031 stage ACK unknown; reconcile exact trade_date keys before publish");
        }catch(Exception failure){attempted=true;throw failure;}finally{senderStopped=attempted;}
    }
    @Override public List<MarginZrz> readback(List<MarginZrzKey> keys){if(keys==null||keys.isEmpty()||keys.size()>MAX_BATCH_ROWS||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("D031 readback requires 1..250 unique date keys");requireWriteTarget();var clauses=new ArrayList<String>();var args=new ArrayList<Object>();for(var key:keys){clauses.add("trade_date=cast(? AS TIMESTAMP)");args.add(micros(key.tradeDate()));}
        var rows=jdbc.query(select(writeTable)+" WHERE ("+String.join(" OR ",clauses)+") ORDER BY trade_date LIMIT "+(keys.size()+1),this::physical,args.toArray());if(rows.size()>keys.size()||rows.stream().map(MarginZrz::key).distinct().count()!=rows.size())throw new IllegalStateException("D031 duplicate natural key in stage readback");return List.copyOf(rows);}
    public List<MarginZrz> readWindow(String table,LocalDate from,LocalDate to){requireBounded(from,to);requireTableIdentity(table);var rows=jdbc.query(select(table)+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT "+(MarginZrzStorage.MAX_ROWS+1),this::physical,micros(from),micros(to.plusDays(1)));if(rows.size()>MarginZrzStorage.MAX_ROWS||rows.stream().map(MarginZrz::key).distinct().count()!=rows.size())throw new IllegalStateException("D031 window exceeds bound or has duplicate dates");return List.copyOf(rows);}
    public List<MarginZrz> readDate(LocalDate date){Objects.requireNonNull(date);requireTableIdentity(formalTable);var rows=jdbc.query(select(formalTable)+" WHERE trade_date=cast(? AS TIMESTAMP) LIMIT 2",this::physical,micros(date));if(rows.size()>1)throw new IllegalStateException("D031 duplicate physical trade_date");return List.copyOf(rows);}
    public List<LocalDate> readExistingDates(){requireTableIdentity(formalTable);var dates=jdbc.query("SELECT DISTINCT cast(trade_date AS long) AS trade_micros FROM \""+formalTable+"\" ORDER BY trade_micros LIMIT 10001",(rs,n)->date(rs.getLong(1)));if(dates.size()>10000)throw new IllegalStateException("D031 existing-date inventory exceeds 10000 dates");return List.copyOf(dates);}
    public TargetRange readTargetRange(){requireTableIdentity(formalTable);return jdbc.query("SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros,count(*) AS row_count FROM \""+formalTable+"\"",rs->{if(!rs.next())throw new SQLException("D031 target range unavailable");Object min=rs.getObject("min_micros"),max=rs.getObject("max_micros"),count=rs.getObject("row_count");if(!(count instanceof Number n))throw new SQLException("D031 count unavailable");if(min==null&&max==null){if(n.longValue()!=0)throw new SQLException("D031 empty range/count mismatch");return new TargetRange(null,null,0);}if(!(min instanceof Number a)||!(max instanceof Number b)||n.longValue()<1)throw new SQLException("D031 target range invalid");return new TargetRange(date(a.longValue()),date(b.longValue()),n.longValue());});}
    public record TargetRange(LocalDate min,LocalDate max,long rows){public TargetRange{if((min==null)!=(max==null)||min!=null&&min.isAfter(max)||rows<0||(min==null)!=(rows==0))throw new IllegalArgumentException("Invalid D031 range");}public boolean empty(){return rows==0;}}
    @Override public boolean walSettled(){requireWriteTarget();return QuestDbWriteChecks.walSettled(jdbc,writeTable);}@Override public boolean uncertainSenderStopped(){return senderStopped;}
    private MarginZrz physical(ResultSet rs,int n)throws SQLException{Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number micros))throw new SQLException("D031 timestamp missing");var v=new LinkedHashMap<String,Object>();v.put("trade_date",date(micros.longValue()));for(String f:List.of("ob","auc_amount","repo_amount","repay_amount","cb")){Object x=rs.getObject(f);if(x==null)v.put(f,null);else if(x instanceof Number number&&Double.isFinite(number.doubleValue()))v.put(f,number.doubleValue());else throw new SQLException("D031 invalid physical value "+f);}try{return mapper.fromValues(new DatasetValues(v));}catch(RuntimeException e){throw new SQLException("Invalid D031 row",e);}}
    private static String select(String table){return "SELECT cast(trade_date AS long) AS trade_micros,ob,auc_amount,repo_amount,repay_amount,cb FROM \""+table+"\"";}
    private void requireWriteTarget(){requireTableIdentity(writeTable);}private void requireTableIdentity(String table){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String dir)||!writeTargetId.equals(StaticTargetIdentity.identify(jdbc,table,id.longValue(),dir)))throw new IllegalStateException("D031 physical target generation changed");}
    private static long micros(LocalDate date){return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);}private static LocalDate date(long micros){return TemporalValues.CalendarTimestamp.fromStorageEpoch(micros,TemporalValues.EpochUnit.MICROS).date();}
    private static void requireBounded(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to)||java.time.temporal.ChronoUnit.DAYS.between(from,to)+1>MarginZrzSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D031 bounded date window required");}
}
