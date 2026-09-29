package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.IndexRow;
import com.zoutrankil.questdbwithdata.mapper.IndexCatalogMapper;
import com.zoutrankil.questdbwithdata.service.IndexCatalogFileSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Complete bounded physical snapshot for merge and publication checks; never changes the target. */
public final class IndexCatalogStorage {
    public record Identity(long id,String directory) {}
    public record Snapshot(Identity identity,List<IndexRow> rows,String fingerprint,int bytes) {
        public Snapshot { rows=List.copyOf(rows); }
        public List<IndexCatalogEntry> businessRows() {
            var mapper=new IndexCatalogMapper();return rows.stream().map(mapper::fromStorage).toList();
        }
    }
    private final JdbcTemplate jdbc;
    private final String table;
    public IndexCatalogStorage(JdbcTemplate jdbc,String table) {
        DatasetDefinition.identifier(table);this.table=table;
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(20);
        this.jdbc.setMaxRows(IndexCatalogFileSource.MAX_ROWS+1);
    }
    public Identity preflight() {
        QuestDbWriteChecks.preflight(jdbc,table,IndexCatalogDataset.DEFINITION);
        var objects=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(objects.size()!=1 || !(objects.getFirst().get("id") instanceof Number id)
                || !(objects.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact physical catalog identity required");
        return new Identity(id.longValue(),directory);
    }
    public Snapshot snapshot() throws Exception {
        var before=preflight();
        var projection=IndexCatalogDataset.DEFINITION.columns().stream().map(c->c.storageName().equals("import_time")
                ?"cast(import_time as long) AS import_micros":c.storageName()).toList();
        var values=jdbc.queryForList("SELECT "+String.join(",",projection)+" FROM \""+table+"\" ORDER BY index_code LIMIT "
                +(IndexCatalogFileSource.MAX_ROWS+1));
        if(values.size()>IndexCatalogFileSource.MAX_ROWS) throw new IllegalStateException("Catalog target exceeds bounded snapshot");
        var rows=new ArrayList<IndexRow>();var keys=new HashSet<String>();var mapper=new IndexCatalogMapper();
        for(var v:values) {
            if(!(v.get("import_micros") instanceof Number timestamp)) throw new IllegalStateException("Import timestamp required");
            long micros=timestamp.longValue();var instant=Instant.ofEpochSecond(Math.floorDiv(micros,1000000),Math.floorMod(micros,1000000)*1000);
            var row=new IndexRow(text(v,"index_code"),text(v,"index_short_name"),text(v,"index_full_name"),text(v,"base_date"),
                    number(v,"base_point"),text(v,"index_series"),number(v,"sample_count"),number(v,"latest_close"),number(v,"return_1m"),
                    text(v,"asset_class"),text(v,"index_hotspot"),text(v,"currency"),text(v,"is_cooperation"),text(v,"has_tracking_product"),
                    text(v,"compliance_status"),text(v,"index_category"),text(v,"publish_date"),instant);
            mapper.fromStorage(row);
            if(!keys.add(row.indexCode())) throw new IllegalStateException("Duplicate physical catalog code");
            rows.add(row);
        }
        if(!before.equals(preflight())) throw new IllegalStateException("Catalog identity changed while reading");
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(rows);
        if(bytes.length>IndexCatalogFileSource.MAX_BYTES) throw new IllegalStateException("Catalog snapshot exceeds byte bound");
        return new Snapshot(before,rows,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length);
    }
    private static String text(Map<String,Object> row,String name) { return (String)row.get(name); }
    private static Double number(Map<String,Object> row,String name) {
        var value=row.get(name);return value==null?null:((Number)value).doubleValue();
    }
}
