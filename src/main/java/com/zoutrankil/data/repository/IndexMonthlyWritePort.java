package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.IndexMonthlyDataset;

import com.zoutrankil.data.domain.policy.IndexMonthlyUniverse;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.IndexMonthlyMapper;
import com.zoutrankil.data.service.IndexMonthlySource;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** D022 bounded-batch writer; during a run it may be rebound only to the journal-owned stage table. */
public final class IndexMonthlyWritePort implements VerifiedBatchExecutor.Port<IndexMonthly,IndexMonthlyKey> {
    public static final int MAX_BATCH_ROWS=250,MAX_BATCH_BYTES=1024*1024,MAX_EXISTING_ROWS=100_000;
    private static final Duration ACK_TIMEOUT=Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<IndexMonthly,IndexMonthlyKey> CODEC=new VerifiedBatchExecutor.Codec<>() {
        @Override public IndexMonthlyKey key(IndexMonthly row){return row==null?null:row.key();}
        @Override public byte[] canonicalBytes(IndexMonthly row){
            try{return JobDefinitionJson.mapper().writeValueAsBytes(new IndexMonthlyMapper().values(row).asMap());}
            catch(Exception failure){throw new IllegalArgumentException("Cannot encode D022 full row",failure);}
        }
        @Override public int estimatedTransportBytes(IndexMonthly row,byte[] canonical){return Math.addExact(Math.multiplyExact(canonical.length,4),256);}
    };
    private final String table,targetId;private volatile String writeTable,writeTargetId;
    private final JdbcTemplate jdbc;private final QuestDB questdb;private final IndexMonthlyMapper mapper=new IndexMonthlyMapper();
    private volatile boolean uncertainSenderStopped;
    @FunctionalInterface public interface PublicationCallback { String publish() throws Exception; }
    private volatile int expectedRows,attemptedRows;
    private volatile List<IndexMonthly> expectedSource=List.of();
    private volatile PublicationCallback publicationCallback;
    private volatile boolean publicationComplete;
    private volatile Exception publicationFailure;
    public IndexMonthlyWritePort(String table,String targetId,JdbcTemplate jdbc,QuestDB questdb) {
        IndexMonthlyDataset.requireIsolatedTableName(table);
        if(targetId==null||!targetId.matches("static-v2-[0-9a-f]{64}"))throw new IllegalArgumentException("Frozen isolated D022 target id required");
        this.table=table;this.targetId=targetId;this.writeTable=table;this.writeTargetId=targetId;
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(MAX_EXISTING_ROWS+1);this.questdb=Objects.requireNonNull(questdb);
    }
    /** Bind generic verified-batch writes to the exact stage created for the current frozen window. */
    public synchronized void useStagingTarget(String stage,String physicalId) {
        IndexMonthlyDataset.requireIsolatedTableName(stage);
        if(!stage.contains("_stage_")||physicalId==null||!physicalId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("D022 journal-owned stage identity required");
        this.writeTable=stage;this.writeTargetId=physicalId;
    }
    public synchronized void configureFinalPublication(List<IndexMonthly> rows,PublicationCallback callback) {
        if(writeTable.equals(table)||rows==null||rows.isEmpty()||rows.size()>IndexMonthlySource.CLIENT_ROW_CAP||callback==null)
            throw new IllegalArgumentException("D022 nonempty bounded stage and publication callback required");
        if(rows.stream().map(IndexMonthly::key).distinct().count()!=rows.size())throw new IllegalArgumentException("D022 stage source keys must be unique");
        expectedSource=List.copyOf(rows);expectedRows=rows.size();attemptedRows=0;publicationCallback=callback;
        publicationComplete=false;publicationFailure=null;uncertainSenderStopped=false;
    }
    public boolean publicationComplete(){return publicationComplete;}
    @Override public void preflight(){requireTarget();QuestDbWriteChecks.preflight(jdbc,writeTable,IndexMonthlyDataset.isolatedWriteDefinition(writeTable));requireTarget();}
    @Override public void send(List<IndexMonthly> rows)throws Exception {
        if(rows==null||rows.isEmpty()||rows.size()>MAX_BATCH_ROWS)throw new IllegalArgumentException("Nonempty D022 batch <=250 rows required");
        if(publicationCallback==null||writeTable.equals(table))
            throw new IllegalStateException("D022 writes require a frozen non-DEDUP staging target and full-snapshot publication callback");
        preflight();uncertainSenderStopped=false;
        DatasetWritePreparation.prepareWalReplace(IndexMonthlyDataset.isolatedWriteDefinition(writeTable),rows,mapper::values,
                new DatasetWritePreparation.Limits(MAX_BATCH_ROWS,MAX_BATCH_BYTES));
        long bytes=0;for(var row:rows){if(row==null)throw new IllegalArgumentException("Null D022 row");bytes=Math.addExact(bytes,CODEC.estimatedTransportBytes(row,CODEC.canonicalBytes(row)));}
        if(bytes>MAX_BATCH_BYTES)throw new IllegalArgumentException("D022 batch exceeds one MiB before send");
        int attemptedCount=Math.addExact(attemptedRows,rows.size());
        if(publicationCallback!=null&&attemptedCount>expectedRows)throw new IllegalStateException("D022 stage submitted more than the frozen source row count");
        attemptedRows=attemptedCount;
        boolean attempted=false;
        try(Sender sender=questdb.borrowSender()) {
            for(var row:rows) {
                // QuestDB ILP requires all SYMBOL assignments before any field assignments.
                var line=sender.table(writeTable).symbol("ts_code",row.tsCode())
                        .symbol("layer",row.layer()).symbol("bucket",row.bucket());
                if(row.close()!=null)line.doubleColumn("close",row.close());if(row.open()!=null)line.doubleColumn("open",row.open());
                if(row.high()!=null)line.doubleColumn("high",row.high());if(row.low()!=null)line.doubleColumn("low",row.low());
                if(row.preClose()!=null)line.doubleColumn("pre_close",row.preClose());if(row.change()!=null)line.doubleColumn("change",row.change());
                if(row.pctChg()!=null)line.doubleColumn("pct_chg",row.pctChg());if(row.vol()!=null)line.doubleColumn("vol",row.vol());
                if(row.amount()!=null)line.doubleColumn("amount",row.amount());
                line.timestampColumn("update_time",row.updateTime())
                        .at(new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());
            }
            long sequence=sender.flushAndGetSequence();attempted=true;
            if(sequence<0||!sender.awaitAckedFsn(sequence,ACK_TIMEOUT.toMillis()))throw new IllegalStateException("D022 write ACK unknown; exact-key reconciliation required");
        } catch(Exception failure){attempted=true;throw failure;} finally {uncertainSenderStopped=attempted;}
    }
    @Override public List<IndexMonthly> readback(List<IndexMonthlyKey> keys) {
        if(keys==null||keys.isEmpty()||keys.size()>MAX_BATCH_ROWS||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("At most 250 unique D022 readback keys required");
        if(publicationFailure!=null)throw new IllegalStateException("D022 table publication is unresolved",publicationFailure);
        requireTarget();List<IndexMonthly> actual=queryKeys(writeTable,keys);
        if(publicationCallback!=null&&!publicationComplete&&attemptedRows==expectedRows&&batchMatches(keys,actual)) {
            try {
                String publishedPhysical=publicationCallback.publish();
                var current=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
                if(current.size()!=1||!(current.getFirst().get("id") instanceof Number id)||!(current.getFirst().get("directoryName") instanceof String dir)
                        ||!publishedPhysical.equals(StaticTargetIdentity.identify(jdbc,table,id.longValue(),dir)))
                    throw new IllegalStateException("D022 publication callback returned a different physical generation");
                writeTable=table;writeTargetId=publishedPhysical;publicationComplete=true;
                actual=queryKeys(writeTable,keys);
            } catch(Exception failure) {
                publicationFailure=failure;throw new IllegalStateException("D022 staged target replacement did not reach verified publication",failure);
            }
        }
        return actual;
    }
    private List<IndexMonthly> queryKeys(String tableName,List<IndexMonthlyKey> keys) {
        var where=new ArrayList<String>();var args=new ArrayList<Object>();
        for(var key:keys){where.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");args.add(key.tsCode());args.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));}
        return jdbc.query(select(tableName)+" WHERE "+String.join(" OR ",where)+" ORDER BY trade_date,ts_code LIMIT "+(keys.size()+1),(rs,n)->physical(rs),args.toArray());
    }
    private boolean batchMatches(List<IndexMonthlyKey> keys,List<IndexMonthly> actual) {
        if(actual==null||actual.size()!=keys.size())return false;
        var expected=new HashMap<IndexMonthlyKey,byte[]>();for(var row:expectedSource)expected.put(row.key(),CODEC.canonicalBytes(row));
        var seen=new HashSet<IndexMonthlyKey>();for(var row:actual)if(!seen.add(row.key())||!expected.containsKey(row.key())
                ||!Arrays.equals(expected.get(row.key()),CODEC.canonicalBytes(row)))return false;
        return seen.size()==keys.size()&&seen.containsAll(keys);
    }
    public List<IndexMonthly> readExistingRows(String code) {
        if(!IndexMonthlyUniverse.validProviderCode(code))throw new IllegalArgumentException("Known D022 provider code required");requireTarget();
        var rows=jdbc.query(select(table)+" WHERE ts_code=? ORDER BY trade_date LIMIT "+(MAX_EXISTING_ROWS+1),(rs,n)->physical(rs),code);
        if(rows.size()>MAX_EXISTING_ROWS)throw new IllegalStateException("D022 per-code reconciliation cap exceeded");return List.copyOf(rows);
    }
    public TargetRange readExistingRange(String code) {
        if(!IndexMonthlyUniverse.validProviderCode(code))throw new IllegalArgumentException("Known D022 provider code required");requireTarget();
        return jdbc.query("SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros FROM \""+table+"\" WHERE ts_code=?",rs->{
            if(!rs.next())throw new IllegalStateException("D022 range query returned no row");Object min=rs.getObject("min_micros"),max=rs.getObject("max_micros");
            if(min==null&&max==null)return new TargetRange(null,null);if(!(min instanceof Number a)||!(max instanceof Number b))throw new SQLException("Invalid D022 timestamp range");return new TargetRange(date(a.longValue()),date(b.longValue()));
        },code);
    }
    public List<IndexMonthly> readRange(String code,LocalDate from,LocalDate to) {
        if(!IndexMonthlyUniverse.validProviderCode(code)||from==null||to==null||from.isAfter(to))throw new IllegalArgumentException("Known code and increasing D022 range required");
        requireTarget();long lower=new TemporalValues.CalendarTimestamp(from).storageEpoch(TemporalValues.EpochUnit.MICROS),upper=new TemporalValues.CalendarTimestamp(to.plusDays(1)).storageEpoch(TemporalValues.EpochUnit.MICROS);
        var rows=jdbc.query(select(table)+" WHERE ts_code=? AND trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT "+(MAX_EXISTING_ROWS+1),(rs,n)->physical(rs),code,lower,upper);
        if(rows.size()>MAX_EXISTING_ROWS)throw new IllegalStateException("D022 range reconciliation cap exceeded");return List.copyOf(rows);
    }
    @Override public boolean walSettled(){requireTarget();return QuestDbWriteChecks.walSettled(jdbc,writeTable);}
    @Override public boolean uncertainSenderStopped(){return uncertainSenderStopped;}
    private IndexMonthly physical(ResultSet rs)throws SQLException {
        Object date=rs.getObject("trade_date_micros"),update=rs.getObject("update_time_micros");
        if(!(date instanceof Number d)||!(update instanceof Number u))throw new SQLException("D022 timestamps required");
        try{return new IndexMonthly(new IndexMonthlyKey(rs.getString("ts_code"),date(d.longValue())),finite(rs,"close"),finite(rs,"open"),finite(rs,"high"),finite(rs,"low"),
                finite(rs,"pre_close"),finite(rs,"change"),finite(rs,"pct_chg"),finite(rs,"vol"),finite(rs,"amount"),rs.getString("layer"),rs.getString("bucket"),
                TemporalValues.epoch(u.longValue(),TemporalValues.EpochUnit.MICROS,TemporalValues.Precision.MICROS));}
        catch(RuntimeException invalid){throw new SQLException("Invalid D022 physical row",invalid);}
    }
    private static Double finite(ResultSet rs,String name)throws SQLException {Object v=rs.getObject(name);if(v==null)return null;if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new SQLException("Invalid D022 metric "+name);return n.doubleValue();}
    private static LocalDate date(long micros)throws SQLException {try{return TemporalValues.CalendarTimestamp.fromStorageEpoch(micros,TemporalValues.EpochUnit.MICROS).date();}catch(RuntimeException bad){throw new SQLException("D022 date carrier invalid",bad);}}
    private String select(String tableName){return "SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,close,open,high,low,pre_close,change,pct_chg,vol,amount,layer,bucket,cast(update_time AS long) AS update_time_micros FROM \""+tableName+"\"";}
    private void requireTarget(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",writeTable);
        if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String dir)
                ||!writeTargetId.equals(StaticTargetIdentity.identify(jdbc,writeTable,id.longValue(),dir)))throw new IllegalStateException("D022 isolated physical target identity changed");}
    public record TargetRange(LocalDate min,LocalDate max){public TargetRange{if((min==null)!=(max==null)||min!=null&&min.isAfter(max))throw new IllegalArgumentException("Invalid D022 target range");}}
}
