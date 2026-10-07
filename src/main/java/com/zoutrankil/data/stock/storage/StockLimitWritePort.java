package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.stock.domain.StockExecutionTables;

import com.zoutrankil.data.stock.port.StockDateWriteSession;

import com.zoutrankil.data.repository.*;


import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.stock.mapper.StockLimitMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** Bounded WAL/QWP writer with exact full-key/all-column readback and frozen-target checks. */
public final class StockLimitWritePort implements StockDateWriteSession<StockLimit, StockLimitKey> {
    private static final int MAX_ROWS = 250;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<StockLimit, StockLimitKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public StockLimitKey key(StockLimit row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(StockLimit row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new StockLimitMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot encode stk_limit row", failure); }
        }
        @Override public int estimatedTransportBytes(StockLimit row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 128);
        }
    };

    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final StockLimitMapper mapper = new StockLimitMapper();
    private volatile boolean uncertainSenderStopped;

    public StockLimitWritePort(String table, String expectedTargetId, JdbcTemplate jdbc, QuestDB questdb) {
        StockExecutionTables.requireStockLimit(table);
        if (expectedTargetId == null || !expectedTargetId.startsWith("static-v2-"))
            throw new IllegalArgumentException("Frozen stk_limit target identity required");
        this.table = table; this.expectedTargetId = expectedTargetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(10_001);
        this.questdb = Objects.requireNonNull(questdb);
    }
    @Override public void preflight() {
        requireExpectedTarget();
        QuestDbWriteChecks.preflight(jdbc, table, StockLimitDataset.definition(table));
        requireExpectedTarget();
    }
    @Override public void send(List<StockLimit> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS)
            throw new IllegalArgumentException("Nonempty stk_limit batch of at most 250 rows required");
        preflight(); uncertainSenderStopped = false;
        long estimated = 0;
        for (var row : rows) {
            if (row == null) throw new IllegalArgumentException("Null stk_limit row");
            estimated = Math.addExact(estimated, CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
            if (estimated > MAX_BYTES) throw new IllegalArgumentException("stk_limit batch exceeds one MiB before send");
        }
        boolean flushAttempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var value : rows) {
                var line = sender.table(table).symbol("ts_code", value.tsCode());
                if (value.upLimit() != null) line.doubleColumn("up_limit", value.upLimit());
                if (value.downLimit() != null) line.doubleColumn("down_limit", value.downLimit());
                line.at(new TemporalValues.CalendarTimestamp(value.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("stk_limit QWP acknowledgement unknown; reconcile exact keys before replay");
        } catch (Exception failure) {
            flushAttempted = true; throw failure;
        } finally { uncertainSenderStopped = flushAttempted; }
    }
    @Override public List<StockLimit> readback(List<StockLimitKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique complete stk_limit keys required");
        requireExpectedTarget();
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        var firstDate = keys.stream().map(StockLimitKey::tradeDate).min(Comparator.naturalOrder()).orElseThrow();
        var lastDate = keys.stream().map(StockLimitKey::tradeDate).max(Comparator.naturalOrder()).orElseThrow();
        parameters.add(new TemporalValues.CalendarTimestamp(firstDate).storageEpoch(TemporalValues.EpochUnit.MICROS));
        parameters.add(new TemporalValues.CalendarTimestamp(lastDate.plusDays(1)).storageEpoch(TemporalValues.EpochUnit.MICROS));
        var codes = keys.stream().map(StockLimitKey::tsCode).distinct().sorted().toList();
        parameters.addAll(codes);
        for (var key : keys) {
            clauses.add("(ts_code = ? AND trade_date = cast(? as TIMESTAMP))");
            parameters.add(key.tsCode());
            parameters.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        String sql = "SELECT ts_code, cast(trade_date as long) AS trade_date_micros, up_limit, down_limit FROM \""
                + table + "\" WHERE trade_date >= cast(? AS TIMESTAMP) AND trade_date < cast(? AS TIMESTAMP)"
                + " AND ts_code IN (" + String.join(",", Collections.nCopies(codes.size(), "?")) + ")"
                + " AND (" + String.join(" OR ", clauses) + ") ORDER BY trade_date, ts_code LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, index) -> physical(rs), parameters.toArray());
    }
    /** Bounded distinct physical date inventory used to reject unexplained existing target rows. */
    public List<LocalDate> readExistingDates() {
        requireExpectedTarget();
        var rows = jdbc.query("SELECT DISTINCT cast(trade_date as long) AS trade_date_micros FROM \"" + table
                + "\" ORDER BY trade_date_micros LIMIT 10001", (rs, index) -> date(rs));
        if (rows.size() > 10000) throw new IllegalStateException("stk_limit physical date inventory exceeds bounded reconciliation cap");
        return List.copyOf(rows);
    }
    /** Complete date rows are capped below the documented API maximum; excess is not silently truncated. */
    public List<StockLimit> readDate(LocalDate date) {
        Objects.requireNonNull(date); requireExpectedTarget();
        var micros = new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);
        String sql = "SELECT ts_code, cast(trade_date as long) AS trade_date_micros, up_limit, down_limit FROM \""
                + table + "\" WHERE trade_date = cast(? as TIMESTAMP) ORDER BY ts_code LIMIT 5801";
        var result = jdbc.query(sql, (rs, index) -> physical(rs), micros);
        if (result.size() > 5800) throw new IllegalStateException("stk_limit date exceeds official 5800-row response bound");
        return List.copyOf(result);
    }
    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }

    private StockLimit physical(ResultSet rs) throws SQLException {
        LocalDate date = date(rs);
        return new StockLimit(rs.getString("ts_code"), date, finite(rs, "up_limit"), finite(rs, "down_limit"));
    }
    private static LocalDate date(ResultSet rs) throws SQLException {
        Object raw = rs.getObject("trade_date_micros");
        if (!(raw instanceof Number value)) throw new SQLException("stk_limit timestamp required");
        return TemporalValues.CalendarTimestamp.fromStorageEpoch(value.longValue(), TemporalValues.EpochUnit.MICROS).date();
    }
    private static Double finite(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new SQLException("Invalid physical stk_limit value: " + column);
        return number.doubleValue();
    }
    private void requireExpectedTarget() {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D010 stk_limit target identity changed during write or readback");
    }
    @Override public VerifiedBatchExecutor.Codec<StockLimit, StockLimitKey> codec() { return CODEC; }
}
