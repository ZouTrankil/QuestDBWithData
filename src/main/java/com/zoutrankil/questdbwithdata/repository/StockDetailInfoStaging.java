package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.StockDetailInfoRow;
import com.zoutrankil.questdbwithdata.mapper.StockDetailInfoMapper;
import com.zoutrankil.questdbwithdata.service.StockDetailInfoMerge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Builds and verifies an owned non-WAL replacement. It never renames or alters the current table. */
public final class StockDetailInfoStaging {
    public record Prepared(StockDetailInfoStorage.Snapshot before,List<StockDetailInfoRow> rows,
                           StockDetailInfoMerge.Result merge) {
        public Prepared { rows=List.copyOf(rows); }
    }
    public record Verified(String table,StockDetailInfoStorage.Snapshot snapshot,String receipt) {}
    private final JdbcTemplate jdbc;
    public StockDetailInfoStaging(JdbcTemplate jdbc) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(20);
    }
    public static Prepared prepare(StockDetailInfoStorage.Snapshot before,List<StockDetailInfo> incoming) throws Exception {
        var merge=StockDetailInfoMerge.merge(before.businessRows(),incoming);
        var raw=new TreeMap<String,StockDetailInfoRow>();before.rows().forEach(r->raw.put(r.tsCode(),r));
        var mapper=new StockDetailInfoMapper();merge.changedRows().forEach(r->raw.put(r.tsCode(),mapper.toStorage(r)));
        var rows=new ArrayList<>(raw.values());
        if(JobDefinitionJson.mapper().writeValueAsBytes(rows).length>StockDetailInfoStorage.MAX_BYTES)
            throw new IllegalArgumentException("Prepared static replacement exceeds byte budget");
        return new Prepared(before,rows,merge);
    }
    public Verified write(Prepared prepared,Path evidence) throws Exception {
        return write(prepared,evidence,()->false);
    }
    public Verified write(Prepared prepared,Path evidence,java.util.function.BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(cancelled);checkCancelled(cancelled);
        if(!prepared.merge().requiresPublication()) throw new IllegalArgumentException("Unchanged/empty source must not create staging table");
        // Recompute the merge to prevent callers from supplying mismatched prepared payloads.
        var checked=prepare(prepared.before(),prepared.merge().changedRows());
        if(!checked.rows().equals(prepared.rows())) throw new IllegalArgumentException("Prepared replacement rows differ from merge");
        String stage="java_stock_detail_stage_"+UUID.randomUUID().toString().replace("-","");
        Files.createDirectories(evidence);Path intent=evidence.resolve(stage+"-intent.json");
        Files.writeString(intent,JobDefinitionJson.mapper().writeValueAsString(Map.of("stage",stage,
                "priorIdentity",prepared.before().identity(),"priorFingerprint",prepared.before().fingerprint(),
                "rows",prepared.rows())),StandardOpenOption.CREATE_NEW);
        var columns=StockDetailInfoDataset.DEFINITION.columns();
        jdbc.execute("CREATE TABLE "+stage+" ("+String.join(",",columns.stream()
                .map(c->c.storageName()+" "+c.storageType().name()).toList())+")");
        new StockDetailInfoStorage(jdbc,stage).preflight();
        String sql="INSERT INTO "+stage+" ("+String.join(",",columns.stream().map(DatasetDefinition.Column::storageName).toList())
                +") VALUES (?,cast(? AS TIMESTAMP),"+String.join(",",Collections.nCopies(16,"?"))+")";
        for(int start=0;start<prepared.rows().size();start+=250) {
            checkCancelled(cancelled);
            var batch=prepared.rows().subList(start,Math.min(start+250,prepared.rows().size()));
            jdbc.batchUpdate(sql,new BatchPreparedStatementSetter() {
                public int getBatchSize() { return batch.size(); }
                public void setValues(PreparedStatement statement,int index) throws SQLException {
                    var r=batch.get(index);statement.setString(1,r.tsCode());
                    statement.setLong(2,Math.addExact(Math.multiplyExact(r.updateTime().getEpochSecond(),1_000_000),r.updateTime().getNano()/1000));
                    String[] fields={r.symbol(),r.name(),r.market(),r.exchange(),r.listStatus(),r.listDate(),r.fullname(),
                            r.enname(),r.cnspell(),r.area(),r.industry(),r.currType(),r.delistDate(),r.isHs(),r.actName(),r.actEntType()};
                    for(int i=0;i<fields.length;i++) statement.setString(i+3,fields[i]);
                }
            });
        }
        checkCancelled(cancelled);
        var actual=new StockDetailInfoStorage(jdbc,stage).snapshot();
        if(!actual.rows().equals(prepared.rows())) throw new IllegalStateException("Staging full-value readback mismatch; retain stage for inspection");
        Path receipt=evidence.resolve(stage+"-verified.json");
        Files.writeString(receipt,JobDefinitionJson.mapper().writeValueAsString(Map.of("stage",stage,"snapshot",actual,
                "insertedRows",prepared.merge().insertedRows(),"updatedRows",prepared.merge().updatedRows(),
                "unchangedRows",prepared.merge().unchangedRows())),StandardOpenOption.CREATE_NEW);
        return new Verified(stage,actual,receipt.toString());
    }
    private static void checkCancelled(java.util.function.BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Static staging cancelled; retain any owned stage for inspection");
    }
}
