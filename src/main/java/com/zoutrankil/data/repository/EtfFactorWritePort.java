package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.EtfFactor;
import com.zoutrankil.data.domain.EtfFactorDataset;
import com.zoutrankil.data.domain.EtfFactorKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.EtfFactorMapper;
import com.zoutrankil.data.service.EtfFactorJobService;
import com.zoutrankil.data.service.StaticTargetIdentity;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded QWP write plus full-key/all-field readback against the exact frozen D017 target. */
public final class EtfFactorWritePort implements VerifiedBatchExecutor.Port<EtfFactor, EtfFactorKey> {
    /** Matches the generic runner batch size; the byte estimate can split batches below this row count. */
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_ROWS_PER_DATE = EtfFactorJobService.MAX_ROWS_PER_DATE;
    private static final int MAX_ISOLATED_TABLE_BYTES = EtfFactorDataset.ISOLATED_PREFIX.length() + 80;
    private static final int LINE_PROTOCOL_FRAMING_BYTES_PER_ROW = 64;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<EtfFactor, EtfFactorKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public EtfFactorKey key(EtfFactor row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(EtfFactor row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new EtfFactorMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot canonicalize etf_factor row", failure); }
        }
        @Override public int estimatedTransportBytes(EtfFactor row, byte[] canonical) {
            Objects.requireNonNull(row); Objects.requireNonNull(canonical);
            long estimated = MAX_ISOLATED_TABLE_BYTES + LINE_PROTOCOL_FRAMING_BYTES_PER_ROW;
            estimated = Math.addExact(estimated, utf8Length(",ts_code=") + utf8Length(row.tsCode()));
            for (var field : row.factors().entrySet()) {
                if (field.getValue() == null) continue;
                // QWP serializes these finite DOUBLEs using their decimal text representation.
                estimated = Math.addExact(estimated, 2L + utf8Length(field.getKey())
                        + utf8Length(Double.toString(field.getValue())));
            }
            long timestampNanos = Math.multiplyExact(
                    row.tradeDate().atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond(), 1_000_000_000L);
            estimated = Math.addExact(estimated, 2L + Long.toString(timestampNanos).length());
            // Include every canonical source/physical field, including null-valued columns, as a lower bound.
            return Math.toIntExact(Math.max(canonical.length, estimated));
        }

        private int utf8Length(String value) {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
    };
    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final EtfFactorMapper mapper = new EtfFactorMapper();
    private volatile boolean uncertainSenderStopped;

    public EtfFactorWritePort(String table, String expectedTargetId, JdbcTemplate jdbc, QuestDB questdb) {
        EtfFactorJobService.requireIsolatedTableName(table);
        if (expectedTargetId == null || !expectedTargetId.startsWith("static-v2-"))
            throw new IllegalArgumentException("Frozen isolated etf_factor identity required");
        this.table = table; this.expectedTargetId = expectedTargetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(30); this.jdbc.setMaxRows(MAX_ROWS_PER_DATE + 1);
        this.questdb = Objects.requireNonNull(questdb);
    }
    @Override public void preflight() {
        requireExpectedTarget(); QuestDbWriteChecks.preflight(jdbc, table, EtfFactorDataset.definition(table)); requireExpectedTarget();
    }
    @Override public void send(List<EtfFactor> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("Nonempty etf_factor batch of at most 250 rows required");
        preflight(); uncertainSenderStopped = false; long estimated = 0;
        var prepared = DatasetWritePreparation.prepare(EtfFactorDataset.definition(table), rows, mapper::values,
                new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        if (prepared.empty()) throw new IllegalArgumentException("Empty etf_factor write must not reach QWP");
        var keys = new HashSet<EtfFactorKey>();
        for (var row : rows) {
            if (row == null || !keys.add(row.key())) throw new IllegalArgumentException("Null or duplicate etf_factor key in batch");
            estimated = Math.addExact(estimated, CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
            if (estimated > MAX_BATCH_BYTES) throw new IllegalArgumentException("etf_factor batch exceeds one MiB");
        }
        boolean flushAttempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var value : rows) {
                var line = sender.table(table).symbol("ts_code", value.tsCode());
                for (var field : EtfFactorDataset.DEFINITION.columns()) {
                    String name = field.storageName();
                    if (name.equals("ts_code") || name.equals("trade_date")) continue;
                    Double number = value.factors().get(name);
                    if (number != null) line.doubleColumn(name, number);
                }
                line.at(new TemporalValues.CalendarTimestamp(value.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("etf_factor QWP ACK is unknown; exact readback is required before replay");
        } catch (Exception failure) { flushAttempted = true; throw failure; }
        finally { uncertainSenderStopped = flushAttempted; }
    }
    @Override public List<EtfFactor> readback(List<EtfFactorKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique full etf_factor keys required");
        requireExpectedTarget(); var clauses = new ArrayList<String>(); var params = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code = ? AND trade_date = cast(? as TIMESTAMP))"); params.add(key.tsCode());
            params.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        String sql = selectSql() + " WHERE " + String.join(" OR ", clauses)
                + " ORDER BY trade_date, ts_code LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, index) -> physical(rs), params.toArray());
    }
    public List<LocalDate> readExistingDates() {
        requireExpectedTarget();
        var rows = jdbc.query("SELECT DISTINCT cast(trade_date AS long) AS trade_date_micros FROM \"" + table
                + "\" ORDER BY trade_date_micros LIMIT 10001", (rs, index) -> date(rs));
        if (rows.size() > 10_000) throw new IllegalStateException("etf_factor distinct-date inventory exceeds 10000-date cap");
        return List.copyOf(rows);
    }
    public List<EtfFactor> readDate(LocalDate date) {
        Objects.requireNonNull(date); requireExpectedTarget();
        long micros = new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);
        var rows = jdbc.query(selectSql() + " WHERE trade_date = cast(? as TIMESTAMP) ORDER BY ts_code LIMIT "
                + (MAX_ROWS_PER_DATE + 1), (rs, index) -> physical(rs), micros);
        if (rows.size() > MAX_ROWS_PER_DATE) throw new IllegalStateException("etf_factor physical date exceeds bounded 8000-row inventory");
        return List.copyOf(rows);
    }
    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }

    private String selectSql() {
        var selected = new ArrayList<String>(); selected.add("ts_code");
        selected.add("cast(trade_date as long) AS trade_date_micros");
        EtfFactorDataset.DEFINITION.columns().stream().map(c -> c.storageName())
                .filter(name -> !name.equals("ts_code") && !name.equals("trade_date"))
                .forEach(selected::add);
        return "SELECT " + String.join(", ", selected) + " FROM \"" + table + "\"";
    }
    private EtfFactor physical(ResultSet rs) throws SQLException {
        var values = new java.util.LinkedHashMap<String,Object>(); values.put("ts_code", rs.getString("ts_code")); values.put("trade_date", date(rs));
        for (String field : EtfFactorDataset.FACTOR_FIELDS) values.put(field, finite(rs, field));
        for (String field : List.of("open", "high", "low", "close", "pre_close", "change", "pct_change", "vol", "amount"))
            values.put(field, finite(rs, field));
        return new EtfFactorMapper().fromValues(new com.zoutrankil.data.domain.DatasetValues(values));
    }
    private static LocalDate date(ResultSet rs) throws SQLException {
        Object raw = rs.getObject("trade_date_micros");
        if (!(raw instanceof Number value)) throw new SQLException("etf_factor physical trade_date is required");
        try { return TemporalValues.CalendarTimestamp.fromStorageEpoch(value.longValue(), TemporalValues.EpochUnit.MICROS).date(); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid etf_factor calendar timestamp", invalid); }
    }
    private static Double finite(ResultSet rs, String field) throws SQLException {
        Object value = rs.getObject(field); if (value == null) return null;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new SQLException("Invalid physical etf_factor value: " + field);
        return number.doubleValue();
    }
    private void requireExpectedTarget() {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D017 etf_factor physical target identity changed during write/readback");
    }
}
