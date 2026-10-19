package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.index.domain.IndexMembershipState.*;
import com.zoutrankil.data.index.port.IndexMembershipTables;
import com.zoutrankil.data.index.port.IndexMembershipStagingPort;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.IndexMemberRow;
import com.zoutrankil.data.index.mapper.IndexMembershipMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;

/** Preserve physical strings, nulls and observation epochs for verified incremental publication. */
public final class IndexMembershipStorage implements IndexMembershipTables.Table {
    public static final int MAX_ROWS=IndexMembershipDataset.MAX_ROWS,MAX_BYTES=IndexMembershipDataset.MAX_BYTES;
    private final JdbcTemplate jdbc;private final String table;
    public IndexMembershipStorage(JdbcTemplate source,String table) {
        DatasetDefinition.identifier(table);this.table=table;
        jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(20);jdbc.setMaxRows(MAX_ROWS+1);
    }
    public Identity preflight() {
        QuestDbWriteChecks.preflight(jdbc,table,IndexMembershipDataset.DEFINITION);
        var result=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(result.size()!=1 || !(result.getFirst().get("id") instanceof Number id)
                || !(result.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact membership physical identity required");
        return new Identity(id.longValue(),directory);
    }
    private long transaction() {
        var rows=jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?",table);
        if(rows.size()!=1 || !(rows.getFirst().get("writerTxn") instanceof Number txn))
            throw new IllegalStateException("Membership WAL transaction unavailable");
        return txn.longValue();
    }
    public Snapshot snapshot() throws Exception {
        var identity=preflight();long txn=transaction();
        var mapper=new IndexMembershipMapper();var json=JobDefinitionJson.mapper();
        var keys=new HashSet<IndexMembership.Key>();int[] size={2};
        var rows=jdbc.query("SELECT index_code,ts_code,cast(update_time AS long) AS observed_us,index_name,con_code,con_name,"
                +"in_date,out_date,is_new,weight,level,l1_name,l2_name,l3_name FROM \""+table+"\" "
                +"ORDER BY index_code,ts_code,in_date LIMIT "+(MAX_ROWS+1),(rs,n)->{
            if(n>=MAX_ROWS) throw new IllegalStateException("Membership row budget exceeded");
            Long micros=rs.getObject("observed_us",Long.class);
            if(micros==null) throw new IllegalStateException("Required membership observation absent");
            var observed=Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000),Math.floorMod(micros,1_000_000)*1000);
            var row=new IndexMemberRow(rs.getString("index_code"),rs.getString("ts_code"),observed,
                    rs.getString("index_name"),rs.getString("con_code"),rs.getString("con_name"),rs.getString("in_date"),
                    rs.getString("out_date"),rs.getString("is_new"),rs.getObject("weight",Double.class),rs.getString("level"),
                    rs.getString("l1_name"),rs.getString("l2_name"),rs.getString("l3_name"));
            if(!keys.add(mapper.fromStorage(row).key())) throw new IllegalStateException("Duplicate physical membership period");
            try { size[0]=Math.addExact(size[0],json.writeValueAsBytes(row).length+1); }
            catch(java.io.IOException failure) { throw new IllegalStateException("Cannot encode membership row",failure); }
            if(size[0]>MAX_BYTES) throw new IllegalStateException("Membership byte budget exceeded");
            return row;
        });
        if(!identity.equals(preflight()) || txn!=transaction()) throw new IllegalStateException("Membership changed while reading snapshot");
        byte[] bytes=json.writeValueAsBytes(rows);
        if(bytes.length>MAX_BYTES) throw new IllegalStateException("Membership snapshot byte bound exceeded");
        return new Snapshot(identity,rows,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length);
    }
}
