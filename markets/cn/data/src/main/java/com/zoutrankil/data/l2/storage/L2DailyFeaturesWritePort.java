package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesDataset;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.l2.domain.L2DailyFeaturesRows;
import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Bounded D086 QWP writer with isolated targets and an explicit fixed formal-table factory. */
public final class L2DailyFeaturesWritePort implements L2DailyFeaturesWriteSession {
    public static final String ISOLATED_TABLE_PREFIX = L2DailyFeaturesRows.ISOLATED_TABLE_PREFIX;
    public static final int MAX_BATCH_ROWS = L2DailyFeaturesRows.MAX_BATCH_ROWS;
    public static final int MAX_BATCH_BYTES = L2DailyFeaturesRows.MAX_BATCH_BYTES;
    public static final int MAX_READBACK_KEYS = L2DailyFeaturesRows.MAX_READBACK_KEYS;
    private static final List<String> SELECT_COLUMNS = L2DailyFeaturesMapper.columns();

    public static final VerifiedBatchExecutor.Codec<L2DailyFeatures, L2DailyFeaturesKey> CODEC =
            L2DailyFeaturesWriteSession.CODEC;

    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final L2DailyFeaturesMapper mapper = new L2DailyFeaturesMapper();
    private final AtomicBoolean senderActive = new AtomicBoolean();
    private final boolean formal;

    public L2DailyFeaturesWritePort(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this(table, jdbc, questdb, false);
    }

