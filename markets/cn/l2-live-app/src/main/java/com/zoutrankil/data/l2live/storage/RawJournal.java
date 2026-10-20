package com.zoutrankil.data.l2live.storage;

import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteState;
import com.zoutrankil.data.l2live.domain.RawCapture;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 原始报文字节和冻结后的归一化事务均持久化，采集器或进程失败后可恢复。 */
public final class RawJournal implements AutoCloseable {
    public static final int MAX_BATCH_ROWS = 200;
    private static final int APPLICATION_ID = 0x4c324c56;
    private static final long DEFAULT_RAW_BYTES = 8L * 1024 * 1024 * 1024;
    private final Path database;
    private final long maxRawBytes;
    private final ParquetBatchStore parquet;
    private volatile boolean closed;
    private final Object captureLock=new Object();
    // 接收和提交使用不同连接，但 SQLite 只有一个写入者。公平排队可避免
    // 连续原始报文抢占批次提交；Parquet 编码放在这把写锁之外。
    private final java.util.concurrent.locks.ReentrantLock writeLock=new java.util.concurrent.locks.ReentrantLock(true);
    private record WriteLease(java.util.concurrent.locks.ReentrantLock lock) implements AutoCloseable {
        @Override public void close(){lock.unlock();}
    }
    private WriteLease writer(){writeLock.lock();return new WriteLease(writeLock);}
    private Connection captureConnection;
    private java.sql.PreparedStatement captureInsert,captureTotals;


    public record StoredBatch(String id, List<L2CleanEvent> events, List<L2MinuteState> states,
            Map<String, Object> accumulatorState) {
        public StoredBatch { events=List.copyOf(events); states=List.copyOf(states); accumulatorState=freezeState(accumulatorState); }
    }
    record FrozenData(List<L2CleanEvent> events, List<L2MinuteState> states, Map<String, Object> accumulatorState) {
        FrozenData { events=List.copyOf(events); states=List.copyOf(states); accumulatorState=freezeState(accumulatorState); }
    }
    // Parquet 回读结果会在同一提交内复用，检查点必须连同嵌套容器一起冻结。
    private static Map<String,Object> freezeState(Map<String,Object> state) {
        var result=new java.util.LinkedHashMap<String,Object>();
        state.forEach((key,value)->result.put(key,freezeStateValue(value)));
        return java.util.Collections.unmodifiableMap(result);
    }
    private static Object freezeStateValue(Object value) {
        if(value instanceof Map<?,?> map) {
            var result=new java.util.LinkedHashMap<String,Object>();
            map.forEach((key,item)->{
                if(!(key instanceof String text))throw new IllegalArgumentException("String checkpoint key required");
                result.put(text,freezeStateValue(item));
            });
            return java.util.Collections.unmodifiableMap(result);
        }
        if(value instanceof List<?> list) {
            var result=new ArrayList<Object>(list.size());
            list.forEach(item->result.add(freezeStateValue(item)));
            return java.util.Collections.unmodifiableList(result);
        }
        return value;
    }
    record Snapshot(Map<String, Object> accumulatorState) {}
    record Outbox(String batchId, String encodingSha256, String eventsIdentity,
            String statesIdentity, String delivery) {}
    private record BatchReference(String id,String payloadSha,long payloadLength,boolean active,boolean verified,
            String createdAt,String completedAt,ParquetBatchStore.Receipt files,Outbox outbox) {}
    private record VerifiedActive(StoredBatch batch,BatchReference reference) {}
    private record CaptureReference(String id,long sequence,String session,String topic,String payloadSha,String envelopeSha,
            String received,String date,int qos,boolean retained,boolean duplicate,boolean authorized,long bytes,boolean completed) {}
    private static final String CAPTURE_METADATA="capture_id,sequence,session_id,topic,payload_sha256,envelope_sha256,recv_ts,capture_date,qos,retained,duplicate,subscription_authorized,length(payload) AS payload_length,completed";
    public record Status(Path database, long captures, long pending, long completed, long rawBytes,
            String preparedBatchId) {}
    public record ExportEvidence(Path path, long captures, long lastSequence, long bytes, String sha256) {}

