package com.zoutrankil.data.margin.storage;

import com.zoutrankil.data.margin.domain.MarginDetailLimits;

import com.zoutrankil.data.margin.domain.MarginDetailTargetRange;

import com.zoutrankil.data.margin.port.MarginDetailWriteSession;

import com.zoutrankil.data.repository.StaticTargetIdentity;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.margin.mapper.MarginDetailMapper;
import com.zoutrankil.data.service.*;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** DEDUP=true isolated QWP writer; every batch is full-key/full-value read back. */
public final class MarginDetailWritePort implements MarginDetailWriteSession {
    public static final int MAX_BATCH_ROWS=MarginDetailLimits.MAX_BATCH_ROWS,MAX_BATCH_BYTES=MarginDetailLimits.MAX_BATCH_BYTES,MAX_DATE_ROWS=MarginDetailLimits.API_ROW_CAP;
    private static final Duration ACK_TIMEOUT=Duration.ofSeconds(10);
    private static final List<String> NUMERIC=List.of("rzye","rzmre","rzche","rqye","rqyl","rqchl","rqmcl","rzrqye");
    public static final VerifiedBatchExecutor.Codec<MarginDetail,MarginDetailKey> CODEC=new VerifiedBatchExecutor.Codec<>(){
        @Override public MarginDetailKey key(MarginDetail row){return row==null?null:row.key();}
        @Override public byte[] canonicalBytes(MarginDetail row){try{return JobDefinitionJson.canonicalMapper().writeValueAsBytes(new MarginDetailMapper().values(row).asMap());}catch(Exception invalid){throw new IllegalArgumentException("Cannot canonically encode D029 row",invalid);}}
        @Override public int estimatedTransportBytes(MarginDetail row,byte[] canonical){return Math.addExact(Math.multiplyExact(canonical.length,4),256);}};
    private final String table,targetId;private final JdbcTemplate jdbc;private final QuestDB questdb;private final MarginDetailMapper mapper=new MarginDetailMapper();private volatile boolean senderStopped;
    public MarginDetailWritePort(String table,String targetId,JdbcTemplate jdbc,QuestDB questdb){MarginDetailDataset.requireWriteTable(table);if(targetId==null||!targetId.matches("static-v2-[0-9a-f]{64}"))throw new IllegalArgumentException("Frozen D029 physical target identity required");this.table=table;this.targetId=targetId;this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(MarginDetailLimits.MAX_WINDOW_DAYS*MAX_DATE_ROWS+1);this.questdb=Objects.requireNonNull(questdb);}
    @Override public void preflight(){requireTarget();QuestDbWriteChecks.preflight(jdbc,table,MarginDetailDataset.definition(table));requireTarget();}
    @Override public void send(List<MarginDetail> rows)throws Exception{if(rows==null||rows.isEmpty()||rows.size()>MAX_BATCH_ROWS)throw new IllegalArgumentException("D029 requires a nonempty batch of at most 250 rows");preflight();senderStopped=false;
        DatasetWritePreparation.prepare(MarginDetailDataset.definition(table),rows,mapper::values,new DatasetWritePreparation.Limits(MAX_BATCH_ROWS,MAX_BATCH_BYTES));long bytes=0;for(var row:rows){bytes=Math.addExact(bytes,CODEC.estimatedTransportBytes(row,CODEC.canonicalBytes(row)));if(bytes>MAX_BATCH_BYTES)throw new IllegalArgumentException("D029 batch exceeds 1 MiB before send");}
        boolean attempted=false;try(Sender sender=questdb.borrowSender()){for(var row:rows){var line=sender.table(table).symbol("ts_code",row.tsCode());if(row.name()!=null)line.stringColumn("name",row.name());for(String field:NUMERIC){Double value=numeric(row,field);if(value!=null)line.doubleColumn(field,value);}line.at(new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());}
            long sequence=sender.flushAndGetSequence();attempted=true;if(sequence<0||!sender.awaitAckedFsn(sequence,ACK_TIMEOUT.toMillis()))throw new IllegalStateException("D029 write acknowledgement is unknown; reconcile complete keys before replay");
        }catch(Exception failure){attempted=true;throw failure;}finally{senderStopped=attempted;}}
    @Override public List<MarginDetail> readback(List<MarginDetailKey> keys){
        if(keys==null||keys.isEmpty()||keys.size()>MAX_BATCH_ROWS||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("D029 readback requires 1..250 unique full keys");
        requireTarget();var clauses=new ArrayList<String>();var args=new ArrayList<Object>();
        LocalDate min=keys.stream().map(MarginDetailKey::tradeDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate max=keys.stream().map(MarginDetailKey::tradeDate).max(LocalDate::compareTo).orElseThrow();
        var codes=new LinkedHashSet<String>();keys.forEach(key->codes.add(key.tsCode()));
        args.add(micros(min));args.add(micros(max.plusDays(1)));args.addAll(codes);
        for(var key:keys){clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");args.add(key.tsCode());args.add(micros(key.tradeDate()));}
        String filter="trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) AND ts_code IN ("
                +String.join(",",Collections.nCopies(codes.size(),"?"))+") AND ("+String.join(" OR ",clauses)+")";
        return jdbc.query(select()+" WHERE "+filter+" ORDER BY trade_date,ts_code LIMIT "+(keys.size()+1),this::physical,args.toArray());
    }
    public List<MarginDetail> readDate(LocalDate date){requireTarget();var rows=jdbc.query(select()+" WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT "+(MAX_DATE_ROWS+1),this::physical,micros(date));if(rows.size()>=MAX_DATE_ROWS)throw new IllegalStateException("D029 physical trade-date reaches the unpaged 6000-row source cap");return List.copyOf(rows);}
    public List<MarginDetail> readRange(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to)||java.time.temporal.ChronoUnit.DAYS.between(from,to)+1>MarginDetailLimits.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D029 physical range exceeds finite window");requireTarget();var rows=jdbc.query(select()+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "+(MarginDetailLimits.MAX_WINDOW_DAYS*MAX_DATE_ROWS+1),this::physical,micros(from),micros(to.plusDays(1)));if(rows.size()>MarginDetailLimits.MAX_WINDOW_DAYS*MAX_DATE_ROWS)throw new IllegalStateException("D029 physical range exceeds bounded row count");return List.copyOf(rows);}
    public MarginDetailTargetRange readTargetRange(){requireTarget();String sql="SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros,count(*) AS row_count FROM \""+table+"\"";return jdbc.query(sql,rs->{if(!rs.next())throw new SQLException("D029 physical range unavailable");Object min=rs.getObject(1),max=rs.getObject(2),count=rs.getObject(3);if(!(count instanceof Number c))throw new SQLException("D029 physical count unavailable");if(min==null&&max==null){if(c.longValue()!=0)throw new SQLException("D029 empty physical range has rows");return new MarginDetailTargetRange(null,null,0);}if(!(min instanceof Number a)||!(max instanceof Number b)||c.longValue()<1)throw new SQLException("D029 invalid physical range");return new MarginDetailTargetRange(date(a.longValue()),date(b.longValue()),c.longValue());});}
    public List<LocalDate> readExistingDates(){requireTarget();var dates=jdbc.query("SELECT DISTINCT cast(trade_date AS long) AS trade_date_micros FROM \""+table+"\" ORDER BY trade_date_micros LIMIT 10001",(rs,n)->date(rs.getLong(1)));if(dates.size()>10_000)throw new IllegalStateException("D029 distinct date inventory exceeds 10000");return List.copyOf(dates);}

    @Override public boolean walSettled(){requireTarget();return QuestDbWriteChecks.walSettled(jdbc,table);}@Override public boolean uncertainSenderStopped(){return senderStopped;}
    private String select(){return "SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,name,rzye,rzmre,rzche,rqye,rqyl,rqchl,rqmcl,rzrqye FROM \""+table+"\"";}
    private MarginDetail physical(ResultSet rs,int index)throws SQLException{Object raw=rs.getObject("trade_date_micros");if(!(raw instanceof Number micros))throw new SQLException("D029 physical trade_date required");var values=new LinkedHashMap<String,Object>();values.put("ts_code",rs.getString("ts_code"));values.put("trade_date",date(micros.longValue()));values.put("name",rs.getString("name"));for(String field:NUMERIC){Object value=rs.getObject(field);values.put(field,value==null?null:((Number)value).doubleValue());}try{return mapper.fromValues(new DatasetValues(values));}catch(RuntimeException invalid){throw new SQLException("Invalid physical margin_detail row",invalid);}}
    private void requireTarget(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String dir)||!targetId.equals(StaticTargetIdentity.identify(jdbc,table,id.longValue(),dir)))throw new IllegalStateException("D029 physical isolated target identity changed");}
    private static Double numeric(MarginDetail row,String field){return switch(field){case "rzye"->row.rzye();case "rzmre"->row.rzmre();case "rzche"->row.rzche();case "rqye"->row.rqye();case "rqyl"->row.rqyl();case "rqchl"->row.rqchl();case "rqmcl"->row.rqmcl();default->row.rzrqye();};}
    private static long micros(LocalDate date){return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);}private static LocalDate date(long micros){return TemporalValues.CalendarTimestamp.fromStorageEpoch(micros,TemporalValues.EpochUnit.MICROS).date();}
    @Override public VerifiedBatchExecutor.Codec<MarginDetail,MarginDetailKey> codec() { return CODEC; }
}
