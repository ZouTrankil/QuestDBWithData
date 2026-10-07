package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.IndexDailyMarketMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Isolated D019 WAL writer with bounded, exact-key/full-field readback. */
public final class IndexDailyMarketWritePort implements VerifiedBatchExecutor.Port<IndexDailyMarket,IndexDailyMarketKey> {
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_EXISTING_ROWS = 100_000;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<IndexDailyMarket,IndexDailyMarketKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public IndexDailyMarketKey key(IndexDailyMarket row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(IndexDailyMarket row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new IndexDailyMarketMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot encode index_daily_market row", failure); }
        }
        @Override public int estimatedTransportBytes(IndexDailyMarket row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 192);
        }
    };
    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final IndexDailyMarketMapper mapper = new IndexDailyMarketMapper();
    private volatile boolean uncertainSenderStopped;
    public IndexDailyMarketWritePort(String table, String targetId, JdbcTemplate jdbc, QuestDB questdb) {
        IsolatedTablePolicy.INDEX_DAILY_MARKET.require(table);
        if (targetId == null || !targetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D019 isolated target identity required");
        this.table = table; this.expectedTargetId = targetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(MAX_EXISTING_ROWS + 1);
        this.questdb = Objects.requireNonNull(questdb);
    }
    @Override public void preflight() {
        requireExpectedTarget(); QuestDbWriteChecks.preflight(jdbc, table, IndexDailyMarketDataset.definition(table));
        requireExpectedTarget();
    }
    @Override public void send(List<IndexDailyMarket> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("Nonempty D019 batch of at most 250 rows required");
        preflight(); uncertainSenderStopped = false;
        DatasetWritePreparation.prepare(IndexDailyMarketDataset.definition(table), rows, mapper::values,
                new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        long bytes = 0;
        for (var row : rows) {
            if (row == null) throw new IllegalArgumentException("Null D019 row");
            bytes = Math.addExact(bytes, CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
            if (bytes > MAX_BATCH_BYTES) throw new IllegalArgumentException("D019 batch exceeds one MiB before send");
        }
        boolean flushAttempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var value : rows) {
                var line = sender.table(table).symbol("ts_code", value.tsCode());
                if (value.close() != null) line.doubleColumn("close", value.close());
                if (value.open() != null) line.doubleColumn("open", value.open());
                if (value.high() != null) line.doubleColumn("high", value.high());
                if (value.low() != null) line.doubleColumn("low", value.low());
                if (value.preClose() != null) line.doubleColumn("pre_close", value.preClose());
                if (value.change() != null) line.doubleColumn("change", value.change());
                if (value.pctChg() != null) line.doubleColumn("pct_chg", value.pctChg());
                if (value.vol() != null) line.doubleColumn("vol", value.vol());
                if (value.amount() != null) line.doubleColumn("amount", value.amount());
                line.timestampColumn("update_time", value.updateTime())
                        .at(new TemporalValues.CalendarTimestamp(value.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("D019 QWP ACK is unknown; reconcile exact keys before replay");
        } catch (Exception failure) { flushAttempted = true; throw failure; }
        finally { uncertainSenderStopped = flushAttempted; }
    }
    @Override public List<IndexDailyMarket> readback(List<IndexDailyMarketKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique D019 keys required for readback");
        requireExpectedTarget(); var clauses = new ArrayList<String>(); var args = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code = ? AND timestamp = cast(? AS TIMESTAMP))"); args.add(key.tsCode());
            args.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        String sql = selectColumns() + " WHERE " + String.join(" OR ", clauses)
                + " ORDER BY timestamp, ts_code LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, row) -> physical(rs), args.toArray());
    }
    /** Full existing history for one frozen code; every physical row must have verified receipt coverage. */
    public List<IndexDailyMarket> readExistingRows(String tsCode) {
        requireKnownCode(tsCode); requireExpectedTarget();
        var rows = jdbc.query(selectColumns() + " WHERE ts_code = ? ORDER BY timestamp LIMIT " + (MAX_EXISTING_ROWS + 1),
                (rs, row) -> physical(rs), tsCode);
        if (rows.size() > MAX_EXISTING_ROWS) throw new IllegalStateException("D019 per-code existing target rows exceed the reconciliation cap");
        return List.copyOf(rows);
    }
    public TargetRange readExistingRange(String tsCode) {
        requireKnownCode(tsCode); requireExpectedTarget();
        String sql = "SELECT cast(min(timestamp) AS long) AS min_micros, cast(max(timestamp) AS long) AS max_micros FROM \""
                + table + "\" WHERE ts_code = ?";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return D019 target range");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return new TargetRange(null, null);
            if (!(min instanceof Number a) || !(max instanceof Number b)) throw new SQLException("Invalid D019 target timestamp range");
            return new TargetRange(date(a.longValue()), date(b.longValue()));
        }, tsCode);
    }
    public List<IndexDailyMarket> readRange(String tsCode, LocalDate fromInclusive, LocalDate toInclusive) {
        requireKnownCode(tsCode); Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toInclusive);
        if (fromInclusive.isAfter(toInclusive)) throw new IllegalArgumentException("Increasing D019 range required");
        requireExpectedTarget();
        long from = new TemporalValues.CalendarTimestamp(fromInclusive).storageEpoch(TemporalValues.EpochUnit.MICROS);
        long to = new TemporalValues.CalendarTimestamp(toInclusive.plusDays(1)).storageEpoch(TemporalValues.EpochUnit.MICROS);
        String sql = selectColumns() + " WHERE ts_code = ? AND timestamp >= cast(? AS TIMESTAMP) AND timestamp < cast(? AS TIMESTAMP) "
                + "ORDER BY timestamp LIMIT " + (MAX_EXISTING_ROWS + 1);
        var rows = jdbc.query(sql, (rs, row) -> physical(rs), tsCode, from, to);
        if (rows.size() > MAX_EXISTING_ROWS) throw new IllegalStateException("D019 range readback exceeds reconciliation cap");
        return List.copyOf(rows);
    }
    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }

    private IndexDailyMarket physical(ResultSet rs) throws SQLException {
        Object timestamp = rs.getObject("trade_date_micros"), observed = rs.getObject("update_time_micros");
        if (!(timestamp instanceof Number t) || !(observed instanceof Number u)) throw new SQLException("D019 timestamps required");
        try {
            return new IndexDailyMarket(new IndexDailyMarketKey(rs.getString("ts_code"), date(t.longValue())),
                    finite(rs, "close"), finite(rs, "open"), finite(rs, "high"), finite(rs, "low"),
                    finite(rs, "pre_close"), finite(rs, "change"), finite(rs, "pct_chg"), finite(rs, "vol"),
                    finite(rs, "amount"), TemporalValues.epoch(u.longValue(), TemporalValues.EpochUnit.MICROS, TemporalValues.Precision.MICROS));
        } catch (RuntimeException invalid) { throw new SQLException("Invalid D019 physical row", invalid); }
    }
    private static Double finite(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column); if (value == null) return null;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new SQLException("Invalid D019 metric: " + column);
        return n.doubleValue();
    }
    private static LocalDate date(long micros) throws SQLException {
        try { return TemporalValues.CalendarTimestamp.fromStorageEpoch(micros, TemporalValues.EpochUnit.MICROS).date(); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid D019 calendar date carrier", invalid); }
    }
    private String selectColumns() {
        return "SELECT ts_code, cast(timestamp AS long) AS trade_date_micros, close, open, high, low, pre_close, change, pct_chg, vol, amount, "
                + "cast(update_time AS long) AS update_time_micros FROM \"" + table + "\"";
    }
    private static void requireKnownCode(String code) {
        if (!com.zoutrankil.data.domain.policy.IndexDailyMarketUniverse.valid(code))
            throw new IllegalArgumentException("Known frozen D019 source ts_code required");
    }
    private void requireExpectedTarget() {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D019 isolated QuestDB target identity changed");
    }
    public record TargetRange(LocalDate min, LocalDate max) {
        public TargetRange { if ((min == null) != (max == null) || min != null && min.isAfter(max)) throw new IllegalArgumentException("Invalid D019 target range"); }
    }
}