    public RawJournal(Path root) { this(root, DEFAULT_RAW_BYTES); }
    public RawJournal(Path root, long maxRawBytes) {
        Objects.requireNonNull(root);
        if(maxRawBytes < RawCapture.MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Positive raw journal byte quota required");
        this.maxRawBytes=maxRawBytes;
        Path absolute=root.toAbsolutePath().normalize(); this.database=absolute.resolve("raw-journal.sqlite3");
        try {
            Files.createDirectories(absolute);
            if(Files.isSymbolicLink(absolute) || Files.isSymbolicLink(database))
                throw new IllegalArgumentException("Live raw journal must not use linked paths");
            if(Files.exists(database,LinkOption.NOFOLLOW_LINKS)) {
                if(!Files.isRegularFile(database,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Raw journal is not a file");
                try(var c=DriverManager.getConnection("jdbc:sqlite:"+database.toUri().toASCIIString()+"?mode=ro")) {
                    try(var s=c.createStatement();var r=s.executeQuery("PRAGMA application_id")) {
                        if(!r.next() || r.getInt(1)!=APPLICATION_ID) throw new IllegalArgumentException("Existing file is not a live raw journal");
                    }
                    try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")) {
                        if(!r.next() || !java.util.Set.of(2,3,4).contains(r.getInt(1))) throw new IllegalArgumentException("Unsupported live raw journal schema; subscription proof requires schema 2, 3 or 4");
                    }
                }
            }
            initialize();
            try(var c=open()){DailyParquetArchive.recoverCleanup(c,absolute.resolve("parquet"));}
            parquet=new ParquetBatchStore(absolute.resolve("parquet"),maxRawBytes);
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw new IllegalStateException("Cannot initialize live raw journal",e); }
    }

    public Path databasePath() { return database; }
    private Connection open() throws Exception {
        if(closed) throw new IllegalStateException("Raw journal is closed");
        var c=DriverManager.getConnection("jdbc:sqlite:"+database);
        try(var s=c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA synchronous=FULL");
            long pageSize;
            try(var rows=s.executeQuery("PRAGMA page_size")) {rows.next();pageSize=rows.getLong(1);}
            long maxPages=maxRawBytes/pageSize;
            try(var rows=s.executeQuery("PRAGMA max_page_count="+maxPages)) {
                if(!rows.next()||rows.getLong(1)>maxPages)throw new IllegalStateException("Existing raw/outbox journal exceeds its configured page quota");
            }
        } catch(Exception e) {c.close();throw e;}
        return c;
    }
    private void initialize() throws Exception {
        try(var c=open();var s=c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS raw_capture(sequence INTEGER PRIMARY KEY AUTOINCREMENT,capture_id TEXT NOT NULL UNIQUE,session_id TEXT NOT NULL,topic TEXT NOT NULL,payload BLOB NOT NULL,payload_sha256 TEXT NOT NULL,envelope_sha256 TEXT NOT NULL,recv_ts TEXT NOT NULL,capture_date TEXT NOT NULL,qos INTEGER NOT NULL,retained INTEGER NOT NULL,duplicate INTEGER NOT NULL,subscription_authorized INTEGER NOT NULL CHECK(subscription_authorized IN (0,1)),completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1)))");
            s.execute("CREATE INDEX IF NOT EXISTS raw_pending_sequence ON raw_capture(completed,sequence)");
            s.execute("CREATE TABLE IF NOT EXISTS journal_totals(singleton INTEGER PRIMARY KEY CHECK(singleton=1),raw_bytes INTEGER NOT NULL,next_sequence INTEGER NOT NULL)");
            s.execute("INSERT INTO journal_totals(singleton,raw_bytes,next_sequence) SELECT 1,coalesce(sum(length(payload)),0),coalesce(max(sequence),0) FROM raw_capture WHERE NOT EXISTS(SELECT 1 FROM journal_totals) ON CONFLICT(singleton) DO NOTHING");
            var totalColumns=new HashSet<String>();try(var r=s.executeQuery("PRAGMA table_info(journal_totals)")){while(r.next())totalColumns.add(r.getString("name"));}
            if(!totalColumns.contains("captures"))s.execute("ALTER TABLE journal_totals ADD COLUMN captures INTEGER NOT NULL DEFAULT 0");
            if(!totalColumns.contains("completed"))s.execute("ALTER TABLE journal_totals ADD COLUMN completed INTEGER NOT NULL DEFAULT 0");
            // 只在打开日志时核对一次；运行中的状态查询不再扫描整日 payload。
            s.execute("UPDATE journal_totals SET captures=(SELECT count(*) FROM raw_capture),completed=(SELECT count(*) FROM raw_capture WHERE completed=1) WHERE singleton=1");
            s.execute("CREATE TABLE IF NOT EXISTS prepared_batch(batch_id TEXT PRIMARY KEY,payload BLOB NOT NULL,payload_sha256 TEXT NOT NULL,active INTEGER NOT NULL CHECK(active IN (0,1)),write_verified INTEGER NOT NULL DEFAULT 0 CHECK(write_verified IN (0,1)),created_at TEXT NOT NULL,completed_at TEXT)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_active_live_batch ON prepared_batch(active) WHERE active=1");
            s.execute("CREATE TABLE IF NOT EXISTS committed_accumulator(singleton INTEGER PRIMARY KEY CHECK(singleton=1),payload BLOB NOT NULL,payload_sha256 TEXT NOT NULL,batch_id TEXT NOT NULL REFERENCES prepared_batch(batch_id))");
            s.execute("CREATE TABLE IF NOT EXISTS live_outbox(batch_id TEXT PRIMARY KEY REFERENCES prepared_batch(batch_id),encoding_sha256 TEXT NOT NULL,events_identity TEXT NOT NULL,states_identity TEXT NOT NULL,delivery TEXT NOT NULL CHECK(delivery IN ('INTENT','UNKNOWN','ACKNOWLEDGED','VERIFIED')))");
            s.execute("CREATE TABLE IF NOT EXISTS parquet_batches(batch_id TEXT PRIMARY KEY REFERENCES prepared_batch(batch_id),raw_sha256 TEXT NOT NULL,events_sha256 TEXT NOT NULL,minutes_sha256 TEXT NOT NULL,checkpoint_sha256 TEXT NOT NULL)");
            DailyParquetArchive.initialize(c);
            s.execute("PRAGMA application_id="+APPLICATION_ID); s.execute("PRAGMA user_version=4");
        }
    }

    public RawCapture append(String topic, byte[] payload, Instant recvTs, String sessionId,
            int qos, boolean retained, boolean duplicate) {
        return append(topic,payload,recvTs,sessionId,qos,retained,duplicate,false);
    }
    public RawCapture append(String topic, byte[] payload, Instant recvTs, String sessionId,
            int qos, boolean retained, boolean duplicate, boolean subscriptionAuthorized) {
        Objects.requireNonNull(payload); Objects.requireNonNull(recvTs);
        byte[] frozen=payload.clone();
        var proposed=new RawCapture("cap_"+UUID.randomUUID().toString().replace("-",""),0,sessionId,topic,
                Base64.getEncoder().encodeToString(frozen),recvTs,recvTs.atZone(RawCapture.SHANGHAI).toLocalDate(),qos,retained,duplicate,subscriptionAuthorized);
        return appendBatch(List.of(proposed)).getFirst();
    }

