package com.zoutrankil.data.l2live.storage;

import com.zoutrankil.data.l2live.domain.AliyunL2WireParser;
import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteAccumulator;
import com.zoutrankil.data.l2live.domain.RawCapture;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** 独立发布已经 FULL 落盘并通过原生准入的当日盘口，不推进全量微状态检查点。 */
public final class LiveMarketSnapshotStore implements AutoCloseable {
    private static final int APPLICATION_ID = 0x4c324d53;
    private static final int MAX_ROWS = RawJournal.MAX_BATCH_ROWS;
    private static final MessageType SCHEMA =
            MessageTypeParser.parseMessageType(
                    "message l2_market_snapshots {"
                            + "required binary capture_id (STRING); required int64 sequence; required binary session_id (STRING);"
                            + "required binary topic (STRING); required binary payload; required binary payload_sha256 (STRING);"
                            + "required binary envelope_sha256 (STRING); required int64 recv_ts (TIMESTAMP(NANOS,true));"
                            + "required int32 capture_date (DATE); required int32 qos; required boolean retained; required boolean duplicate;"
                            + "required boolean subscription_authorized; required binary symbol (STRING); required int32 trade_date (DATE);"
                            + "required int64 exchange_ts (TIMESTAMP(NANOS,true)); required int64 available_at (TIMESTAMP(NANOS,true));"
                            + "required binary parse_status (STRING); required binary parser_version (STRING);"
                            + "required fixed_len_byte_array(16) price (DECIMAL(38,4));"
                            + "required fixed_len_byte_array(16) bid_price_1 (DECIMAL(38,4));"
                            + "required fixed_len_byte_array(16) ask_price_1 (DECIMAL(38,4));"
                            + "required int64 total_amount; required int64 total_volume; required binary quality_flags (STRING); }");

    private record Candidate(RawCapture raw, String envelope, L2CleanEvent event) {}

    private final Path rawDatabase, root, pending, batches;
    private final Clock clock;
    private final List<String> topics;
    private final AliyunL2WireParser parser = new AliyunL2WireParser();
    private final Object lifecycle = new Object();
    private final TreeMap<Long, Candidate> deferred = new TreeMap<>();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Connection index;
    private final Thread worker;
    private volatile Map<String, Object> current =
            Map.of("phase", "created", "fullMicroStateReady", false);
    private volatile boolean closing, started;
    private long cursor = -1, publishedRows, rejected;
    private Instant latestExchange, lastPublished;

