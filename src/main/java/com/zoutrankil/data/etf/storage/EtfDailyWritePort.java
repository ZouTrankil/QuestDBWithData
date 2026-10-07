package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.repository.*;


import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.etf.mapper.EtfDailyMapper;
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
public final class EtfDailyWritePort implements com.zoutrankil.data.etf.port.EtfWriteSession<EtfDaily, EtfDailyKey> {
    private static final int MAX_ROWS = 250;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<EtfDaily, EtfDailyKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public EtfDailyKey key(EtfDaily row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(EtfDaily row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new EtfDailyMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot encode etf_daily row", failure); }
        }
        @Override public int estimatedTransportBytes(EtfDaily row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 128);
        }
    };

    @Override public VerifiedBatchExecutor.Codec<EtfDaily, EtfDailyKey> codec() { return CODEC; }

    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final EtfDailyMapper mapper = new EtfDailyMapper();
    private volatile boolean uncertainSenderStopped;

    public EtfDailyWritePort(String table, String expectedTargetId, JdbcTemplate jdbc, QuestDB questdb) {
        EtfDailyDataset.requireExecutionTable(table);
        if (expectedTargetId == null || !expectedTargetId.startsWith("static-v2-"))
            throw new IllegalArgumentException("Frozen isolated etf_daily target identity required");
        this.table = table; this.expectedTargetId = expectedTargetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(10_001);
        this.questdb = Objects.requireNonNull(questdb);
    }
    @Override public void preflight() {
        requireExpectedTarget();
        QuestDbWriteChecks.preflight(jdbc, table, EtfDailyDataset.definition(table));
        requireExpectedTarget();
    }
    @Override public void send(List<EtfDaily> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS)
            throw new IllegalArgumentException("Nonempty etf_daily batch of at most 250 rows required");
        preflight(); uncertainSenderStopped = false;
        long estimated = 0;
        for (var row : rows) {
            if (row == null) throw new IllegalArgumentException("Null etf_daily row");
            estimated = Math.addExact(estimated, CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
            if (estimated > MAX_BYTES) throw new IllegalArgumentException("etf_daily batch exceeds one MiB before send");
        }
        boolean flushAttempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var value : rows) {
                var line = sender.table(table).symbol("ts_code", value.tsCode());
                if (value.preClose() != null) line.doubleColumn("pre_close", value.preClose());
                if (value.open() != null) line.doubleColumn("open", value.open());
                if (value.high() != null) line.doubleColumn("high", value.high());
                if (value.low() != null) line.doubleColumn("low", value.low());
                if (value.close() != null) line.doubleColumn("close", value.close());
                if (value.change() != null) line.doubleColumn("change", value.change());
                if (value.pctChg() != null) line.doubleColumn("pct_chg", value.pctChg());
                if (value.vol() != null) line.doubleColumn("vol", value.vol());
                if (value.amount() != null) line.doubleColumn("amount", value.amount());
                line.at(new TemporalValues.CalendarTimestamp(value.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("etf_daily QWP acknowledgement unknown; reconcile exact keys before replay");
        } catch (Exception failure) {
            flushAttempted = true; throw failure;
        } finally { uncertainSenderStopped = flushAttempted; }
    }
    @Override public List<EtfDaily> readback(List<EtfDailyKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique complete etf_daily keys required");
        requireExpectedTarget();
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code = ? AND timestamp = cast(? as TIMESTAMP_NS))");
            parameters.add(key.tsCode());
            parameters.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.NANOS));
        }
        String sql = "SELECT ts_code, cast(timestamp as long) AS trade_date_nanos, \"pre_close\", \"open\", \"high\", \"low\", \"close\", \"change\", \"pct_chg\", \"vol\", \"amount\" FROM \""
                + table + "\" WHERE " + String.join(" OR ", clauses) + " ORDER BY timestamp, ts_code LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, index) -> physical(rs), parameters.toArray());
    }
    /** Bounded distinct physical date inventory used to reject unexplained existing target rows. */
    public List<LocalDate> readExistingDates() {
        requireExpectedTarget();
        var rows = jdbc.query("SELECT DISTINCT cast(timestamp as long) AS trade_date_nanos FROM \"" + table
                + "\" ORDER BY trade_date_nanos LIMIT 10001", (rs, index) -> date(rs));
        if (rows.size() > 10000) throw new IllegalStateException("etf_daily physical date inventory exceeds bounded reconciliation cap");
        return List.copyOf(rows);
    }
    /** Complete date rows are capped below the documented API maximum; excess is not silently truncated. */
    public List<EtfDaily> readDate(LocalDate date) {
        Objects.requireNonNull(date); requireExpectedTarget();
        var micros = new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.NANOS);
        String sql = "SELECT ts_code, cast(timestamp as long) AS trade_date_nanos, \"pre_close\", \"open\", \"high\", \"low\", \"close\", \"change\", \"pct_chg\", \"vol\", \"amount\" FROM \""
                + table + "\" WHERE timestamp = cast(? as TIMESTAMP_NS) ORDER BY ts_code LIMIT 5001";
        var result = jdbc.query(sql, (rs, index) -> physical(rs), micros);
        if (result.size() > 5000) throw new IllegalStateException("etf_daily date exceeds official 5000-row response bound");
        return List.copyOf(result);
    }
    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }

    private EtfDaily physical(ResultSet rs) throws SQLException {
        LocalDate date = date(rs);
        return new EtfDaily(rs.getString("ts_code"), date, finite(rs, "pre_close"), finite(rs, "open"), finite(rs, "high"), finite(rs, "low"), finite(rs, "close"), finite(rs, "change"), finite(rs, "pct_chg"), finite(rs, "vol"), finite(rs, "amount"));
    }
    private static LocalDate date(ResultSet rs) throws SQLException {
        Object raw = rs.getObject("trade_date_nanos");
        if (!(raw instanceof Number value)) throw new SQLException("etf_daily timestamp required");
        return TemporalValues.CalendarTimestamp.fromStorageEpoch(value.longValue(), TemporalValues.EpochUnit.NANOS).date();
    }
    private static Double finite(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new SQLException("Invalid physical etf_daily value: " + column);
        return number.doubleValue();
    }
    private void requireExpectedTarget() {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D014 etf_daily target identity changed during write or readback");
    }
}