    /** 单条接口仍同步返回；运行时可将有序报文合并成一次 FULL 提交，提交前不报告已保存。 */
    public List<RawCapture> appendBatch(List<RawCapture> proposed) {
        proposed=List.copyOf(proposed);
        if(proposed.isEmpty()||proposed.size()>MAX_BATCH_ROWS)throw new IllegalArgumentException("Bounded raw capture batch required");
        var payloads=proposed.stream().map(RawCapture::payloadBytes).toList();
        long bytes=payloads.stream().mapToLong(payload->payload.length).sum();
        if(bytes>4L*1024*1024)throw new IllegalArgumentException("Raw capture batch exceeds byte budget");
        synchronized(captureLock) {
            try (var write=writer()) {
                if(closed)throw new IllegalStateException("Raw journal is closed");
                if(Files.getFileStore(database).getUsableSpace()<64L*1024*1024+bytes*4L)throw new IllegalStateException("L2_STORAGE_HEADROOM：磁盘余量不足，停止接收");
                // 只复用原始接收连接；Parquet 编码仍可与后续报文落盘并行。
                if(captureConnection==null) {
                    var connection=open();
                    try {
                        var totals=connection.prepareStatement("UPDATE journal_totals SET raw_bytes=raw_bytes+?,next_sequence=next_sequence+?,captures=captures+? WHERE singleton=1 AND raw_bytes<=?");
                        var insert=connection.prepareStatement("INSERT INTO raw_capture(sequence,capture_id,session_id,topic,payload,payload_sha256,envelope_sha256,recv_ts,capture_date,qos,retained,duplicate,subscription_authorized) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)");
                        captureTotals=totals;captureInsert=insert;captureConnection=connection;
                    } catch(Exception failure) {
                        try {connection.close();}catch(Exception closeFailure){failure.addSuppressed(closeFailure);}
                        throw failure;
                    }
                }
                var c=captureConnection;
                c.setAutoCommit(false);
                try {
                    {
                        var s=captureTotals;
                        s.setLong(1,bytes);s.setInt(2,proposed.size());s.setInt(3,proposed.size());s.setLong(4,maxRawBytes-bytes);
                        if(s.executeUpdate()!=1) throw new IllegalStateException("Raw journal quota reached; receive must stop before acknowledgement");
                    }
                    long sequence;
                    try(var s=c.createStatement();var r=s.executeQuery("SELECT next_sequence FROM journal_totals WHERE singleton=1")) { r.next();sequence=r.getLong(1); }
                    sequence-=proposed.size()-1;
                    var results=new ArrayList<RawCapture>();
                    for(int index=0;index<proposed.size();index++) {
                        var input=proposed.get(index);var frozen=payloads.get(index);
                        var result=new RawCapture(input.captureId(),sequence++,input.sessionId(),input.topic(),input.payloadBase64(),input.recvTs(),input.captureDate(),input.qos(),input.retained(),input.duplicate(),input.subscriptionAuthorized());
                        var s=captureInsert;
                        s.setLong(1,result.sequence());s.setString(2,result.captureId());s.setString(3,result.sessionId());s.setString(4,result.topic());s.setBytes(5,frozen);
                        s.setString(6,LiveStorageJson.sha256(frozen));s.setString(7,LiveStorageJson.sha256(LiveStorageJson.bytes(result)));
                        s.setString(8,result.recvTs().toString());s.setString(9,result.captureDate().toString());s.setInt(10,result.qos());s.setBoolean(11,result.retained());s.setBoolean(12,result.duplicate());s.setBoolean(13,result.subscriptionAuthorized());s.executeUpdate();
                        results.add(result);
                    }
                    c.commit();
                    return List.copyOf(results);
                } catch(Exception e) { rollback(c,e);throw e; }
            } catch(RuntimeException e) { throw e; }
            catch(Exception e) { throw failure("Cannot persist raw capture",e); }
        }
    }

    public synchronized List<RawCapture> pending(int limit) {
        if(limit<1 || limit>MAX_BATCH_ROWS) throw new IllegalArgumentException("At most 200 pending captures per read");
        try(var c=open();var s=c.prepareStatement("SELECT * FROM raw_capture WHERE completed=0 ORDER BY sequence LIMIT ?")) {
            s.setInt(1,limit);var result=new ArrayList<RawCapture>();long bytes=0;
            try(var r=s.executeQuery()) {
                while(r.next()) {
                    var capture=capture(r);int size=capture.payloadBytes().length;
                    if(!result.isEmpty() && bytes+size>4L*1024*1024) break;
                    result.add(capture);bytes+=size;
                }
            }
            return List.copyOf(result);
        } catch(Exception e) { throw failure("Cannot read pending raw captures",e); }
    }

