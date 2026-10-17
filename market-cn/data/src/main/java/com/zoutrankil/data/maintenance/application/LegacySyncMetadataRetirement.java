package com.zoutrankil.data.maintenance.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅清理明确登记的六张旧辅助表，先完整归档全部字段并确保持久化。 */
public final class LegacySyncMetadataRetirement {
    public static final List<String> TABLES=List.of("cashflow_period_meta","express_period_meta",
            "fina_indicator_period_meta","forecast_period_meta","income_period_meta","etf_market_overview_mv_refresh");
    private final JdbcTemplate jdbc;
    private final Path ledger,archive;
    public LegacySyncMetadataRetirement(JdbcTemplate jdbc,Path ledger,Path archive) {
        this.jdbc=jdbc;this.ledger=ledger.toAbsolutePath().normalize();this.archive=archive.toAbsolutePath().normalize();
    }
    private record Snapshot(String table,List<Map<String,Object>> identity,Map<String,Object> document,byte[] bytes,String sha256) {}

    public Map<String,Object> retire() throws Exception {
        // 先确认 ETF 的新校验机制可用，再移除旧的校验依据。
        if(!existing("etf_market_overview_mv_refresh").isEmpty()
                && EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(jdbc,ledger)==null)
            throw new IllegalStateException("Activate and certify the ETF native MV before retirement");
        var snapshots=new ArrayList<Snapshot>();
        var dependencies=new ArrayList<Map<String,Object>>();
        dependencies.addAll(jdbc.queryForList("SELECT view_name,view_sql FROM views()"));
        dependencies.addAll(jdbc.queryForList("SELECT view_name,view_sql FROM materialized_views()"));
        for(String name:TABLES) {
            if(existing(name).isEmpty())continue;
            for(var dependency:dependencies)
                if(java.util.regex.Pattern.compile("(?i)(?<![a-z0-9_])"+name+"(?![a-z0-9_])")
                        .matcher(Objects.toString(dependency.get("view_sql"),"")).find())
                    throw new IllegalStateException("Legacy table is still referenced by "+dependency.get("view_name"));
            snapshots.add(capture(name));
        }
        // 整组归档全部成功后，才能执行第一条 DROP。
        for(var snapshot:snapshots)archive(snapshot);
        var removed=new ArrayList<String>();long rows=0;
        for(var saved:snapshots) {
            var current=capture(saved.table());
            if(!saved.sha256().equals(current.sha256()))throw new IllegalStateException("Legacy table changed after archival: "+saved.table());
            jdbc.execute("DROP TABLE \""+saved.table()+"\"");
            if(!existing(saved.table()).isEmpty())throw new IllegalStateException("Legacy DROP did not complete: "+saved.table());
            removed.add(saved.table());rows+=((Number)saved.document().get("rowCount")).longValue();
        }
        return Map.of("status","RETIRED","tables",removed,"archivedRows",rows,"archive",archive.toString(),"ledger",ledger.toString());
    }

    private List<Map<String,Object>> existing(String name) {
        return jdbc.queryForList("SELECT id,directoryName,table_txn FROM tables() WHERE table_name=?",name);
    }

    private Snapshot capture(String name) throws Exception {
        var before=existing(name);
        if(before.size()!=1)throw new IllegalStateException("Unique legacy table required: "+name);
        long count=Objects.requireNonNull(jdbc.queryForObject("SELECT count() FROM \""+name+"\"",Long.class));
        if(count>10000)throw new IllegalStateException("Legacy metadata exceeds archival row budget: "+name);
        var schema=jdbc.queryForList("SELECT * FROM table_columns('"+name+"')");
        Set<String> columns=new HashSet<>();for(var c:schema)columns.add(c.get("column").toString());
        Set<String> expected=name.endsWith("_period_meta")?Set.of("period","last_fetched_at","row_count")
                :Set.of("refreshed_at","source_version","output_version");
        if(!columns.equals(expected))throw new IllegalStateException("Unexpected legacy schema: "+name);
        var rows=jdbc.query("SELECT * FROM \""+name+"\" ORDER BY "+(name.endsWith("_period_meta")?"period":"refreshed_at"),
                (rs,index) -> {
                    var row=new LinkedHashMap<String,Object>();var md=rs.getMetaData();
                    for(int i=1;i<=md.getColumnCount();i++) {
                        Object value=rs.getObject(i);
                        row.put(md.getColumnLabel(i),value instanceof Timestamp t?t.toInstant().toString():value);
                    }
                    return row;
                });
        if(rows.size()!=count || !before.equals(existing(name)))throw new IllegalStateException("Legacy metadata changed during archival");
        var doc=new LinkedHashMap<String,Object>();doc.put("format","questdb.retired-sync-metadata.v1");
        doc.put("databaseIdentity",jdbc.queryForList("SELECT current_data_id()"));doc.put("table",name);doc.put("identity",before);
        doc.put("schema",schema);doc.put("ddl",jdbc.queryForList("SHOW CREATE TABLE \""+name+"\""));
        doc.put("rowCount",count);doc.put("rows",rows);
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(doc);
        return new Snapshot(name,before,doc,bytes,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private void archive(Snapshot snapshot) throws Exception {
        Files.createDirectories(archive);Files.createDirectories(ledger.getParent());
        Path file=archive.resolve(snapshot.table()+"-"+snapshot.sha256()+".json");
        if(Files.exists(file)) {
            if(!Arrays.equals(snapshot.bytes(),Files.readAllBytes(file)))throw new IllegalStateException("Archive conflict: "+file);
        } else {
            Path partial=Files.createTempFile(archive,snapshot.table(),".partial");
            try {
                try(var channel=FileChannel.open(partial,StandardOpenOption.WRITE)) {
                    var buffer=ByteBuffer.wrap(snapshot.bytes());while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
                }
                Files.move(partial,file,StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(partial); }
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledger);var s=db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA synchronous=FULL");db.setAutoCommit(false);
            s.execute("CREATE TABLE IF NOT EXISTS retired_questdb_sync_metadata(table_name TEXT NOT NULL,sha256 TEXT NOT NULL,"
                    +"row_count INTEGER NOT NULL,snapshot_json BLOB NOT NULL,archive_file TEXT NOT NULL,"
                    +"PRIMARY KEY(table_name,sha256))");
            try(var insert=db.prepareStatement("INSERT OR IGNORE INTO retired_questdb_sync_metadata VALUES(?,?,?,?,?)")) {
                insert.setString(1,snapshot.table());insert.setString(2,snapshot.sha256());
                insert.setLong(3,((Number)snapshot.document().get("rowCount")).longValue());insert.setBytes(4,snapshot.bytes());
                insert.setString(5,file.toString());insert.executeUpdate();
            }
            try(var check=db.prepareStatement("SELECT snapshot_json FROM retired_questdb_sync_metadata WHERE table_name=? AND sha256=?")) {
                check.setString(1,snapshot.table());check.setString(2,snapshot.sha256());
                try(var row=check.executeQuery()) {
                    if(!row.next()||!Arrays.equals(snapshot.bytes(),row.getBytes(1)))throw new IllegalStateException("Local archival verification failed");
                }
            }
            db.commit();
        }
    }
}
