import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded JDBC snapshots and proof-gated retirement. Numeric values avoid HTTP JSON formatting. */
public final class QuestDbRecordSnapshot {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> ALLOWED = Set.of(
        "trade_signals", "order_records", "trade_records", "paired_execution_plans",
        "broker_statement_cashflows", "broker_statement_deliveries", "qmt_assets", "qmt_positions", "qmt_orders", "qmt_trades",
        "strategy_templates", "strategy_instances", "strategy_backtest_runs", "strategy_backtest_equity_daily",
        "strategy_backtest_positions", "strategy_backtest_trades", "strategy_daily_runs", "strategy_target_positions",
        "strategy_recommended_orders", "strategy_production_runs", "strategy_production_equity_daily",
        "strategy_production_positions", "strategy_production_orders", "strategy_production_state_daily",
        "strategy_production_pnl_attribution_daily", "strategy_run_artifact_manifest", "orders", "order_fills");
    static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    static Object value(ResultSet rs, int i) throws Exception {
        Object v = rs.getObject(i);
        if (v instanceof Timestamp) return rs.getTimestamp(i, UTC).toInstant().toString();
        if (v instanceof java.sql.Date) return rs.getDate(i, UTC).toLocalDate().toString();
        if (v instanceof Double d && !Double.isFinite(d)) throw new IllegalStateException("Non-finite double requires explicit migration handling");
        if (v instanceof Float f && !Float.isFinite(f)) throw new IllegalStateException("Non-finite float requires explicit migration handling");
        if (v instanceof byte[]) throw new IllegalStateException("Binary data requires explicit migration handling");
        return v;
    }
    static List<Map<String,Object>> rows(Connection db, String sql) throws Exception {
        try (var st = db.createStatement()) {
            st.setQueryTimeout(30);
            try (var rs = st.executeQuery(sql)) {
                var md = rs.getMetaData(); var result = new ArrayList<Map<String,Object>>();
                while (rs.next()) {
                    if (result.size() >= 1000) throw new IllegalStateException("Metadata row bound exceeded");
                    var row = new LinkedHashMap<String,Object>();
                    for (int i=1;i<=md.getColumnCount();i++) row.put(md.getColumnLabel(i),value(rs,i));
                    result.add(row);
                }
                return result;
            }
        }
    }
    static List<Map<String,Object>> state(Connection db, String name) throws Exception {
        return rows(db,"SELECT id,table_name,directoryName,table_txn,wal_txn,wal_pending_row_count,table_suspended FROM tables() WHERE table_name='"+name+"'");
    }
    static String hash(Path path) throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)) {byte[] buf=new byte[1024*1024];int n;while((n=input.read(buf))>0)hash.update(buf,0,n);}
        return HexFormat.of().formatHex(hash.digest());
    }
    static void retire(String connection, Path root) throws Exception {
        var migration=JSON.readTree(root.resolve("migration-verification.json").toFile());
        var consumers=JSON.readTree(root.resolve("consumer-verification.json").toFile());
        if(!"DEPLOYED_AND_VERIFIED".equals(migration.path("status").asText()) || !"PASS".equals(consumers.path("status").asText())
            || migration.path("tables").size()!=26 || consumers.path("tables").size()!=26) throw new IllegalStateException("Complete migration and consumer proofs required");
        var targets=consumers.path("targets").elements();
        while(targets.hasNext()) {var t=targets.next();if(!hash(Path.of(t.path("path").asText())).equals(t.path("sha256").asText()))throw new IllegalStateException("Consumer ledger changed; revalidate before retiring sources");}
        var cfg=JSON.readTree(Path.of(connection).toFile());var p=new Properties();
        p.setProperty("user",cfg.path("user").asText());p.setProperty("password",cfg.path("password").asText());p.setProperty("socketTimeout","60");
        try(var db=DriverManager.getConnection(cfg.path("url").asText(),p)) {
            String identity=JSON.writeValueAsString(rows(db,"SELECT current_data_id()"));
            if(!identity.equals(JSON.readTree(root.resolve("source/database-identity.json").toFile()).toString()))throw new IllegalStateException("Database identity changed");
            var selected=JSON.readTree(root.resolve("tables.json").toFile());
            var definitions=new ArrayList<Map<String,Object>>(rows(db,"SELECT view_name,view_sql FROM views()"));
            definitions.addAll(rows(db,"SELECT view_name,view_sql FROM materialized_views()"));
            // Validate the entire batch before issuing the first DROP.
            for(var item:selected) {
                String name=item.asText();if(!ALLOWED.contains(name))throw new IllegalStateException("Not in the exact retirement allowlist");
                var snapshot=JSON.readTree(root.resolve("source/"+name+".json").toFile());
                if(!JSON.writeValueAsString(state(db,name)).equals(snapshot.path("before").toString()))throw new IllegalStateException("Source changed or disappeared: "+name);
                if(Set.of("orders","order_fills").contains(name)) {if(snapshot.path("row_count").asLong()!=0)throw new IllegalStateException("Empty-table admission failed");}
                else if(!hash(root.resolve("source/"+name+".json")).equals(migration.path("tables").path(name).path("snapshot_sha256").asText()))throw new IllegalStateException("Source archive hash differs: "+name);
                var pattern=java.util.regex.Pattern.compile("(?i)(?<![A-Za-z0-9_])"+java.util.regex.Pattern.quote(name)+"(?![A-Za-z0-9_])");
                for(var view:definitions)if(pattern.matcher(String.valueOf(view.get("view_sql"))).find())throw new IllegalStateException("A database view still depends on "+name);
            }
            for(var item:selected) {
                String name=item.asText();var snapshot=JSON.readTree(root.resolve("source/"+name+".json").toFile());
                if(!JSON.writeValueAsString(state(db,name)).equals(snapshot.path("before").toString()))throw new IllegalStateException("Source changed immediately before retirement: "+name);
                try(var st=db.createStatement()){st.setQueryTimeout(30);st.execute("DROP TABLE "+name);}
                if(!state(db,name).isEmpty())throw new IllegalStateException("Retirement did not complete: "+name);
                var action=Map.of("table",name,"action","DROP TABLE","status","VERIFIED_ABSENT","at",java.time.Instant.now().toString(),"source_sha256",hash(root.resolve("source/"+name+".json")));
                Files.writeString(root.resolve("cleanup-actions.jsonl"),JSON.writeValueAsString(action)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                System.out.println(name+" retired; absence verified");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length==3 && args[0].equals("--retire")){retire(args[1],Path.of(args[2]));return;}
        if (args.length!=3) throw new IllegalArgumentException("connection.json tables.json output-directory");
        var cfg=JSON.readTree(Path.of(args[0]).toFile());
        var tables=JSON.readTree(Path.of(args[1]).toFile());
        Path root=Path.of(args[2]); Files.createDirectories(root);
        var properties=new Properties(); properties.setProperty("user",cfg.path("user").asText());
        properties.setProperty("password",cfg.path("password").asText()); properties.setProperty("connectTimeout","10");
        properties.setProperty("socketTimeout","60"); properties.setProperty("ApplicationName","QuestDbRecordSnapshot-read-only");
        try (var db=DriverManager.getConnection(cfg.path("url").asText(),properties)) {
            db.setReadOnly(true);
            JSON.writeValue(root.resolve("database-identity.json").toFile(),rows(db,"SELECT current_data_id()"));
            for (var item:tables) {
                String name=item.asText(); if (!ALLOWED.contains(name)) throw new IllegalArgumentException("Not an admitted record table: "+name);
                var before=state(db,name);
                if (before.size()!=1 || !Objects.equals(before.getFirst().get("table_txn"),before.getFirst().get("wal_txn"))
                    || ((Number)before.getFirst().get("wal_pending_row_count")).longValue()!=0
                    || Boolean.TRUE.equals(before.getFirst().get("table_suspended"))) throw new IllegalStateException("Unsettled source: "+name);
                Path file=root.resolve(name+".json"), partial=root.resolve(name+".json.partial");
                if (Files.exists(file)) throw new IllegalStateException("Never overwrite a completed archive: "+file);
                var schema=rows(db,"SELECT * FROM table_columns('"+name+"')");
                var ddl=rows(db,"SHOW CREATE TABLE "+name);
                long count=0;
                try (var st=db.createStatement(); var g=JSON.getFactory().createGenerator(Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW))) {
                    st.setQueryTimeout(45); st.setFetchSize(1024);
                    g.writeStartObject();g.writeObjectField("before",before);g.writeObjectField("schema",schema);g.writeObjectField("ddl",ddl);
                    try (var rs=st.executeQuery("SELECT * FROM "+name)) {
                        var md=rs.getMetaData(); var cols=new ArrayList<Map<String,String>>();
                        for (int i=1;i<=md.getColumnCount();i++) cols.add(Map.of("name",md.getColumnLabel(i),"type",md.getColumnTypeName(i)));
                        g.writeObjectField("columns",cols);g.writeArrayFieldStart("dataset");
                        while (rs.next()) {
                            if (++count>200000) throw new IllegalStateException("Record export row bound exceeded: "+name);
                            g.writeStartArray();for (int i=1;i<=md.getColumnCount();i++) g.writeObject(value(rs,i));g.writeEndArray();
                        }
                        g.writeEndArray();
                    }
                    var after=state(db,name);if(!before.equals(after)) throw new IllegalStateException("Source changed during archive: "+name);
                    g.writeObjectField("after",after);g.writeNumberField("row_count",count);g.writeEndObject();
                }
                Files.move(partial,file,StandardCopyOption.ATOMIC_MOVE);
                System.out.println(name+" "+count+" rows archived");
            }
        }
    }
}