    public synchronized Optional<StoredBatch> preparedBatch() {
        try(var c=open()) { return active(c); }
        catch(Exception e) { throw failure("Cannot read prepared live batch",e); }
    }
    /** 同时冻结事件、分钟观察及累计状态；后续提交只能使用这份内容及其摘要。 */
    public synchronized StoredBatch prepareBatch(List<L2CleanEvent> events, List<L2MinuteState> states,
            Map<String,Object> accumulatorState) {
        if(events==null || events.isEmpty() || events.size()>MAX_BATCH_ROWS || states==null || states.size()>MAX_BATCH_ROWS)
            throw new IllegalArgumentException("One bounded nonempty live event batch is required");
        var data=new FrozenData(List.copyOf(events),List.copyOf(states),Objects.requireNonNull(accumulatorState));
        String hash=LiveValueIdentity.batch(data), id="live_pq_"+hash;
        try(var c=open()) {
            var previous=active(c);
            if(previous.isPresent()) {
                if(!LiveValueIdentity.batch(new FrozenData(previous.get().events(),previous.get().states(),previous.get().accumulatorState())).equals(hash))
                    throw new IllegalStateException("A different frozen live batch already owns the checkpoint");
                return previous.get();
            }
            var captures=validateCaptures(c,events);
            // 文件编码不持有 SQLite 写锁，接收线程可继续追加原始报文。
            var receipt=parquet.freeze(id,data);
            var readback=parquet.read(id,receipt);
            if(!hash.equals(LiveValueIdentity.batch(readback)))throw new IllegalStateException("Frozen Parquet logical values changed");
            var frozen=new StoredBatch(id,readback.events(),readback.states(),readback.accumulatorState());
            try(var write=writer()) {
                c.setAutoCommit(false);
                try {
                    lockJournal(c);
                    if(activeId(c).isPresent())throw new IllegalStateException("Another prepared batch owns the checkpoint");
                    // 原文和完整 envelope 已在锁外核验；事务内只比对同一份不可变原始引用。
                    requireCapturesUnchanged(c,captures);
                    try(var q=c.prepareStatement("INSERT INTO prepared_batch(batch_id,payload,payload_sha256,active,created_at) VALUES(?,X'',?,1,?)")) {
                        q.setString(1,id);q.setString(2,hash);q.setString(3,Instant.now().toString());q.executeUpdate();
                    }
                    saveReceipt(c,id,receipt);c.commit();
                } catch(Exception e) { rollback(c,e);throw e; }
            }
            return frozen;
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw failure("Cannot freeze Parquet normalization batch",e); }
    }
    private static List<CaptureReference> validateCaptures(Connection c,List<L2CleanEvent> events) throws Exception {
        var ids=new HashSet<String>();var references=new ArrayList<CaptureReference>();
        try(var q=c.prepareStatement("SELECT *,length(payload) AS payload_length FROM raw_capture WHERE capture_id=?")) {
            for(var event:events) {
                var raw=event.capture();if(!ids.add(raw.captureId()))throw new IllegalArgumentException("Duplicate capture in prepared batch");
                q.setString(1,raw.captureId());try(var r=q.executeQuery()) {
                    if(!r.next()||r.getBoolean("completed")||!capture(r).equals(raw))throw new IllegalArgumentException("Prepared event must bind an exact pending raw capture");
                    references.add(captureReference(r));
                }
            }
        }return List.copyOf(references);
    }
    private static CaptureReference captureReference(ResultSet r) throws Exception {
        return new CaptureReference(r.getString("capture_id"),r.getLong("sequence"),r.getString("session_id"),r.getString("topic"),
                r.getString("payload_sha256"),r.getString("envelope_sha256"),r.getString("recv_ts"),r.getString("capture_date"),r.getInt("qos"),
                r.getBoolean("retained"),r.getBoolean("duplicate"),r.getBoolean("subscription_authorized"),r.getLong("payload_length"),r.getBoolean("completed"));
    }
    private static void requireCapturesUnchanged(Connection c,List<CaptureReference> expected) throws Exception {
        try(var query=c.prepareStatement("SELECT "+CAPTURE_METADATA+" FROM raw_capture WHERE capture_id=?")) {
            for(var reference:expected) {
                query.setString(1,reference.id());try(var rows=query.executeQuery()) {
                    if(!rows.next()||!reference.equals(captureReference(rows)))throw new IllegalStateException("Raw capture reference changed before freeze commit");
                }
            }
        }
    }

    public synchronized Map<String,Object> committedAccumulatorState() {
        try(var c=open();var s=c.createStatement();var r=s.executeQuery("SELECT payload,payload_sha256,batch_id FROM committed_accumulator WHERE singleton=1")) {
            if(!r.next())return Map.of();byte[] bytes=r.getBytes(1);
            if(bytes.length>0){verifyHash(bytes,r.getString(2));return LiveStorageJson.read(bytes,Snapshot.class).accumulatorState();}
            String id=r.getString(3);var receipt=receipt(c,id);
            if(!receipt.checkpoint().equals(r.getString(2)))throw new IllegalStateException("Parquet checkpoint reference changed");
            return parquet.read(id,receipt).accumulatorState();
        } catch(Exception e) { throw failure("Cannot read committed accumulator checkpoint",e); }
    }

    /**
     * 在同一事务中标记原始报文完成、保存累计检查点并关闭批次。
     * 若中途失败，重启仍能读取原冻结批次，不会出现报文已完成而检查点尚未保存的状态。
     */
    public synchronized void completeBatch(String id) {
        try(var c=open()) {
            var active=verifiedActive(c);
            StoredBatch batch=active.map(VerifiedActive::batch).orElse(null);
            BatchReference expected=active.map(VerifiedActive::reference).orElse(null);
            if(batch!=null) {
                if(!batch.id().equals(id))throw new IllegalStateException("Prepared batch identity mismatch");
                parquet.verifyPublished(id,expected.files());
            }
            try(var write=writer()) {
                c.setAutoCommit(false);
                try {
                    lockJournal(c);
                    if(batch==null) {
                        if(activeId(c).isPresent())throw new IllegalStateException("Active batch changed before completion");
                        try(var q=c.prepareStatement("SELECT count(*) FROM prepared_batch p JOIN live_outbox o ON o.batch_id=p.batch_id WHERE p.batch_id=? AND p.active=0 AND p.write_verified=1 AND p.completed_at IS NOT NULL AND o.delivery='VERIFIED'")) {
                            q.setString(1,id);try(var r=q.executeQuery()) {
                                r.next();if(r.getInt(1)!=1)throw new IllegalStateException("No active or previously verified completed batch");
                            }
                        }
                        c.commit();return;
                    }
                    requireReferenceUnchanged(c,expected);requireVerified(c,id);
                    try(var s=c.prepareStatement("UPDATE raw_capture SET completed=1 WHERE capture_id=? AND completed=0")) {
                        for(var event:batch.events()) {
                            s.setString(1,event.capture().captureId());
                            if(s.executeUpdate()!=1) throw new IllegalStateException("Prepared capture checkpoint changed before commit");
                        }
                    }
                    try(var s=c.prepareStatement("UPDATE journal_totals SET completed=completed+? WHERE singleton=1")){s.setInt(1,batch.events().size());if(s.executeUpdate()!=1)throw new IllegalStateException("原始日志统计缺失");}
                    try(var s=c.prepareStatement("INSERT INTO committed_accumulator(singleton,payload,payload_sha256,batch_id) VALUES(1,?,?,?) ON CONFLICT(singleton) DO UPDATE SET payload=excluded.payload,payload_sha256=excluded.payload_sha256,batch_id=excluded.batch_id")) {
                        s.setBytes(1,new byte[0]);s.setString(2,expected.files().checkpoint());s.setString(3,id);s.executeUpdate();
                    }
                    try(var s=c.prepareStatement("UPDATE prepared_batch SET active=0,completed_at=? WHERE batch_id=? AND active=1")) {
                        s.setString(1,Instant.now().toString());s.setString(2,id);
                        if(s.executeUpdate()!=1) throw new IllegalStateException("Prepared batch completion conflict");
                    }
                    c.commit();
                } catch(Exception e) { rollback(c,e);throw e; }
            }
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw failure("Cannot commit verified live checkpoint",e); }
    }
    /** 兼容入口；只有已核验的冻结批次，才能将对应原始采集标记为完成。 */
    public synchronized void complete(String captureId) {
        var batch=preparedBatch().orElseThrow(()->new IllegalStateException("Capture completion requires a verified prepared batch"));
        if(batch.events().size()!=1 || !batch.events().getFirst().capture().captureId().equals(captureId))
            throw new IllegalStateException("Complete multi-capture transactions with completeBatch");
        completeBatch(batch.id());
    }