    private L2DailyFeaturesWritePort(
            String table, JdbcTemplate jdbc, QuestDB questdb, boolean formal) {
        if (formal) {
            if (!"l2_daily_features".equals(table))
                throw new IllegalArgumentException("Exact formal L2 table required");
        } else requireIsolatedTableName(table);
        this.formal = formal;
        this.table = table;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20);
        this.questdb = Objects.requireNonNull(questdb);
    }

    /** Admit only the existing canonical table; this method performs no DDL or history repair. */
    public static L2DailyFeaturesWritePort formal(JdbcTemplate jdbc, QuestDB questdb) {
        return new L2DailyFeaturesWritePort("l2_daily_features", jdbc, questdb, true);
    }

    @Override
    public VerifiedBatchExecutor.Codec<L2DailyFeatures, L2DailyFeaturesKey> codec() {
        return CODEC;
    }

    public String tableName() {
        return table;
    }

    public void createIsolatedTargetIfMissing() {
        requireIsolatedTableName(table);
        String columns =
                L2DailyFeatureField.values().length == 0
                        ? ""
                        : Arrays.stream(L2DailyFeatureField.values())
                                .map(L2DailyFeaturesWritePort::ddlColumn)
                                .collect(Collectors.joining(", "));
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS \""
                        + table
                        + "\" ("
                        + columns
                        + ") TIMESTAMP(ts) PARTITION BY DAY WAL DEDUP UPSERT KEYS(ts,symbol)");
        preflight();
    }

    @Override
    public void preflight() {
        if (!formal) requireIsolatedTableName(table);
        QuestDbWriteChecks.preflight(jdbc, table, L2DailyFeaturesDataset.DEFINITION);
    }

    @Override
    public void send(List<L2DailyFeatures> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("One nonempty bounded D086 batch is required");
        long byteBudget = formal ? L2DailyFeaturesRows.MAX_FORMAL_BATCH_BYTES : MAX_BATCH_BYTES;
        var normalized =
                DatasetWritePreparation.prepare(
                        L2DailyFeaturesDataset.DEFINITION,
                        rows,
                        mapper::values,
                        new DatasetWritePreparation.Limits(
                                MAX_BATCH_ROWS, Math.toIntExact(byteBudget)));
        long estimated =
                rows.stream()
                        .mapToLong(
                                row ->
                                        CODEC.estimatedTransportBytes(
                                                row, CODEC.canonicalBytes(row)))
                        .sum();
        if (normalized.empty() || estimated > byteBudget)
            throw new IllegalArgumentException(
                    "D086 batch exceeds its normalized or transport byte budget");
        if (!senderActive.compareAndSet(false, true))
            throw new IllegalStateException("D086 writer already active");
        try (Sender sender = questdb.borrowSender()) {
            for (L2DailyFeatures value : rows) {
                var line = sender.table(table).symbol("symbol", value.symbol());
                for (var field : L2DailyFeatureField.values()) {
                    if (field.identity()) continue;
                    Object metric = value.features().get(field);
                    if (metric == null) continue;
                    line =
                            switch (field.storageType()) {
                                case BOOLEAN ->
                                        line.boolColumn(field.fieldName(), (Boolean) metric);
                                case LONG -> line.longColumn(field.fieldName(), (Long) metric);
                                case DOUBLE ->
                                        line.doubleColumn(field.fieldName(), (Double) metric);
                                case STRING ->
                                        line.stringColumn(field.fieldName(), (String) metric);
                                default ->
                                        throw new IllegalArgumentException(
                                                "Unexpected writable D086 field type: "
                                                        + field.fieldName());
                            };
                }
                line.at(new TemporalValues.CalendarTimestamp(value.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10_000))
                throw new IllegalStateException(
                        "D086 QWP acknowledgement unknown; reconcile before replay");
        } finally {
            senderActive.set(false);
        }
    }

    @Override
    public List<L2DailyFeatures> readback(List<L2DailyFeaturesKey> keys) {
        if (keys == null
                || keys.isEmpty()
                || keys.size() > MAX_READBACK_KEYS
                || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException(
                    "At most 200 unique D086 keys may be verified at once");
        String projection =
                String.join(
                        ", ",
                        SELECT_COLUMNS.stream()
                                .map(
                                        column ->
                                                column.equals("ts")
                                                        ? "cast(ts as long) AS ts_micros"
                                                        : column)
                                .toList());
        var keysByDate = new TreeMap<LocalDate, List<L2DailyFeaturesKey>>();
        for (var key : keys)
            keysByDate.computeIfAbsent(key.tradeDate(), ignored -> new ArrayList<>()).add(key);
        var rows = new ArrayList<L2DailyFeatures>();
        for (var entry : keysByDate.entrySet()) {
            var dateKeys = entry.getValue();
            var parameters = new ArrayList<Object>(dateKeys.size() + 1);
            parameters.add(
                    new TemporalValues.CalendarTimestamp(entry.getKey())
                            .storageEpoch(TemporalValues.EpochUnit.MICROS));
            for (var key : dateKeys) parameters.add(key.symbol());
            String symbols = String.join(", ", Collections.nCopies(dateKeys.size(), "?"));
            String sql =
                    "SELECT "
                            + projection
                            + " FROM \""
                            + table
                            + "\" WHERE ts=cast(? as TIMESTAMP)"
                            + " AND symbol IN ("
                            + symbols
                            + ") ORDER BY ts,symbol LIMIT "
                            + (dateKeys.size() + 1);
            rows.addAll(
                    jdbc.query(
                            connection -> {
                                var statement = connection.prepareStatement(sql);
                                statement.setQueryTimeout(20);
                                statement.setMaxRows(dateKeys.size() + 1);
                                statement.setFetchSize(dateKeys.size() + 1);
                                for (int i = 0; i < parameters.size(); i++) {
                                    Object parameter = parameters.get(i);
                                    if (parameter instanceof String text)
                                        statement.setString(i + 1, text);
                                    else if (parameter instanceof Long number)
                                        statement.setLong(i + 1, number);
                                    else throw new SQLException("Unsupported D086 key binding");
                                }
                                return statement;
                            },
                            (rs, index) -> mapper.fromValues(readValues(rs))));
        }
        return List.copyOf(rows);
    }

    public List<LocalDate> readExistingDates() {
        if (formal)
            throw new IllegalStateException(
                    "Formal L2 history must be inspected by an explicit bounded date");
        String sql = "SELECT ts FROM \"" + table + "\" GROUP BY ts ORDER BY ts LIMIT 32";
        return jdbc.query(
                sql,
                (rs, index) -> {
                    if (index > 31)
                        throw new IllegalStateException(
                                "D086 checkpoint date scan exceeded its bound");
                    String timestamp = rs.getString("ts");
                    if (timestamp == null || timestamp.length() < 10)
                        throw new SQLException("D086 target ts is malformed");
                    return LocalDate.parse(timestamp.substring(0, 10));
                });
    }

    public int countRows(LocalDate date) {
        Long count =
                jdbc.queryForObject(
                        "SELECT count() FROM \"" + table + "\" WHERE ts=cast(? as TIMESTAMP)",
                        Long.class,
                        new TemporalValues.CalendarTimestamp(date)
                                .storageEpoch(TemporalValues.EpochUnit.MICROS));
        if (count == null) return 0;
        if (count > Integer.MAX_VALUE)
            throw new IllegalStateException("D086 row count exceeds its bound");
        return count.intValue();
    }

    @Override
    public boolean walSettled() {
        return QuestDbWriteChecks.walSettled(jdbc, table);
    }

    @Override
    public boolean uncertainSenderStopped() {
        return !senderActive.get();
    }

    public static void requireIsolatedTableName(String table) {
        L2DailyFeaturesRows.requireIsolatedTableName(table);
    }

    private DatasetValues readValues(ResultSet rs) throws SQLException {
        long micros = rs.getLong("ts_micros");
        if (rs.wasNull()) throw new SQLException("D086 designated timestamp is null");
        LocalDate date =
                TemporalValues.epoch(
                                micros,
                                TemporalValues.EpochUnit.MICROS,
                                TemporalValues.Precision.MICROS)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate();
        var values = new LinkedHashMap<String, Object>();
        values.put("ts", date);
        for (var field : L2DailyFeatureField.values()) {
            if (field == L2DailyFeatureField.TS) continue;
            Object value = rs.getObject(field.fieldName());
            values.put(field.fieldName(), normalizeResult(field, value));
        }
        return new DatasetValues(values);
    }

    private static Object normalizeResult(L2DailyFeatureField field, Object value)
            throws SQLException {
        if (value == null) return null;
        try {
            return field.normalize(value);
        } catch (IllegalArgumentException failure) {
            throw new SQLException("D086 readback type mismatch: " + field.fieldName(), failure);
        }
    }

    private static String ddlColumn(L2DailyFeatureField field) {
        String type =
                switch (field.storageType()) {
                    case TIMESTAMP -> "TIMESTAMP";
                    case SYMBOL -> "SYMBOL";
                    case STRING -> "STRING";
                    case BOOLEAN -> "BOOLEAN";
                    case LONG -> "LONG";
                    case DOUBLE -> "DOUBLE";
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported D086 QuestDB type: " + field.fieldName());
                };
        return field.fieldName() + " " + type;
    }
}
