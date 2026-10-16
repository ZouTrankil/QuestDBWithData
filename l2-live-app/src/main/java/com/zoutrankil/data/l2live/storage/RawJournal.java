package com.zoutrankil.data.l2live.storage;

import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteState;
import com.zoutrankil.data.l2live.domain.RawCapture;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
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

/** Original wire bytes and frozen normalization transactions survive collector/process failures. */
public final class RawJournal implements AutoCloseable {
    public static final int MAX_BATCH_ROWS = 200;
    private static final int APPLICATION_ID = 0x4c324c56;
    private static final int MAX_FROZEN_BYTES = 8 * 1024 * 1024;
    private static final long DEFAULT_RAW_BYTES = 8L * 1024 * 1024 * 1024;
    private final Path database;
    private final long maxRawBytes;
    private volatile boolean closed;

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
                        if(!r.next() || r.getInt(1)!=2) throw new IllegalArgumentException("Unsupported live raw journal schema; subscription proof requires schema 2");
                    }
                }
            }
            initialize();
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
            s.execute("PRAGMA application_id="+APPLICATION_ID); s.execute("PRAGMA user_version=2");
        }
    }

    public synchronized RawCapture append(String topic, byte[] payload, Instant recvTs, String sessionId,
            int qos, boolean retained, boolean duplicate) {
        return append(topic,payload,recvTs,sessionId,qos,retained,duplicate,false);
    }
    public synchronized RawCapture append(String topic, byte[] payload, Instant recvTs, String sessionId,
            int qos, boolean retained, boolean duplicate, boolean subscriptionAuthorized) {
        Objects.requireNonNull(payload); Objects.requireNonNull(recvTs);
        byte[] frozen=payload.clone();
        var proposed=new RawCapture("cap_"+UUID.randomUUID().toString().replace("-",""),0,sessionId,topic,
                Base64.getEncoder().encodeToString(frozen),recvTs,recvTs.atZone(RawCapture.SHANGHAI).toLocalDate(),qos,retained,duplicate,subscriptionAuthorized);
        try(var c=open()) {
            c.setAutoCommit(false);
            try {
                try(var s=c.prepareStatement("UPDATE journal_totals SET raw_bytes=raw_bytes+?,next_sequence=next_sequence+1 WHERE singleton=1 AND raw_bytes<=?")) {
                    s.setInt(1,frozen.length);s.setLong(2,maxRawBytes-frozen.length);
                    if(s.executeUpdate()!=1) throw new IllegalStateException("Raw journal quota reached; receive must stop before acknowledgement");
                }
                long sequence;
                try(var s=c.createStatement();var r=s.executeQuery("SELECT next_sequence FROM journal_totals WHERE singleton=1")) { r.next();sequence=r.getLong(1); }
                var result=new RawCapture(proposed.captureId(),sequence,sessionId,topic,proposed.payloadBase64(),recvTs,proposed.captureDate(),qos,retained,duplicate,subscriptionAuthorized);
                try(var s=c.prepareStatement("INSERT INTO raw_capture(sequence,capture_id,session_id,topic,payload,payload_sha256,envelope_sha256,recv_ts,capture_date,qos,retained,duplicate,subscription_authorized) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
    public synchronized StoredBatch prepareBatch(List<L2CleanEvent> events, List<L2MinuteState> states,
            Map<String,Object> accumulatorState) {
        if(events==null || events.isEmpty() || events.size()>MAX_BATCH_ROWS || states==null || states.size()>MAX_BATCH_ROWS)
            throw new IllegalArgumentException("One bounded nonempty live event batch is required");
        byte[] bytes=LiveStorageJson.bytes(new FrozenData(List.copyOf(events),List.copyOf(states),Objects.requireNonNull(accumulatorState)));
        if(bytes.length>MAX_FROZEN_BYTES) throw new IllegalArgumentException("Prepared live batch exceeds frozen-byte budget");
        String hash=LiveStorageJson.sha256(bytes), id="live_"+hash;
        try(var c=open()) {
            c.setAutoCommit(false);
            try {
                var previous=active(c);
                if(previous.isPresent()) {
                    if(!previous.get().id().equals(id)) throw new IllegalStateException("A different frozen live batch already owns the checkpoint");
                    c.commit();return previous.get();
                }
                var ids=new HashSet<String>();
                for(var event:events) {
                    var raw=event.capture();
                    if(!ids.add(raw.captureId())) throw new IllegalArgumentException("Duplicate capture in prepared batch");
                    try(var q=c.prepareStatement("SELECT * FROM raw_capture WHERE capture_id=?")) {
                        q.setString(1,raw.captureId());try(var r=q.executeQuery()) {
                            if(!r.next() || r.getBoolean("completed") || !capture(r).equals(raw))
                                throw new IllegalArgumentException("Prepared event must bind an exact pending raw capture");
                        }
                    }
                }
                try(var q=c.prepareStatement("INSERT INTO prepared_batch(batch_id,payload,payload_sha256,active,created_at) VALUES(?,?,?,1,?)")) {
                    q.setString(1,id);q.setBytes(2,bytes);q.setString(3,hash);q.setString(4,Instant.now().toString());q.executeUpdate();
                }
                c.commit(); return stored(id,bytes);
            } catch(Exception e) { rollback(c,e);throw e; }
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw failure("Cannot freeze live normalization batch",e); }
    }

    public synchronized Map<String,Object> committedAccumulatorState() {
        try(var c=open();var s=c.createStatement();var r=s.executeQuery("SELECT payload,payload_sha256 FROM committed_accumulator WHERE singleton=1")) {
            if(!r.next()) return Map.of();byte[] bytes=r.getBytes(1);verifyHash(bytes,r.getString(2));
            return LiveStorageJson.read(bytes,Snapshot.class).accumulatorState();
        } catch(Exception e) { throw failure("Cannot read committed accumulator checkpoint",e); }
    }
    public synchronized void completeBatch(String id) {
        try(var c=open()) {
            c.setAutoCommit(false);
            try {
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
                byte[] snapshot=LiveStorageJson.bytes(new Snapshot(batch.accumulatorState()));
                try(var s=c.prepareStatement("INSERT INTO committed_accumulator(singleton,payload,payload_sha256,batch_id) VALUES(1,?,?,?) ON CONFLICT(singleton) DO UPDATE SET payload=excluded.payload,payload_sha256=excluded.payload_sha256,batch_id=excluded.batch_id")) {
                    s.setBytes(1,snapshot);s.setString(2,LiveStorageJson.sha256(snapshot));s.setString(3,id);s.executeUpdate();
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
    /** Compatibility method; only an already verified frozen batch can complete its raw captures. */
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
                if(active(c).filter(b->b.id().equals(batchId)).isEmpty()) throw new IllegalStateException("No matching prepared batch");
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
    private static Optional<StoredBatch> active(Connection c) throws Exception {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT batch_id,payload,payload_sha256 FROM prepared_batch WHERE active=1")) {
            if(!r.next()) return Optional.empty();String id=r.getString(1);byte[] bytes=r.getBytes(2);verifyHash(bytes,r.getString(3));
            if(!id.equals("live_"+LiveStorageJson.sha256(bytes))) throw new IllegalStateException("Prepared batch content identity mismatch");
            return Optional.of(stored(id,bytes));
        }
    }
    private static StoredBatch stored(String id,byte[] bytes) {
        var body=LiveStorageJson.read(bytes,FrozenData.class);
        return new StoredBatch(id,body.events(),body.states(),body.accumulatorState());
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
    public synchronized ExportEvidence exportJsonl(Path destination) {
        Path target=destination.toAbsolutePath().normalize(), temporary=null;
        try {
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Raw export destination already exists");
            Files.createDirectories(target.getParent());
            temporary=Files.createTempFile(target.getParent(),".raw-export-",".partial");
            long count=0,last=0;
            try(var c=open()) {
                c.setAutoCommit(false);
                try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM raw_capture ORDER BY sequence");
                        BufferedWriter writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)) {
                    while(r.next()) {
                        var capture=capture(r);writer.write(new String(LiveStorageJson.bytes(capture),StandardCharsets.UTF_8));writer.newLine();
                        count++;last=capture.sequence();
                    }
                }
                c.commit();
            }
            String hash;
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var in=Files.newInputStream(temporary)) { byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))!=-1;)digest.update(bytes,0,n); }
            hash=java.util.HexFormat.of().formatHex(digest.digest());long bytes=Files.size(temporary);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);temporary=null;
            return new ExportEvidence(target,count,last,bytes,hash);
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw failure("Cannot export immutable raw JSONL",e); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(Exception ignored) {} }
    }
    @Override public void close() { closed=true; }
    private static void rollback(Connection connection,Exception original) {
        try {connection.rollback();}catch(Exception rollbackFailure) {original.addSuppressed(rollbackFailure);}
    }
    private static IllegalStateException failure(String message,Exception error) { return new IllegalStateException(message,error); }
}