    synchronized Outbox reserveOutbox(String batchId,String encodingSha,String eventsIdentity,String statesIdentity) {
        for(String value:List.of(encodingSha,eventsIdentity,statesIdentity))
            if(!value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Complete frozen outbox SHA-256 identities required");
        try(var c=open()) {
            var active=verifiedActive(c).filter(value->value.batch().id().equals(batchId))
                    .orElseThrow(()->new IllegalStateException("Outbox must bind the active prepared batch"));
            var expected=active.reference();
            try(var write=writer()) {
                c.setAutoCommit(false);
                try {
                    lockJournal(c);requireReferenceUnchanged(c,expected);
                    try(var s=c.prepareStatement("INSERT INTO live_outbox(batch_id,encoding_sha256,events_identity,states_identity,delivery) VALUES(?,?,?,?,'INTENT') ON CONFLICT(batch_id) DO NOTHING")) {
                        s.setString(1,batchId);s.setString(2,encodingSha);s.setString(3,eventsIdentity);s.setString(4,statesIdentity);s.executeUpdate();
                    }
                    var actual=outbox(c,batchId);
                    if(!actual.encodingSha256().equals(encodingSha)||!actual.eventsIdentity().equals(eventsIdentity)||!actual.statesIdentity().equals(statesIdentity))
                        throw new IllegalStateException("Frozen outbox encoding or physical target identity changed");
                    c.commit();return actual;
                } catch(Exception error){rollback(c,error);throw error;}
            }
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw failure("Cannot reserve live write outbox",e); }
    }
    synchronized void claimUnknown(String batchId) { delivery(batchId,"INTENT","UNKNOWN"); }
    synchronized void acknowledge(String batchId) { delivery(batchId,"UNKNOWN","ACKNOWLEDGED"); }
    private void delivery(String id,String before,String after) {
        try(var c=open();var write=writer();var s=c.prepareStatement("UPDATE live_outbox SET delivery=? WHERE batch_id=? AND delivery=?")) {
            s.setString(1,after);s.setString(2,id);s.setString(3,before);
            if(s.executeUpdate()!=1) throw new IllegalStateException("Live outbox delivery transition conflict");
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw failure("Cannot persist live write delivery state",e); }
    }
    synchronized void markWriteVerified(String batchId) {
        try(var c=open()) {
            var expected=batchReference(c,batchId);
            if(!expected.active())throw new IllegalStateException("No matching prepared batch");
            var files=expected.files();parquet.verifyPublished(batchId,files);
            var intent=expected.outbox();
            if(intent==null||!intent.encodingSha256().equals(files.fingerprint())||!intent.eventsIdentity().equals(files.events())||!intent.statesIdentity().equals(files.minutes()))
                throw new IllegalStateException("Parquet outbox receipt differs");
            try(var write=writer()) {
                c.setAutoCommit(false);
                try {
                    lockJournal(c);requireReferenceUnchanged(c,expected);
                    try(var s=c.prepareStatement("UPDATE live_outbox SET delivery='VERIFIED' WHERE batch_id=?")) {
                        s.setString(1,batchId);if(s.executeUpdate()!=1) throw new IllegalStateException("Missing durable outbox");
                    }
                    try(var s=c.prepareStatement("UPDATE prepared_batch SET write_verified=1 WHERE batch_id=? AND active=1")) {
                        s.setString(1,batchId);if(s.executeUpdate()!=1) throw new IllegalStateException("Missing active prepared batch");
                    }
                    c.commit();
                } catch(Exception e) { rollback(c,e);throw e; }
            }
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw failure("Cannot persist live write verification",e); }
    }
    private static void requireVerified(Connection c,String id) throws Exception {
        try(var s=c.prepareStatement("SELECT p.write_verified,o.delivery FROM prepared_batch p LEFT JOIN live_outbox o ON o.batch_id=p.batch_id WHERE p.batch_id=? AND p.active=1")) {
            s.setString(1,id);try(var r=s.executeQuery()) {
                if(!r.next() || !r.getBoolean(1) || !"VERIFIED".equals(r.getString(2)))
                    throw new IllegalStateException("Raw checkpoint cannot advance without exact verified target values");
            }
        }
    }
    // 文件内容和业务值在锁外完整核验。拿到 SQLite 写事务后，只允许原批次引用原样提交。
    // 单 JVM 的归一化入口仍由 synchronized 串行；原始接收不占这把监视器。
    private static void lockJournal(Connection c) throws Exception {
        try(var lock=c.createStatement()){lock.executeUpdate("UPDATE journal_totals SET raw_bytes=raw_bytes WHERE singleton=1");}
    }
    private static Optional<String> activeId(Connection c) throws Exception {
        try(var query=c.createStatement();var rows=query.executeQuery("SELECT batch_id FROM prepared_batch WHERE active=1")) {
            return rows.next()?Optional.of(rows.getString(1)):Optional.empty();
        }
    }
    private static BatchReference batchReference(Connection c,String id) throws Exception {
        String hash,created,completed;long length;boolean active,verified;
        try(var query=c.prepareStatement("SELECT payload_sha256,length(payload),active,write_verified,created_at,completed_at FROM prepared_batch WHERE batch_id=?")) {
            query.setString(1,id);try(var rows=query.executeQuery()) {
                if(!rows.next())throw new IllegalStateException("Missing prepared batch reference");
                hash=rows.getString(1);length=rows.getLong(2);active=rows.getBoolean(3);verified=rows.getBoolean(4);created=rows.getString(5);completed=rows.getString(6);
            }
        }
        Outbox outbox=null;
        try(var query=c.prepareStatement("SELECT encoding_sha256,events_identity,states_identity,delivery FROM live_outbox WHERE batch_id=?")) {
            query.setString(1,id);try(var rows=query.executeQuery()) {
                if(rows.next())outbox=new Outbox(id,rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4));
            }
        }
        return new BatchReference(id,hash,length,active,verified,created,completed,length==0?receipt(c,id):null,outbox);
    }
    private static void requireReferenceUnchanged(Connection c,BatchReference expected) throws Exception {
        if(!expected.active()||!expected.equals(batchReference(c,expected.id())))
            throw new IllegalStateException("Prepared batch or publication reference changed before commit");
    }
    private Optional<VerifiedActive> verifiedActive(Connection c) throws Exception {
        var id=activeId(c);if(id.isEmpty())return Optional.empty();
        var before=batchReference(c,id.get());
        var batch=active(c).filter(value->value.id().equals(id.get()))
                .orElseThrow(()->new IllegalStateException("Active batch changed during value verification"));
        if(!before.equals(batchReference(c,id.get())))throw new IllegalStateException("Batch reference changed during value verification");
        return Optional.of(new VerifiedActive(batch,before));
    }
    private static Outbox outbox(Connection c,String id) throws Exception {
        try(var s=c.prepareStatement("SELECT * FROM live_outbox WHERE batch_id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()) {
                if(!r.next()) throw new IllegalStateException("Missing live outbox");
                return new Outbox(id,r.getString("encoding_sha256"),r.getString("events_identity"),r.getString("states_identity"),r.getString("delivery"));
            }
        }
    }
    private Optional<StoredBatch> active(Connection c) throws Exception {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT batch_id,payload,payload_sha256 FROM prepared_batch WHERE active=1")) {
            if(!r.next())return Optional.empty();return Optional.of(stored(c,r.getString(1),r.getBytes(2),r.getString(3)));
        }
    }
    public synchronized StoredBatch storedBatch(String id) {
        try(var c=open();var q=c.prepareStatement("SELECT payload,payload_sha256 FROM prepared_batch WHERE batch_id=?")) {
            q.setString(1,id);try(var r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("Unknown retained batch");return stored(c,id,r.getBytes(1),r.getString(2));}
        } catch(RuntimeException e){throw e;}catch(Exception e){throw failure("Cannot read retained Parquet batch",e);}
    }
    private StoredBatch stored(Connection c,String id,byte[] bytes,String hash) throws Exception {
        FrozenData data;
        if(bytes.length>0) {
            verifyHash(bytes,hash);if(!id.equals("live_"+hash))throw new IllegalStateException("Legacy prepared batch content identity mismatch");
            data=LiveStorageJson.read(bytes,FrozenData.class);
        } else {
            var archived=DailyParquetArchive.read(c,database.getParent().resolve("parquet"),id,receipt(c,id));
            data=archived.isPresent()?archived.get():parquet.read(id,receipt(c,id));if(!hash.equals(LiveValueIdentity.batch(data))||id.startsWith("live_pq_")&&!id.equals("live_pq_"+hash))throw new IllegalStateException("Parquet logical content identity mismatch");
        }
        return new StoredBatch(id,data.events(),data.states(),data.accumulatorState());
    }
    private static void saveReceipt(Connection c,String id,ParquetBatchStore.Receipt receipt) throws Exception {
        try(var s=c.prepareStatement("INSERT INTO parquet_batches VALUES(?,?,?,?,?)")) {
            s.setString(1,id);for(int i=0;i<4;i++)s.setString(i+2,receipt.hashes().get(i));s.executeUpdate();
        }
    }
    private static ParquetBatchStore.Receipt receipt(Connection c,String id) throws Exception {
        try(var s=c.prepareStatement("SELECT raw_sha256,events_sha256,minutes_sha256,checkpoint_sha256 FROM parquet_batches WHERE batch_id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("Missing Parquet receipt");return new ParquetBatchStore.Receipt(r.getString(1),r.getString(2),r.getString(3),r.getString(4));}
        }
    }
    synchronized ParquetBatchStore.Receipt parquetReceipt(String id) throws Exception {
        migrateBatch(id);
        try(var c=open()){return receipt(c,id);}
    }
    synchronized void publishParquet(String id,ParquetBatchStore.Receipt receipt) throws Exception { parquet.publish(id,receipt); }
    /** 将历史 JSON 批次逐个迁移；旧远端写入未决状态不会被改写为本地成功。 */
    public synchronized long migrateParquet() {
        long count=0;
        try {
            while(true) {
                String id;
                try(var c=open();var s=c.createStatement();var r=s.executeQuery("SELECT batch_id FROM prepared_batch WHERE length(payload)>0 ORDER BY created_at,batch_id LIMIT 1")){if(!r.next())break;id=r.getString(1);}
                migrateBatch(id);count++;
            }
            // 恢复文件已冻结、SQLite 迁移已提交但目录尚未发布的历史已完成批次。
            try(var c=open();var s=c.createStatement();var r=s.executeQuery("SELECT batch_id FROM prepared_batch WHERE active=0 AND length(payload)=0 AND batch_id NOT IN (SELECT batch_id FROM parquet_archive_members)")){while(r.next()){String id=r.getString(1);parquet.publish(id,receipt(c,id));}}
            return count;
        } catch(RuntimeException e){throw e;}catch(Exception e){throw failure("Cannot migrate retained Parquet history",e);}
    }
    private void migrateBatch(String id) throws Exception {
        try(var c=open();var q=c.prepareStatement("SELECT payload,payload_sha256,active,write_verified FROM prepared_batch WHERE batch_id=?")) {
            q.setString(1,id);byte[] bytes;String hash;boolean active,verified;
            try(var r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("Unknown legacy batch");bytes=r.getBytes(1);if(bytes.length==0)return;hash=r.getString(2);active=r.getBoolean(3);verified=r.getBoolean(4);}
            var batch=stored(c,id,bytes,hash);var data=new FrozenData(batch.events(),batch.states(),batch.accumulatorState());
            String oldIdentity=LiveStorageJson.sha256(LiveStorageJson.bytes(List.of("local-sqlite-v1",database.toString())));
            boolean hadOutbox;
            try(var check=c.prepareStatement("SELECT count(*) FROM live_outbox WHERE batch_id=?")){check.setString(1,id);try(var r=check.executeQuery()){r.next();hadOutbox=r.getInt(1)>0;}}
            if(hadOutbox) {
                var old=outbox(c,id);
                if(!old.eventsIdentity().equals(oldIdentity)||!old.statesIdentity().equals(oldIdentity)
                        ||!old.encodingSha256().equals(LiveStorageJson.sha256(LiveStorageJson.bytes(List.of(data.events(),data.states())))))
                    throw new IllegalStateException("LEGACY_REMOTE_OUTBOX_REQUIRES_RECONCILIATION");
                if(!active&&(!verified||!old.delivery().equals("VERIFIED")))throw new IllegalStateException("Incomplete legacy local publication");
            } else if(!active)throw new IllegalStateException("Legacy completed batch lacks local publication evidence");
            var files=parquet.freeze(id,data);if(!active)parquet.publish(id,files);
            c.setAutoCommit(false);
            try {
                try(var update=c.prepareStatement("UPDATE prepared_batch SET payload=X'',payload_sha256=?,write_verified=? WHERE batch_id=? AND payload_sha256=? AND length(payload)>0")) {
                    update.setString(1,LiveValueIdentity.batch(data));update.setBoolean(2,!active);update.setString(3,id);update.setString(4,hash);if(update.executeUpdate()!=1)throw new IllegalStateException("Legacy batch changed during migration");
                }
                saveReceipt(c,id,files);
                try(var remove=c.prepareStatement("DELETE FROM live_outbox WHERE batch_id=?")){remove.setString(1,id);remove.executeUpdate();}
                if(!active)try(var add=c.prepareStatement("INSERT INTO live_outbox VALUES(?,?,?,?,'VERIFIED')")){add.setString(1,id);add.setString(2,files.fingerprint());add.setString(3,files.events());add.setString(4,files.minutes());add.executeUpdate();}
                try(var check=c.prepareStatement("SELECT payload,payload_sha256 FROM committed_accumulator WHERE batch_id=?")) {
                    check.setString(1,id);try(var r=check.executeQuery()) {if(r.next()&&r.getBytes(1).length>0) {
                        verifyHash(r.getBytes(1),r.getString(2));var prior=LiveStorageJson.read(r.getBytes(1),Snapshot.class);
                        if(!LiveValueIdentity.hash(prior.accumulatorState()).equals(LiveValueIdentity.hash(data.accumulatorState())))throw new IllegalStateException("Legacy checkpoint differs from retained batch");
                        try(var update=c.prepareStatement("UPDATE committed_accumulator SET payload=X'',payload_sha256=? WHERE batch_id=?")){update.setString(1,files.checkpoint());update.setString(2,id);update.executeUpdate();}
                    }}
                }
                c.commit();
            }catch(Exception e){rollback(c,e);throw e;}
        }
    }
    private static RawCapture capture(ResultSet r) throws Exception {
        byte[] payload=r.getBytes("payload");verifyHash(payload,r.getString("payload_sha256"));
        var capture=new RawCapture(r.getString("capture_id"),r.getLong("sequence"),r.getString("session_id"),r.getString("topic"),
                Base64.getEncoder().encodeToString(payload),Instant.parse(r.getString("recv_ts")),java.time.LocalDate.parse(r.getString("capture_date")),
                r.getInt("qos"),r.getBoolean("retained"),r.getBoolean("duplicate"),r.getBoolean("subscription_authorized"));
        verifyHash(LiveStorageJson.bytes(capture),r.getString("envelope_sha256"));return capture;
    }
    private static void verifyHash(byte[] bytes,String hash) {
        if(bytes==null || !LiveStorageJson.sha256(bytes).equals(hash)) throw new IllegalStateException("Retained raw/checkpoint hash mismatch");
    }
    public synchronized Status status() {
        try(var c=open();var s=c.createStatement();var r=s.executeQuery("SELECT captures,captures-completed,completed,raw_bytes FROM journal_totals WHERE singleton=1")) {
            if(!r.next())throw new IllegalStateException("原始日志统计缺失");String active="";
            try(var q=c.createStatement();var batch=q.executeQuery("SELECT batch_id FROM prepared_batch WHERE active=1")){if(batch.next())active=batch.getString(1);}
            return new Status(database,r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),active);
        } catch(Exception e) { throw failure("Cannot read raw journal status",e); }
    }

    /** 调用方持有 collector.lock，接收连接也暂停；原始 SQLite 日志保留，不因合并而删报文。 */
    public synchronized Map<String,Object> archiveDay(java.time.LocalDate date,java.time.LocalDate today) {
        synchronized(captureLock) {
            try(var c=open()) {
                var result=DailyParquetArchive.seal(this,c,database.getParent().resolve("parquet"),date,today);
                parquet.recount();return Map.of("date",result.date(),"batches",result.batches(),"filesRemoved",result.filesRemoved(),"manifestSha256",result.manifestSha256());
            }catch(RuntimeException e){throw e;}catch(Exception e){throw failure("日归档未完成；保留原账本和文件",e);}
        }
    }
    public synchronized Map<String,Object> verifyStorage() {
        try(var c=open();var s=c.createStatement()) {
            try(var r=s.executeQuery("PRAGMA quick_check")){if(!r.next()||!"ok".equals(r.getString(1)))throw new IllegalStateException("SQLite 完整性检查未通过");}
            DailyParquetArchive.verifyAll(c,database.getParent().resolve("parquet"));long batches=0,valid=0,minutes=0;
            try(var r=s.executeQuery("SELECT batch_id FROM prepared_batch")){while(r.next()){var batch=storedBatch(r.getString(1));valid+=batch.events().stream().filter(L2CleanEvent::isValid).count();minutes+=batch.states().size();batches++;}}
            long raw=0;try(var r=s.executeQuery("SELECT * FROM raw_capture ORDER BY sequence")){while(r.next()){capture(r);raw++;}}
            long bytes;try(var paths=Files.walk(database.getParent())){bytes=paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new IllegalStateException(e);}}).sum();}
            return Map.of("verified",true,"batches",batches,"rawCaptures",raw,"validNormalizedEvents",valid,"minuteObservations",minutes,"diskBytes",bytes,"usableDiskBytes",Files.getFileStore(database).getUsableSpace(),"pending",status().pending(),"rawQuotaBytes",maxRawBytes);
        }catch(RuntimeException e){throw e;}catch(Exception e){throw failure("L2 存储核验失败",e);}
    }
    /** 只列 SQLite 已提交的文件；归档引用接管后，即使旧文件还没清完，也不能重复读取。 */
    public synchronized Map<String,List<String>> parquetFiles() {
        try(var c=open()) {
            Path root=database.getParent().resolve("parquet");var directories=new ArrayList<>(DailyParquetArchive.directories(c,root));
            try(var s=c.createStatement();var r=s.executeQuery("SELECT batch_id FROM prepared_batch WHERE active=0 AND write_verified=1 AND length(payload)=0 AND batch_id NOT IN (SELECT batch_id FROM parquet_archive_members) ORDER BY created_at,batch_id")){
                while(r.next()){String id=r.getString(1);parquet.verifyPublished(id,receipt(c,id));directories.add(root.resolve("batches").resolve(id));}
            }
            var result=new java.util.TreeMap<String,List<String>>();for(String kind:List.of("raw","events","minutes"))result.put(kind,directories.stream().map(p->p.resolve(kind+".parquet").toAbsolutePath().toString()).toList());return java.util.Collections.unmodifiableMap(result);
        }catch(RuntimeException e){throw e;}catch(Exception e){throw failure("不能列出已提交的 L2 文件",e);}
    }
    public synchronized ExportEvidence exportParquet(Path destination) {
        Path target=destination.toAbsolutePath().normalize(), temporary=null;
        try {
            if(!target.getFileName().toString().endsWith(".parquet"))throw new IllegalArgumentException("Parquet export requires a .parquet destination");
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Raw export destination already exists");
            Files.createDirectories(target.getParent());temporary=Files.createTempFile(target.getParent(),".raw-export-",".partial");Files.delete(temporary);
            long count=0,last=0;
            try(var c=open()) {
                c.setAutoCommit(false);
                try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM raw_capture ORDER BY sequence");
                    var writer=LiveParquetCodec.writer(temporary,LiveParquetCodec.RAW,"raw","export")) {
                    while(r.next()){var capture=capture(r);writer.write(LiveParquetCodec.raw(capture));count++;last=capture.sequence();}
                }
                c.commit();
            }
            LiveParquetCodec.validateRawExport(temporary,count,last);
            try(var file=java.nio.channels.FileChannel.open(temporary,java.nio.file.StandardOpenOption.WRITE)){file.force(true);}
            String hash=ParquetBatchStore.sha(temporary);long size=Files.size(temporary);
            // 同目录硬链接以 CREATE_NEW 语义发布，目标存在时绝不覆盖旧导出。
            Files.createLink(target,temporary);Files.delete(temporary);temporary=null;ParquetBatchStore.syncDirectory(target.getParent());
            return new ExportEvidence(target,count,last,size,hash);
        } catch(RuntimeException e){throw e;}catch(Exception e){throw failure("Cannot export immutable raw Parquet",e);}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
    @Override public void close() {
        synchronized(captureLock) {
            closed=true;
            if(captureConnection!=null) {
                try { captureConnection.close(); }
                catch(Exception e) { throw failure("Cannot close raw capture connection",e); }
                finally { captureConnection=null;captureInsert=null;captureTotals=null; }
            }
        }
    }
    private static void rollback(Connection connection,Exception original) {
        try {connection.rollback();}catch(Exception rollbackFailure) {original.addSuppressed(rollbackFailure);}
    }
    private static IllegalStateException failure(String message,Exception error) { return new IllegalStateException(message,error); }
}
