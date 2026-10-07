package com.zoutrankil.data.derived.storage;
import com.zoutrankil.data.derived.port.MacroCoreMonthlyWriteSession;
import com.zoutrankil.data.derived.domain.MacroCoreMonthlyRows;

import com.zoutrankil.data.repository.StaticTargetIdentity;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyMapper;
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

/** Isolated, bounded D104 publisher. One explicit HTTP flush; no retry or close-time submission. */
public class MacroCoreMonthlyWritePort implements MacroCoreMonthlyWriteSession {
    public static final int MAX_ROWS = 12;
    public static final int MAX_BYTES = 1024 * 1024;
    public static final Duration ACK_TIMEOUT = Duration.ofSeconds(20);
    public static final VerifiedBatchExecutor.Codec<MacroCoreMonthly,YearMonth> CODEC=MacroCoreMonthlyWriteSession.CODEC;


    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDbProperties properties;
    private final Supplier<Sender> senderFactory;
    private final MacroCoreMonthlyMapper mapper = new MacroCoreMonthlyMapper();
    private volatile String frozenTargetId;
    private volatile boolean senderActive;
    private volatile boolean senderStopped;
    private volatile boolean unresolved;

    public MacroCoreMonthlyWritePort(JdbcTemplate jdbc, QuestDbProperties properties, String isolatedTable) {
        this(jdbc, properties, isolatedTable, () -> httpSender(properties));
    }
    /** Injection changes only sender construction; production guards remain mandatory. */
    public MacroCoreMonthlyWritePort(JdbcTemplate jdbc, QuestDbProperties properties, String isolatedTable,
                                       Supplier<Sender> senderFactory) {
        requireIsolatedTable(isolatedTable);
        this.table = isolatedTable; this.properties = Objects.requireNonNull(properties);
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(Objects.requireNonNull(jdbc).getDataSource()));
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(64); this.jdbc.setFetchSize(32);
        this.senderFactory = Objects.requireNonNull(senderFactory);
    }
    public static String requireIsolatedTable(String table){return MacroCoreMonthlyRows.requireIsolatedTable(table);}
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
            String fields = String.join(",", MacroCoreMonthlyDataset.STORAGE_COLUMNS.stream()
                    .map(c -> "\"" + c + "\" " + (c.equals("month") ? "TIMESTAMP" : "DOUBLE")).toList());
            jdbc.execute("CREATE TABLE \"" + table + "\" (" + fields
                    + ") TIMESTAMP(month) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(month)");
        }
        preflight();
    }
    @Override public synchronized void preflight() {
        requireEndpoint();
        if (senderActive || unresolved) throw new IllegalStateException("Unresolved D104 submission; reconcile before a new send");
        var snapshot = targetSnapshot();
        requireIdentity(snapshot);
        if (!snapshot.settled()) throw new IllegalStateException("D104 target WAL is not settled");
        QuestDbWriteChecks.preflight(jdbc, table, MacroCoreMonthlyDataset.definition(table));
        var after = targetSnapshot(); requireIdentity(after);
        if (!after.settled()) throw new IllegalStateException("D104 WAL changed during preflight");
    }

    @Override public synchronized void send(List<MacroCoreMonthly> rows) throws Exception {
        requireBatch(rows); preflight();
        Sender sender = null; boolean attempted = false; boolean acknowledged = false; Throwable failure = null;
        senderStopped = false; senderActive = true;
        try {
            sender = Objects.requireNonNull(senderFactory.get(), "sender required");
            for (var value : rows) {
                var line = sender.table(table); var values = mapper.values(value).asMap();
                for (int i = 1; i < MacroCoreMonthlyDataset.STORAGE_COLUMNS.size(); i++) {
                    String column = MacroCoreMonthlyDataset.STORAGE_COLUMNS.get(i);
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

    public static void requireBatch(List<MacroCoreMonthly> rows){MacroCoreMonthlyRows.requireBatch(rows);}

    @Override public List<MacroCoreMonthly> readback(List<YearMonth> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 12 unique complete month keys required");
        keys.forEach(MacroCoreMonthlyKey::new);
        var before = targetSnapshot(); requireIdentity(before);
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        for (var month : keys) {
            clauses.add("month=cast(? AS TIMESTAMP)");
            parameters.add(new MacroCoreMonthlyKey(month).storageMicros());
        }
        var rows = jdbc.query(projection() + " WHERE " + String.join(" OR ", clauses)
                + " ORDER BY month LIMIT " + (keys.size() + 1), (rs, index) -> physical(rs), parameters.toArray());
        requireRows(rows, keys.size()); requireStable(before, targetSnapshot());
        return List.copyOf(rows);
    }

    public List<MacroCoreMonthly> readActualRange(YearMonth from, YearMonth toInclusive) {
        var start = new MacroCoreMonthlyKey(from); var end = new MacroCoreMonthlyKey(toInclusive);
        long months = ChronoUnit.MONTHS.between(from, toInclusive) + 1;
        if (months < 1 || months > MAX_ROWS) throw new IllegalArgumentException("D104 range must contain 1..12 months");
        var before = targetSnapshot(); requireIdentity(before);
        long upper = new MacroCoreMonthlyKey(end.month().plusMonths(1)).storageMicros();
        var rows = jdbc.query(projection() + " WHERE month>=cast(? AS TIMESTAMP) AND month<cast(? AS TIMESTAMP)"
                + " ORDER BY month LIMIT 13", (rs, index) -> physical(rs), start.storageMicros(), upper);
        requireRows(rows, (int) months); requireStable(before, targetSnapshot());
        return List.copyOf(rows);
    }

    @Override public boolean walSettled() {
        var snapshot = targetSnapshot(); requireIdentity(snapshot); return snapshot.settled();
    }
    @Override public boolean uncertainSenderStopped() { return senderStopped && !senderActive; }

    public MacroCoreMonthlyTargetSnapshot targetSnapshot() {
        var before = metadata();
        var schema = jdbc.queryForList("SELECT \"column\",\"type\",designated,\"upsertKey\" FROM table_columns('" + table + "') LIMIT 10");
        if (schema.size() != 9) throw new IllegalStateException("D104 target must contain exactly nine columns");
        var expected = MacroCoreMonthlyDataset.STORAGE_COLUMNS; var canonical = new StringBuilder();
        for (int i = 0; i < schema.size(); i++) {
            var column = schema.get(i); String name = Objects.toString(column.get("column"), "");
            String type = Objects.toString(column.get("type"), "");
            boolean key = flag(column.get("upsertKey"));
            boolean designated = flag(column.get("designated"));
            if (!name.equals(expected.get(i)) || !type.equals(i == 0 ? "TIMESTAMP" : "DOUBLE")
                    || key != (i == 0) || designated != (i == 0))
                throw new IllegalStateException("D104 exact physical schema or UPSERT key changed");
            canonical.append(name).append(':').append(type).append(':').append(key).append(':').append(designated).append('\n');
        }
        var count = jdbc.queryForList("SELECT count() AS n FROM \"" + table + "\"");
        if (count.size() != 1) throw new IllegalStateException("Independent target row count missing");
        long rows = counter(count.getFirst().get("n"), "actual count");
        var after = metadata();
        if (!before.equals(after)) throw new IllegalStateException("D104 metadata changed while reading its snapshot");
        long id = counter(before.get("id"), "table id"); String directory = requiredString(before.get("directoryName"));
        String schemaHash = hash(canonical.toString());
        String identity = targetIdentity(StaticTargetIdentity.identify(jdbc, table, id, directory), schemaHash);
        boolean suspended = flag(before.get("table_suspended")) || flag(before.get("suspended"));
        if (!flag(before.get("walEnabled")) || !flag(before.get("dedup")) || flag(before.get("matView"))
                || !"YEAR".equals(before.get("partitionBy"))
                || !"month".equals(before.get("designatedTimestamp")))
            throw new IllegalStateException("D104 YEAR/WAL/month contract changed");
        return new MacroCoreMonthlyTargetSnapshot(identity, id, directory, schemaHash, nullableCounter(before.get("table_txn")),
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
    private synchronized void requireIdentity(MacroCoreMonthlyTargetSnapshot snapshot) {
        if (frozenTargetId == null) frozenTargetId = snapshot.targetId();
        if (!frozenTargetId.equals(snapshot.targetId())) throw new IllegalStateException("D104 physical target identity changed");
    }
    private static void requireStable(MacroCoreMonthlyTargetSnapshot before, MacroCoreMonthlyTargetSnapshot after) {
        if (!before.equals(after) || !after.settled()) throw new IllegalStateException("D104 target changed or WAL is unsettled during readback");
    }
    private static void requireRows(List<MacroCoreMonthly> rows, int max) {
        if (rows.size() > max || rows.stream().map(MacroCoreMonthly::month).distinct().count() != rows.size())
            throw new IllegalStateException("Unexpected extra or duplicate month rows");
    }
    private String projection() {
        return "SELECT " + String.join(",", MacroCoreMonthlyDataset.STORAGE_COLUMNS.stream()
                .map(c -> c.equals("month") ? "cast(month AS long) AS month_micros" : "\"" + c + "\"").toList())
                + " FROM \"" + table + "\"";
    }
    private MacroCoreMonthly physical(ResultSet rs) throws SQLException {
        Object raw = rs.getObject("month_micros");
        if (!(raw instanceof Long micros)) throw new SQLException("Exact TIMESTAMP micros required");
        var key = MacroCoreMonthlyKey.fromStorage(TemporalValues.epoch(micros, TemporalValues.EpochUnit.MICROS,
                TemporalValues.Precision.MICROS));
        var values = new LinkedHashMap<String, Object>(); values.put("month", key.storageDate());
        for (int i = 1; i < MacroCoreMonthlyDataset.STORAGE_COLUMNS.size(); i++) {
            String column = MacroCoreMonthlyDataset.STORAGE_COLUMNS.get(i); Object value = rs.getObject(column);
            if (value != null && !(value instanceof Double)) throw new SQLException("Exact DOUBLE storage value required: " + column);
            values.put(column, value);
        }
        try { return mapper.fromValues(values); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid physical D104 row", invalid); }
    }
    private void requireEndpoint() {
        String url = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getURL());
        try {
            if (url == null || !url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            URI endpoint = URI.create(url.substring(5).split("\\?", 2)[0]);
            if (!Objects.equals(endpoint.getHost(), properties.getHost()) || endpoint.getPort() != properties.getPgPort()
                    || !Objects.equals(endpoint.getPath(), "/" + properties.getDatabase()))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalStateException("D104 PGWire and ILP endpoint configuration disagree"); }
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
        return "d104-" + hash(staticId + "\n" + schemaHash);
    }
}

