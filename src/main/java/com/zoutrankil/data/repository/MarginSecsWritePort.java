package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginSecs;
import com.zoutrankil.data.domain.MarginSecsDataset;
import com.zoutrankil.data.domain.MarginSecsKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.MarginSecsMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** D030 bounded WAL/DEDUP writer with full composite-key, all-column readback. */
public final class MarginSecsWritePort implements VerifiedBatchExecutor.Port<MarginSecs,MarginSecsKey> {
    public static final int MAX_BATCH_ROWS=250,MAX_BATCH_BYTES=1024*1024;
    private static final Duration ACK_TIMEOUT=Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<MarginSecs,MarginSecsKey> CODEC=new VerifiedBatchExecutor.Codec<>() {
        @Override public MarginSecsKey key(MarginSecs row){return row==null?null:row.key();}
        @Override public byte[] canonicalBytes(MarginSecs row){try{return JobDefinitionJson.mapper().writeValueAsBytes(new MarginSecsMapper().values(row).asMap());}
            catch(Exception failure){throw new IllegalArgumentException("Cannot canonicalize D030 row",failure);}}
        @Override public int estimatedTransportBytes(MarginSecs row,byte[] canonical){return Math.addExact(Math.multiplyExact(canonical.length,4),128);}
    };
    private final String table,targetId;private final JdbcTemplate jdbc;private final QuestDB questdb;private final MarginSecsMapper mapper=new MarginSecsMapper();
    private volatile boolean senderStopped;
    public MarginSecsWritePort(String table,String targetId,JdbcTemplate jdbc,QuestDB questdb){
        MarginSecsDataset.requireIsolatedTable(table);if(targetId==null||!targetId.matches("static-v2-[0-9a-f]{64}"))throw new IllegalArgumentException("Frozen D030 physical target identity required");
        this.table=table;this.targetId=targetId;this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());this.jdbc.setQueryTimeout(20);
        this.jdbc.setMaxRows(MarginSecsStorage.MAX_ROWS+1);this.questdb=Objects.requireNonNull(questdb);
    }
    public String table(){return table;}
    public String targetId(){return targetId;}
    @Override public void preflight(){requireTarget();QuestDbWriteChecks.preflight(jdbc,table,MarginSecsDataset.isolatedWriteDefinition(table));requireTarget();}
    @Override public void send(List<MarginSecs> rows)throws Exception {
        if(rows==null||rows.isEmpty()||rows.size()>MAX_BATCH_ROWS)throw new IllegalArgumentException("D030 requires a nonempty batch of at most 250 rows");
        preflight();senderStopped=false;DatasetWritePreparation.prepare(MarginSecsDataset.isolatedWriteDefinition(table),rows,mapper::values,
                new DatasetWritePreparation.Limits(MAX_BATCH_ROWS,MAX_BATCH_BYTES));
        long estimated=0;for(var row:rows){byte[] bytes=CODEC.canonicalBytes(row);estimated=Math.addExact(estimated,CODEC.estimatedTransportBytes(row,bytes));
            if(estimated>MAX_BATCH_BYTES)throw new IllegalArgumentException("D030 ILP batch exceeds one MiB before send");}
        boolean attempted=false;
        try(Sender sender=questdb.borrowSender()){
            for(var row:rows){var line=sender.table(table).symbol("ts_code",row.tsCode()).symbol("exchange",row.exchange());
                if(row.name()!=null)line.stringColumn("name",row.name());line.at(new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());}
            long sequence=sender.flushAndGetSequence();attempted=true;
            if(sequence<0||!sender.awaitAckedFsn(sequence,ACK_TIMEOUT.toMillis()))throw new IllegalStateException("D030 QWP ACK unknown; reconcile exact keys before replay");
        }catch(Exception failure){attempted=true;throw failure;}finally{senderStopped=attempted;}
    }
    @Override public List<MarginSecs> readback(List<MarginSecsKey> keys){
        if(keys==null||keys.isEmpty()||keys.size()>MAX_BATCH_ROWS||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("D030 exact readback requires 1..250 unique keys");
        requireTarget();var predicates=new ArrayList<String>();var args=new ArrayList<Object>();
        for(var key:keys){predicates.add("(trade_date=cast(? AS TIMESTAMP) AND ts_code=?)");args.add(micros(key.tradeDate()));args.add(key.tsCode());}
        var rows=jdbc.query(select()+" WHERE "+String.join(" OR ",predicates)+" ORDER BY trade_date,ts_code LIMIT "+(keys.size()+1),this::physical,args.toArray());
        if(rows.size()>keys.size())throw new IllegalStateException("D030 exact-key readback returned duplicate/extraneous rows");return List.copyOf(rows);
    }
    public List<MarginSecs> readDate(LocalDate date){requireTarget();var rows=jdbc.query(select()+" WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT 6001",this::physical,micros(date));
        if(rows.size()>6000)throw new IllegalStateException("D030 day exceeds the conservative source bound");return List.copyOf(rows);}
    public MarginSecsStorage.TargetRange readTargetRange(){requireTarget();return new MarginSecsStorage(jdbc,table).targetRange();}
    @Override public boolean walSettled(){requireTarget();return QuestDbWriteChecks.walSettled(jdbc,table);}
    @Override public boolean uncertainSenderStopped(){return senderStopped;}
    private String select(){return "SELECT cast(trade_date AS long) AS trade_micros,ts_code,name,exchange FROM \""+table+"\"";}
    private MarginSecs physical(ResultSet rs,int row)throws SQLException{Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number n))throw new SQLException("D030 trade_date required");
        var values=new LinkedHashMap<String,Object>();values.put("trade_date",date(n.longValue()));values.put("ts_code",rs.getString("ts_code"));values.put("name",rs.getString("name"));values.put("exchange",rs.getString("exchange"));
        try{return mapper.fromValues(new com.zoutrankil.data.domain.DatasetValues(values));}catch(RuntimeException invalid){throw new SQLException("Invalid physical D030 row",invalid);}}
    private void requireTarget(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String directory)
                ||!targetId.equals(StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory)))throw new IllegalStateException("D030 isolated physical table generation changed");}
    private static long micros(LocalDate day){return new TemporalValues.CalendarTimestamp(day).storageEpoch(TemporalValues.EpochUnit.MICROS);}
    private static LocalDate date(long value)throws SQLException{try{return TemporalValues.CalendarTimestamp.fromStorageEpoch(value,TemporalValues.EpochUnit.MICROS).date();}catch(RuntimeException invalid){throw new SQLException("D030 invalid business timestamp",invalid);}}
}
