package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.EtfPortfolio;
import com.zoutrankil.questdbwithdata.domain.EtfPortfolioDataset;
import com.zoutrankil.questdbwithdata.domain.EtfPortfolioKey;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.mapper.EtfPortfolioMapper;
import com.zoutrankil.questdbwithdata.service.EtfPortfolioJobService;
import com.zoutrankil.questdbwithdata.service.EtfPortfolioSource;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import com.zoutrankil.questdbwithdata.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** Bounded WAL writer with exact full-key/all-column readback and frozen physical-target checks. */
public final class EtfPortfolioWritePort implements VerifiedBatchExecutor.Port<EtfPortfolio, EtfPortfolioKey> {
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_ROWS_PER_ANN_DATE = EtfPortfolioSource.MAX_ROWS_PER_ANN_DATE;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<EtfPortfolio, EtfPortfolioKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public EtfPortfolioKey key(EtfPortfolio row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(EtfPortfolio row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new EtfPortfolioMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot encode etf_portfolio row", failure); }
        }
        @Override public int estimatedTransportBytes(EtfPortfolio row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 192);
        }
    };

    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private volatile boolean uncertainSenderStopped;

    public EtfPortfolioWritePort(String table, String expectedTargetId, JdbcTemplate jdbc, QuestDB questdb) {
        EtfPortfolioDataset.requireIsolatedTable(table);
        if (expectedTargetId == null || !expectedTargetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen isolated etf_portfolio target identity required");
        this.table = table; this.expectedTargetId = expectedTargetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(MAX_ROWS_PER_ANN_DATE + 1);
        this.questdb = Objects.requireNonNull(questdb);
    }

    @Override public void preflight() {
        requireExpectedTarget();
        QuestDbWriteChecks.preflight(jdbc, table, EtfPortfolioDataset.definition(table));
        requireExpectedTarget();
    }

    @Override public void send(List<EtfPortfolio> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("Nonempty etf_portfolio batch of at most 250 rows required");
        preflight(); uncertainSenderStopped = false;
        var prepared = DatasetWritePreparation.prepare(EtfPortfolioDataset.definition(table), rows,
                new EtfPortfolioMapper()::values, new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        if (prepared.empty()) throw new IllegalArgumentException("Empty etf_portfolio write must not reach QWP");
        long transportBytes = 0;
        for (var row : rows) transportBytes = Math.addExact(transportBytes,
                CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
        if (transportBytes > MAX_BATCH_BYTES) throw new IllegalArgumentException("etf_portfolio transport byte budget exceeded before send");

        Sender sender = questdb.borrowSender(); boolean flushAttempted = false;
        try {
            for (var value : rows) {
                var line = sender.table(table).symbol("ts_code", value.tsCode()).symbol("symbol", value.symbol());
                if (value.mkv() != null) line.doubleColumn("mkv", value.mkv());
                if (value.amount() != null) line.doubleColumn("amount", value.amount());
                if (value.stkMkvRatio() != null) line.doubleColumn("stk_mkv_ratio", value.stkMkvRatio());
                if (value.stkFloatRatio() != null) line.doubleColumn("stk_float_ratio", value.stkFloatRatio());
                line.timestampColumn("ann_date", new TemporalValues.CalendarTimestamp(value.annDate()).storageCarrier())
                        .timestampColumn("update_time", value.updateTime())
                        .at(new TemporalValues.CalendarTimestamp(value.endDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("etf_portfolio QWP acknowledgement unknown; reconcile exact keys before replay");
        } catch (Exception failure) {
            flushAttempted = true; throw failure;
        } finally {
            try { sender.close(); uncertainSenderStopped = flushAttempted; }
            catch (Exception closeFailure) { uncertainSenderStopped = false; throw closeFailure; }
        }
    }

    @Override public List<EtfPortfolio> readback(List<EtfPortfolioKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique complete etf_portfolio keys required");
        requireExpectedTarget();
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code=? AND ann_date=cast(? AS TIMESTAMP) AND end_date=cast(? AS TIMESTAMP) AND symbol=?)");
            parameters.add(key.tsCode()); parameters.add(calendarMicros(key.annDate()));
            parameters.add(calendarMicros(key.endDate())); parameters.add(key.symbol());
        }
        String sql = "SELECT ts_code,cast(ann_date as long) AS ann_date_micros,cast(end_date as long) AS end_date_micros,"
                + "symbol,mkv,amount,stk_mkv_ratio,stk_float_ratio,cast(update_time as long) AS update_time_micros FROM \""
                + table + "\" WHERE " + String.join(" OR ", clauses)
                + " ORDER BY ts_code,ann_date,end_date,symbol LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, index) -> physical(rs), parameters.toArray());
    }

    /** Bounded date inventory used to reject rows outside receipt-backed checkpoint coverage. */
    public List<LocalDate> readExistingAnnouncementDates() {
        requireExpectedTarget();
        var dates = jdbc.query("SELECT DISTINCT cast(ann_date as long) AS ann_date_micros FROM \"" + table
                + "\" ORDER BY ann_date_micros LIMIT 10001", (rs, index) -> date(rs, "ann_date_micros"));
        if (dates.size() > 10_000) throw new IllegalStateException("etf_portfolio physical ann_date inventory exceeds 10000-date cap");
        return List.copyOf(dates);
    }

    /** Complete rows for one announcement date, capped to the source-contract row budget. */
    public List<EtfPortfolio> readAnnouncementDate(LocalDate annDate) {
        Objects.requireNonNull(annDate); requireExpectedTarget();
        String sql = "SELECT ts_code,cast(ann_date as long) AS ann_date_micros,cast(end_date as long) AS end_date_micros,"
                + "symbol,mkv,amount,stk_mkv_ratio,stk_float_ratio,cast(update_time as long) AS update_time_micros FROM \""
                + table + "\" WHERE ann_date=cast(? AS TIMESTAMP) ORDER BY ts_code,end_date,symbol LIMIT "
                + (MAX_ROWS_PER_ANN_DATE + 1);
        var rows = jdbc.query(sql, (rs, index) -> physical(rs), calendarMicros(annDate));
        if (rows.size() > MAX_ROWS_PER_ANN_DATE)
            throw new IllegalStateException("etf_portfolio ann_date exceeds bounded source/readback row cap");
        return List.copyOf(rows);
    }

    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }

    private EtfPortfolio physical(ResultSet rs) throws SQLException {
        Object update = rs.getObject("update_time_micros");
        if (!(update instanceof Number updateMicros)) throw new SQLException("etf_portfolio update_time required");
        try {
            return new EtfPortfolio(new EtfPortfolioKey(rs.getString("ts_code"), date(rs, "ann_date_micros"),
                    date(rs, "end_date_micros"), rs.getString("symbol")), finite(rs, "mkv"), finite(rs, "amount"),
                    finite(rs, "stk_mkv_ratio"), finite(rs, "stk_float_ratio"),
                    TemporalValues.epoch(updateMicros.longValue(), TemporalValues.EpochUnit.MICROS, TemporalValues.Precision.MICROS));
        } catch (RuntimeException invalid) { throw new SQLException("Invalid physical etf_portfolio row", invalid); }
    }
    private static Double finite(ResultSet rs, String column) throws SQLException {
        Object raw = rs.getObject(column);
        if (raw == null) return null;
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new SQLException("Invalid physical etf_portfolio numeric field: " + column);
        return number.doubleValue();
    }
    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Object raw = rs.getObject(column);
        if (!(raw instanceof Number number)) throw new SQLException("etf_portfolio calendar timestamp required: " + column);
        try { return TemporalValues.CalendarTimestamp.fromStorageEpoch(number.longValue(), TemporalValues.EpochUnit.MICROS).date(); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid etf_portfolio calendar carrier: " + column, invalid); }
    }
    private static long calendarMicros(LocalDate date) {
        return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);
    }
    private void requireExpectedTarget() {
        var identity = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (identity.size() != 1 || !(identity.getFirst().get("id") instanceof Number id)
                || !(identity.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D018 etf_portfolio physical target identity changed during write/readback");
    }
}
