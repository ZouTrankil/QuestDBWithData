package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.L2DatasetManifest;
import com.zoutrankil.data.domain.L2DatasetManifestDataset;
import com.zoutrankil.data.domain.L2DatasetManifestKey;
import com.zoutrankil.data.domain.table.L2DatasetManifestRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.L2DatasetManifestMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded D085 writer; all writes require an explicitly isolated java_d085_* target. */
public final class L2DatasetManifestWritePort
        implements VerifiedBatchExecutor.Port<L2DatasetManifest, L2DatasetManifestKey> {
    public static final String ISOLATED_TABLE_PREFIX = "java_d085_l2_dataset_manifest_";
    public static final int MAX_BATCH_ROWS = 200;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_READBACK_KEYS = 200;
    public static final int MAX_DATE_ROWS = 100_000;
    private static final List<String> SELECT_COLUMNS = List.of("trade_date", "symbol", "market", "board",
            "source_root", "output_root", "feature_version", "daily_feature_ok", "t0_ok",
            "raw_row_counts", "output_paths", "cost_config", "horizons_min", "errors", "batch_id",
            "trade_date_ts");

    public static final VerifiedBatchExecutor.Codec<L2DatasetManifest, L2DatasetManifestKey> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                @Override public L2DatasetManifestKey key(L2DatasetManifest row) { return row.key(); }

                @Override public byte[] canonicalBytes(L2DatasetManifest row) {
                    try {
                        var bytes = new ByteArrayOutputStream(512);
                        try (var output = new DataOutputStream(bytes)) {
                            writeString(output, row.tradeDate().toString());
                            writeString(output, row.symbol());
                            writeNullableString(output, row.market());
                            writeNullableString(output, row.board());
                            writeNullableString(output, row.sourceRoot());
                            writeNullableString(output, row.outputRoot());
                            writeNullableString(output, row.featureVersion());
                            writeNullableBoolean(output, row.dailyFeatureOk());
                            writeNullableBoolean(output, row.t0Ok());
                            writeNullableString(output, row.rawRowCounts());
                            writeNullableString(output, row.outputPaths());
                            writeNullableString(output, row.costConfig());
                            writeNullableString(output, row.horizonsMin());
                            writeNullableString(output, row.errors());
                            output.writeLong(row.batchId());
                            writeString(output, row.tradeDateTs().toString());
                        }
                        return bytes.toByteArray();
                    } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
                }

                @Override public int estimatedTransportBytes(L2DatasetManifest row, byte[] canonical) {
                    return Math.addExact(Math.multiplyExact(canonical.length, 8), 256);
                }
            };

    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final L2DatasetManifestMapper mapper = new L2DatasetManifestMapper();
    private final AtomicBoolean senderActive = new AtomicBoolean();

    public L2DatasetManifestWritePort(String table, JdbcTemplate jdbc, QuestDB questdb) {
        requireIsolatedTableName(table);
        this.table = table;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20);
        this.questdb = Objects.requireNonNull(questdb);
    }

    public String tableName() { return table; }

    /** Explicit setup command only. It cannot target the production object name. */
    public void createIsolatedTargetIfMissing() {
        requireIsolatedTableName(table);
        String ddl = "CREATE TABLE IF NOT EXISTS \"" + table + "\" ("
                + "trade_date STRING, symbol SYMBOL, market STRING, board STRING, "
                + "source_root STRING, output_root STRING, feature_version STRING, "
                + "daily_feature_ok BOOLEAN, t0_ok BOOLEAN, raw_row_counts STRING, output_paths STRING, "
                + "cost_config STRING, horizons_min STRING, errors STRING, batch_id LONG, trade_date_ts TIMESTAMP) "
                + "TIMESTAMP(trade_date_ts) PARTITION BY DAY WAL "
                + "DEDUP UPSERT KEYS(symbol,batch_id,trade_date_ts)";
        jdbc.execute(ddl);
        preflight();
    }

    @Override public void preflight() {
        requireIsolatedTableName(table);
        QuestDbWriteChecks.preflight(jdbc, table, L2DatasetManifestDataset.DEFINITION);
    }

    @Override public void send(List<L2DatasetManifest> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("One nonempty bounded D085 batch is required");
        var normalized = DatasetWritePreparation.prepare(L2DatasetManifestDataset.DEFINITION, rows,
                mapper::values, new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        long estimated = rows.stream().mapToLong(row -> CODEC.estimatedTransportBytes(row,
                CODEC.canonicalBytes(row))).sum();
        if (normalized.empty() || estimated > MAX_BATCH_BYTES)
            throw new IllegalArgumentException("D085 batch exceeds its normalized or transport byte budget");
        if (!senderActive.compareAndSet(false, true)) throw new IllegalStateException("D085 writer already active");
        try (Sender sender = questdb.borrowSender()) {
            for (L2DatasetManifest value : rows) {
                var row = sender.table(table)
                        .stringColumn("trade_date", TemporalValues.formatDate(value.tradeDate(), TemporalValues.DateFormat.BASIC))
                        .symbol("symbol", value.symbol());
                if (value.market() != null) row.stringColumn("market", value.market());
                if (value.board() != null) row.stringColumn("board", value.board());
                if (value.sourceRoot() != null) row.stringColumn("source_root", value.sourceRoot());
                if (value.outputRoot() != null) row.stringColumn("output_root", value.outputRoot());
                if (value.featureVersion() != null) row.stringColumn("feature_version", value.featureVersion());
                if (value.dailyFeatureOk() != null) row.boolColumn("daily_feature_ok", value.dailyFeatureOk());
                if (value.t0Ok() != null) row.boolColumn("t0_ok", value.t0Ok());
                if (value.rawRowCounts() != null) row.stringColumn("raw_row_counts", value.rawRowCounts());
                if (value.outputPaths() != null) row.stringColumn("output_paths", value.outputPaths());
                if (value.costConfig() != null) row.stringColumn("cost_config", value.costConfig());
                if (value.horizonsMin() != null) row.stringColumn("horizons_min", value.horizonsMin());
                if (value.errors() != null) row.stringColumn("errors", value.errors());
                row.longColumn("batch_id", value.batchId());
                row.at(new TemporalValues.CalendarTimestamp(value.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10_000))
                throw new IllegalStateException("D085 QWP acknowledgement unknown; reconcile before replay");
        } finally {
            senderActive.set(false);
        }
    }

    @Override public List<L2DatasetManifest> readback(List<L2DatasetManifestKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_READBACK_KEYS
                || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 200 unique D085 keys may be verified at once");
        var clauses = new ArrayList<String>(keys.size());
        var parameters = new ArrayList<Object>(keys.size() * 4);
        for (var key : keys) {
            clauses.add("(trade_date=? AND symbol=? AND batch_id=? AND trade_date_ts=cast(? as TIMESTAMP))");
            parameters.add(TemporalValues.formatDate(key.tradeDate(), TemporalValues.DateFormat.BASIC));
            parameters.add(key.symbol());
            parameters.add(key.batchId());
            parameters.add(new TemporalValues.CalendarTimestamp(key.tradeDate())
                    .storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        String projection = String.join(", ", SELECT_COLUMNS.stream().map(column -> column.equals("trade_date_ts")
                ? "cast(trade_date_ts as long) AS trade_date_ts_micros" : column).toList());
        String sql = "SELECT " + projection + " FROM \"" + table + "\" WHERE "
                + String.join(" OR ", clauses) + " ORDER BY trade_date,symbol,batch_id LIMIT " + (keys.size() + 1);
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(20);
            statement.setMaxRows(keys.size() + 1);
            statement.setFetchSize(keys.size() + 1);
            for (int i = 0; i < parameters.size(); i++) {
                Object value = parameters.get(i);
                if (value instanceof String text) statement.setString(i + 1, text);
                else if (value instanceof Long number) statement.setLong(i + 1, number);
                else throw new SQLException("Unsupported D085 key binding");
            }
            return statement;
        }, (rs, index) -> mapper.fromStorage(readRow(rs)));
    }

    /** Dates with target rows are bounded before incremental checkpoint planning. */
    public List<LocalDate> readExistingDates() {
        String sql = "SELECT trade_date FROM \"" + table + "\" GROUP BY trade_date ORDER BY trade_date LIMIT 33";
        return jdbc.query(sql, (rs, index) -> {
            if (index > 32) throw new IllegalStateException("D085 target exceeds the 32-date checkpoint scan bound");
            return TemporalValues.businessDate(rs.getString("trade_date"), TemporalValues.DateFormat.BASIC);
        });
    }

    public int countRows(LocalDate date) {
        Long count = jdbc.queryForObject("SELECT count() FROM \"" + table + "\" WHERE trade_date=?",
                Long.class, TemporalValues.formatDate(date, TemporalValues.DateFormat.BASIC));
        if (count == null) return 0;
        if (count > Integer.MAX_VALUE) throw new IllegalStateException("D085 target row count exceeds integer bounds");
        return count.intValue();
    }

    @Override public boolean walSettled() { return QuestDbWriteChecks.walSettled(jdbc, table); }

    @Override public boolean uncertainSenderStopped() { return !senderActive.get(); }

    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX) || table.length() <= ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException("D085 writes require a java_d085_l2_dataset_manifest_<suffix> target");
    }

    private static L2DatasetManifestRow readRow(ResultSet rs) throws SQLException {
        long timestampMicros = rs.getLong("trade_date_ts_micros");
        if (rs.wasNull()) throw new SQLException("D085 trade_date_ts is null");
        var timestamp = TemporalValues.epoch(timestampMicros, TemporalValues.EpochUnit.MICROS,
                TemporalValues.Precision.MICROS);
        return new L2DatasetManifestRow(
                rs.getString("trade_date"), rs.getString("symbol"), rs.getString("market"), rs.getString("board"),
                rs.getString("source_root"), rs.getString("output_root"), rs.getString("feature_version"),
                rs.getObject("daily_feature_ok", Boolean.class), rs.getObject("t0_ok", Boolean.class),
                rs.getString("raw_row_counts"), rs.getString("output_paths"), rs.getString("cost_config"),
                rs.getString("horizons_min"), rs.getString("errors"), rs.getLong("batch_id"), timestamp);
    }

    private static void writeNullableString(DataOutputStream output, String value) throws java.io.IOException {
        output.writeBoolean(value != null);
        if (value != null) writeString(output, value);
    }

    private static void writeNullableBoolean(DataOutputStream output, Boolean value) throws java.io.IOException {
        output.writeBoolean(value != null);
        if (value != null) output.writeBoolean(value);
    }

    private static void writeString(DataOutputStream output, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
