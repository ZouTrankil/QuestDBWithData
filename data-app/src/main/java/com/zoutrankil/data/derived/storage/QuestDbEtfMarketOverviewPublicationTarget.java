package com.zoutrankil.data.derived.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.EtfMarketOverviewObservedPublication;
import com.zoutrankil.data.derived.port.EtfMarketOverviewPublicationTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.zoutrankil.data.derived.domain.EtfMarketOverviewOwnerJson.*;

/** Bounded private five-table physical readback; no publication or run state. */
public class QuestDbEtfMarketOverviewPublicationTarget implements EtfMarketOverviewPublicationTarget {
    private final JdbcTemplate jdbc;
    public QuestDbEtfMarketOverviewPublicationTarget(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }
    @Override public void preflight(EtfMarketOverviewCachePublicationEnvelope expected) throws Exception {
        requireJdbcTarget();verifySchemas(expected);readSnapshots(expected);
    }
    @Override public EtfMarketOverviewObservedPublication readback(EtfMarketOverviewCachePublicationEnvelope expected,
            EtfMarketOverviewDailyCacheKey key) throws Exception {
        requireJdbcTarget();verifySchemas(expected);var before=readSnapshots(expected);
        List<EtfMarketOverviewDailyCache> cache=readCache(key);List<MarketBarometerCacheCoverage> receipts=readReceipt(key);
        var after=readSnapshots(expected);if(!before.equals(after))throw new IllegalStateException("Physical source or target changed during exact cache/receipt readback");
        return new EtfMarketOverviewObservedPublication(cache,receipts);
    }
    @Override public boolean walSettled(EtfMarketOverviewCachePublicationEnvelope expected) throws Exception {
        requireJdbcTarget();readSnapshots(expected);return true;
    }
    void requireJdbcTarget()throws SQLException{
        if(jdbc.getDataSource()==null)throw new IllegalStateException("Private JDBC target is absent");
        try(var connection=jdbc.getDataSource().getConnection()){
            String url=connection.getMetaData().getURL();
            if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:18832/[^?]*(?:\\?.*)?"))throw new IllegalStateException("D101 exact private PGWire endpoint required");
        }
    }
    /** Verify all original owner schemas, not just the Java cache projection. */
    void verifySchemas(EtfMarketOverviewCachePublicationEnvelope bound)throws Exception{
        JsonNode schemas=bound.previewResponse().get("validated_schemas");
        for(String table:allTables()){
            JsonNode contract=schemas==null?null:schemas.get(table);if(contract==null)throw new IllegalStateException("Original schema attestation missing");
            Map<String,String> types=new LinkedHashMap<>();var upsert=new HashSet<String>();var designated=new ArrayList<String>();
            select("SELECT \"column\",\"type\",upsertKey,designated FROM table_columns('"+table+"') LIMIT 257",rs->{
                while(rs.next()){String name=rs.getString("column");if(types.put(name,rs.getString("type"))!=null||types.size()>256)throw new IllegalStateException("Duplicate or unbounded physical schema");if(requiredBoolean(rs,"upsertKey"))upsert.add(name);if(requiredBoolean(rs,"designated"))designated.add(name);}return null;});
            var expected=new LinkedHashMap<String,String>();contract.path("physical_types").fields().forEachRemaining(item->expected.put(item.getKey(),item.getValue().asText()));
            var expectedKeys=new HashSet<String>();contract.path("upsert_keys").forEach(item->expectedKeys.add(item.asText()));
            if(expected.isEmpty()||!types.equals(expected)||!new ArrayList<>(types.keySet()).equals(new ArrayList<>(expected.keySet()))
                    ||!upsert.equals(expectedKeys)||!designated.equals(List.of(string(contract,"timestamp"))))throw new IllegalStateException("Original five-table physical schema changed");
        }
    }
    /** Source txn is frozen exactly; target txn may advance but its identity and schema cannot change. */
    Map<String,JsonNode> readSnapshots(EtfMarketOverviewCachePublicationEnvelope bound)throws Exception{
        var result=new LinkedHashMap<String,JsonNode>();
        for(String table:allTables()){
            JsonNode expected=bound.sources().containsKey(table)?bound.sources().get(table):bound.targets().get(table);
            boolean source=EtfMarketOverviewCachePublicationEnvelope.SOURCE_TABLES.contains(table);
            var current=readPhysical(table);
            validateSnapshot(current,!source);
            JsonNode p=current.get("physical"),e=expected.get("physical"),w=current.get("wal"),ew=expected.get("wal");
            Long actualTxn=nullableCounter(p,"table_txn"),expectedTxn=nullableCounter(e,"table_txn");
            if(actualTxn==null){
                long count=select("SELECT count() AS actual_count FROM "+table,rs->{if(!rs.next())throw new IllegalStateException("Target COUNT proof missing");long value=requiredLong(rs,"actual_count");if(rs.next())throw new IllegalStateException("Duplicate target COUNT proof");return value;});
                if(count!=0||!current.equals(readPhysical(table)))throw new IllegalStateException("Newly empty target COUNT or stable physical/WAL proof failed");
            }
            if(nonnegative(p,"id")!=nonnegative(e,"id"))throw new IllegalStateException("Frozen owner table identity changed");
            for(String field:List.of("directoryName","partitionBy","designatedTimestamp"))if(!string(p,field).equals(string(e,field)))throw new IllegalStateException("Frozen owner contract changed");
            for(String field:List.of("walEnabled","dedup"))if(bool(p,field)!=bool(e,field))throw new IllegalStateException("Frozen owner contract changed");
            boolean sourceWalChanged=nonnegative(w,"writerTxn")!=nonnegative(ew,"writerTxn")||nonnegative(w,"sequencerTxn")!=nonnegative(ew,"sequencerTxn")
                    ||nonnegative(w,"bufferedTxnSize")!=nonnegative(ew,"bufferedTxnSize")||bool(w,"suspended")!=bool(ew,"suspended");
            if(source&&(actualTxn==null||expectedTxn==null||!actualTxn.equals(expectedTxn)||sourceWalChanged)
                    ||!source&&(expectedTxn!=null&&(actualTxn==null||actualTxn<expectedTxn)||nonnegative(w,"sequencerTxn")<nonnegative(ew,"sequencerTxn")))
                throw new IllegalStateException("Source changed or target frontier regressed");
            result.put(table,current);
        }
        return result;
    }
    private JsonNode readPhysical(String table){
        return select("SELECT t.id,t.directoryName,t.table_txn,t.table_row_count,t.partitionBy,t.designatedTimestamp,t.walEnabled,t.dedup,"
                +"t.table_suspended,t.wal_pending_row_count,w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended "
                +"FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='"+table+"'",rs->{
            if(!rs.next())throw new IllegalStateException("Physical/WAL owner table is absent");
            var root=JobDefinitionJson.mapper().createObjectNode();var physical=root.putObject("physical");var wal=root.putObject("wal");
            for(String column:List.of("id","wal_pending_row_count"))physical.put(column,requiredLong(rs,column));
            for(String column:List.of("table_txn","table_row_count")){long value=rs.getLong(column);if(rs.wasNull())physical.putNull(column);else{if(value<0)throw new IllegalStateException("Negative physical counter");physical.put(column,value);}}
            for(String column:List.of("directoryName","partitionBy","designatedTimestamp")){String text=rs.getString(column);if(text==null||text.isBlank())throw new IllegalStateException("Physical metadata missing");physical.put(column,text);}
            for(String column:List.of("walEnabled","dedup","table_suspended"))physical.put(column,requiredBoolean(rs,column));
            for(String column:List.of("sequencerTxn","writerTxn","bufferedTxnSize"))wal.put(column,requiredLong(rs,column));wal.put("suspended",requiredBoolean(rs,"suspended"));root.put("settled",true);
            if(rs.next())throw new IllegalStateException("Duplicate owner table metadata");return root;
        });
    }
    List<EtfMarketOverviewDailyCache> readCache(EtfMarketOverviewDailyCacheKey key)throws Exception{
        String sql="SELECT cast(trade_date as long) AS trade_date,etf_count,total_share,total_size_yi,source_version FROM "+EtfMarketOverviewCachePublicationEnvelope.CACHE
                +" WHERE trade_date='"+key.tradeDate()+"' AND source_version='"+key.sourceVersion()+"' LIMIT 2";
        return select(sql,rs->{var rows=new ArrayList<EtfMarketOverviewDailyCache>();while(rs.next())rows.add(new EtfMarketOverviewDailyCache(date(rs),requiredLong(rs,"etf_count"),nullableDouble(rs,"total_share"),nullableDouble(rs,"total_size_yi"),rs.getString("source_version")));return rows;});
    }
    List<MarketBarometerCacheCoverage> readReceipt(EtfMarketOverviewDailyCacheKey key)throws Exception{
        String sql="SELECT cast(trade_date as long) AS trade_date,dataset_id,source_version,row_count,content_digest FROM "+EtfMarketOverviewCachePublicationEnvelope.COVERAGE
                +" WHERE trade_date='"+key.tradeDate()+"' AND dataset_id='"+EtfMarketOverviewCachePublicationEnvelope.DATASET+"' AND source_version='"+key.sourceVersion()+"' LIMIT 2";
        return select(sql,rs->{var rows=new ArrayList<MarketBarometerCacheCoverage>();while(rs.next())rows.add(new MarketBarometerCacheCoverage(date(rs),rs.getString("dataset_id"),rs.getString("source_version"),requiredLong(rs,"row_count"),rs.getString("content_digest")));return rows;});
    }
    private <T>T select(String sql,ResultSetExtractor<T> reader){return jdbc.query(connection->{var statement=connection.prepareStatement(sql);statement.setQueryTimeout(20);statement.setMaxRows(257);statement.setFetchSize(257);return statement;},reader);}
    private static List<String> allTables(){var tables=new ArrayList<>(EtfMarketOverviewCachePublicationEnvelope.SOURCE_TABLES);tables.addAll(EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES);return tables;}
    private static long requiredLong(ResultSet rs,String field)throws SQLException{long value=rs.getLong(field);if(rs.wasNull()||value<0)throw new IllegalStateException("Required nonnegative LONG missing: "+field);return value;}
    private static boolean requiredBoolean(ResultSet rs,String field)throws SQLException{boolean value=rs.getBoolean(field);if(rs.wasNull())throw new IllegalStateException("Required physical boolean missing: "+field);return value;}
    private static Double nullableDouble(ResultSet rs,String field)throws SQLException{Object value=rs.getObject(field);if(value==null)return null;if(!(value instanceof Double number)||!Double.isFinite(number))throw new IllegalStateException("Exact finite PG binary64 required");return number;}
    private static LocalDate date(ResultSet rs)throws SQLException{long micros=rs.getLong("trade_date");if(rs.wasNull()||Math.floorMod(micros,86_400_000_000L)!=0)throw new IllegalStateException("Exact UTC midnight TIMESTAMP required");return LocalDate.ofEpochDay(Math.floorDiv(micros,86_400_000_000L));}
}
