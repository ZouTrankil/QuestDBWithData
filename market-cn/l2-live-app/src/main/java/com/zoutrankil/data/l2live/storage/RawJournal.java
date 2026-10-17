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
    private Connection captureConnection;
    private java.sql.PreparedStatement captureInsert,captureTotals;


    public record StoredBatch(String id, List<L2CleanEvent> events, List<L2MinuteState> states,
            Map<String, Object> accumulatorState) {
        public StoredBatch { events=List.copyOf(events); states=List.copyOf(states); }
    }
    record FrozenData(List<L2CleanEvent> events, List<L2MinuteState> states, Map<String, Object> accumulatorState) {}
    record Snapshot(Map<String, Object> accumulatorState) {}
    record Outbox(String batchId, String encodingSha256, String eventsIdentity,
            String statesIdentity, String delivery) {}
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
                        if(!r.next() || !java.util.Set.of(2,3).contains(r.getInt(1))) throw new IllegalArgumentException("Unsupported live raw journal schema; subscription proof requires schema 2 or 3");
                    }
                }
            }
            initialize();
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
            s.execute("CREATE TABLE IF NOT EXISTS prepared_batch(batch_id TEXT PRIMARY KEY,payload BLOB NOT NULL,payload_sha256 TEXT NOT NULL,active INTEGER NOT NULL CHECK(active IN (0,1)),write_verified INTEGER NOT NULL DEFAULT 0 CHECK(write_verified IN (0,1)),created_at TEXT NOT NULL,completed_at TEXT)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_active_live_batch ON prepared_batch(active) WHERE active=1");
            s.execute("CREATE TABLE IF NOT EXISTS committed_accumulator(singleton INTEGER PRIMARY KEY CHECK(singleton=1),payload BLOB NOT NULL,payload_sha256 TEXT NOT NULL,batch_id TEXT NOT NULL REFERENCES prepared_batch(batch_id))");
            s.execute("CREATE TABLE IF NOT EXISTS live_outbox(batch_id TEXT PRIMARY KEY REFERENCES prepared_batch(batch_id),encoding_sha256 TEXT NOT NULL,events_identity TEXT NOT NULL,states_identity TEXT NOT NULL,delivery TEXT NOT NULL CHECK(delivery IN ('INTENT','UNKNOWN','ACKNOWLEDGED','VERIFIED')))");
            s.execute("CREATE TABLE IF NOT EXISTS parquet_batches(batch_id TEXT PRIMARY KEY REFERENCES prepared_batch(batch_id),raw_sha256 TEXT NOT NULL,events_sha256 TEXT NOT NULL,minutes_sha256 TEXT NOT NULL,checkpoint_sha256 TEXT NOT NULL)");
            s.execute("PRAGMA application_id="+APPLICATION_ID); s.execute("PRAGMA user_version=3");
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
        synchronized(captureLock) {
            try {
                if(closed)throw new IllegalStateException("Raw journal is closed");
                // 只复用原始接收连接；Parquet 编码仍可与后续报文落盘并行。
                if(captureConnection==null) {
                    var connection=open();
                    try {
                        var totals=connection.prepareStatement("UPDATE journal_totals SET raw_bytes=raw_bytes+?,next_sequence=next_sequence+1 WHERE singleton=1 AND raw_bytes<=?");
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
                        s.setInt(1,frozen.length);s.setLong(2,maxRawBytes-frozen.length);
                        if(s.executeUpdate()!=1) throw new IllegalStateException("Raw journal quota reached; receive must stop before acknowledgement");
                    }
                    long sequence;
                    try(var s=c.createStatement();var r=s.executeQuery("SELECT next_sequence FROM journal_totals WHERE singleton=1")) { r.next();sequence=r.getLong(1); }
                    var result=new RawCapture(proposed.captureId(),sequence,sessionId,topic,proposed.payloadBase64(),recvTs,proposed.captureDate(),qos,retained,duplicate,subscriptionAuthorized);
                    {
                        var s=captureInsert;
                        s.setLong(1,sequence);s.setString(2,result.captureId());s.setString(3,sessionId);s.setString(4,topic);s.setBytes(5,frozen);
                        s.setString(6,LiveStorageJson.sha256(frozen));s.setString(7,LiveStorageJson.sha256(LiveStorageJson.bytes(result)));
                        s.setString(8,recvTs.toString());s.setString(9,result.captureDate().toString());s.setInt(10,qos);s.setBoolean(11,retained);s.setBoolean(12,duplicate);s.setBoolean(13,subscriptionAuthorized);s.executeUpdate();
                    }
                    c.commit();
                    return result;
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
            validateCaptures(c,events);
            // 文件编码不持有 SQLite 写锁，接收线程可继续追加原始报文。
            var receipt=parquet.freeze(id,data);
            c.setAutoCommit(false);
            try {
                try(var lock=c.createStatement()){lock.executeUpdate("UPDATE journal_totals SET raw_bytes=raw_bytes WHERE singleton=1");}
                if(active(c).isPresent())throw new IllegalStateException("Another prepared batch owns the checkpoint");
                validateCaptures(c,events);
                try(var q=c.prepareStatement("INSERT INTO prepared_batch(batch_id,payload,payload_sha256,active,created_at) VALUES(?,X'',?,1,?)")) {
                    q.setString(1,id);q.setString(2,hash);q.setString(3,Instant.now().toString());q.executeUpdate();
                }
                saveReceipt(c,id,receipt);c.commit();return storedBatch(id);
            } catch(Exception e) { rollback(c,e);throw e; }
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw failure("Cannot freeze Parquet normalization batch",e); }
    }
    private static void validateCaptures(Connection c,List<L2CleanEvent> events) throws Exception {
        var ids=new HashSet<String>();
        for(var event:events) {
            var raw=event.capture();if(!ids.add(raw.captureId()))throw new IllegalArgumentException("Duplicate capture in prepared batch");
            try(var q=c.prepareStatement("SELECT * FROM raw_capture WHERE capture_id=?")) {
                q.setString(1,raw.captureId());try(var r=q.executeQuery()) {
                    if(!r.next()||r.getBoolean("completed")||!capture(r).equals(raw))throw new IllegalArgumentException("Prepared event must bind an exact pending raw capture");
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
            c.setAutoCommit(false);
            try {
                try(var lock=c.createStatement()){lock.executeUpdate("UPDATE journal_totals SET raw_bytes=raw_bytes WHERE singleton=1");}
                var active=active(c);
                if(active.isEmpty()) {
                    try(var q=c.prepareStatement("SELECT count(*) FROM prepared_batch p JOIN live_outbox o ON o.batch_id=p.batch_id WHERE p.batch_id=? AND p.active=0 AND p.write_verified=1 AND p.completed_at IS NOT NULL AND o.delivery='VERIFIED'")) {
                        q.setString(1,id);try(var r=q.executeQuery()) {
                            r.next();if(r.getInt(1)!=1)throw new IllegalStateException("No active or previously verified completed batch");
                        }
                    }
                    c.commit();return;
                }
                var batch=active.get();
                if(!batch.id().equals(id)) throw new IllegalStateException("Prepared batch identity mismatch");
                requireVerified(c,id);
                for(var event:batch.events()) {
                    try(var s=c.prepareStatement("UPDATE raw_capture SET completed=1 WHERE capture_id=? AND completed=0")) {
                        s.setString(1,event.capture().captureId());
                        if(s.executeUpdate()!=1) throw new IllegalStateException("Prepared capture checkpoint changed before commit");
                    }
                }
                var retained=receipt(c,id);
                if(!parquet.published(id))throw new IllegalStateException("Parquet batch has not been published");
                try(var s=c.prepareStatement("INSERT INTO committed_accumulator(singleton,payload,payload_sha256,batch_id) VALUES(1,?,?,?) ON CONFLICT(singleton) DO UPDATE SET payload=excluded.payload,payload_sha256=excluded.payload_sha256,batch_id=excluded.batch_id")) {
                    s.setBytes(1,new byte[0]);s.setString(2,retained.checkpoint());s.setString(3,id);s.executeUpdate();
                }
                try(var s=c.prepareStatement("UPDATE prepared_batch SET active=0,completed_at=? WHERE batch_id=? AND active=1")) {
                    s.setString(1,Instant.now().toString());s.setString(2,id);
                    if(s.executeUpdate()!=1) throw new IllegalStateException("Prepared batch completion conflict");
                }
                c.commit();
            } catch(Exception e) { rollback(c,e);throw e; }
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
            if(active(c).filter(b->b.id().equals(batchId)).isEmpty()) throw new IllegalStateException("Outbox must bind the active prepared batch");
            try(var s=c.prepareStatement("INSERT INTO live_outbox(batch_id,encoding_sha256,events_identity,states_identity,delivery) VALUES(?,?,?,?,'INTENT') ON CONFLICT(batch_id) DO NOTHING")) {
                s.setString(1,batchId);s.setString(2,encodingSha);s.setString(3,eventsIdentity);s.setString(4,statesIdentity);s.executeUpdate();
            }
            var actual=outbox(c,batchId);
            if(!actual.encodingSha256().equals(encodingSha)||!actual.eventsIdentity().equals(eventsIdentity)||!actual.statesIdentity().equals(statesIdentity))
                throw new IllegalStateException("Frozen outbox encoding or physical target identity changed");
            return actual;
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw failure("Cannot reserve live write outbox",e); }
    }
    synchronized void claimUnknown(String batchId) { delivery(batchId,"INTENT","UNKNOWN"); }
    synchronized void acknowledge(String batchId) { delivery(batchId,"UNKNOWN","ACKNOWLEDGED"); }
    private void delivery(String id,String before,String after) {
        try(var c=open();var s=c.prepareStatement("UPDATE live_outbox SET delivery=? WHERE batch_id=? AND delivery=?")) {
            s.setString(1,after);s.setString(2,id);s.setString(3,before);
            if(s.executeUpdate()!=1) throw new IllegalStateException("Live outbox delivery transition conflict");
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw failure("Cannot persist live write delivery state",e); }
    }
    synchronized void markWriteVerified(String batchId) {
        try(var c=open()) {
            c.setAutoCommit(false);
            try {
                try(var lock=c.createStatement()){lock.executeUpdate("UPDATE journal_totals SET raw_bytes=raw_bytes WHERE singleton=1");}
                try(var check=c.prepareStatement("SELECT count(*) FROM prepared_batch WHERE batch_id=? AND active=1")) {
                    check.setString(1,batchId);try(var r=check.executeQuery()){r.next();if(r.getInt(1)!=1)throw new IllegalStateException("No matching prepared batch");}
                }
                var files=receipt(c,batchId);
                parquet.verifyPublished(batchId,files);
                var intent=outbox(c,batchId);
                if(!intent.encodingSha256().equals(files.fingerprint())||!intent.eventsIdentity().equals(files.events())||!intent.statesIdentity().equals(files.minutes()))
                    throw new IllegalStateException("Parquet outbox receipt differs");
                try(var s=c.prepareStatement("UPDATE live_outbox SET delivery='VERIFIED' WHERE batch_id=?")) {
                    s.setString(1,batchId);if(s.executeUpdate()!=1) throw new IllegalStateException("Missing durable outbox");
                }
                try(var s=c.prepareStatement("UPDATE prepared_batch SET write_verified=1 WHERE batch_id=? AND active=1")) {
                    s.setString(1,batchId);if(s.executeUpdate()!=1) throw new IllegalStateException("Missing active prepared batch");
                }
                c.commit();
            } catch(Exception e) { rollback(c,e);throw e; }
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
            data=parquet.read(id,receipt(c,id));if(!hash.equals(LiveValueIdentity.batch(data))||id.startsWith("live_pq_")&&!id.equals("live_pq_"+hash))throw new IllegalStateException("Parquet logical content identity mismatch");
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
            try(var c=open();var s=c.createStatement();var r=s.executeQuery("SELECT batch_id FROM prepared_batch WHERE active=0 AND length(payload)=0")){while(r.next()){String id=r.getString(1);parquet.publish(id,receipt(c,id));}}
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
        try(var c=open();var s=c.createStatement();var r=s.executeQuery("SELECT count(*),coalesce(sum(1-completed),0),coalesce(sum(completed),0),coalesce(sum(length(payload)),0) FROM raw_capture")) {
            r.next();return new Status(database,r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),active(c).map(StoredBatch::id).orElse(""));
        } catch(Exception e) { throw failure("Cannot read raw journal status",e); }
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
