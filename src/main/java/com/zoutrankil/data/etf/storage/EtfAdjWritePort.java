package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.repository.*;


import com.zoutrankil.data.domain.EtfAdj;
import com.zoutrankil.data.domain.EtfAdjDataset;
import com.zoutrankil.data.domain.EtfAdjKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.etf.mapper.EtfAdjMapper;
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

/** Bounded WAL/QWP writer with exact full-key/all-column readback and frozen-target checks. */
public final class EtfAdjWritePort implements com.zoutrankil.data.etf.port.EtfAdjWriteSession {
    private static final int MAX_ROWS = 250;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_ROWS_PER_DATE = 10_000;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<EtfAdj, EtfAdjKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public EtfAdjKey key(EtfAdj row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(EtfAdj row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new EtfAdjMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot encode etf_adj row", failure); }
        }
        @Override public int estimatedTransportBytes(EtfAdj row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 128);
        }
    };

    @Override public VerifiedBatchExecutor.Codec<EtfAdj, EtfAdjKey> codec() { return CODEC; }

    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private volatile boolean uncertainSenderStopped;

    public EtfAdjWritePort(String table, String expectedTargetId, JdbcTemplate jdbc, QuestDB questdb) {
        EtfAdjDataset.requireExecutionTable(table);
        if (expectedTargetId == null || !expectedTargetId.startsWith("static-v2-"))
            throw new IllegalArgumentException("Frozen isolated etf_adj target identity required");
        this.table = table; this.expectedTargetId = expectedTargetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(MAX_ROWS_PER_DATE + 1);
        this.questdb = Objects.requireNonNull(questdb);
    }
    @Override public void preflight() {
        requireExpectedTarget();
        QuestDbWriteChecks.preflight(jdbc, table, EtfAdjDataset.definition(table));
        requireExpectedTarget();
    }
    @Override public void send(List<EtfAdj> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS)
            throw new IllegalArgumentException("Nonempty etf_adj batch of at most 250 rows required");
        preflight(); uncertainSenderStopped = false;
        long estimated = 0;
        for (var row : rows) {
            if (row == null) throw new IllegalArgumentException("Null etf_adj row");
            estimated = Math.addExact(estimated, CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
            if (estimated > MAX_BYTES) throw new IllegalArgumentException("etf_adj batch exceeds one MiB before send");
        }
        boolean flushAttempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var value : rows) {
                var line = sender.table(table).symbol("ts_code", value.tsCode());
                if (value.adjFactor() != null) line.doubleColumn("adj_factor", value.adjFactor());
                // timestamp is a UTC-midnight calendar carrier, physically TIMESTAMP (microsecond precision).
                line.at(new TemporalValues.CalendarTimestamp(value.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("etf_adj QWP acknowledgement unknown; reconcile exact keys before replay");
        } catch (Exception failure) {
            flushAttempted = true; throw failure;
        } finally { uncertainSenderStopped = flushAttempted; }
    }
    @Override public List<EtfAdj> readback(List<EtfAdjKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique complete etf_adj keys required");
        requireExpectedTarget();
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code = ? AND timestamp = cast(? as TIMESTAMP))");
            parameters.add(key.tsCode());
            parameters.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        String sql = "SELECT ts_code, adj_factor, cast(timestamp as long) AS trade_date_micros FROM \""
                + table + "\" WHERE " + String.join(" OR ", clauses)
                + " ORDER BY timestamp, ts_code LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, index) -> physical(rs), parameters.toArray());
    }
    /** Bounded distinct physical date inventory used to reject unexplained existing target rows. */
    public List<LocalDate> readExistingDates() {
        requireExpectedTarget();
        var rows = jdbc.query("SELECT DISTINCT cast(timestamp as long) AS trade_date_micros FROM \"" + table
                + "\" ORDER BY trade_date_micros LIMIT 10001", (rs, index) -> date(rs));
        if (rows.size() > 10_000) throw new IllegalStateException("etf_adj physical date inventory exceeds bounded reconciliation cap");
        return List.copyOf(rows);
    }
    /** Full physical date reconciliation is capped; excess rows fail closed instead of being truncated. */
    public List<EtfAdj> readDate(LocalDate date) {
        Objects.requireNonNull(date); requireExpectedTarget();
        long micros = new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);
        String sql = "SELECT ts_code, adj_factor, cast(timestamp as long) AS trade_date_micros FROM \""
                + table + "\" WHERE timestamp = cast(? as TIMESTAMP) ORDER BY ts_code LIMIT 10001";
        var result = jdbc.query(sql, (rs, index) -> physical(rs), micros);
        if (result.size() > MAX_ROWS_PER_DATE) throw new IllegalStateException("etf_adj date exceeds bounded 10000-row readback cap");
        return List.copyOf(result);
    }
    public boolean formalTarget() { return "etf_adj".equals(table); }

    /** Source-certified upserts cannot silently leave unexplained legacy keys in the requested date. */
    public void requireCompatibleFormalDate(LocalDate date, List<EtfAdj> expected) {
        if (!formalTarget()) return;
        if (expected.isEmpty()) throw new IllegalStateException("Formal etf_adj source is empty on an expected SSE open date: " + date);
        var expectedKeys = new HashSet<EtfAdjKey>();
        for (var row : expected) if (!date.equals(row.tradeDate()) || !expectedKeys.add(row.key()))
            throw new IllegalArgumentException("Formal etf_adj requires unique keys in the exact source date");
        var actualKeys = new HashSet<EtfAdjKey>();
        for (var row : readDate(date)) {
            if (!actualKeys.add(row.key())) throw new IllegalStateException("Formal etf_adj contains duplicate existing business keys on " + date);
            if (!expectedKeys.contains(row.key())) throw new IllegalStateException("Formal etf_adj contains a key absent from the complete current source on " + date + "; reconcile source coverage before a retraction");
        }
    }
    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }

    private EtfAdj physical(ResultSet rs) throws SQLException {
        return new EtfAdj(rs.getString("ts_code"), date(rs), finite(rs, "adj_factor"));
    }
    private static LocalDate date(ResultSet rs) throws SQLException {
        Object raw = rs.getObject("trade_date_micros");
        if (!(raw instanceof Number value)) throw new SQLException("etf_adj timestamp required");
        try { return TemporalValues.CalendarTimestamp.fromStorageEpoch(value.longValue(), TemporalValues.EpochUnit.MICROS).date(); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid etf_adj timestamp business-date carrier", invalid); }
    }
    private static Double finite(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new SQLException("Invalid physical etf_adj value: " + column);
        return number.doubleValue();
    }
    private void requireExpectedTarget() {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D015 etf_adj target identity changed during write or readback");
    }
}
