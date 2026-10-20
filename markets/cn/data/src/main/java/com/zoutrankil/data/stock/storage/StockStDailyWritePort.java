package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.stock.basic.StockStDaily;
import com.zoutrankil.data.domain.stock.basic.StockStDailyDataset;
import com.zoutrankil.data.domain.stock.basic.StockStDailyKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.stock.domain.StockExecutionTables;
import com.zoutrankil.data.stock.mapper.StockStDailyMapper;
import com.zoutrankil.data.stock.port.StockStDailyWriteSession;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

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

/** Bounded D012 WAL writer; the job's authoritative-window publisher applies retractions on an isolated stage. */
public final class StockStDailyWritePort implements StockStDailyWriteSession.Versioned {
    private static final int MAX_ROWS = 250;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_DATE_ROWS = 10_000;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<StockStDaily, StockStDailyKey> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                @Override
                public StockStDailyKey key(StockStDaily row) {
                    return row == null ? null : row.key();
                }

                @Override
                public byte[] canonicalBytes(StockStDaily row) {
                    try {
                        return JobDefinitionJson.mapper()
                                .writeValueAsBytes(new StockStDailyMapper().values(row).asMap());
                    } catch (Exception failure) {
                        throw new IllegalArgumentException(
                                "Cannot encode stk_st_daily row", failure);
                    }
                }

