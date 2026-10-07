package com.zoutrankil.data.repository;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.EquityStyleMonthlyMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/** Isolated, bounded D103 publisher. One explicit HTTP flush; no retry or close-time submission. */
public class EquityStyleMonthlyWritePort implements VerifiedBatchExecutor.Port<EquityStyleMonthly, YearMonth> {
    public static final int MAX_ROWS = 12;
    public static final int MAX_BYTES = 1024 * 1024;
    public static final Duration ACK_TIMEOUT = Duration.ofSeconds(20);
    public static final VerifiedBatchExecutor.Codec<EquityStyleMonthly, YearMonth> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public YearMonth key(EquityStyleMonthly row) { return Objects.requireNonNull(row, "row required").month(); }
        @Override public byte[] canonicalBytes(EquityStyleMonthly row) {
            Objects.requireNonNull(row, "row required");
            try {
                var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
                out.writeInt(row.month().getYear()); out.writeByte(row.month().getMonthValue());
                var values = new EquityStyleMonthlyMapper().values(row).asMap();
                int nulls = 0;
                for (int i = 1; i < EquityStyleMonthlyDataset.STORAGE_COLUMNS.size(); i++)
                    if (values.get(EquityStyleMonthlyDataset.STORAGE_COLUMNS.get(i)) == null) nulls |= 1 << (i - 1);
                out.writeInt(nulls);
                for (int i = 1; i < EquityStyleMonthlyDataset.STORAGE_COLUMNS.size(); i++) {
                    Double value = (Double) values.get(EquityStyleMonthlyDataset.STORAGE_COLUMNS.get(i));
                    if (value != null) out.writeLong(Double.doubleToRawLongBits(value));
                }
                out.flush(); return bytes.toByteArray();
            } catch (java.io.IOException impossible) { throw new IllegalStateException("Cannot canonicalize equity style row", impossible); }
        }
        @Override public int estimatedTransportBytes(EquityStyleMonthly row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 2048);
        }
    };


    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDbProperties properties;
    private final Supplier<Sender> senderFactory;
    private final EquityStyleMonthlyMapper mapper = new EquityStyleMonthlyMapper();
    private volatile String frozenTargetId;
    private volatile boolean senderActive;
    private volatile boolean senderStopped;
    private volatile boolean unresolved;

    public EquityStyleMonthlyWritePort(JdbcTemplate jdbc, QuestDbProperties properties, String isolatedTable) {
        this(jdbc, properties, isolatedTable, () -> httpSender(properties));
    }
    /** Injection changes only sender construction; production guards remain mandatory. */
    public EquityStyleMonthlyWritePort(JdbcTemplate jdbc, QuestDbProperties properties, String isolatedTable,
                                       Supplier<Sender> senderFactory) {
        requireIsolatedTable(isolatedTable);
        this.table = isolatedTable; this.properties = Objects.requireNonNull(properties);
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(Objects.requireNonNull(jdbc).getDataSource()));
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(64); this.jdbc.setFetchSize(32);
        this.senderFactory = Objects.requireNonNull(senderFactory);
    }
    public static String requireIsolatedTable(String table) {
        if (table == null || !table.matches("java_d103_equity_style_monthly_[a-z0-9]{1,64}"))
            throw new IllegalArgumentException("Explicit isolated D103 table required");
        return table;
    }
    public String table() { return table; }
    public String targetId() { return targetSnapshot().targetId(); }
    public boolean unresolved() { return unresolved; }

    /** Missing-only DDL. An existing target is checked in place; no reset, ALTER or repair. */
    public synchronized void createIsolatedTarget() {
        requireEndpoint();
        if (senderActive || unresolved) throw new IllegalStateException("Unresolved sender cannot install a target");
        var existing = jdbc.queryForList("SELECT id FROM tables() WHERE table_name=? LIMIT 2", table);
        if (existing.size() > 1) throw new IllegalStateException("Ambiguous isolated target");
        if (existing.isEmpty()) {
            String fields = String.join(",", EquityStyleMonthlyDataset.STORAGE_COLUMNS.stream()
                    .map(c -> "\"" + c + "\" " + (c.equals("month") ? "TIMESTAMP" : "DOUBLE")).toList());
            jdbc.execute("CREATE TABLE \"" + table + "\" (" + fields
                    + ") TIMESTAMP(month) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(month)");
        }
        preflight();
    }
    @Override public synchronized void preflight() {
        requireEndpoint();
        if (senderActive || unresolved) throw new IllegalStateException("Unresolved D103 submission; reconcile before a new send");
        var snapshot = targetSnapshot();
        requireIdentity(snapshot);
        if (!snapshot.settled()) throw new IllegalStateException("D103 target WAL is not settled");
        QuestDbWriteChecks.preflight(jdbc, table, EquityStyleMonthlyDataset.definition(table));
        var after = targetSnapshot(); requireIdentity(after);
        if (!after.settled()) throw new IllegalStateException("D103 WAL changed during preflight");
    }

    @Override public synchronized void send(List<EquityStyleMonthly> rows) throws Exception {
        requireBatch(rows); preflight();
        Sender sender = null; boolean attempted = false; boolean acknowledged = false; Throwable failure = null;
        senderStopped = false; senderActive = true;
        try {
            sender = Objects.requireNonNull(senderFactory.get(), "sender required");
            for (var value : rows) {
                var line = sender.table(table); var values = mapper.values(value).asMap();
                for (int i = 1; i < EquityStyleMonthlyDataset.STORAGE_COLUMNS.size(); i++) {
                    String column = EquityStyleMonthlyDataset.STORAGE_COLUMNS.get(i);
                    Double number = (Double) values.get(column);
                    if (number != null) line.doubleColumn(column, number);
                }
                line.at(value.key().storageCarrier());
            }
            // The synchronous HTTP sender returns only after its one request is acknowledged.
            // Retry timeout is zero and auto flush is disabled in httpSender().
            attempted = true;
            sender.flush();
            acknowledged = true;
        } catch (Throwable error) { failure = error; throw error; }
        finally {
            try {
                if (sender != null) {
                    // Discard buffered rows before close, including failures while constructing a row.
                    // With auto flush disabled, neither reset nor close sends another request.
                    sender.reset(); sender.close();
                }
                senderStopped = true; senderActive = false;
            } catch (Throwable closeFailure) {
                senderStopped = false; senderActive = true; unresolved = true;
                if (failure != null) failure.addSuppressed(closeFailure); else throw closeFailure;
            } finally {
                if (attempted && !acknowledged) unresolved = true;
            }
        }
    }

    public static void requireBatch(List<EquityStyleMonthly> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS)
            throw new IllegalArgumentException("Nonempty D103 batch of at most 12 months required");
        var months = new HashSet<YearMonth>(); long bytes = 0;
        for (var row : rows) {
            if (!months.add(CODEC.key(row))) throw new IllegalArgumentException("Duplicate complete month key");
            byte[] canonical = CODEC.canonicalBytes(row);
            bytes = Math.addExact(bytes, CODEC.estimatedTransportBytes(row, canonical));
            if (bytes > MAX_BYTES) throw new IllegalArgumentException("D103 batch exceeds one MiB");
            if (new EquityStyleMonthlyMapper().values(row).asMap().entrySet().stream()
                    .noneMatch(e -> !e.getKey().equals("month") && e.getValue() != null))
                throw new IllegalArgumentException("A timestamp-only all-null row cannot be transported as ILP");
        }
    }

    @Override public List<EquityStyleMonthly> readback(List<YearMonth> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 12 unique complete month keys required");
        keys.forEach(EquityStyleMonthlyKey::new);
        var before = targetSnapshot(); requireIdentity(before);
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        for (var month : keys) {
            clauses.add("month=cast(? AS TIMESTAMP)");
            parameters.add(new EquityStyleMonthlyKey(month).storageMicros());
        }
        var rows = jdbc.query(projection() + " WHERE " + String.join(" OR ", clauses)
                + " ORDER BY month LIMIT " + (keys.size() + 1), (rs, index) -> physical(rs), parameters.toArray());
        requireRows(rows, keys.size()); requireStable(before, targetSnapshot());
        return List.copyOf(rows);
    }

    public List<EquityStyleMonthly> readActualRange(YearMonth from, YearMonth toInclusive) {
        var start = new EquityStyleMonthlyKey(from); var end = new EquityStyleMonthlyKey(toInclusive);
        long months = ChronoUnit.MONTHS.between(from, toInclusive) + 1;
        if (months < 1 || months > MAX_ROWS) throw new IllegalArgumentException("D103 range must contain 1..12 months");
        var before = targetSnapshot(); requireIdentity(before);
        long upper = new EquityStyleMonthlyKey(end.month().plusMonths(1)).storageMicros();
        var rows = jdbc.query(projection() + " WHERE month>=cast(? AS TIMESTAMP) AND month<cast(? AS TIMESTAMP)"
                + " ORDER BY month LIMIT 13", (rs, index) -> physical(rs), start.storageMicros(), upper);
        requireRows(rows, (int) months); requireStable(before, targetSnapshot());
        return List.copyOf(rows);
    }

    @Override public boolean walSettled() {
        var snapshot = targetSnapshot(); requireIdentity(snapshot); return snapshot.settled();
    }
    @Override public boolean uncertainSenderStopped() { return senderStopped && !senderActive; }

    public EquityStyleMonthlyTargetSnapshot targetSnapshot() {
        var before = metadata();
        var schema = jdbc.queryForList("SELECT \"column\",\"type\",designated,\"upsertKey\" FROM table_columns('" + table + "') LIMIT 31");
        if (schema.size() != 30) throw new IllegalStateException("D103 target must contain exactly 30 columns");
        var expected = EquityStyleMonthlyDataset.STORAGE_COLUMNS; var canonical = new StringBuilder();
        for (int i = 0; i < schema.size(); i++) {
            var column = schema.get(i); String name = Objects.toString(column.get("column"), "");
            String type = Objects.toString(column.get("type"), "");
            boolean key = flag(column.get("upsertKey"));
            boolean designated = flag(column.get("designated"));
            if (!name.equals(expected.get(i)) || !type.equals(i == 0 ? "TIMESTAMP" : "DOUBLE")
                    || key != (i == 0) || designated != (i == 0))
                throw new IllegalStateException("D103 exact physical schema or UPSERT key changed");
            canonical.append(name).append(':').append(type).append(':').append(key).append(':').append(designated).append('\n');
        }
        var count = jdbc.queryForList("SELECT count() AS n FROM \"" + table + "\"");
        if (count.size() != 1) throw new IllegalStateException("Independent target row count missing");
        long rows = counter(count.getFirst().get("n"), "actual count");
        var after = metadata();
        if (!before.equals(after)) throw new IllegalStateException("D103 metadata changed while reading its snapshot");
        long id = counter(before.get("id"), "table id"); String directory = requiredString(before.get("directoryName"));
        String schemaHash = hash(canonical.toString());
        String identity = targetIdentity(StaticTargetIdentity.identify(jdbc, table, id, directory), schemaHash);
        boolean suspended = flag(before.get("table_suspended")) || flag(before.get("suspended"));
        if (!flag(before.get("walEnabled")) || !flag(before.get("dedup")) || flag(before.get("matView"))
                || !"YEAR".equals(before.get("partitionBy"))
                || !"month".equals(before.get("designatedTimestamp")))
            throw new IllegalStateException("D103 YEAR/WAL/month contract changed");
        return new EquityStyleMonthlyTargetSnapshot(identity, id, directory, schemaHash, nullableCounter(before.get("table_txn")),
                nullableCounter(before.get("wal_txn")), counter(before.get("sequencerTxn"), "sequence txn"),
                counter(before.get("writerTxn"), "writer txn"), counter(before.get("wal_pending_row_count"), "pending rows"),
                counter(before.get("bufferedTxnSize"), "buffered txns"), suspended, rows,
                nullableCounter(before.get("table_row_count")));
    }

    private Map<String, Object> metadata() {
        var rows = jdbc.queryForList("SELECT t.id,t.directoryName,t.table_txn,t.wal_txn,t.table_row_count,"
                + "t.partitionBy,t.designatedTimestamp,t.walEnabled,t.dedup,t.matView,t.table_suspended,t.wal_pending_row_count,"
                + "w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended FROM tables() t "
                + "JOIN wal_tables() w ON t.table_name=w.name WHERE t.table_name=? LIMIT 2", table);
        if (rows.size() != 1) throw new IllegalStateException("One actual WAL target required");
        return rows.getFirst();
    }
    private synchronized void requireIdentity(EquityStyleMonthlyTargetSnapshot snapshot) {
        if (frozenTargetId == null) frozenTargetId = snapshot.targetId();
        if (!frozenTargetId.equals(snapshot.targetId())) throw new IllegalStateException("D103 physical target identity changed");
    }
    private static void requireStable(EquityStyleMonthlyTargetSnapshot before, EquityStyleMonthlyTargetSnapshot after) {
        if (!before.equals(after) || !after.settled()) throw new IllegalStateException("D103 target changed or WAL is unsettled during readback");
    }
    private static void requireRows(List<EquityStyleMonthly> rows, int max) {
        if (rows.size() > max || rows.stream().map(EquityStyleMonthly::month).distinct().count() != rows.size())
            throw new IllegalStateException("Unexpected extra or duplicate month rows");
    }
    private String projection() {
        return "SELECT " + String.join(",", EquityStyleMonthlyDataset.STORAGE_COLUMNS.stream()
                .map(c -> c.equals("month") ? "cast(month AS long) AS month_micros" : "\"" + c + "\"").toList())
                + " FROM \"" + table + "\"";
    }
    private EquityStyleMonthly physical(ResultSet rs) throws SQLException {
        Object raw = rs.getObject("month_micros");
        if (!(raw instanceof Long micros)) throw new SQLException("Exact TIMESTAMP micros required");
        var key = EquityStyleMonthlyKey.fromStorage(TemporalValues.epoch(micros, TemporalValues.EpochUnit.MICROS,
                TemporalValues.Precision.MICROS));
        var values = new LinkedHashMap<String, Object>(); values.put("month", key.storageDate());
        for (int i = 1; i < EquityStyleMonthlyDataset.STORAGE_COLUMNS.size(); i++) {
            String column = EquityStyleMonthlyDataset.STORAGE_COLUMNS.get(i); Object value = rs.getObject(column);
            if (value != null && !(value instanceof Double)) throw new SQLException("Exact DOUBLE storage value required: " + column);
            values.put(column, value);
        }
        try { return mapper.fromValues(values); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid physical D103 row", invalid); }
    }
    private void requireEndpoint() {
        String url = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getURL());
        try {
            if (url == null || !url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            URI endpoint = URI.create(url.substring(5).split("\\?", 2)[0]);
            if (!Objects.equals(endpoint.getHost(), properties.getHost()) || endpoint.getPort() != properties.getPgPort()
                    || !Objects.equals(endpoint.getPath(), "/" + properties.getDatabase()))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalStateException("D103 PGWire and ILP endpoint configuration disagree"); }
    }
    private static Sender httpSender(QuestDbProperties properties) {
        return Sender.builder(Sender.Transport.HTTP).address(properties.getHost()).port(properties.getQwpPort())
                .httpUsernamePassword(properties.getUsername(), properties.getPassword())
                .disableAutoFlush().retryTimeoutMillis(0).httpTimeoutMillis((int) ACK_TIMEOUT.toMillis())
                .connectTimeoutMillis(5000).build();
    }
    private static boolean flag(Object value) {
        if (!(value instanceof Boolean flag)) throw new IllegalStateException("Boolean target metadata required");
        return flag;
    }
    private static long counter(Object value, String field) {
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 0)
            throw new IllegalStateException("Nonnegative integral metadata required: " + field);
        return ((Number) value).longValue();
    }
    private static Long nullableCounter(Object value) { return value == null ? null : counter(value, "nullable counter"); }
    private static String requiredString(Object value) {
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalStateException("Target directory required");
        return text;
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String targetIdentity(String staticId, String schemaHash) {
        if (staticId == null || !staticId.matches("static-v2-[0-9a-f]{64}")
                || schemaHash == null || !schemaHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen endpoint/table/schema hashes required");
        return "d103-" + hash(staticId + "\n" + schemaHash);
    }
}

