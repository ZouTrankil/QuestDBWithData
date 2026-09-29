package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.StockDetailInfoRow;
import com.zoutrankil.questdbwithdata.mapper.StockDetailInfoMapper;
import com.zoutrankil.questdbwithdata.service.StockDetailInfoMerge;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;

/** Explicit non-WAL static-table access. Snapshots retain legacy raw values for unchanged rows. */
public final class StockDetailInfoStorage {
    public record Identity(long id,String directory) {}
    public record Snapshot(Identity identity,List<StockDetailInfoRow> rows,String fingerprint,int bytes) {
        public Snapshot { rows=List.copyOf(rows); }
        public List<StockDetailInfo> businessRows() {
            var mapper=new StockDetailInfoMapper();return rows.stream().map(mapper::fromStorage).toList();
        }
    }
    public static final int MAX_BYTES=16*1024*1024;
    private static final String PROJECTION="ts_code,cast(update_time as long) AS update_micros,symbol,name,market,exchange,"
            +"list_status,list_date,fullname,enname,cnspell,area,industry,curr_type,delist_date,is_hs,act_name,act_ent_type";
    private final JdbcTemplate jdbc;
    private final String table;
    public StockDetailInfoStorage(JdbcTemplate jdbc,String table) {
        DatasetDefinition.identifier(table);this.table=table;
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(20);this.jdbc.setMaxRows(StockDetailInfoMerge.MAX_ROWS+1);
    }
    public Identity preflight() {
        var columns=jdbc.queryForList("SELECT \"column\",\"type\",\"upsertKey\" FROM table_columns('"+table+"')");
        var actual=new HashMap<String,String>();
        for(var column:columns) {
            if(!Boolean.FALSE.equals(column.get("upsertKey"))) throw new IllegalStateException("Unexpected static-table dedup key");
            actual.put(column.get("column").toString(),column.get("type").toString());
        }
        var expected=new HashMap<String,String>();
        StockDetailInfoDataset.DEFINITION.columns().forEach(c->expected.put(c.storageName(),c.storageType().name()));
        if(!actual.equals(expected)) throw new IllegalStateException("Stock detail schema differs from declared columns");
        var metadata=jdbc.queryForList("SELECT id,directoryName,designatedTimestamp,partitionBy,walEnabled,dedup FROM tables() WHERE table_name=?",table);
        if(metadata.size()!=1) throw new IllegalStateException("Static table identity required");
        var row=metadata.getFirst();
        if(row.get("designatedTimestamp")!=null || !"NONE".equals(row.get("partitionBy"))
                || !Boolean.FALSE.equals(row.get("walEnabled")) || !Boolean.FALSE.equals(row.get("dedup"))
                || !(row.get("id") instanceof Number id) || !(row.get("directoryName") instanceof String directory))
            throw new IllegalStateException("Expected unpartitioned non-WAL static table");
        return new Identity(id.longValue(),directory);
    }
    public Snapshot snapshot() throws Exception {
        Identity identity=preflight();
        var rows=jdbc.query("SELECT "+PROJECTION+" FROM "+table+" ORDER BY ts_code LIMIT "+(StockDetailInfoMerge.MAX_ROWS+1),
                (rs,index)->physical(rs));
        if(rows.size()>StockDetailInfoMerge.MAX_ROWS) throw new IllegalStateException("Static snapshot exceeds row budget");
        var mapper=new StockDetailInfoMapper();var keys=new HashSet<String>();
        for(var row:rows) {
            mapper.fromStorage(row);
            if(!keys.add(row.tsCode())) throw new IllegalStateException("Duplicate static-table stock identity");
        }
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(rows);
        if(bytes.length>MAX_BYTES) throw new IllegalStateException("Static snapshot exceeds byte budget");
        if(!identity.equals(preflight())) throw new IllegalStateException("Static table changed identity during read");
        return new Snapshot(identity,rows,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length);
    }
    public List<StockDetailInfoRow> readKeys(List<String> codes) {
        if(codes.isEmpty()) return List.of();
        if(codes.size()>250 || new HashSet<>(codes).size()!=codes.size()
                || codes.stream().anyMatch(c->!StockDetailInfo.validCode(c)))
            throw new IllegalArgumentException("At most 250 unique explicit stock codes required");
        preflight();
        var rows=jdbc.query("SELECT "+PROJECTION+" FROM "+table+" WHERE ts_code IN ("
                +String.join(",",Collections.nCopies(codes.size(),"?"))+") ORDER BY ts_code LIMIT "+(codes.size()+1),
                (rs,index)->physical(rs),codes.toArray());
        if(rows.size()>codes.size() || rows.stream().map(StockDetailInfoRow::tsCode).distinct().count()!=rows.size())
            throw new IllegalStateException("Duplicate static-table key");
        return List.copyOf(rows);
    }
    private static StockDetailInfoRow physical(ResultSet rs) throws SQLException {
        Object raw=rs.getObject("update_micros");
        if(!(raw instanceof Number n)) throw new SQLException("Missing observation microseconds");
        long micros=n.longValue();
        Instant observed=Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000),Math.floorMod(micros,1_000_000)*1000L);
        return new StockDetailInfoRow(rs.getString("ts_code"),observed,rs.getString("symbol"),rs.getString("name"),
                rs.getString("market"),rs.getString("exchange"),rs.getString("list_status"),rs.getString("list_date"),
                rs.getString("fullname"),rs.getString("enname"),rs.getString("cnspell"),rs.getString("area"),
                rs.getString("industry"),rs.getString("curr_type"),rs.getString("delist_date"),rs.getString("is_hs"),
                rs.getString("act_name"),rs.getString("act_ent_type"));
    }
}
