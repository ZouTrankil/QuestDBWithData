package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.ThsIndexRow;
import com.zoutrankil.questdbwithdata.mapper.ThsIndexMapper;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Prepare and verify a complete bounded current-row stage; does not rename the serving table. */
public final class ThsIndexStaging {
    public record Prepared(ThsIndexStorage.Snapshot before,List<ThsIndex> source,ThsIndexSource.Scope scope,
                           List<ThsIndexRow> rows,ThsIndexMerge.Result merge) {
        public Prepared { source=List.copyOf(source);rows=List.copyOf(rows); }
    }
    public record Verified(String table,ThsIndexStorage.Snapshot snapshot,String receipt,int batches) {}
    private final JdbcTemplate jdbc;
    public ThsIndexStaging(JdbcTemplate jdbc) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(20);
    }
    public static Prepared prepare(ThsIndexStorage.Snapshot before,List<ThsIndex> source,ThsIndexSource.Scope scope) throws Exception {
        return prepareMerged(before,source,Objects.requireNonNull(scope),
                ThsIndexMerge.merge(before.businessRows(),source,scope));
    }
    public static Prepared preparePrepared(ThsIndexStorage.Snapshot before,List<ThsIndex> rows) throws Exception {
        return prepareMerged(before,rows,null,ThsIndexMerge.mergePrepared(before.businessRows(),rows));
    }
    private static Prepared prepareMerged(ThsIndexStorage.Snapshot before,List<ThsIndex> source,
                                          ThsIndexSource.Scope scope,ThsIndexMerge.Result merged) throws Exception {
        var mapper=new ThsIndexMapper();
        var rows=merged.rows().stream().map(mapper::toStorage).toList();
        if(JobDefinitionJson.mapper().writeValueAsBytes(rows).length>ThsIndexStorage.MAX_BYTES)
            throw new IllegalArgumentException("Merged THS snapshot exceeds byte budget");
        return new Prepared(before,source,scope,rows,merged);
    }
    public Verified write(Prepared prepared,Path evidence,BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        if(!prepared.merge().requiresWrite()) throw new IllegalArgumentException("Unchanged THS content must not stage a replacement");
        var recomputed=prepared.scope()==null?preparePrepared(prepared.before(),prepared.source())
                :prepare(prepared.before(),prepared.source(),prepared.scope());
        if(!recomputed.equals(prepared))
            throw new IllegalArgumentException("THS prepared rows differ from frozen content merge");
        var batches=new ArrayList<List<ThsIndexRow>>();var batch=new ArrayList<ThsIndexRow>();int bytes=0;
        for(var row:prepared.rows()) {
            int estimate=JobDefinitionJson.mapper().writeValueAsBytes(row).length+128;
            if(estimate>256*1024) throw new IllegalArgumentException("THS row exceeds per-batch payload budget");
            if(batch.size()==250 || bytes+estimate>256*1024) { batches.add(List.copyOf(batch));batch.clear();bytes=0; }
            batch.add(row);bytes+=estimate;
        }
        if(!batch.isEmpty()) batches.add(List.copyOf(batch));
        String stage="java_ths_index_stage_"+UUID.randomUUID().toString().replace("-","");Files.createDirectories(evidence);
        Files.writeString(evidence.resolve(stage+"-intent.json"),JobDefinitionJson.mapper().writeValueAsString(Map.of(
                "stage",stage,"prepared",prepared,"batches",batches.size(),"maxBatchRows",250,"estimatedBatchBytes",256*1024)),StandardOpenOption.CREATE_NEW);
        jdbc.execute("CREATE TABLE "+stage+" ("+String.join(",",ThsIndexDataset.DEFINITION.columns().stream()
                .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())
                +") TIMESTAMP(update_time) PARTITION BY MONTH WAL DEDUP UPSERT KEYS(ts_code,update_time)");
        new ThsIndexStorage(jdbc,stage).preflight();
        String sql="INSERT INTO "+stage+" (ts_code,name,\"count\",exchange,list_date,\"type\",update_time) VALUES (?,?,?,?,?,?,cast(? AS TIMESTAMP))";
        for(var rows:batches) {
            check(cancelled);
            jdbc.batchUpdate(sql,new BatchPreparedStatementSetter() {
                public int getBatchSize() { return rows.size(); }
                public void setValues(PreparedStatement statement,int index) throws SQLException {
                    var row=rows.get(index);statement.setString(1,row.tsCode());statement.setString(2,row.name());
                    if(row.count()==null) statement.setNull(3,Types.INTEGER);else statement.setInt(3,row.count());
                    statement.setString(4,row.exchange());statement.setString(5,row.listDate());statement.setString(6,row.type());
                    statement.setLong(7,Math.addExact(Math.multiplyExact(row.updateTime().getEpochSecond(),1_000_000),row.updateTime().getNano()/1000));
                }
            });
        }
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(!QuestDbWriteChecks.walSettled(jdbc,stage)) {
            check(cancelled);if(System.nanoTime()>deadline) throw new IllegalStateException("THS stage WAL not settled; retain stage");
            Thread.sleep(50);
        }
        check(cancelled);var actual=new ThsIndexStorage(jdbc,stage).snapshot();
        if(!prepared.rows().equals(actual.rows())) throw new IllegalStateException("THS stage full-field mismatch; retain stage");
        Path receipt=evidence.resolve(stage+"-verified.json");
        Files.writeString(receipt,JobDefinitionJson.mapper().writeValueAsString(Map.of("stage",stage,"snapshot",actual,
                "batches",batches.size(),"inserted",prepared.merge().inserted(),"revised",prepared.merge().revised(),
                "retainedAbsent",prepared.merge().retainedAbsent())),StandardOpenOption.CREATE_NEW);
        return new Verified(stage,actual,receipt.toString(),batches.size());
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("THS staging cancelled; retain owned stage");
    }
}
