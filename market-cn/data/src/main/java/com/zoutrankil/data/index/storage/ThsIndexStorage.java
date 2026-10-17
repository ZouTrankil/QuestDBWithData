package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.index.domain.ThsIndexState.*;
import com.zoutrankil.data.index.domain.ThsIndexState;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.ThsIndexRow;
import com.zoutrankil.data.index.mapper.ThsIndexMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Exact bounded physical snapshot; a current catalog has one row per natural THS code. */
public final class ThsIndexStorage implements com.zoutrankil.data.index.port.ThsIndexTables.Table {
    public static final int MAX_ROWS=ThsIndexState.MAX_ROWS,MAX_BYTES=ThsIndexState.MAX_BYTES;


    private final JdbcTemplate jdbc;
    private final String table;
    public ThsIndexStorage(JdbcTemplate source,String table) {
        DatasetDefinition.identifier(table);this.table=table;
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
        this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(MAX_ROWS+1);
    }
    public Identity preflight() {
        QuestDbWriteChecks.preflight(jdbc,table,ThsIndexDataset.DEFINITION);
        var objects=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(objects.size()!=1 || !(objects.getFirst().get("id") instanceof Number id)
                || !(objects.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact physical THS catalog identity required");
        return new Identity(id.longValue(),directory);
    }
    public Snapshot snapshot() throws Exception {
        var before=preflight();
        var values=jdbc.queryForList("SELECT ts_code,name,\"count\",exchange,list_date,\"type\","
                +"cast(update_time as long) AS update_micros FROM \""+table+"\" "
                +"ORDER BY ts_code LIMIT "+(MAX_ROWS+1));
        if(values.size()>MAX_ROWS) throw new IllegalStateException("THS target exceeds bounded snapshot");
        var rows=new ArrayList<ThsIndexRow>();var keys=new HashSet<String>();var mapper=new ThsIndexMapper();
        for(var v:values) {
            if(!(v.get("update_micros") instanceof Number timestamp))
                throw new IllegalStateException("THS observation timestamp required");
            long micros=timestamp.longValue();var instant=Instant.ofEpochSecond(
                    Math.floorDiv(micros,1000000),Math.floorMod(micros,1000000)*1000);
            var count=v.get("count");
            if(count!=null && (!(count instanceof Number n) || n.longValue()<0 || n.longValue()>Integer.MAX_VALUE))
                throw new IllegalStateException("Invalid physical THS member count");
            var row=new ThsIndexRow((String)v.get("ts_code"),(String)v.get("name"),
                    count==null?null:((Number)count).intValue(),(String)v.get("exchange"),
                    (String)v.get("list_date"),(String)v.get("type"),instant);
            mapper.fromStorage(row);
            if(!keys.add(row.tsCode())) throw new IllegalStateException("Duplicate physical THS business identity");
            rows.add(row);
        }
        if(!before.equals(preflight())) throw new IllegalStateException("THS physical identity changed while reading");
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(rows);
        if(bytes.length>MAX_BYTES) throw new IllegalStateException("THS catalog snapshot exceeds byte bound");
        return new Snapshot(before,rows,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length);
    }
}