                @Override
                public int estimatedTransportBytes(StockStDaily row, byte[] canonical) {
                    return Math.addExact(Math.multiplyExact(canonical.length, 4), 128);
                }
            };
    // v1 字节编码保持原样；v2 的 trade_date 字段必须生成自己的摘要。
    private static final VerifiedBatchExecutor.Codec<StockStDaily, StockStDailyKey>
            TRADE_DATE_CODEC =
                    new VerifiedBatchExecutor.Codec<>() {
                        public StockStDailyKey key(StockStDaily row) {
                            return CODEC.key(row);
                        }

                        public byte[] canonicalBytes(StockStDaily row) {
                            try {
                                return JobDefinitionJson.mapper()
                                        .writeValueAsBytes(
                                                new StockStDailyMapper()
                                                        .values(
                                                                row,
                                                                CnTradeDateLayout.TRADE_DATE_V2)
                                                        .asMap());
                            } catch (Exception failure) {
                                throw new IllegalArgumentException("无法编码新版 ST 交易日行", failure);
                            }
                        }

                        public int estimatedTransportBytes(StockStDaily row, byte[] canonical) {
                            return CODEC.estimatedTransportBytes(row, canonical);
                        }
                    };

    public static VerifiedBatchExecutor.Codec<StockStDaily, StockStDailyKey> codec(
            CnTradeDateLayout layout) {
        return Objects.requireNonNull(layout) == CnTradeDateLayout.LEGACY_V1
                ? CODEC
                : TRADE_DATE_CODEC;
    }

    private final CnTradeDateLayout layout;
    private final String dateColumn;
    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private volatile boolean uncertainSenderStopped;

    public StockStDailyWritePort(
            String table, String expectedTargetId, JdbcTemplate jdbc, QuestDB questdb) {
        this(table, expectedTargetId, jdbc, questdb, CnTradeDateLayout.LEGACY_V1);
    }

    public StockStDailyWritePort(
            String table,
            String expectedTargetId,
            JdbcTemplate jdbc,
            QuestDB questdb,
            CnTradeDateLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        dateColumn = layout.physicalDateColumn("stk_st_daily");
        StockExecutionTables.requireStockStDaily(table);
        if (expectedTargetId == null || !expectedTargetId.startsWith("static-v2-"))
            throw new IllegalArgumentException(
                    "Frozen isolated stk_st_daily target identity required");
        this.table = table;
        this.expectedTargetId = expectedTargetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20);
        this.jdbc.setMaxRows(MAX_DATE_ROWS + 1);
        this.questdb = Objects.requireNonNull(questdb);
    }

    @Override
    public int datasetVersion() {
        return layout.schemaVersion();
    }

    @Override
    public void preflight() {
        requireExpectedTarget();
        QuestDbWriteChecks.preflight(jdbc, table, StockStDailyDataset.definition(table, layout));
        requireExpectedTarget();
    }

    @Override
    public void send(List<StockStDaily> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS)
            throw new IllegalArgumentException(
                    "Nonempty stk_st_daily batch of at most 250 rows required");
        preflight();
        uncertainSenderStopped = false;
        long estimated = 0;
        for (var row : rows) {
            if (row == null) throw new IllegalArgumentException("Null stk_st_daily row");
            estimated =
                    Math.addExact(
                            estimated,
                            codec().estimatedTransportBytes(row, codec().canonicalBytes(row)));
            if (estimated > MAX_BYTES)
                throw new IllegalArgumentException(
                        "stk_st_daily batch exceeds one MiB before send");
        }
        boolean flushAttempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var value : rows) {
                sender.table(table)
                        .symbol("ts_code", value.tsCode())
                        .intColumn("is_st", value.isSt())
                        .at(
                                new TemporalValues.CalendarTimestamp(value.timestamp())
                                        .storageCarrier());
            }
            long sequence = sender.flushAndGetSequence();
            flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException(
                        "stk_st_daily QWP acknowledgement unknown; exact keys must be reconciled before replay");
        } catch (Exception failure) {
            flushAttempted = true;
            throw failure;
        } finally {
            uncertainSenderStopped = flushAttempted;
        }
    }

    @Override
    public List<StockStDaily> readback(List<StockStDailyKey> keys) {
        if (keys == null
                || keys.isEmpty()
                || keys.size() > MAX_ROWS
                || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException(
                    "At most 250 unique complete stk_st_daily keys required");
        requireExpectedTarget();
        var clauses = new ArrayList<String>();
        var params = new ArrayList<Object>();
        LocalDate from =
                keys.stream()
                        .map(StockStDailyKey::timestamp)
                        .min(LocalDate::compareTo)
                        .orElseThrow();
        LocalDate to =
                keys.stream()
                        .map(StockStDailyKey::timestamp)
                        .max(LocalDate::compareTo)
                        .orElseThrow();
        params.add(
                new TemporalValues.CalendarTimestamp(from)
                        .storageEpoch(TemporalValues.EpochUnit.MICROS));
        params.add(
                new TemporalValues.CalendarTimestamp(to.plusDays(1))
                        .storageEpoch(TemporalValues.EpochUnit.MICROS));
        var codes = keys.stream().map(StockStDailyKey::tsCode).distinct().sorted().toList();
        params.addAll(codes);
        for (var key : keys) {
            clauses.add("(ts_code=? AND " + dateColumn + "=cast(? AS TIMESTAMP))");
            params.add(key.tsCode());
            params.add(
                    new TemporalValues.CalendarTimestamp(key.timestamp())
                            .storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        String sql =
                "SELECT ts_code, is_st, cast("
                        + dateColumn
                        + " AS long) AS timestamp_micros FROM \""
                        + table
                        + "\" WHERE "
                        + dateColumn
                        + ">=cast(? AS TIMESTAMP) AND "
                        + dateColumn
                        + "<cast(? AS TIMESTAMP) AND ts_code IN ("
                        + String.join(",", codes.stream().map(code -> "?").toList())
                        + ") AND ("
                        + String.join(" OR ", clauses)
                        + ") ORDER BY "
                        + dateColumn
                        + ",ts_code LIMIT "
                        + (keys.size() + 1);
        return jdbc.query(sql, (rs, index) -> physical(rs), params.toArray());
    }

    public List<LocalDate> readExistingDates() {
        requireExpectedTarget();
        var result =
                jdbc.query(
                        "SELECT DISTINCT cast("
                                + dateColumn
                                + " AS long) AS timestamp_micros FROM \""
                                + table
                                + "\" ORDER BY timestamp_micros LIMIT "
                                + (MAX_DATE_ROWS + 1),
                        (rs, index) -> date(rs));
        if (result.size() > MAX_DATE_ROWS)
            throw new IllegalStateException("stk_st_daily physical date inventory exceeds bound");
        return List.copyOf(result);
    }

    public List<StockStDaily> readDate(LocalDate date) {
        Objects.requireNonNull(date);
        requireExpectedTarget();
        long micros =
                new TemporalValues.CalendarTimestamp(date)
                        .storageEpoch(TemporalValues.EpochUnit.MICROS);
        String sql =
                "SELECT ts_code,is_st,cast("
                        + dateColumn
                        + " AS long) AS timestamp_micros FROM \""
                        + table
                        + "\" WHERE "
                        + dateColumn
                        + "=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT "
                        + (MAX_DATE_ROWS + 1);
        var rows = jdbc.query(sql, (rs, index) -> physical(rs), micros);
        if (rows.size() > MAX_DATE_ROWS)
            throw new IllegalStateException("stk_st_daily exceeds bounded per-date row inventory");
        return List.copyOf(rows);
    }

    /** Existing positive keys absent from a newly fetched interval prove a correction/retraction. */
    public void requireNoRevokedKeys(LocalDate date, List<StockStDaily> expected) {
        var expectedKeys = new HashSet<StockStDailyKey>();
        for (var row : expected)
            if (!expectedKeys.add(row.key()))
                throw new IllegalArgumentException("Duplicate stk_st_daily expected key");
        for (var row : readDate(date))
            if (!expectedKeys.contains(row.key()))
                throw new IllegalStateException(
                        "Namechange revoked/shortened an existing stk_st_daily key on "
                                + date
                                + "; fail-closed reconcile/delete is required before this date can advance");
    }

    @Override
    public boolean walSettled() {
        requireExpectedTarget();
        return QuestDbWriteChecks.walSettled(jdbc, table);
    }

    @Override
    public boolean uncertainSenderStopped() {
        return uncertainSenderStopped;
    }

    private static StockStDaily physical(ResultSet rs) throws SQLException {
        LocalDate day = date(rs);
        int status = rs.getInt("is_st");
        if (rs.wasNull()) throw new SQLException("stk_st_daily is_st is null");
        return new StockStDaily(rs.getString("ts_code"), day, status);
    }

    private static LocalDate date(ResultSet rs) throws SQLException {
        Object raw = rs.getObject("timestamp_micros");
        if (!(raw instanceof Number value))
            throw new SQLException("stk_st_daily timestamp required");
        return TemporalValues.CalendarTimestamp.fromStorageEpoch(
                        value.longValue(), TemporalValues.EpochUnit.MICROS)
                .date();
    }

    private void requireExpectedTarget() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1
                || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(
                        StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D012 stk_st_daily isolated target identity changed");
    }

    @Override
    public VerifiedBatchExecutor.Codec<StockStDaily, StockStDailyKey> codec() {
        return codec(layout);
    }
}
