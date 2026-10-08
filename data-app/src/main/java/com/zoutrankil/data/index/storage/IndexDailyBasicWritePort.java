package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.index.domain.IndexDailyBasicTargetRange;
import com.zoutrankil.data.index.port.IndexDailyBasicWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.index.mapper.IndexDailyBasicMapper;
import com.zoutrankil.data.domain.policy.IndexDailyBasicUniverse;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** Isolated D020 WAL writer with bounded batches and complete-key/full-field readback. */
public final class IndexDailyBasicWritePort implements IndexDailyBasicWriteSession {
    @Override public VerifiedBatchExecutor.Codec<IndexDailyBasic,IndexDailyBasicKey> codec() { return CODEC; }
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_EXISTING_ROWS = 100_000;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<IndexDailyBasic,IndexDailyBasicKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public IndexDailyBasicKey key(IndexDailyBasic row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(IndexDailyBasic row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new IndexDailyBasicMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot encode D020 row", failure); }
        }
        @Override public int estimatedTransportBytes(IndexDailyBasic row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 256);
        }
    };
    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final IndexDailyBasicMapper mapper = new IndexDailyBasicMapper();
    private volatile boolean uncertainSenderStopped;
    public IndexDailyBasicWritePort(String table, String targetId, JdbcTemplate jdbc, QuestDB questdb) {
        IsolatedTablePolicy.INDEX_DAILY_BASIC.require(table);
        if (targetId == null || !targetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D020 isolated target identity required");
        this.table = table; this.expectedTargetId = targetId; this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(MAX_EXISTING_ROWS + 1); this.questdb = Objects.requireNonNull(questdb);
    }
    @Override public void preflight() {
        requireExpectedTarget(); QuestDbWriteChecks.preflight(jdbc, table, IndexDailyBasicDataset.definition(table));
        requireExpectedTarget();
    }
    @Override public void send(List<IndexDailyBasic> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("Nonempty D020 batch of at most 250 rows required");
        preflight(); uncertainSenderStopped = false;
        DatasetWritePreparation.prepare(IndexDailyBasicDataset.definition(table), rows, mapper::values,
                new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        long bytes = 0;
        for (var row : rows) {
            if (row == null) throw new IllegalArgumentException("Null D020 row");
            bytes = Math.addExact(bytes, CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
            if (bytes > MAX_BATCH_BYTES) throw new IllegalArgumentException("D020 batch exceeds one MiB before send");
        }
        boolean attempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var value : rows) {
                var line = sender.table(table).symbol("ts_code", value.tsCode());
                if (value.totalMv() != null) line.doubleColumn("total_mv", value.totalMv());
                if (value.floatMv() != null) line.doubleColumn("float_mv", value.floatMv());
                if (value.totalShare() != null) line.doubleColumn("total_share", value.totalShare());
                if (value.floatShare() != null) line.doubleColumn("float_share", value.floatShare());
                if (value.freeShare() != null) line.doubleColumn("free_share", value.freeShare());
                if (value.turnoverRate() != null) line.doubleColumn("turnover_rate", value.turnoverRate());
                if (value.turnoverRateF() != null) line.doubleColumn("turnover_rate_f", value.turnoverRateF());
                if (value.pe() != null) line.doubleColumn("pe", value.pe());
                if (value.peTtm() != null) line.doubleColumn("pe_ttm", value.peTtm());
                if (value.pb() != null) line.doubleColumn("pb", value.pb());
                line.at(new TemporalValues.CalendarTimestamp(value.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); attempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("D020 QWP ACK is unknown; reconcile exact keys before replay");
        } catch (Exception failure) { attempted = true; throw failure; }
        finally { uncertainSenderStopped = attempted; }
    }
    @Override public List<IndexDailyBasic> readback(List<IndexDailyBasicKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique D020 keys required for readback");
        requireExpectedTarget(); var clauses = new ArrayList<String>(); var args = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code = ? AND trade_date = cast(? AS TIMESTAMP))"); args.add(key.tsCode());
            args.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        return jdbc.query(selectColumns() + " WHERE " + String.join(" OR ", clauses)
                + " ORDER BY trade_date, ts_code LIMIT " + (keys.size() + 1), (rs, row) -> physical(rs), args.toArray());
    }
    public List<IndexDailyBasic> readExistingRows(String code) {
        IndexDailyBasicUniverse.requireCode(code); requireExpectedTarget();
        var rows = jdbc.query(selectColumns() + " WHERE ts_code = ? ORDER BY trade_date LIMIT " + (MAX_EXISTING_ROWS + 1),
                (rs, row) -> physical(rs), code);
        if (rows.size() > MAX_EXISTING_ROWS) throw new IllegalStateException("D020 per-code row count exceeds reconciliation cap");
        return List.copyOf(rows);
    }
    public IndexDailyBasicTargetRange readExistingRange(String code) {
        IndexDailyBasicUniverse.requireCode(code); requireExpectedTarget();
        String sql = "SELECT cast(min(trade_date) AS long) AS min_micros, cast(max(trade_date) AS long) AS max_micros FROM \"" + table + "\" WHERE ts_code = ?";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return D020 target range");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return new IndexDailyBasicTargetRange(null, null);
            if (!(min instanceof Number a) || !(max instanceof Number b)) throw new SQLException("Invalid D020 timestamp range");
            return new IndexDailyBasicTargetRange(date(a.longValue()), date(b.longValue()));
        }, code);
    }
    public List<IndexDailyBasic> readRange(String code, LocalDate from, LocalDate to) {
        IndexDailyBasicUniverse.requireCode(code); Objects.requireNonNull(from); Objects.requireNonNull(to);
        if (from.isAfter(to)) throw new IllegalArgumentException("Increasing D020 range required");
        requireExpectedTarget(); long lo = new TemporalValues.CalendarTimestamp(from).storageEpoch(TemporalValues.EpochUnit.MICROS);
        long hi = new TemporalValues.CalendarTimestamp(to.plusDays(1)).storageEpoch(TemporalValues.EpochUnit.MICROS);
        var rows = jdbc.query(selectColumns() + " WHERE ts_code = ? AND trade_date >= cast(? AS TIMESTAMP) AND trade_date < cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT "
                + (MAX_EXISTING_ROWS + 1), (rs, row) -> physical(rs), code, lo, hi);
        if (rows.size() > MAX_EXISTING_ROWS) throw new IllegalStateException("D020 range exceeds reconciliation cap");
        return List.copyOf(rows);
    }
    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }
    private IndexDailyBasic physical(ResultSet rs) throws SQLException {
        Object timestamp = rs.getObject("trade_date_micros");
        if (!(timestamp instanceof Number t)) throw new SQLException("D020 trade_date timestamp required");
        try { return new IndexDailyBasic(new IndexDailyBasicKey(rs.getString("ts_code"), date(t.longValue())),
                finite(rs, "total_mv"), finite(rs, "float_mv"), finite(rs, "total_share"), finite(rs, "float_share"),
                finite(rs, "free_share"), finite(rs, "turnover_rate"), finite(rs, "turnover_rate_f"),
                finite(rs, "pe"), finite(rs, "pe_ttm"), finite(rs, "pb")); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid D020 physical row", invalid); }
    }
    private static Double finite(ResultSet rs, String name) throws SQLException {
        Object value = rs.getObject(name); if (value == null) return null;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new SQLException("Invalid D020 metric: " + name);
        return n.doubleValue();
    }
    private static LocalDate date(long micros) throws SQLException {
        try { return TemporalValues.CalendarTimestamp.fromStorageEpoch(micros, TemporalValues.EpochUnit.MICROS).date(); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid D020 calendar date", invalid); }
    }
    private String selectColumns() {
        return "SELECT ts_code, cast(trade_date AS long) AS trade_date_micros, total_mv, float_mv, total_share, float_share, free_share, turnover_rate, turnover_rate_f, pe, pe_ttm, pb FROM \"" + table + "\"";
    }
    private void requireExpectedTarget() {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D020 isolated target identity changed");
    }

}
