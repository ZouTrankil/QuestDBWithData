package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.IndexRow;
import com.zoutrankil.data.mapper.IndexCatalogMapper;
import com.zoutrankil.data.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Owned monthly-WAL stage; full-key/content verification precedes any publication admission. */
public final class IndexCatalogStaging {
    public record Prepared(IndexCatalogStorage.Snapshot before,List<IndexRow> rows,IndexCatalogMerge.Result merge) {
        public Prepared { rows=List.copyOf(rows); }
    }
    public record Verified(String table,IndexCatalogStorage.Snapshot snapshot,String receipt,int batches) {}
    private final JdbcTemplate jdbc;
    public IndexCatalogStaging(JdbcTemplate jdbc) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(20);
    }
    public static Prepared prepare(IndexCatalogStorage.Snapshot before,List<IndexCatalogEntry> incoming) throws Exception {
        var merged=IndexCatalogMerge.merge(before.businessRows(),incoming);var raw=new TreeMap<String,IndexRow>();
        before.rows().forEach(row->raw.put(row.indexCode(),row));var mapper=new IndexCatalogMapper();
        merged.changedRows().forEach(row->raw.put(row.indexCode(),mapper.toStorage(row)));
        var rows=new ArrayList<>(raw.values());
        if(JobDefinitionJson.mapper().writeValueAsBytes(rows).length>IndexCatalogFileSource.MAX_BYTES)
            throw new IllegalArgumentException("Merged physical catalog exceeds byte bound");
        return new Prepared(before,rows,merged);
    }
    public Verified write(Prepared prepared,Path evidence,BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        if(!prepared.merge().requiresWrite()) throw new IllegalArgumentException("Unchanged input must not stage a replacement");
        if(!prepare(prepared.before(),prepared.merge().changedRows()).rows().equals(prepared.rows()))
            throw new IllegalArgumentException("Prepared catalog rows differ from content merge");
        var batches=new ArrayList<List<IndexRow>>();var batch=new ArrayList<IndexRow>();int bytes=0;
        for(var row:prepared.rows()) {
            int estimate=JobDefinitionJson.mapper().writeValueAsBytes(row).length+256;
            if(estimate>256*1024) throw new IllegalArgumentException("Catalog row exceeds per-batch payload budget");
            if(batch.size()==250 || bytes+estimate>256*1024) { batches.add(List.copyOf(batch));batch.clear();bytes=0; }
            batch.add(row);bytes+=estimate;
        }
        if(!batch.isEmpty()) batches.add(List.copyOf(batch));
        String stage="java_index_catalog_stage_"+UUID.randomUUID().toString().replace("-","");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve(stage+"-intent.json"),JobDefinitionJson.mapper().writeValueAsString(
                Map.of("stage",stage,"before",prepared.before(),"rows",prepared.rows(),"batches",batches.size(),
                        "maxBatchRows",250,"estimatedBatchBytes",256*1024)),StandardOpenOption.CREATE_NEW);
        var columns=IndexCatalogDataset.DEFINITION.columns();
        jdbc.execute("CREATE TABLE "+stage+" ("+String.join(",",columns.stream()
                .map(c->c.storageName()+" "+c.storageType().name()).toList())+") timestamp(import_time) PARTITION BY MONTH WAL");
        new IndexCatalogStorage(jdbc,stage).preflight();
        String sql="INSERT INTO "+stage+" ("+String.join(",",columns.stream().map(DatasetDefinition.Column::storageName).toList())
                +") VALUES ("+String.join(",",Collections.nCopies(17,"?"))+",cast(? AS TIMESTAMP))";
        for(var rows:batches) {
            check(cancelled);
            jdbc.batchUpdate(sql,new BatchPreparedStatementSetter() {
                public int getBatchSize() { return rows.size(); }
                public void setValues(PreparedStatement statement,int index) throws SQLException {
                    var r=rows.get(index);
                    Object[] fields={r.indexCode(),r.indexShortName(),r.indexFullName(),r.baseDate(),r.basePoint(),r.indexSeries(),
                            r.sampleCount(),r.latestClose(),r.return1m(),r.assetClass(),r.indexHotspot(),r.currency(),
                            r.isCooperation(),r.hasTrackingProduct(),r.complianceStatus(),r.indexCategory(),r.publishDate()};
                    for(int i=0;i<fields.length;i++) {
                        boolean numeric=i==4 || i==6 || i==7 || i==8;
                        if(fields[i]==null) statement.setNull(i+1,numeric?Types.DOUBLE:Types.VARCHAR);
                        else if(numeric) statement.setDouble(i+1,(Double)fields[i]);
                        else statement.setString(i+1,(String)fields[i]);
                    }
                    statement.setLong(18,Math.addExact(Math.multiplyExact(r.importTime().getEpochSecond(),1000000),r.importTime().getNano()/1000));
                }
            });
        }
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(!QuestDbWriteChecks.walSettled(jdbc,stage)) {
            check(cancelled);
            if(System.nanoTime()>deadline) throw new IllegalStateException("Catalog stage WAL did not settle; retain stage");
            Thread.sleep(50);
        }
        check(cancelled);var actual=new IndexCatalogStorage(jdbc,stage).snapshot();
        if(!prepared.rows().equals(actual.rows())) throw new IllegalStateException("Catalog stage full-row mismatch; retain stage");
        Path receipt=evidence.resolve(stage+"-verified.json");
        Files.writeString(receipt,JobDefinitionJson.mapper().writeValueAsString(Map.of("stage",stage,"snapshot",actual,
                "inserted",prepared.merge().inserted(),"revised",prepared.merge().revised(),
                "retainedAbsent",prepared.merge().retainedAbsent(),"batches",batches.size())),StandardOpenOption.CREATE_NEW);
        return new Verified(stage,actual,receipt.toString(),batches.size());
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Catalog staging cancelled; retain owned stage");
    }
}
