package com.zoutrankil.data.repository;

import com.zoutrankil.data.service.EtfShareSource;

import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.EtfShare;
import com.zoutrankil.data.domain.EtfShareDataset;
import com.zoutrankil.data.domain.EtfShareKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.EtfShareMapper;
import com.zoutrankil.data.service.EtfShareJobService;
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

/** Bounded WAL writer and full-row isolated readback for the provider's nullable extension fields. */
public final class EtfShareWritePort implements VerifiedBatchExecutor.Port<EtfShare, EtfShareKey> {
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_ROWS_PER_DATE = EtfShareSource.MAX_ROWS_PER_DATE;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<EtfShare, EtfShareKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public EtfShareKey key(EtfShare row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(EtfShare row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new EtfShareMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot encode etf_share row", failure); }
        }
        @Override public int estimatedTransportBytes(EtfShare row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 192);
        }
    };

    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final EtfShareMapper mapper = new EtfShareMapper();
    private volatile boolean uncertainSenderStopped;

    public EtfShareWritePort(String table, String expectedTargetId, JdbcTemplate jdbc, QuestDB questdb) {
        EtfShareJobService.requireIsolatedTableName(table);
        if (expectedTargetId == null || !expectedTargetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen isolated etf_share target identity required");
        this.table = table; this.expectedTargetId = expectedTargetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(MAX_ROWS_PER_DATE + 1);
        this.questdb = Objects.requireNonNull(questdb);
    }

    @Override public void preflight() {
        requireExpectedTarget();
        QuestDbWriteChecks.preflight(jdbc, table, EtfShareDataset.definition(table));
        requireExpectedTarget();
    }

    @Override public void send(List<EtfShare> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("Nonempty etf_share batch of at most 250 rows required");
        preflight(); uncertainSenderStopped = false;
        DatasetWritePreparation.prepare(EtfShareDataset.definition(table), rows, mapper::values,
                new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        long bytes = 0;
        for (var row : rows) {
            if (row == null) throw new IllegalArgumentException("Null etf_share row");
            bytes = Math.addExact(bytes, CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
            if (bytes > MAX_BATCH_BYTES) throw new IllegalArgumentException("etf_share batch exceeds one MiB before send");
        }
        boolean flushAttempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var value : rows) {
                var line = sender.table(table).symbol("ts_code", value.tsCode());
                if (value.fdShare() != null) line.doubleColumn("fd_share", value.fdShare());
                // Omitting a null setter is the Java sender's documented SQL NULL representation; DEDUP replaces the row.
                if (value.fundType() != null) line.stringColumn("fund_type", value.fundType());
                line.symbol("market", value.market());
                line.timestampColumn("update_time", value.updateTime())
                        .at(new TemporalValues.CalendarTimestamp(value.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("etf_share QWP acknowledgement unknown; reconcile exact keys before replay");
        } catch (Exception failure) {
            flushAttempted = true; throw failure;
        } finally { uncertainSenderStopped = flushAttempted; }
    }

    @Override public List<EtfShare> readback(List<EtfShareKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_ROWS
                || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique complete etf_share keys required");
        requireExpectedTarget();
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code = ? AND timestamp = cast(? AS TIMESTAMP))");
            parameters.add(key.tsCode());
            parameters.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        String sql = "SELECT ts_code, cast(timestamp AS long) AS trade_date_micros, fd_share, fund_type, market, "
                + "cast(update_time AS long) AS update_time_micros FROM \"" + table + "\" WHERE "
                + String.join(" OR ", clauses) + " ORDER BY timestamp, ts_code LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, index) -> physical(rs), parameters.toArray());
    }

    public List<LocalDate> readExistingDates() {
        requireExpectedTarget();
        var dates = jdbc.query("SELECT DISTINCT cast(timestamp AS long) AS trade_date_micros FROM \"" + table
                + "\" ORDER BY trade_date_micros LIMIT 10001", (rs, index) -> date(rs));
        if (dates.size() > 10_000) throw new IllegalStateException("etf_share date inventory exceeds 10000-row reconciliation cap");
        return List.copyOf(dates);
    }

    public List<EtfShare> readDate(LocalDate date) {
        Objects.requireNonNull(date); requireExpectedTarget();
        long micros = new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);
        String sql = "SELECT ts_code, cast(timestamp AS long) AS trade_date_micros, fd_share, fund_type, market, "
                + "cast(update_time AS long) AS update_time_micros FROM \"" + table
                + "\" WHERE timestamp = cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT " + (MAX_ROWS_PER_DATE + 1);
        var rows = jdbc.query(sql, (rs, index) -> physical(rs), micros);
        if (rows.size() > MAX_ROWS_PER_DATE) throw new IllegalStateException("etf_share date exceeds the bounded three-market readback cap");
        return List.copyOf(rows);
    }

    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }

    private EtfShare physical(ResultSet rs) throws SQLException {
        Object update = rs.getObject("update_time_micros");
        if (!(update instanceof Number updateMicros)) throw new SQLException("etf_share update_time required");
        Object shares = rs.getObject("fd_share");
        if (shares != null && !(shares instanceof Number)) throw new SQLException("Invalid physical etf_share fd_share");
        Double amount = shares == null ? null : ((Number) shares).doubleValue();
        if (amount != null && !Double.isFinite(amount)) throw new SQLException("Non-finite physical etf_share fd_share");
        try {
            return new EtfShare(new EtfShareKey(rs.getString("ts_code"), date(rs)), amount,
                    rs.getString("fund_type"), rs.getString("market"),
                    TemporalValues.epoch(updateMicros.longValue(), TemporalValues.EpochUnit.MICROS, TemporalValues.Precision.MICROS));
        } catch (RuntimeException invalid) { throw new SQLException("Invalid physical etf_share row", invalid); }
    }

    private static LocalDate date(ResultSet rs) throws SQLException {
        Object raw = rs.getObject("trade_date_micros");
        if (!(raw instanceof Number value)) throw new SQLException("etf_share timestamp required");
        try { return TemporalValues.CalendarTimestamp.fromStorageEpoch(value.longValue(), TemporalValues.EpochUnit.MICROS).date(); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid etf_share business-date carrier", invalid); }
    }

    private void requireExpectedTarget() {
        var identity = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (identity.size() != 1 || !(identity.getFirst().get("id") instanceof Number id)
                || !(identity.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D016 etf_share physical target identity changed during write/readback");
    }
}
