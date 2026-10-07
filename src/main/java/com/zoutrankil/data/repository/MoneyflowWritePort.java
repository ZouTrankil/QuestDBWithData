package com.zoutrankil.data.repository;
import com.zoutrankil.data.service.MoneyflowSource;
import com.zoutrankil.data.service.MoneyflowJobService;
import com.zoutrankil.data.service.MoneyflowSyncJobOwner;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.MoneyflowMapper;
import com.zoutrankil.data.service.StaticTargetIdentity;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** DEDUP=true D024 sender with full-key readback, bounded rows/bytes, and a frozen target generation. */
public final class MoneyflowWritePort implements VerifiedBatchExecutor.Port<Moneyflow,MoneyflowKey> {
    public static final int MAX_BATCH_ROWS=250,MAX_BATCH_BYTES=1024*1024,MAX_DATE_ROWS=MoneyflowSource.API_ROW_CAP;
    private static final Duration ACK_TIMEOUT=Duration.ofSeconds(10);private static final List<String> VOLUMES=List.of("buy_sm_vol","sell_sm_vol","buy_md_vol","sell_md_vol","buy_lg_vol","sell_lg_vol","buy_elg_vol","sell_elg_vol","net_mf_vol");
    public static final VerifiedBatchExecutor.Codec<Moneyflow,MoneyflowKey> CODEC=new VerifiedBatchExecutor.Codec<>(){
        @Override public MoneyflowKey key(Moneyflow row){return row==null?null:row.key();}
        @Override public byte[] canonicalBytes(Moneyflow row){try{return JobDefinitionJson.mapper().writeValueAsBytes(new MoneyflowMapper().values(row).asMap());}catch(Exception e){throw new IllegalArgumentException("Cannot encode moneyflow row",e);}}
        @Override public int estimatedTransportBytes(Moneyflow row,byte[] canonical){return Math.addExact(Math.multiplyExact(canonical.length,4),256);}};
    private final String table,targetId;private final JdbcTemplate jdbc;private final QuestDB questdb;private final MoneyflowMapper mapper=new MoneyflowMapper();private volatile boolean senderStopped;
    public MoneyflowWritePort(String table,String targetId,JdbcTemplate jdbc,QuestDB questdb){MoneyflowJobService.requireExecutionTableName(table);if(targetId==null||!targetId.matches("static-v2-[0-9a-f]{64}"))throw new IllegalArgumentException("Frozen D024 physical target identity required");this.table=table;this.targetId=targetId;this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(30001);this.questdb=Objects.requireNonNull(questdb);}
    @Override public void preflight(){requireTarget();QuestDbWriteChecks.preflight(jdbc,table,MoneyflowDataset.definition(table));requireTarget();}
    @Override public void send(List<Moneyflow> rows)throws Exception{if(rows==null||rows.isEmpty()||rows.size()>MAX_BATCH_ROWS)throw new IllegalArgumentException("Nonempty moneyflow batch of at most 250 rows required");preflight();senderStopped=false;
        DatasetWritePreparation.prepare(MoneyflowDataset.definition(table),rows,mapper::values,new DatasetWritePreparation.Limits(MAX_BATCH_ROWS,MAX_BATCH_BYTES));long bytes=0;for(var row:rows){bytes=Math.addExact(bytes,CODEC.estimatedTransportBytes(row,CODEC.canonicalBytes(row)));if(bytes>MAX_BATCH_BYTES)throw new IllegalArgumentException("moneyflow batch exceeds 1 MiB before send");}
        boolean attempted=false;try(Sender sender=questdb.borrowSender()){for(var row:rows){var line=sender.table(table).symbol("ts_code",row.tsCode());for(String f:VOLUMES){Long value=volume(row,f);line.longColumn(f,value);}for(String f:MoneyflowDataset.columns().stream().map(DatasetDefinition.Column::storageName).filter(f->f.endsWith("_amount")).toList()){Double value=amount(row,f);if(value!=null)line.doubleColumn(f,value);}line.at(new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());}
            long sequence=sender.flushAndGetSequence();attempted=true;if(sequence<0||!sender.awaitAckedFsn(sequence,ACK_TIMEOUT.toMillis()))throw new IllegalStateException("moneyflow QWP acknowledgement unknown; reconcile complete keys before replay");
        }catch(Exception e){attempted=true;throw e;}finally{senderStopped=attempted;}}
    @Override public List<Moneyflow> readback(List<MoneyflowKey> keys){
        if(keys==null||keys.isEmpty()||keys.size()>MAX_BATCH_ROWS||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("1..250 unique moneyflow readback keys required");
        requireTarget();var clauses=new ArrayList<String>();var args=new ArrayList<Object>();
        var firstDate=keys.stream().map(MoneyflowKey::tradeDate).min(Comparator.naturalOrder()).orElseThrow();
        var lastDate=keys.stream().map(MoneyflowKey::tradeDate).max(Comparator.naturalOrder()).orElseThrow();
        args.add(micros(firstDate));args.add(micros(lastDate.plusDays(1)));
        var codes=keys.stream().map(MoneyflowKey::tsCode).distinct().sorted().toList();args.addAll(codes);
        for(var key:keys){clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");args.add(key.tsCode());args.add(micros(key.tradeDate()));}
        return jdbc.query(select()+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP)"
                +" AND ts_code IN ("+String.join(",",Collections.nCopies(codes.size(),"?"))+")"
                +" AND ("+String.join(" OR ",clauses)+") ORDER BY trade_date,ts_code LIMIT "+(keys.size()+1),this::physical,args.toArray());
    }
    public List<Moneyflow> readDate(LocalDate date){requireTarget();var rows=jdbc.query(select()+" WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT "+(MAX_DATE_ROWS+1),this::physical,micros(date));if(rows.size()>MAX_DATE_ROWS)throw new IllegalStateException("Physical moneyflow date exceeds source row cap");return List.copyOf(rows);}
    public List<Moneyflow> readRange(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to))throw new IllegalArgumentException("Ordered moneyflow date range required");requireTarget();long days=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;if(days>MoneyflowSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("moneyflow read range exceeds five-day bound");var rows=jdbc.query(select()+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "+(MoneyflowSyncJobOwner.MAX_WINDOW_DAYS*MAX_DATE_ROWS+1),this::physical,micros(from),micros(to.plusDays(1)));if(rows.size()>MoneyflowSyncJobOwner.MAX_WINDOW_DAYS*MAX_DATE_ROWS)throw new IllegalStateException("moneyflow physical range exceeds run bound");return List.copyOf(rows);}
    public TargetRange readTargetRange(){requireTarget();String sql="SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros,count(*) AS row_count FROM \""+table+"\"";return jdbc.query(sql,rs->{if(!rs.next())throw new SQLException("moneyflow target range unavailable");Object min=rs.getObject(1),max=rs.getObject(2),count=rs.getObject(3);if(!(count instanceof Number c))throw new SQLException("moneyflow target count unavailable");if(min==null&&max==null){if(c.longValue()!=0)throw new SQLException("moneyflow empty date range has nonzero row count");return new TargetRange(null,null,0);}if(!(min instanceof Number a)||!(max instanceof Number b)||c.longValue()<1)throw new SQLException("Invalid moneyflow target range/count");return new TargetRange(date(a.longValue()),date(b.longValue()),c.longValue());});}
    public List<LocalDate> readExistingDates(){requireTarget();var values=jdbc.query("SELECT DISTINCT cast(trade_date AS long) AS trade_date_micros FROM \""+table+"\" ORDER BY trade_date_micros LIMIT 10001",(rs,n)->date(rs.getLong(1)));if(values.size()>10000)throw new IllegalStateException("moneyflow distinct-date inventory exceeds 10000");return List.copyOf(values);}
    public record TargetRange(LocalDate min,LocalDate max,long rows){public TargetRange{if((min==null)!=(max==null)||min!=null&&min.isAfter(max)||rows<0||(min==null)!=(rows==0))throw new IllegalArgumentException("Invalid moneyflow target range/count");}public boolean empty(){return rows==0;}}
    @Override public boolean walSettled(){requireTarget();return QuestDbWriteChecks.walSettled(jdbc,table);}@Override public boolean uncertainSenderStopped(){return senderStopped;}
    private static Long volume(Moneyflow r,String f){return switch(f){case "buy_sm_vol"->r.buySmVol();case "sell_sm_vol"->r.sellSmVol();case "buy_md_vol"->r.buyMdVol();case "sell_md_vol"->r.sellMdVol();case "buy_lg_vol"->r.buyLgVol();case "sell_lg_vol"->r.sellLgVol();case "buy_elg_vol"->r.buyElgVol();case "sell_elg_vol"->r.sellElgVol();default->r.netMfVol();};}
    private static Double amount(Moneyflow r,String f){return switch(f){case "buy_sm_amount"->r.buySmAmount();case "sell_sm_amount"->r.sellSmAmount();case "buy_md_amount"->r.buyMdAmount();case "sell_md_amount"->r.sellMdAmount();case "buy_lg_amount"->r.buyLgAmount();case "sell_lg_amount"->r.sellLgAmount();case "buy_elg_amount"->r.buyElgAmount();case "sell_elg_amount"->r.sellElgAmount();default->r.netMfAmount();};}
    private String select(){return "SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,buy_sm_vol,buy_sm_amount,sell_sm_vol,sell_sm_amount,buy_md_vol,buy_md_amount,sell_md_vol,sell_md_amount,buy_lg_vol,buy_lg_amount,sell_lg_vol,sell_lg_amount,buy_elg_vol,buy_elg_amount,sell_elg_vol,sell_elg_amount,net_mf_vol,net_mf_amount FROM \""+table+"\"";}
    private Moneyflow physical(ResultSet rs,int n)throws SQLException{Object raw=rs.getObject("trade_date_micros");if(!(raw instanceof Number micros))throw new SQLException("moneyflow trade_date required");var v=new LinkedHashMap<String,Object>();v.put("ts_code",rs.getString("ts_code"));v.put("trade_date",date(micros.longValue()));for(String f:VOLUMES){Object x=rs.getObject(f);v.put(f,x==null?null:((Number)x).longValue());}for(String f:MoneyflowDataset.columns().stream().map(DatasetDefinition.Column::storageName).filter(f->f.endsWith("_amount")).toList()){Object x=rs.getObject(f);v.put(f,x==null?null:((Number)x).doubleValue());}try{return mapper.fromValues(new DatasetValues(v));}catch(RuntimeException e){throw new SQLException("Invalid physical moneyflow row",e);}}
    private void requireTarget(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String dir)||!targetId.equals(StaticTargetIdentity.identify(jdbc,table,id.longValue(),dir)))throw new IllegalStateException("D024 QuestDB physical target identity changed");}
    private static long micros(LocalDate date){return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);}private static LocalDate date(long micros){return TemporalValues.CalendarTimestamp.fromStorageEpoch(micros,TemporalValues.EpochUnit.MICROS).date();}
}
