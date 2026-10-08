package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Server-side full/enrichment window staging, verified by streaming every typed composite-key row.
 * The runner submits month integrity receipts, not ten million buffered data rows. Original data is retained.
 */
public final class BacktestDailyMaterializationPort implements VerifiedBatchExecutor.Port<BacktestDailyMaterializationPort.MonthReceipt,LocalDate> {
    public static final String BASE="backtest_daily", ALIAS="v_backtest_daily", MV="v_backtest_daily";
    public static final List<String> SOURCES=List.of("stk_factor","stk_limit","stk_suspend","stk_st_daily");
    public static final List<String> COLUMNS=List.of("trade_date","ts_code","open","high","low","close","vol","amount","adj_factor","up_limit","down_limit","is_suspended","is_st");
    public static final long MAX_TOTAL_ROWS=40_000_000,MAX_MONTH_ROWS=1_000_000;
    // Exact authoritative backtest_view.py VIEW_SELECT, including the no-factor suspension ASOF path.
    public static final String SOURCE_SQL="""
            SELECT b.trade_date, b.ts_code, b.open, b.high, b.low, b.close,
                   b.vol, b.amount, b.adj_factor, l.up_limit, l.down_limit,
                   coalesce(s.is_suspended, 0) is_suspended, coalesce(st.is_st, 0) is_st
            FROM stk_factor b
            LEFT JOIN stk_limit l ON (b.trade_date = l.trade_date AND b.ts_code = l.ts_code)
            LEFT JOIN (SELECT timestamp, ts_code, max(is_suspended) is_suspended
                       FROM stk_suspend GROUP BY timestamp, ts_code) s ON (b.trade_date = s.timestamp AND b.ts_code = s.ts_code)
            LEFT JOIN stk_st_daily st ON (b.trade_date = st.timestamp AND b.ts_code = st.ts_code)
            UNION ALL
            SELECT s.trade_date, s.ts_code, f.close AS open, f.close AS high,
                   f.close AS low, f.close AS close, 0.0 AS vol, 0.0 AS amount,
                   f.adj_factor, l.up_limit, l.down_limit, s.is_suspended,
                   coalesce(st.is_st, 0) is_st
            FROM ((SELECT timestamp AS trade_date, ts_code, max(is_suspended) is_suspended
                  FROM stk_suspend GROUP BY timestamp, ts_code ORDER BY trade_date) TIMESTAMP(trade_date)) s
            ASOF JOIN stk_factor f ON (ts_code)
            LEFT JOIN stk_limit l ON (s.trade_date = l.trade_date AND s.ts_code = l.ts_code)
            LEFT JOIN stk_st_daily st ON (s.trade_date = st.timestamp AND s.ts_code = st.ts_code)
            JOIN (SELECT DISTINCT trade_date FROM stk_factor) fd ON s.trade_date = fd.trade_date
            WHERE f.trade_date < s.trade_date
            """.strip();
    public static final String MV_SQL="SELECT trade_date,ts_code,last(open) AS open,last(high) AS high,last(low) AS low,last(close) AS close,"
            +"last(vol) AS vol,last(amount) AS amount,last(adj_factor) AS adj_factor,last(up_limit) AS up_limit,last(down_limit) AS down_limit,"
            +"cast(last(is_suspended) AS LONG) AS is_suspended,last(is_st) AS is_st FROM backtest_daily SAMPLE BY 1d ALIGN TO CALENDAR";
    public record Identity(long id,String directory,long rows,LocalDate from,LocalDate to,long tableTxn,long sequenceTxn,long writerTxn) {
        public String fingerprint(){return sha(id+"|"+directory+"|"+rows+"|"+from+"|"+to+"|"+tableTxn+"|"+sequenceTxn+"|"+writerTxn);}
    }
    public record Bounds(LocalDate from,LocalDate to,long rows) {}
    public record MonthReceipt(LocalDate from,LocalDate to,long rows,int dates,String sha256) {
        public MonthReceipt { if(from==null||to==null||from.isAfter(to)||rows<0||rows>MAX_MONTH_ROWS||dates<0||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Complete finite month receipt required"); }
    }
    public record NativeState(String name,long id,String directory,String base,long baseId,long baseTxn,long refreshTxn,long mvTxn,long sequenceTxn,String sql,String refreshStarted,String refreshFinished) {}
    public static final VerifiedBatchExecutor.Codec<MonthReceipt,LocalDate> CODEC=new VerifiedBatchExecutor.Codec<>() {
        public LocalDate key(MonthReceipt r){return r.from();}
        public byte[] canonicalBytes(MonthReceipt r){return (r.from()+"|"+r.to()+"|"+r.rows()+"|"+r.dates()+"|"+r.sha256()+"\n").getBytes(StandardCharsets.UTF_8);}
    };
    private final JdbcTemplate jdbc;
    private Identity before; private LocalDate from,to;private boolean bootstrap,staged;private String stage;
    private VerifiedBatchExecutor.Submission submission;
    private BooleanSupplier cancelled=()->Thread.currentThread().isInterrupted();
    public BacktestDailyMaterializationPort(JdbcTemplate source){jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(1800);jdbc.setFetchSize(1024);}
    public void freeze(Identity before,LocalDate from,LocalDate to,boolean bootstrap,String stage,BooleanSupplier stopped){
        requireName(stage,"java_backtest_daily_stage_");this.before=Objects.requireNonNull(before);this.from=Objects.requireNonNull(from);this.to=Objects.requireNonNull(to);this.bootstrap=bootstrap;this.stage=stage;cancelled=Objects.requireNonNull(stopped);
    }
    public String stage(){return stage;}
    public void check(){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Backtest materialization cancelled");}
    public static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public static String normalized(String text){return text==null?"":text.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    public static boolean nativeName(String value){return MV.equals(value);}
    public static void requireName(String name,String prefix){DatasetDefinition.identifier(name);if(!name.startsWith(prefix)||!name.substring(prefix.length()).matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Exact owned artifact name required");}
    public static String timestamp(LocalDate d){return "cast('"+d+"T00:00:00.000000Z' AS TIMESTAMP)";}
    private static String datePredicate(LocalDate lo,LocalDate hi){return "trade_date>="+timestamp(lo)+" AND trade_date<"+timestamp(hi.plusDays(1));}
    private static String quote(String name){DatasetDefinition.identifier(name);return "\""+name+"\"";}
    public String targetId(Identity identity){return StaticTargetIdentity.identify(jdbc,BASE,identity.id(),identity.directory());}
    public Identity identity(String table){
        DatasetDefinition.identifier(table);
        var rows=identityMetadata(table);
        if(rows.size()!=1)throw new IllegalStateException("Exact WAL table identity absent: "+table);
        var r=rows.getFirst();requireIdentityMetadata(r);requireSchema(table,false);
        // tables().table_* are approximate in-memory tracker values and can be NULL after RENAME.
        // Attached partition metadata describes the real physical rows. Pin the on-disk WAL frontier around it.
        var physical=physicalBounds(table);var after=identityMetadata(table);if(after.size()!=1||!sameLiveIdentity(r,after.getFirst()))throw new IllegalStateException("Backtest physical identity/WAL changed while observing partitions");requireIdentityMetadata(after.getFirst());
        long n=number(physical,"n");if(n<0||n>MAX_TOTAL_ROWS)throw new IllegalStateException("Finite complete backtest table row budget exceeded");
        return new Identity(number(r,"id"),Objects.toString(r.get("directoryName")),n,date(physical.get("min_us")),date(physical.get("max_us")),number(r,"table_txn"),number(r,"sequencerTxn"),number(r,"writerTxn"));
    }
    private List<Map<String,Object>> identityMetadata(String table){return jdbc.queryForList("SELECT t.id,t.directoryName,t.table_row_count,coalesce(t.table_txn,w.writerTxn) AS table_txn,cast(t.table_min_timestamp AS LONG) AS min_us,cast(t.table_max_timestamp AS LONG) AS max_us,"
                +"t.walEnabled,t.partitionBy,t.dedup,t.designatedTimestamp,t.table_type,t.table_suspended,t.wal_pending_row_count,w.writerTxn,w.sequencerTxn,w.bufferedTxnSize,w.suspended "
                +"FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name=?",table);}
    private void requireIdentityMetadata(Map<String,Object> r){
        if(!Boolean.TRUE.equals(r.get("walEnabled"))||!Boolean.FALSE.equals(r.get("suspended"))||!Boolean.FALSE.equals(r.get("table_suspended"))||number(r,"wal_pending_row_count")!=0||number(r,"bufferedTxnSize")!=0||number(r,"writerTxn")!=number(r,"sequencerTxn"))throw new IllegalStateException("Backtest WAL is unsettled/suspended: physical id "+r.get("id"));
        if(!"T".equals(Objects.toString(r.get("table_type")))||!"DAY".equals(r.get("partitionBy"))||!Boolean.TRUE.equals(r.get("dedup"))||!"trade_date".equals(r.get("designatedTimestamp")))throw new IllegalStateException("Backtest base requires DAY WAL DEDUP date/stock table");
    }
    private static boolean sameLiveIdentity(Map<String,Object> a,Map<String,Object> b){for(String field:List.of("id","directoryName","table_txn","writerTxn","sequencerTxn","bufferedTxnSize","suspended","walEnabled","partitionBy","dedup","designatedTimestamp","table_type"))if(!Objects.equals(a.get(field),b.get(field)))return false;return true;}
    private Map<String,Object> physicalBounds(String table){DatasetDefinition.identifier(table);var r=jdbc.queryForMap("SELECT sum(numRows) AS n,min(cast(minTimestamp AS LONG)) AS min_us,max(cast(maxTimestamp AS LONG)) AS max_us,count() AS partitions FROM table_partitions('"+table+"') WHERE attached=true");long parts=number(r,"partitions");if(parts<0||parts>36600)throw new IllegalStateException("Complete attached partition budget exceeded");if(r.get("n")==null){if(parts!=0)throw new IllegalStateException("Partition row count unavailable");r.put("n",0L);}long n=number(r,"n");if(n>0&&(r.get("min_us")==null||r.get("max_us")==null))throw new IllegalStateException("Physical nonempty partition bounds missing");return r;}
    private static long number(Map<String,Object> row,String key){Object value=row.get(key);if(!(value instanceof Number n))throw new IllegalStateException("Required metadata counter missing: "+key);return n.longValue();}
    private static LocalDate date(Object value){if(value==null)return null;if(!(value instanceof Number n))throw new IllegalStateException("UTC micros required");long us=n.longValue();if(Math.floorMod(us,86_400_000_000L)!=0)throw new IllegalStateException("Business date is not UTC midnight");return LocalDate.ofEpochDay(Math.floorDiv(us,86_400_000_000L));}
    public void requireSame(Identity expected){if(!identity(BASE).equals(expected))throw new IllegalStateException("Backtest physical target changed since planning");}
    public void requireSchema(String table,boolean nativeMv){
        var cols=jdbc.queryForList("SELECT \"column\",\"type\",designated,upsertKey FROM table_columns('"+table+"') LIMIT 14");
        if(cols.size()!=13)throw new IllegalStateException("Exact 13-column backtest schema required");var keys=new HashSet<String>();
        for(int i=0;i<cols.size();i++){var c=cols.get(i);String name=COLUMNS.get(i),type=i==0?"TIMESTAMP":i==1?"SYMBOL":i<11?"DOUBLE":i==11&&nativeMv?"LONG":"INT";
            if(!name.equals(c.get("column"))||!type.equals(c.get("type"))||Boolean.TRUE.equals(c.get("designated"))!=(i==0))throw new IllegalStateException("Backtest schema/type/designated column differs: "+name);
            if(Boolean.TRUE.equals(c.get("upsertKey")))keys.add(name);}
        if(!keys.equals(nativeMv?Set.of():Set.of("trade_date","ts_code")))throw new IllegalStateException("Backtest exact UPSERT key differs");
    }
    /** Includes the entire historical price source and every side-table version, not merely requested partitions. */
    public String sourcePin(){
        var all=new LinkedHashMap<String,Object>();all.put("canonicalSql",SOURCE_SQL);
        for(String table:SOURCES){
            var rows=jdbc.queryForList("SELECT t.id,t.directoryName,t.table_txn,t.table_row_count,t.walEnabled,t.table_suspended,t.wal_pending_row_count,"
                    +"w.writerTxn,w.sequencerTxn,w.bufferedTxnSize,w.suspended FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name=?",table);
            if(rows.size()!=1)throw new IllegalStateException("Backtest dependency missing: "+table);var r=rows.getFirst();
            if(!Boolean.TRUE.equals(r.get("walEnabled"))||!Boolean.FALSE.equals(r.get("table_suspended"))||!Boolean.FALSE.equals(r.get("suspended"))||number(r,"wal_pending_row_count")!=0||number(r,"bufferedTxnSize")!=0||number(r,"writerTxn")!=number(r,"sequencerTxn"))throw new IllegalStateException("Backtest dependency WAL unsettled: "+table);
            var schema=jdbc.queryForList("SELECT \"column\",\"type\",designated,upsertKey FROM table_columns('"+table+"') LIMIT 401");if(schema.size()>400)throw new IllegalStateException("Backtest source schema budget exceeded");
            var partitions=jdbc.queryForList("SELECT name,numRows,cast(minTimestamp AS LONG) AS min_us,cast(maxTimestamp AS LONG) AS max_us,seqTxn FROM table_partitions('"+table+"') ORDER BY name LIMIT 36601");if(partitions.size()>36600)throw new IllegalStateException("Complete historical source partition budget exceeded");
            // Preserve the frozen canonical field set and present values; fill only absent tracker statistics.
            if(r.get("table_txn")==null)r.put("table_txn",number(r,"writerTxn"));if(r.get("table_row_count")==null)r.put("table_row_count",number(physicalBounds(table),"n"));
            all.put(table,Map.of("metadata",r,"schema",schema,"partitions",partitions));
        }
        try{return sha(JobDefinitionJson.mapper().copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsString(all));}catch(Exception e){throw new IllegalStateException("Cannot pin backtest dependency state",e);}
    }
    public Bounds sourceBounds(){
        // The suspension arm explicitly joins DISTINCT factor dates, so its date bounds equal factor bounds.
        // table_row_count is a lower bound; the complete union count is certified by every monthly typed digest.
        var r=physicalBounds("stk_factor");long n=number(r,"n");LocalDate lo=date(r.get("min_us")),hi=date(r.get("max_us"));if(n<1||n>MAX_TOTAL_ROWS||lo==null||hi==null||java.time.temporal.ChronoUnit.DAYS.between(lo,hi)>=36600)throw new IllegalStateException("Complete finite factor history bounds required");return new Bounds(lo,hi,n);
    }
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private String projection(boolean base){return "cast(trade_date AS LONG) AS date_us,ts_code,open,high,low,close,vol,amount,adj_factor,up_limit,down_limit,"+(base?"cast(is_suspended AS LONG)":"is_suspended")+" AS is_suspended,is_st";}
    private static String physicalColumns(){return "trade_date,ts_code,open,high,low,close,vol,amount,adj_factor,up_limit,down_limit,cast(is_suspended AS INT) AS is_suspended,is_st";}
    /** Sort and validate all rows. Constant memory per JDBC row; duplicates are observed before any DEDUP stage. */
    public MonthReceipt digest(String table,LocalDate lo,LocalDate hi,boolean base){
        DatasetDefinition.identifier(table);return digestRelation(quote(table),lo,hi,base);
    }
    /** Output-only pushdown preserves the ASOF price table's entire prior history. No ordinary view is created. */
    public static String sourceWindowSql(LocalDate lo,LocalDate hi){
        String window=datePredicate(lo,hi),tsWindow=window.replace("trade_date","timestamp");
        return SOURCE_SQL.replace("FROM stk_factor b","FROM (SELECT * FROM stk_factor WHERE "+window+") b")
                .replace("LEFT JOIN stk_limit l","LEFT JOIN (SELECT * FROM stk_limit WHERE "+window+") l")
                .replace("LEFT JOIN stk_st_daily st","LEFT JOIN (SELECT * FROM stk_st_daily WHERE "+tsWindow+") st")
                .replace("FROM stk_suspend GROUP BY","FROM stk_suspend WHERE "+tsWindow+" GROUP BY")
                .replace("SELECT DISTINCT trade_date FROM stk_factor","SELECT DISTINCT trade_date FROM stk_factor WHERE "+window);
    }
    public MonthReceipt sourceDigest(LocalDate lo,LocalDate hi){return digestRelation("("+sourceWindowSql(lo,hi)+")",lo,hi,false);}
    private MonthReceipt digestRelation(String relation,LocalDate lo,LocalDate hi,boolean base){try{
            check();
            var hash=MessageDigest.getInstance("SHA-256");long[] count={0};int[] days={0};BacktestDailyKey[] previous={null};
            jdbc.query("SELECT "+projection(base)+" FROM "+relation+" WHERE "+datePredicate(lo,hi)+" ORDER BY trade_date,ts_code LIMIT "+(MAX_MONTH_ROWS+1),(RowCallbackHandler)rs->{
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Backtest row digest interrupted");
                if(++count[0]>MAX_MONTH_ROWS)throw new IllegalStateException("Backtest month exceeds row budget");
                // The cancellation supplier reads SQLite authority; never invoke it for every business row.
                if(count[0]==1||(count[0]&1023)==0)check();
                LocalDate day=date(rs.getLong("date_us"));if(day==null||day.isBefore(lo)||day.isAfter(hi))throw new IllegalStateException("Backtest row outside exact date scope");
                String code=rs.getString("ts_code");var key=new BacktestDailyKey(day,code);if(previous[0]!=null&&previous[0].equals(key))throw new IllegalStateException("Duplicate backtest date/stock key: "+key);
                if(previous[0]==null||!previous[0].tradeDate().equals(day))days[0]++;previous[0]=key;
                var bytes=new StringBuilder(day+"|"+code);
                for(int c=2;c<11;c++){Object value=rs.getObject(COLUMNS.get(c));bytes.append('|');if(value==null)bytes.append('~');else {if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalStateException("Nonfinite backtest field "+COLUMNS.get(c));bytes.append(Long.toHexString(Double.doubleToRawLongBits(n.doubleValue())));}}
                for(String field:List.of("is_suspended","is_st")){Object value=rs.getObject(field);bytes.append('|');if(value==null)bytes.append('~');else{if(!(value instanceof Number n)||n.longValue()!=n.doubleValue())throw new IllegalStateException("Integral backtest flag required");Math.toIntExact(n.longValue());bytes.append(n.longValue());}}
                bytes.append('\n');hash.update(bytes.toString().getBytes(StandardCharsets.UTF_8));
            });check();return new MonthReceipt(lo,hi,count[0],days[0],HexFormat.of().formatHex(hash.digest()));
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public List<MonthReceipt> fullDigest(String table,Identity identity){
        if(identity.rows()==0)return List.of();var result=new ArrayList<MonthReceipt>();for(var lo=identity.from().withDayOfMonth(1);!lo.isAfter(identity.to());lo=lo.plusMonths(1))result.add(digest(table,lo,lo.plusMonths(1).minusDays(1),true));
        if(result.stream().mapToLong(MonthReceipt::rows).sum()!=identity.rows())throw new IllegalStateException("Full streaming row count differs from physical metadata");return List.copyOf(result);
    }
    public static String fullHash(List<MonthReceipt> months){return sha(months.stream().map(r->new String(CODEC.canonicalBytes(r),StandardCharsets.UTF_8)).reduce("",String::concat));}
    @Override public void preflight(){if(before==null)throw new IllegalStateException("Frozen backtest stage request required");requireSame(before);check();}
    @Override public void submissionRecorded(VerifiedBatchExecutor.Submission context)throws Exception{
        var ledger=SyncRunLedger.openReadOnly(context.ledgerPath());var slice=ledger.get(context.sliceId());var run=ledger.getRun(context.runId());var frozen=JobDefinitionJson.mapper().readTree(run.frozenJson());
        if(slice.kind()!=SyncRunLedger.Kind.SLICE||slice.state()!=SyncRunState.SUBMITTED||slice.revision()!=context.revision()||!context.runId().equals(slice.runId())||!"data.backtest_daily".equals(run.jobId())||run.jobVersion()!=1||!"MATERIALIZE".equals(frozen.path("mode").asText())||!before.fingerprint().equals(frozen.path("parameters").path("target_signature").asText())||!from.toString().equals(frozen.path("from").asText())||!to.toString().equals(frozen.path("to").asText())||!targetId(before).equals(run.targetId()))throw new IllegalStateException("Original owning durable monthly submission required");submission=context;
    }
    @Override public void send(List<MonthReceipt> rows){
        if(rows==null||rows.size()!=1||submission==null)throw new IllegalArgumentException("One durable owning monthly receipt per runner submission required");preflight();var receipt=rows.getFirst();if(receipt.from().isBefore(from)||receipt.to().isAfter(to)||!sha(new String(CODEC.canonicalBytes(receipt),StandardCharsets.UTF_8)).equals(submission.sourceFingerprint()))throw new IllegalStateException("Submitted exact monthly source receipt differs");
        if(!staged){
            jdbc.execute("CREATE TABLE "+quote(stage)+" (trade_date TIMESTAMP,ts_code SYMBOL,open DOUBLE,high DOUBLE,low DOUBLE,close DOUBLE,vol DOUBLE,amount DOUBLE,adj_factor DOUBLE,up_limit DOUBLE,down_limit DOUBLE,is_suspended INT,is_st INT) TIMESTAMP(trade_date) PARTITION BY DAY WAL DEDUP UPSERT KEYS(trade_date,ts_code)");staged=true;
        }
        // One bounded server INSERT per already proven month avoids a single full-history JOIN timeout.
        jdbc.execute("INSERT INTO "+quote(stage)+" ("+String.join(",",COLUMNS)+") SELECT "+physicalColumns()+" FROM ("+sourceWindowSql(receipt.from(),receipt.to())+")");awaitBase(stage);submission=null;
    }
    @Override public List<MonthReceipt> readback(List<LocalDate> keys){if(!staged||keys.size()!=1)throw new IllegalArgumentException("Complete receipt key required");LocalDate lo=keys.getFirst();LocalDate hi=lo.plusMonths(1).withDayOfMonth(1).minusDays(1);if(hi.isAfter(to))hi=to;return List.of(digest(stage,lo,hi,true));}
    @Override public boolean walSettled(){if(!staged)return false;identity(stage);return true;}
    private void awaitBase(String table){long stop=System.nanoTime()+Duration.ofMinutes(20).toNanos();while(true){check();try{identity(table);return;}catch(IllegalStateException waiting){if(System.nanoTime()>=stop)throw waiting;}sleep();}}
    private static void sleep(){try{Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Backtest wait interrupted");}}
    /** Every requested source value and key is read back before the formal table is touched. */
    public void verifyReplacement(List<MonthReceipt> oldMonths,List<MonthReceipt> sourceMonths){
        requireSame(before);var stageBefore=identity(stage);
        for(var expected:sourceMonths)if(!expected.equals(digest(stage,expected.from(),expected.to(),true)))throw new IllegalStateException("Backtest source/stage month differs");
        long expected=sourceMonths.stream().mapToLong(MonthReceipt::rows).sum();if(stageBefore.rows()!=expected||!stageBefore.equals(identity(stage)))throw new IllegalStateException("Backtest complete source stage count or version differs");
    }
    public NativeState nativeState(String name){
        if(!nativeName(name))throw new IllegalArgumentException("Owned native generation required");
        var rows=jdbc.queryForList("SELECT m.view_status,m.base_table_name,m.view_sql,m.refresh_base_table_txn,m.base_table_txn,m.last_refresh_start_timestamp,m.last_refresh_finish_timestamp,v.id,v.directoryName,coalesce(v.table_txn,vw.writerTxn) AS table_txn,v.partitionBy,v.matView,v.dedup,"
                +"b.id AS base_id,bw.writerTxn AS base_writer,bw.sequencerTxn AS base_seq,bw.suspended AS base_suspended,bw.bufferedTxnSize AS base_buffered,"
                +"vw.writerTxn AS mv_writer,vw.sequencerTxn AS mv_seq,vw.suspended AS mv_suspended,vw.bufferedTxnSize AS mv_buffered "
                +"FROM materialized_views() m JOIN tables() v ON v.table_name=m.view_name JOIN tables() b ON b.table_name=m.base_table_name "
                +"JOIN wal_tables() bw ON bw.name=b.table_name JOIN wal_tables() vw ON vw.name=v.table_name WHERE m.view_name=?",name);
        if(rows.size()!=1)throw new IllegalStateException("Native backtest generation is absent");var r=rows.getFirst();
        if(!"valid".equals(r.get("view_status"))||!BASE.equals(r.get("base_table_name"))||!normalized(MV_SQL).equals(normalized(Objects.toString(r.get("view_sql"))))||!"MONTH".equals(r.get("partitionBy"))||!Boolean.TRUE.equals(r.get("matView"))||!Boolean.FALSE.equals(r.get("dedup"))
                ||number(r,"refresh_base_table_txn")!=number(r,"base_table_txn")||number(r,"base_table_txn")!=number(r,"base_seq")||number(r,"base_writer")!=number(r,"base_seq")||number(r,"mv_writer")!=number(r,"mv_seq")||number(r,"base_buffered")!=0||number(r,"mv_buffered")!=0||!Boolean.FALSE.equals(r.get("base_suspended"))||!Boolean.FALSE.equals(r.get("mv_suspended")))throw new IllegalStateException("Native backtest generation invalid, stale, changed or WAL unsettled");
        requireSchema(name,true);return new NativeState(name,number(r,"id"),Objects.toString(r.get("directoryName")),BASE,number(r,"base_id"),number(r,"base_table_txn"),number(r,"refresh_base_table_txn"),number(r,"table_txn"),number(r,"mv_seq"),Objects.toString(r.get("view_sql")),Objects.toString(r.get("last_refresh_start_timestamp"),null),Objects.toString(r.get("last_refresh_finish_timestamp"),null));
    }
    public void installAndVerifyNative(String name,List<MonthReceipt> fullBase){
        installAndVerifyNative(name,fullBase,true);
    }
    public void installAndVerifyNative(String name,List<MonthReceipt> fullBase,boolean fullRefresh){
        if(!nativeName(name))throw new IllegalArgumentException("Fixed native backtest name required");var existing=jdbc.queryForList("SELECT table_name FROM tables() WHERE table_name=?",name);String priorFinished=null;long priorTxn=-1;boolean requireNewFull=fullRefresh||existing.isEmpty();
        if(existing.isEmpty()){
            jdbc.execute("CREATE MATERIALIZED VIEW "+quote(name)+" WITH BASE backtest_daily REFRESH MANUAL DEFERRED AS ("+MV_SQL+") PARTITION BY MONTH");
            jdbc.execute("REFRESH MATERIALIZED VIEW "+quote(name)+" FULL");
        } else {
            var metadata=jdbc.queryForList("SELECT view_sql,base_table_name,last_refresh_finish_timestamp FROM materialized_views() WHERE view_name=?",name);
            if(metadata.size()!=1||!BASE.equals(metadata.getFirst().get("base_table_name"))||!normalized(MV_SQL).equals(normalized(Objects.toString(metadata.getFirst().get("view_sql")))))throw new IllegalStateException("Existing native backtest definition differs");
            priorFinished=Objects.toString(metadata.getFirst().get("last_refresh_finish_timestamp"),null);priorTxn=Objects.requireNonNull(jdbc.queryForObject("SELECT coalesce(t.table_txn,w.writerTxn) FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name=?",Long.class,name));
            // Partition deletion requires FULL; pure append can preserve the valid native checkpoint.
            jdbc.execute("REFRESH MATERIALIZED VIEW "+quote(name)+(fullRefresh?" FULL":" INCREMENTAL"));
        }
        long stop=System.nanoTime()+Duration.ofHours(6).toNanos();NativeState beforeNative;
        while(true){check();try{beforeNative=nativeState(name);if(requireNewFull&&(beforeNative.refreshFinished()==null||Objects.equals(priorFinished,beforeNative.refreshFinished())&&priorTxn==beforeNative.mvTxn()))throw new IllegalStateException("Submitted asynchronous FULL has no new completion receipt");break;}catch(IllegalStateException pending){if(System.nanoTime()>=stop)throw pending;}sleep();}
        var baseBefore=identity(BASE);if(beforeNative.baseId()!=baseBefore.id())throw new IllegalStateException("Native generation binds a different base physical identity");
        long total=0;for(var month:fullBase){if(!month.equals(digest(name,month.from(),month.to(),false)))throw new IllegalStateException("Native singleton aggregate differs from every original field or key: "+month.from());total+=month.rows();}
        if(total>baseBefore.rows())throw new IllegalStateException("Native window count exceeds complete base");
        var count=jdbc.queryForObject("SELECT count() FROM "+quote(name),Long.class);if(count==null||count!=baseBefore.rows()||!dateGrid(BASE).equals(dateGrid(name))||!beforeNative.equals(nativeState(name))||!baseBefore.equals(identity(BASE)))throw new IllegalStateException("Native complete history/date keys changed during exact readback");
    }
    public String targetKind(){var list=jdbc.queryForList("SELECT table_type FROM tables() WHERE table_name=?",MV);if(list.isEmpty())return "ABSENT";if(list.size()!=1)throw new IllegalStateException("Duplicate public backtest object");return Objects.toString(list.getFirst().get("table_type"));}
    public long targetObjectId(){var list=jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",MV);if(list.size()!=1)throw new IllegalStateException("Exact public backtest identity absent");return number(list.getFirst(),"id");}
    public String targetSql(){String kind=targetKind();if(kind.equals("ABSENT"))return "";var list=jdbc.queryForList("SELECT view_sql FROM "+(kind.equals("V")?"views()":"materialized_views()")+" WHERE view_name=?",MV);if(list.size()!=1)throw new IllegalStateException("Public backtest object is not view/MV");return Objects.toString(list.getFirst().get("view_sql"));}
    public void removeOldDefinitionForBootstrap(String expectedKind,String expectedSql,String nativeBackup){
        String kind=targetKind();if(kind.equals("ABSENT"))return;
        if(!kind.equals(expectedKind)||!normalized(targetSql()).equals(normalized(expectedSql)))throw new IllegalStateException("Old public object changed before native cutover");
        if(kind.equals("V"))jdbc.execute("DROP VIEW v_backtest_daily");
        else if(kind.equals("M")){
            requireName(nativeBackup,"java_backtest_daily_native_backup_");
            long oldId=targetObjectId();var scope=jdbc.queryForMap("SELECT count() AS n,cast(min(trade_date) AS LONG) AS lo,cast(max(trade_date) AS LONG) AS hi FROM v_backtest_daily");long count=number(scope,"n");if(count<0||count>MAX_TOTAL_ROWS)throw new IllegalStateException("Retained old native exceeds business row budget");var months=new ArrayList<MonthReceipt>();if(count>0){LocalDate end=date(scope.get("hi"));for(LocalDate lo=date(scope.get("lo")).withDayOfMonth(1);!lo.isAfter(end);lo=lo.plusMonths(1))months.add(digest(MV,lo,lo.plusMonths(1).minusDays(1),false));}if(months.stream().mapToLong(MonthReceipt::rows).sum()!=count)throw new IllegalStateException("Old native history count differs");
            if(jdbc.queryForList("SELECT table_name FROM tables() WHERE table_name=?",nativeBackup).isEmpty())jdbc.execute("CREATE TABLE "+quote(nativeBackup)+" AS (SELECT "+physicalColumns()+" FROM v_backtest_daily) TIMESTAMP(trade_date) PARTITION BY DAY WAL");
            jdbc.execute("ALTER TABLE "+quote(nativeBackup)+" DEDUP ENABLE UPSERT KEYS(trade_date,ts_code)");awaitBase(nativeBackup);
            if(identity(nativeBackup).rows()!=count)throw new IllegalStateException("Old native durable backup has different count");for(var month:months)if(!month.equals(digest(nativeBackup,month.from(),month.to(),true)))throw new IllegalStateException("Old native durable backup differs from every original field/key");if(targetObjectId()!=oldId)throw new IllegalStateException("Old native identity changed before DROP");
            jdbc.execute("DROP MATERIALIZED VIEW v_backtest_daily");
        }else throw new IllegalStateException("Unexpected public backtest object kind");
    }
    public void createWindowBackup(String backup){
        requireName(backup,"java_backtest_daily_backup_");requireSame(before);
        jdbc.execute("CREATE TABLE "+quote(backup)+" AS (SELECT "+String.join(",",COLUMNS)+" FROM backtest_daily WHERE "+datePredicate(from,to)+") TIMESTAMP(trade_date) PARTITION BY DAY WAL");
        jdbc.execute("ALTER TABLE "+quote(backup)+" DEDUP ENABLE UPSERT KEYS(trade_date,ts_code)");awaitBase(backup);
    }
    public List<Map<String,Object>> outsidePartitions(LocalDate lo,LocalDate hi){
        var all=jdbc.queryForList("SELECT name,numRows,cast(minTimestamp AS LONG) AS min_us,cast(maxTimestamp AS LONG) AS max_us,seqTxn FROM table_partitions('backtest_daily') ORDER BY name LIMIT 36601");
        if(all.size()>36600)throw new IllegalStateException("Complete base partition budget exceeded");return all.stream().filter(r->{LocalDate d=LocalDate.parse(Objects.toString(r.get("name")));return d.isBefore(lo)||d.isAfter(hi);}).toList();
    }
    public void replaceWindowInPlace(String sourceWindow,LocalDate lo,LocalDate hi){
        requireName(sourceWindow,"java_backtest_daily_stage_");var parts=jdbc.queryForList("SELECT name FROM table_partitions('backtest_daily') ORDER BY name LIMIT 36601");
        for(var r:parts){LocalDate d=LocalDate.parse(Objects.toString(r.get("name")));if(!d.isBefore(lo)&&!d.isAfter(hi)){check();jdbc.execute("ALTER TABLE backtest_daily DROP PARTITION LIST '"+d+"'");}}
        check();jdbc.execute("INSERT INTO backtest_daily ("+String.join(",",COLUMNS)+") SELECT "+String.join(",",COLUMNS)+" FROM "+quote(sourceWindow));awaitBase(BASE);
    }
    public Map<LocalDate,Long> dateGrid(String table){
        DatasetDefinition.identifier(table);var days=new TreeMap<LocalDate,Long>();jdbc.query("SELECT cast(trade_date AS LONG) AS date_us,count() AS n,count_distinct(ts_code) AS unique_n FROM "+quote(table)+" GROUP BY trade_date ORDER BY trade_date LIMIT 36601",(RowCallbackHandler)rs->{LocalDate d=date(rs.getLong("date_us"));long n=rs.getLong("n");if(n!=rs.getLong("unique_n")||days.putIfAbsent(d,n)!=null)throw new IllegalStateException("Backtest historical date/business key is not unique");});if(days.size()>36600)throw new IllegalStateException("Backtest date budget exceeded");return Map.copyOf(days);
    }
    public void rename(String oldName,String newName){DatasetDefinition.identifier(oldName);DatasetDefinition.identifier(newName);jdbc.execute("RENAME TABLE "+quote(oldName)+" TO "+quote(newName));awaitBase(newName);}
    public Optional<Identity> optionalIdentity(String name){DatasetDefinition.identifier(name);if(jdbc.queryForList("SELECT table_name FROM tables() WHERE table_name=?",name).isEmpty())return Optional.empty();return Optional.of(identity(name));}
}
