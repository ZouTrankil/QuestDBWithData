package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.IndexMemberRow;
import com.zoutrankil.questdbwithdata.mapper.IndexMembershipMapper;
import com.zoutrankil.questdbwithdata.service.IndexMembershipMerge;
import org.springframework.jdbc.core.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded, verified stage only; serving-table publication belongs to the owner protocol. */
public final class IndexMembershipStaging {
    public record Prepared(IndexMembershipStorage.Snapshot before,List<IndexMembership> source,String l2Code,
                           List<IndexMemberRow> rows,IndexMembershipMerge.Result merge) {
        public Prepared { source=List.copyOf(source);rows=List.copyOf(rows); }
    }
    public record Verified(String table,IndexMembershipStorage.Snapshot snapshot,int batches,String receipt) {}
    private final JdbcTemplate jdbc;
    @FunctionalInterface public interface Hook { void afterCreate(String table) throws Exception; }
    private final Hook hook;
    public IndexMembershipStaging(JdbcTemplate source) { this(source,table->{}); }
    public IndexMembershipStaging(JdbcTemplate source,Hook hook) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(20);this.hook=Objects.requireNonNull(hook);
    }
    public static Prepared prepare(IndexMembershipStorage.Snapshot before,List<IndexMembership> source,String l2Code) throws Exception {
        return prepareInternal(before,source,l2Code,false);
    }
    public static Prepared preparePrepared(IndexMembershipStorage.Snapshot before,List<IndexMembership> source,String l2Code) throws Exception {
        return prepareInternal(before,source,l2Code,true);
    }
    private static Prepared prepareInternal(IndexMembershipStorage.Snapshot before,List<IndexMembership> source,
                                            String l2Code,boolean preparedInput) throws Exception {
        var mapper=new IndexMembershipMapper();var merged=preparedInput
                ?IndexMembershipMerge.mergePrepared(before.businessRows(),source,l2Code)
                :IndexMembershipMerge.mergeSource(before.businessRows(),source,l2Code);
        var raw=new HashMap<IndexMembership.Key,IndexMemberRow>();
        before.rows().forEach(r->raw.put(mapper.fromStorage(r).key(),r));
        merged.changedRows().forEach(r->raw.put(r.key(),mapper.toStorage(r)));
        var rows=merged.rows().stream().map(r->raw.get(r.key())).toList();
        if(JobDefinitionJson.mapper().writeValueAsBytes(rows).length>IndexMembershipStorage.MAX_BYTES)
            throw new IllegalArgumentException("Merged membership byte bound exceeded");
        return new Prepared(before,source,preparedInput?"prepared:"+l2Code:l2Code,rows,merged);
    }
    public Verified write(Prepared prepared,Path folder,BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        if(!prepared.merge().requiresWrite()) throw new IllegalArgumentException("Unchanged membership must not stage a write");
        var replay=prepared.l2Code().startsWith("prepared:")
                ?preparePrepared(prepared.before(),prepared.source(),prepared.l2Code().substring(9))
                :prepare(prepared.before(),prepared.source(),prepared.l2Code());
        if(!prepared.equals(replay))
            throw new IllegalArgumentException("Membership preparation changed");
        var json=JobDefinitionJson.mapper();var batches=new ArrayList<List<IndexMemberRow>>();var batch=new ArrayList<IndexMemberRow>();int bytes=0;
        for(var row:prepared.rows()) {
            int size=json.writeValueAsBytes(row).length+128;
            if(size>256*1024) throw new IllegalArgumentException("Membership row exceeds batch byte bound");
            if(batch.size()==250 || bytes+size>256*1024) { batches.add(List.copyOf(batch));batch.clear();bytes=0; }
            batch.add(row);bytes+=size;
        }
        if(!batch.isEmpty()) batches.add(List.copyOf(batch));
        String stage="java_index_member_stage_"+UUID.randomUUID().toString().replace("-","");Files.createDirectories(folder);
        Files.writeString(folder.resolve(stage+"-intent.json"),json.writeValueAsString(Map.of("stage",stage,"prepared",prepared,
                "batches",batches.size(),"maxBatchRows",250,"estimatedBatchBytes",256*1024)),StandardOpenOption.CREATE_NEW);
        var columns=IndexMembershipDataset.DEFINITION.columns();
        jdbc.execute("CREATE TABLE "+stage+" ("+String.join(",",columns.stream().map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())
                +") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
        new IndexMembershipStorage(jdbc,stage).preflight();
        hook.afterCreate(stage);
        String sql="INSERT INTO "+stage+" ("+String.join(",",columns.stream().map(c->"\""+c.storageName()+"\"").toList())
                +") VALUES (?,?,cast(? AS TIMESTAMP),?,?,?,?,?,?,?,?,?,?,?)";
        for(var current:batches) {
            check(cancelled);jdbc.batchUpdate(sql,new BatchPreparedStatementSetter() {
                public int getBatchSize() { return current.size(); }
                public void setValues(PreparedStatement s,int index) throws SQLException {
                    var r=current.get(index);Object[] v={r.indexCode(),r.tsCode(),r.updateTime(),r.indexName(),r.conCode(),r.conName(),
                            r.inDate(),r.outDate(),r.isNew(),r.weight(),r.level(),r.l1Name(),r.l2Name(),r.l3Name()};
                    for(int i=0;i<v.length;i++) {
                        if(i==2) s.setLong(i+1,Math.addExact(Math.multiplyExact(r.updateTime().getEpochSecond(),1_000_000),r.updateTime().getNano()/1000));
                        else if(i==9) { if(r.weight()==null) s.setNull(i+1,Types.DOUBLE);else s.setDouble(i+1,r.weight()); }
                        else s.setString(i+1,(String)v[i]);
                    }
                }
            });
        }
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(!QuestDbWriteChecks.walSettled(jdbc,stage)) {
            check(cancelled);if(System.nanoTime()>deadline) throw new IllegalStateException("Membership stage WAL unresolved; retain stage");Thread.sleep(50);
        }
        check(cancelled);var actual=new IndexMembershipStorage(jdbc,stage).snapshot();
        if(!prepared.rows().equals(actual.rows())) throw new IllegalStateException("Membership stage full-field mismatch; retain stage");
        Path receipt=folder.resolve(stage+"-verified.json");
        Files.writeString(receipt,json.writeValueAsString(Map.of("stage",stage,"actual",actual,"batches",batches.size(),
                "inserted",prepared.merge().inserted(),"revised",prepared.merge().revised())),StandardOpenOption.CREATE_NEW);
        return new Verified(stage,actual,batches.size(),receipt.toString());
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Membership staging cancelled; retain stage");
    }
}
