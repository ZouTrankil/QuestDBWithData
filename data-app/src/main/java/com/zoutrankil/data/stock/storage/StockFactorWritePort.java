package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.stock.mapper.StockFactorMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

/** WAL/QWP writer that keeps acknowledgement separate from complete-key, all-column readback. */
public final class StockFactorWritePort implements VerifiedWriteSession<StockFactor,StockFactorKey> {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questDb;
    private final StockFactorMapper mapper=new StockFactorMapper();
    private final int maxBatchBytes;
    private final Duration acknowledgementTimeout;
    private final String expectedTargetId;
    private volatile boolean uncertainSenderStopped;
    public StockFactorWritePort(String table,JdbcTemplate jdbc,QuestDB questDb) {
        this(table,jdbc,questDb,1024*1024,Duration.ofSeconds(10),null);
    }
    public StockFactorWritePort(String table,JdbcTemplate jdbc,QuestDB questDb,int maxBatchBytes,Duration ackTimeout) {
        this(table,jdbc,questDb,maxBatchBytes,ackTimeout,null);
    }
    public StockFactorWritePort(String table,JdbcTemplate jdbc,QuestDB questDb,int maxBatchBytes,Duration ackTimeout,
                                String expectedTargetId) {
        DatasetDefinition.identifier(table);
        if(maxBatchBytes<1024 || ackTimeout==null || ackTimeout.toMillis()<1)
            throw new IllegalArgumentException("Finite factor batch and acknowledgement budgets required");
        this.table=table;this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(10_001);this.questDb=Objects.requireNonNull(questDb);
        this.maxBatchBytes=maxBatchBytes;this.acknowledgementTimeout=ackTimeout;this.expectedTargetId=expectedTargetId;
    }
    public static final VerifiedBatchExecutor.Codec<StockFactor,StockFactorKey> CODEC=new VerifiedBatchExecutor.Codec<>() {
        @Override public StockFactorKey key(StockFactor row) {
            if(row==null || row.key()==null || row.fields()==null) throw new IllegalArgumentException("Complete factor key and values required");
            return row.key();
        }
        @Override public byte[] canonicalBytes(StockFactor row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new StockFactorMapper().values(row).asMap()); }
            catch(Exception failure) { throw new IllegalArgumentException("Cannot canonicalize stock factor row",failure); }
        }
        @Override public int estimatedTransportBytes(StockFactor row,byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length,4),128);
        }
    };
    @Override public void preflight() {
        QuestDbWriteChecks.preflight(jdbc,table,StockFactorDataset.DEFINITION);
        requireExpectedTarget();
    }
    @Override public void send(List<StockFactor> rows) throws Exception {
        if(rows==null || rows.isEmpty() || rows.size()>10_000) throw new IllegalArgumentException("Finite nonempty factor batch required");
        preflight();uncertainSenderStopped=false;
        long estimate=0;for(var row:rows) {
            byte[] canonical=CODEC.canonicalBytes(row);estimate+=CODEC.estimatedTransportBytes(row,canonical);
            if(estimate>maxBatchBytes) throw new IllegalArgumentException("Stock factor batch byte budget exceeded before send");
        }
        Sender sender=questDb.borrowSender();boolean flushAttempted=false;
        try {
            for(var value:rows) {
                var row=sender.table(table).symbol("ts_code",value.tsCode());
                var values=mapper.values(value).asMap();
                for(var column:StockFactorDataset.DEFINITION.columns()) {
                    if(column.storageName().equals("ts_code") || column.storageName().equals("trade_date")) continue;
                    Double number=(Double)values.get(column.logicalName());
                    if(number!=null) row.doubleColumn(column.storageName(),number);
                }
                var date=new TemporalValues.CalendarTimestamp(value.tradeDate());
                row.at(date.storageCarrier());
            }
            long sequence=sender.flushAndGetSequence();
            flushAttempted=true;
            if(sequence<0 || !sender.awaitAckedFsn(sequence,acknowledgementTimeout.toMillis()))
                throw new IllegalStateException("QWP acknowledgement unknown; reconcile exact keys before replay");
        } catch(Exception failure) {
            flushAttempted=true;throw failure;
        } finally {
            try { sender.close();uncertainSenderStopped=flushAttempted; }
            catch(Exception closeFailure) { uncertainSenderStopped=false;throw closeFailure; }
        }
    }
    @Override public List<StockFactor> readback(List<StockFactorKey> keys) {
        if(keys==null || keys.isEmpty()) return List.of();
        requireExpectedTarget();
        if(keys.size()>250 || new HashSet<>(keys).size()!=keys.size()) throw new IllegalArgumentException("At most 250 unique full factor keys per readback");
        var clauses=new ArrayList<String>();var params=new ArrayList<Object>();
        // Expose the designated-timestamp interval to QuestDB's partition/interval planner.
        // The exact pair predicates below still decide membership, including mixed-date batches.
        var firstDate=keys.stream().map(StockFactorKey::tradeDate).min(Comparator.naturalOrder()).orElseThrow();
        var lastDate=keys.stream().map(StockFactorKey::tradeDate).max(Comparator.naturalOrder()).orElseThrow();
        params.add(new TemporalValues.CalendarTimestamp(firstDate).storageEpoch(TemporalValues.EpochUnit.MICROS));
        params.add(new TemporalValues.CalendarTimestamp(lastDate.plusDays(1)).storageEpoch(TemporalValues.EpochUnit.MICROS));
        var codes=keys.stream().map(StockFactorKey::tsCode).distinct().sorted().toList();
        params.addAll(codes);
        for(var key:keys) {
            clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");params.add(key.tsCode());
            params.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        var projection=String.join(",",StockFactorDataset.STORAGE_COLUMNS.stream().map(c->c.equals("trade_date")
                ?"cast(\"trade_date\" as long) AS trade_micros":"\""+c+"\"").toList());
        String sql="SELECT "+projection+" FROM \""+table+"\" WHERE trade_date>=cast(? AS TIMESTAMP)"
                +" AND trade_date<cast(? AS TIMESTAMP) AND ts_code IN ("+String.join(",",Collections.nCopies(codes.size(),"?"))
                +") AND ("+String.join(" OR ",clauses)+")"
                +" ORDER BY ts_code,trade_date LIMIT "+(keys.size()+1);
        return jdbc.query(sql,(rs,index)->physical(rs),params.toArray());
    }
    private StockFactor physical(ResultSet rs) throws SQLException {
        Object raw=rs.getObject("trade_micros");
        if(!(raw instanceof Number micros)) throw new SQLException("Stock factor trade_date timestamp required");
        var date=TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(),TemporalValues.EpochUnit.MICROS).date();
        var values=new LinkedHashMap<String,Object>();values.put("ts_code",rs.getString("ts_code"));values.put("trade_date",date);
        for(int i=2;i<StockFactorDataset.STORAGE_COLUMNS.size();i++) {
            String column=StockFactorDataset.STORAGE_COLUMNS.get(i);
            Object value=rs.getObject(column);
            if(value!=null && !(value instanceof Number)) throw new SQLException("Non-numeric factor storage value: "+column);
            Double number=value==null?null:((Number)value).doubleValue();
            // QuestDB represents every non-finite DOUBLE value as NULL.
            values.put(column,number==null || !Double.isFinite(number)?null:number);
        }
        try { return mapper.fromValues(new DatasetValues(values)); }
        catch(RuntimeException invalid) { throw new SQLException("Invalid physical stock factor row",invalid); }
    }
    @Override public boolean walSettled() { requireExpectedTarget();return QuestDbWriteChecks.walSettled(jdbc,table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }
    private void requireExpectedTarget() {
        if(expectedTargetId==null) return;
        var identity=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(identity.size()!=1 || !(identity.getFirst().get("id") instanceof Number id)
                || !(identity.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity
                        .identify(jdbc,table,id.longValue(),directory)))
            throw new IllegalStateException("stk_factor physical target identity changed during write verification");
    }
    @Override public VerifiedBatchExecutor.Codec<StockFactor, StockFactorKey> codec() { return CODEC; }
}
