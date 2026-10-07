package com.zoutrankil.data.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import java.io.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.service.EtfMarketOverviewCacheOwnerGateway.*;

/** Delegated cache/receipt publication; no independent Java sender or receipt writer. */
public class EtfMarketOverviewCacheDelegatedPort implements VerifiedBatchExecutor.Port<EtfMarketOverviewCachePublicationEnvelope,EtfMarketOverviewDailyCacheKey> {
    public static final VerifiedBatchExecutor.Codec<EtfMarketOverviewCachePublicationEnvelope,EtfMarketOverviewDailyCacheKey> CODEC=new VerifiedBatchExecutor.Codec<>() {
        public EtfMarketOverviewDailyCacheKey key(EtfMarketOverviewCachePublicationEnvelope row){return row.key();}
        public byte[] canonicalBytes(EtfMarketOverviewCachePublicationEnvelope row){
            try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
                out.writeUTF(row.tradeDate().toString());out.writeUTF(row.sourceVersion());out.writeBoolean(row.cache()!=null);
                if(row.cache()!=null){out.writeLong(row.cache().etfCount());number(out,row.cache().totalShare());number(out,row.cache().totalSizeYi());}
                out.writeBoolean(row.receipt()!=null);if(row.receipt()!=null){out.writeUTF(row.receipt().tradeDate().toString());out.writeUTF(row.receipt().datasetId());out.writeUTF(row.receipt().sourceVersion());out.writeLong(row.receipt().rowCount());out.writeUTF(row.receipt().contentDigest());}
                return bytes.toByteArray();
            }catch(IOException error){throw new IllegalArgumentException("Cannot encode exact D101 publication",error);}
        }
        private void number(DataOutputStream out,Double value)throws IOException{out.writeBoolean(value!=null);if(value!=null)out.writeLong(Double.doubleToRawLongBits(value));}
    };
    protected final EtfMarketOverviewCacheOwnerGateway gateway;
    protected final JdbcTemplate jdbc;
    protected EtfMarketOverviewCachePublicationEnvelope bound;
    private BooleanSupplier cancelled=()->false;
    private VerifiedBatchExecutor.Submission submission;
    private boolean unresolved,knownStopped,attempted;
    private Path reconciliationLedger;
    private String reconciliationRun;
    private final Set<String> attemptedSlices=new HashSet<>();
    public EtfMarketOverviewCacheDelegatedPort(EtfMarketOverviewCacheOwnerGateway gateway,JdbcTemplate jdbc,EtfMarketOverviewCachePublicationEnvelope envelope){
        this.gateway=Objects.requireNonNull(gateway);this.jdbc=Objects.requireNonNull(jdbc);bind(envelope);
    }
    /** Pure binding: the shared runner must first create durable run/attempt authority. */
    public void bind(EtfMarketOverviewCachePublicationEnvelope envelope){bound=Objects.requireNonNull(envelope);submission=null;knownStopped=false;attempted=false;}
    public static String fingerprint(EtfMarketOverviewCachePublicationEnvelope envelope){return envelope.sourceFingerprint();}
    public void cancellationProbe(BooleanSupplier probe){cancelled=Objects.requireNonNull(probe);}
    public Duration visibilityTimeout(){return Duration.ofSeconds(30);}
    public boolean unresolved(){return unresolved;}
    public void reconciliationContext(Path ledgerPath,String runId){reconciliationLedger=Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();reconciliationRun=Objects.requireNonNull(runId);if(runId.isBlank())throw new IllegalArgumentException("Run identity required");}
    public EtfMarketOverviewCachePublicationEnvelope preview(LocalDate date)throws Exception{return gateway.preview(date);}
    public EtfMarketOverviewCachePublicationEnvelope requireExactEnvelope(EtfMarketOverviewDailyCache row)throws Exception{
        Objects.requireNonNull(row);var expected=gateway.preview(row.tradeDate());
        if(!expected.knownSourceDate()||expected.cache()==null||!sameCache(expected.cache(),row))throw new IllegalArgumentException("Prepared caller differs from the current original-owner full five-field preview");return expected;
    }
    @Override public void preflight()throws Exception{requireJdbcTarget();verifySchemas();readSnapshots();}
    @Override public void submissionRecorded(VerifiedBatchExecutor.Submission value)throws Exception{
        if(attempted||unresolved)throw new IllegalStateException("Uncertain original publication cannot be submitted again");
        gateway.validateSubmission(bound,value);submission=value;
    }
    @Override public void send(List<EtfMarketOverviewCachePublicationEnvelope> rows)throws Exception{
        if(rows==null||rows.size()!=1||!bound.knownSourceDate()||!CODEC.equivalent(bound,rows.getFirst())
                ||!bound.sourceFingerprint().equals(rows.getFirst().sourceFingerprint())||submission==null||attempted||unresolved)
            throw new IllegalStateException("One exact known-day publication and its durable submission are required");
        String identity=submission.ledgerPath()+":"+submission.sliceId();if(!attemptedSlices.add(identity))throw new IllegalStateException("Publication slice already attempted; reconcile without resend");
        if(cancelled.getAsBoolean())throw new IllegalStateException("Cancelled before owner invocation");
        preflight();attempted=true;unresolved=true;knownStopped=false;
        // From this boundary onward any exception is unknown. No automatic resend or implicit recovery.
        var result=gateway.publish(bound,submission,cancelled);
        if(result==null||!result.processStopped()||!"VERIFIED_READTHROUGH".equals(result.status()))throw new IOException("Original owner publication lacks actual termination proof");
        knownStopped=true;unresolved=false;
    }
    @Override public List<EtfMarketOverviewCachePublicationEnvelope> readback(List<EtfMarketOverviewDailyCacheKey> keys)throws Exception{
        if(keys==null||keys.size()!=1||!keys.getFirst().equals(bound.key())||!bound.knownSourceDate())throw new IllegalArgumentException("Exact single complete publication key required");
        requireJdbcTarget();verifySchemas();var before=readSnapshots();
        List<EtfMarketOverviewDailyCache> cache=readCache(bound.key());List<MarketBarometerCacheCoverage> receipts=readReceipt(bound.key());
        var after=readSnapshots();if(!before.equals(after))throw new IllegalStateException("Physical source or target changed during exact cache/receipt readback");
        if(cache.size()>1||receipts.size()>1)throw new IllegalStateException("Duplicate complete owner publication key");
        if(receipts.isEmpty())return List.of();
        var actualCache=cache.isEmpty()?null:cache.getFirst();var actualReceipt=receipts.getFirst();
        if(!bound.receipt().equals(actualReceipt)||!sameCache(bound.cache(),actualCache))return List.of();
        return List.of(bound.withActual(actualCache,actualReceipt));
    }
    @Override public boolean walSettled()throws Exception{requireJdbcTarget();readSnapshots();return true;}
    @Override public boolean uncertainSenderStopped()throws Exception{
        // An unknown attempt stays IN_DOUBT until an explicit run-scoped investigation.
        if(reconciliationLedger!=null)return gateway.writerStopped(reconciliationLedger,reconciliationRun);
        return knownStopped&&!unresolved;
    }
    protected void requireJdbcTarget()throws SQLException{
        if(jdbc.getDataSource()==null)throw new IllegalStateException("Private JDBC target is absent");
        try(var connection=jdbc.getDataSource().getConnection()){
            String url=connection.getMetaData().getURL();
            if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:18832/[^?]*(?:\\?.*)?"))throw new IllegalStateException("D101 exact private PGWire endpoint required");
        }
    }
    /** Verify all original owner schemas, not just the Java cache projection. */
    protected void verifySchemas()throws Exception{
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
    protected Map<String,JsonNode> readSnapshots()throws Exception{
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
    protected List<EtfMarketOverviewDailyCache> readCache(EtfMarketOverviewDailyCacheKey key)throws Exception{
        String sql="SELECT cast(trade_date as long) AS trade_date,etf_count,total_share,total_size_yi,source_version FROM "+EtfMarketOverviewCachePublicationEnvelope.CACHE
                +" WHERE trade_date='"+key.tradeDate()+"' AND source_version='"+key.sourceVersion()+"' LIMIT 2";
        return select(sql,rs->{var rows=new ArrayList<EtfMarketOverviewDailyCache>();while(rs.next())rows.add(new EtfMarketOverviewDailyCache(date(rs),requiredLong(rs,"etf_count"),nullableDouble(rs,"total_share"),nullableDouble(rs,"total_size_yi"),rs.getString("source_version")));return rows;});
    }
    protected List<MarketBarometerCacheCoverage> readReceipt(EtfMarketOverviewDailyCacheKey key)throws Exception{
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
    private static boolean sameCache(EtfMarketOverviewDailyCache a,EtfMarketOverviewDailyCache b){if(a==null||b==null)return a==b;return a.key().equals(b.key())&&a.etfCount()==b.etfCount()&&sameDouble(a.totalShare(),b.totalShare())&&sameDouble(a.totalSizeYi(),b.totalSizeYi());}
    private static boolean sameDouble(Double a,Double b){if(a==null||b==null)return a==b;return Double.doubleToRawLongBits(a)==Double.doubleToRawLongBits(b);}
}