    public LiveMarketSnapshotStore(Path liveRoot, Set<String> expectedTopics, Clock clock)
            throws Exception {
        this.clock = Objects.requireNonNull(clock);
        topics =
                expectedTopics.stream()
                        .filter(topic -> topic.startsWith("market/"))
                        .sorted()
                        .toList();
        if (topics.isEmpty()
                || topics.size() > 16
                || topics.stream().anyMatch(topic -> !topic.matches("market/[0-9]{6}")))
            throw new IllegalArgumentException(
                    "Explicit bounded market subscription scope required");
        Path parent = liveRoot.toAbsolutePath().normalize();
        rawDatabase = parent.resolve("raw-journal.sqlite3");
        root = parent.resolve("market-snapshots");
        pending = root.resolve(".pending");
        batches = root.resolve("batches");
        if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(rawDatabase))
            throw new IllegalArgumentException("Linked live root rejected");
        for (Path path : List.of(root, pending, batches)) {
            if (Files.isSymbolicLink(path))
                throw new IllegalArgumentException("Linked snapshot root rejected");
            Files.createDirectories(path);
        }
        lockChannel =
                FileChannel.open(
                        root.resolve("publisher.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        FileLock acquired = null;
        Connection opened = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null)
                throw new IllegalStateException("Snapshot publisher already running");
            Path database = root.resolve("index.sqlite3");
            if (Files.isSymbolicLink(database))
                throw new IllegalArgumentException("Linked snapshot index rejected");
            opened = DriverManager.getConnection("jdbc:sqlite:" + database);
            try (var statement = opened.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA synchronous=FULL");
                int application, version;
                try (var rows = statement.executeQuery("PRAGMA application_id")) {
                    rows.next();
                    application = rows.getInt(1);
                }
                try (var rows = statement.executeQuery("PRAGMA user_version")) {
                    rows.next();
                    version = rows.getInt(1);
                }
                if (application != 0 && application != APPLICATION_ID
                        || version != 0 && version != 1)
                    throw new IllegalStateException("Snapshot index contract differs");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS market_snapshot_batches(batch_id TEXT PRIMARY KEY,trade_date TEXT NOT NULL,relative_path TEXT NOT NULL UNIQUE,sha256 TEXT NOT NULL,row_count INTEGER NOT NULL,min_sequence INTEGER NOT NULL,max_sequence INTEGER NOT NULL,available_at TEXT NOT NULL,min_exchange_ts TEXT NOT NULL,max_exchange_ts TEXT NOT NULL)");
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS market_snapshot_members(capture_id TEXT PRIMARY KEY,sequence INTEGER NOT NULL UNIQUE,batch_id TEXT NOT NULL REFERENCES market_snapshot_batches(batch_id))");
                statement.execute("PRAGMA application_id=" + APPLICATION_ID);
                statement.execute("PRAGMA user_version=1");
            }
        } catch (Exception failure) {
            if (opened != null) opened.close();
            if (acquired != null) acquired.close();
            lockChannel.close();
            throw failure;
        }
        index = opened;
        lock = acquired;
        worker = new Thread(this::run, "l2-native-market-snapshots");
    }

    public void start() {
        synchronized (lifecycle) {
            if (closing || started)
                throw new IllegalStateException("Snapshot publisher lifecycle conflict");
            started = true;
            worker.start();
        }
    }

    public Map<String, Object> status() {
        return current;
    }

    private void run() {
        try {
            while (!closing) {
                long beforeCursor = cursor;
                var beforeDeferred = new TreeMap<>(deferred);
                try (var raw =
                        DriverManager.getConnection(
                                "jdbc:sqlite:"
                                        + rawDatabase.toUri().toASCIIString()
                                        + "?mode=ro")) {
                    try (var statement = raw.createStatement()) {
                        statement.execute("PRAGMA busy_timeout=5000");
                        statement.execute("PRAGMA query_only=ON");
                        try (var rows = statement.executeQuery("PRAGMA application_id")) {
                            if (!rows.next() || rows.getInt(1) != 0x4c324c56)
                                throw new IllegalStateException("Unknown raw source identity");
                        }
                    }
                    publishOnce(raw);
                    updateStatus("running", null);
                } catch (Exception error) {
                    if (!retryable(error)) throw error;
                    // 重试回到本轮起点；已经提交的 capture 由成员索引去重，未发布的不能被游标跳过。
                    // 重新读取时仍检查 180 秒时效，故障期间过期的报价不会冒充新行情。
                    cursor = beforeCursor;
                    deferred.clear();
                    deferred.putAll(beforeDeferred);
                    updateStatus("retrying", error.getClass().getSimpleName());
                }
                synchronized (lifecycle) {
                    if (!closing) lifecycle.wait(3000);
                }
            }
            updateStatus("stopped", null);
        } catch (Exception error) {
            updateStatus("failed", error.getClass().getSimpleName());
        }
    }

    private static boolean retryable(Throwable failure) {
        // 只重试 SQLite 忙/锁和明确的 Windows 占用；损坏、摘要、schema 或未决事务必须停住。
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof IllegalStateException
                    && "SNAPSHOT_INDEX_TRANSACTION_UNRESOLVED".equals(cause.getMessage()))
                return false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && ((sql.getErrorCode() & 255) == 5 || (sql.getErrorCode() & 255) == 6))
                return true;
            if (cause instanceof AccessDeniedException) return true;
            if (cause instanceof FileSystemException file) {
                String reason = Objects.toString(file.getReason(), "").toLowerCase(Locale.ROOT);
                if (reason.contains("sharing")
                        || reason.contains("another process")
                        || reason.contains("temporar")
                        || reason.contains("resource busy")
                        || reason.contains("access is denied")
                        || reason.contains("另一个进程")
                        || reason.contains("另一个程序")
                        || reason.contains("正在使用")) return true;
            }
        }
        return false;
    }

    private void publishOnce(Connection raw) throws Exception {
        Instant now = clock.instant(), cutoff = now.minusSeconds(180);
        LocalDate today = now.atZone(RawCapture.SHANGHAI).toLocalDate();
        long maximum;
        try (var statement = raw.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT coalesce(max(sequence),0) FROM raw_capture")) {
            rows.next();
            maximum = rows.getLong(1);
        }
        // 启动只检查有界近期尾部，不等待全量 trans/order 积压。原日志及完成标记保持不变。
        if (cursor < 0) cursor = Math.max(0, maximum - 50000);
        String placeholders = String.join(",", Collections.nCopies(topics.size(), "?"));
        String sql =
                "SELECT * FROM raw_capture WHERE sequence>? AND sequence<=? AND recv_ts>=? AND capture_date=? AND topic IN ("
                        + placeholders
                        + ") ORDER BY sequence LIMIT ?";
        var candidates = new ArrayList<Candidate>(deferred.values());
        deferred.clear();
        int found = 0;
        try (var query = raw.prepareStatement(sql)) {
            query.setLong(1, cursor);
            query.setLong(2, maximum);
            query.setString(3, cutoff.minusSeconds(1).toString());
            query.setString(4, today.toString());
            for (int i = 0; i < topics.size(); i++) query.setString(5 + i, topics.get(i));
            query.setInt(5 + topics.size(), MAX_ROWS);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    found++;
                    cursor = rows.getLong("sequence");
                    var candidate = candidate(rows);
                    if (candidate != null) candidates.add(candidate);
                }
            }
        }
        if (found < MAX_ROWS) cursor = maximum;
        var accepted = new ArrayList<List<Object>>();
        long bytes = 0;
        for (var candidate : candidates) {
            var capture = candidate.raw();
            var event = candidate.event();
            if (!capture.captureDate().equals(today)
                    || !event.tradeDate().equals(today)
                    || capture.recvTs().isBefore(cutoff)
                    || event.exchangeTs().isBefore(cutoff)) {
                rejected++;
                continue;
            }
            if (capture.recvTs().isAfter(now)
                    || event.exchangeTs()
                            .isAfter(now.plus(L2MinuteAccumulator.EVENT_CLOCK_TOLERANCE))) {
                if (!capture.recvTs().isAfter(now.plusSeconds(5))
                        && !event.exchangeTs().isAfter(now.plusSeconds(5)))
                    deferred.put(capture.sequence(), candidate);
                else rejected++;
                continue;
            }
            try (var query =
                    index.prepareStatement(
                            "SELECT sequence FROM market_snapshot_members WHERE capture_id=?")) {
                query.setString(1, capture.captureId());
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        if (rows.getLong(1) != capture.sequence())
                            throw new IllegalStateException("Snapshot capture identity changed");
                        continue;
                    }
                }
            }
            long size = capture.payloadBytes().length;
            if (!accepted.isEmpty()
                    && (accepted.size() >= MAX_ROWS || bytes + size > 4L * 1024 * 1024)) {
                publish(accepted, today, now);
                accepted.clear();
                bytes = 0;
            }
            accepted.add(values(candidate, now));
            bytes += size;
        }
        if (deferred.size() > 600)
            throw new IllegalStateException("Snapshot clock wait exceeds bounds");
        if (!accepted.isEmpty()) publish(accepted, today, now);
    }

    private Candidate candidate(ResultSet row) throws Exception {
        byte[] payload = row.getBytes("payload");
        if (!LiveStorageJson.sha256(payload).equals(row.getString("payload_sha256")))
            throw new IllegalStateException("Snapshot source payload hash differs");
        var capture =
                new RawCapture(
                        row.getString("capture_id"),
                        row.getLong("sequence"),
                        row.getString("session_id"),
                        row.getString("topic"),
                        Base64.getEncoder().encodeToString(payload),
                        Instant.parse(row.getString("recv_ts")),
                        LocalDate.parse(row.getString("capture_date")),
                        row.getInt("qos"),
                        row.getBoolean("retained"),
                        row.getBoolean("duplicate"),
                        row.getBoolean("subscription_authorized"));
        String envelope = row.getString("envelope_sha256");
        if (!LiveStorageJson.sha256(LiveStorageJson.bytes(capture)).equals(envelope))
            throw new IllegalStateException("Snapshot source envelope hash differs");
        if (capture.qos() != 0
                || !capture.subscriptionAuthorized()
                || !topics.contains(capture.topic())) {
            rejected++;
            return null;
        }
        var event = parser.parse(capture);
        if (!event.isValid()
                || !event.channel().equals("market")
                || !event.symbol()
                        .equals(AliyunL2WireParser.normalizeSymbol(capture.topic().substring(7)))) {
            rejected++;
            return null;
        }
        return new Candidate(capture, envelope, event);
    }

    private static List<Object> values(Candidate candidate, Instant available) {
        var raw = candidate.raw();
        var event = candidate.event();
        var fields = event.fields();
        return List.of(
                raw.captureId(),
                raw.sequence(),
                raw.sessionId(),
                raw.topic(),
                raw.payloadBase64(),
                LiveStorageJson.sha256(raw.payloadBytes()),
                candidate.envelope(),
                nanos(raw.recvTs()),
                Math.toIntExact(raw.captureDate().toEpochDay()),
                raw.qos(),
                raw.retained(),
                raw.duplicate(),
                raw.subscriptionAuthorized(),
                event.symbol(),
                Math.toIntExact(event.tradeDate().toEpochDay()),
                nanos(event.exchangeTs()),
                nanos(available),
                event.parseStatus(),
                fields.get("parser_version"),
                ((BigDecimal) fields.get("price")).setScale(4),
                ((BigDecimal) fields.get("bid_price_1")).setScale(4),
                ((BigDecimal) fields.get("ask_price_1")).setScale(4),
                fields.get("total_amount"),
                fields.get("total_volume"),
                fields.get("quality_flags"));
    }

    private static long nanos(Instant time) {
        return Math.addExact(
                Math.multiplyExact(time.getEpochSecond(), 1_000_000_000L), time.getNano());
    }

    private static Instant instant(long nanos) {
        return Instant.ofEpochSecond(
                Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }

    private void publish(List<List<Object>> rows, LocalDate date, Instant available)
            throws Exception {
        String id = "market_" + LiveValueIdentity.hash(rows);
        Path temporary = Files.createTempFile(pending, ".snapshot-", ".parquet");
        Files.delete(temporary);
        Path target = batches.resolve(id + ".parquet");
        try {
            if (Files.getFileStore(root).getUsableSpace() < 64L * 1024 * 1024)
                throw new IllegalStateException("Snapshot storage headroom exhausted");
            try (var writer = LiveParquetCodec.writer(temporary, SCHEMA, "market_snapshots", id)) {
                for (var row : rows) writer.write(group(row));
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.size(temporary) > 16L * 1024 * 1024)
                throw new IllegalStateException("Snapshot file exceeds bounds");
            String hash = ParquetBatchStore.sha(temporary);
            int[] position = {0};
            // 同一份文件的所有类型化字段逐值回读；读前后 SHA 固定后才能发布索引。
            LiveParquetCodec.read(
                    temporary,
                    SCHEMA,
                    "market_snapshots",
                    id,
                    MAX_ROWS,
                    group -> {
                        if (position[0] >= rows.size()
                                || !rows.get(position[0]++).equals(values(group)))
                            throw new IllegalStateException("Snapshot readback values differ");
                    });
            if (position[0] != rows.size() || !hash.equals(ParquetBatchStore.sha(temporary)))
                throw new IllegalStateException("Snapshot readback coverage or SHA differs");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!hash.equals(ParquetBatchStore.sha(target)))
                    throw new IllegalStateException("Snapshot identity collision");
                Files.delete(temporary);
                temporary = null;
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
                ParquetBatchStore.syncDirectory(batches);
            }
            if (!hash.equals(ParquetBatchStore.sha(target)))
                throw new IllegalStateException("Published snapshot SHA differs");
            long minimum = rows.stream().mapToLong(row -> (Long) row.get(1)).min().orElseThrow();
            long maximum = rows.stream().mapToLong(row -> (Long) row.get(1)).max().orElseThrow();
            Instant earliest =
                    instant(rows.stream().mapToLong(row -> (Long) row.get(15)).min().orElseThrow());
            Instant latest =
                    instant(rows.stream().mapToLong(row -> (Long) row.get(15)).max().orElseThrow());
            index.setAutoCommit(false);
            boolean settled = false;
            try {
                try (var query =
                        index.prepareStatement(
                                "INSERT INTO market_snapshot_batches VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    query.setString(1, id);
                    query.setString(2, date.toString());
                    query.setString(3, "batches/" + id + ".parquet");
                    query.setString(4, hash);
                    query.setInt(5, rows.size());
                    query.setLong(6, minimum);
                    query.setLong(7, maximum);
                    query.setString(8, available.toString());
                    query.setString(9, earliest.toString());
                    query.setString(10, latest.toString());
                    query.executeUpdate();
                }
                try (var query =
                        index.prepareStatement(
                                "INSERT INTO market_snapshot_members VALUES(?,?,?)")) {
                    for (var row : rows) {
                        query.setString(1, (String) row.get(0));
                        query.setLong(2, (Long) row.get(1));
                        query.setString(3, id);
                        query.addBatch();
                    }
                    query.executeBatch();
                }
                index.commit();
                settled = true;
            } catch (Exception failure) {
                try {
                    index.rollback();
                    settled = true;
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    throw new IllegalStateException(
                            "SNAPSHOT_INDEX_TRANSACTION_UNRESOLVED", failure);
                }
                throw failure;
            } finally {
                if (settled) index.setAutoCommit(true);
            }
            publishedRows += rows.size();
            lastPublished = available;
            if (latestExchange == null || latest.isAfter(latestExchange)) latestExchange = latest;
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    private static Group group(List<Object> values) {
        var group = new SimpleGroupFactory(SCHEMA).newGroup();
        for (int i = 0; i < values.size(); i++) {
            String name = SCHEMA.getFieldName(i);
            Object value = values.get(i);
            if (name.equals("payload"))
                group.add(
                        name,
                        Binary.fromConstantByteArray(Base64.getDecoder().decode((String) value)));
            else if (value instanceof BigDecimal decimal) {
                byte[] original = decimal.unscaledValue().toByteArray();
                if (decimal.precision() > 38 || original.length > 16)
                    throw new IllegalArgumentException("Bounded snapshot decimal required");
                byte[] fixed = new byte[16];
                Arrays.fill(fixed, (byte) (decimal.signum() < 0 ? 0xff : 0));
                System.arraycopy(original, 0, fixed, 16 - original.length, original.length);
                group.add(name, Binary.fromConstantByteArray(fixed));
            } else if (value instanceof String text) group.add(name, text);
            else if (value instanceof Long number) group.add(name, number);
            else if (value instanceof Integer number) group.add(name, number);
            else if (value instanceof Boolean flag) group.add(name, flag);
            else throw new IllegalArgumentException("Unsupported snapshot column");
        }
        return group;
    }

    private static List<Object> values(Group group) {
        var row = new ArrayList<Object>();
        for (var field : SCHEMA.getFields()) {
            String name = field.getName();
            Object value =
                    switch (field.asPrimitiveType().getPrimitiveTypeName()) {
                        case INT64 -> group.getLong(name, 0);
                        case INT32 -> group.getInteger(name, 0);
                        case BOOLEAN -> group.getBoolean(name, 0);
                        case FIXED_LEN_BYTE_ARRAY ->
                                new BigDecimal(
                                        new BigInteger(group.getBinary(name, 0).getBytes()), 4);
                        case BINARY ->
                                name.equals("payload")
                                        ? Base64.getEncoder()
                                                .encodeToString(group.getBinary(name, 0).getBytes())
                                        : group.getString(name, 0);
                        default ->
                                throw new IllegalStateException("Unknown snapshot physical type");
                    };
            row.add(value);
        }
        return List.copyOf(row);
    }

    private void updateStatus(String phase, String failure) {
        var values = new LinkedHashMap<String, Object>();
        values.put("phase", phase);
        values.put("product", "native_market_snapshots");
        values.put("publishedRows", publishedRows);
        values.put("rejectedRows", rejected);
        values.put("clockWaitRows", deferred.size());
        values.put("lastSourceSequence", cursor);
        values.put("fullMicroStateReady", false);
        values.put("at", clock.instant().toString());
        if (latestExchange != null) values.put("latestExchangeTs", latestExchange.toString());
        if (lastPublished != null) values.put("lastPublishedAt", lastPublished.toString());
        if (failure != null) values.put("failureCode", failure);
        current = Map.copyOf(values);
    }

    @Override
    public void close() throws Exception {
        synchronized (lifecycle) {
            closing = true;
            lifecycle.notifyAll();
        }
        if (started) {
            worker.join(30000);
            if (worker.isAlive())
                throw new IllegalStateException("Snapshot publisher still draining");
        }
        index.close();
        lock.close();
        lockChannel.close();
    }
}
