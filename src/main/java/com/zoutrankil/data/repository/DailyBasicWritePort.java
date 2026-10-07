package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.DailyBasicMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.time.Duration;
import java.util.*;

/** Bounded QWP writer and complete-key/full-field PGWire verification for D008. */
public final class DailyBasicWritePort implements VerifiedBatchExecutor.Port<DailyBasic, DailyBasicKey> {
    private static final int MAX_BATCH_BYTES = 1024 * 1024;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<DailyBasic, DailyBasicKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public DailyBasicKey key(DailyBasic row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(DailyBasic row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(row); }
            catch (IOException error) { throw new IllegalArgumentException("Cannot encode daily_basic row", error); }
        }
        @Override public int estimatedTransportBytes(DailyBasic row, byte[] bytes) {
            return Math.addExact(Math.multiplyExact(bytes.length, 4), 128);
        }
    };

    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final DailyBasicMapper mapper = new DailyBasicMapper();

    public DailyBasicWritePort(String table, JdbcTemplate jdbc, QuestDB questdb) {
        DatasetDefinition.identifier(table);
        this.table = table;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20);
        this.jdbc.setMaxRows(10_001);
        this.questdb = Objects.requireNonNull(questdb);
    }

    @Override public void preflight() {
        QuestDbWriteChecks.preflight(jdbc, table, DailyBasicDataset.definition(table));
    }

    @Override public void send(List<DailyBasic> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 250)
            throw new IllegalArgumentException("Nonempty daily_basic batch of at most 250 rows required");
        var prepared = DatasetWritePreparation.prepare(DailyBasicDataset.definition(table), rows, mapper::values,
                new DatasetWritePreparation.Limits(250, MAX_BATCH_BYTES));
        long transportBytes = rows.stream().mapToLong(row -> CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row))).sum();
        if (transportBytes > MAX_BATCH_BYTES) throw new IllegalArgumentException("daily_basic batch byte budget exceeded");
        if (prepared.empty()) throw new IllegalArgumentException("Empty write must not reach QWP");
        try (Sender sender = questdb.borrowSender()) {
            for (var row : rows) {
                var target = sender.table(table).symbol("ts_code", row.tsCode());
                if (row.close() != null) target.doubleColumn("close", row.close());
                if (row.turnoverRate() != null) target.doubleColumn("turnover_rate", row.turnoverRate());
                if (row.turnoverRateF() != null) target.doubleColumn("turnover_rate_f", row.turnoverRateF());
                if (row.volumeRatio() != null) target.doubleColumn("volume_ratio", row.volumeRatio());
                if (row.pe() != null) target.doubleColumn("pe", row.pe());
                if (row.peTtm() != null) target.doubleColumn("pe_ttm", row.peTtm());
                if (row.pb() != null) target.doubleColumn("pb", row.pb());
                if (row.ps() != null) target.doubleColumn("ps", row.ps());
                if (row.psTtm() != null) target.doubleColumn("ps_ttm", row.psTtm());
                if (row.dvRatio() != null) target.doubleColumn("dv_ratio", row.dvRatio());
                if (row.dvTtm() != null) target.doubleColumn("dv_ttm", row.dvTtm());
                if (row.totalShare() != null) target.doubleColumn("total_share", row.totalShare());
                if (row.floatShare() != null) target.doubleColumn("float_share", row.floatShare());
                if (row.freeShare() != null) target.doubleColumn("free_share", row.freeShare());
                if (row.totalMv() != null) target.doubleColumn("total_mv", row.totalMv());
                if (row.circMv() != null) target.doubleColumn("circ_mv", row.circMv());
                target.at(new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("daily_basic QWP acknowledgement unknown; reconcile before replay");
        }
    }

    @Override public List<DailyBasic> readback(List<DailyBasicKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > 250 || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("Finite unique complete daily_basic keys required");
        var clauses = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        // Keep exact key pairs while making the timestamp interval visible to the storage planner.
        var firstDate = keys.stream().map(DailyBasicKey::tradeDate).min(Comparator.naturalOrder()).orElseThrow();
        var lastDate = keys.stream().map(DailyBasicKey::tradeDate).max(Comparator.naturalOrder()).orElseThrow();
        parameters.add(new TemporalValues.CalendarTimestamp(firstDate).storageEpoch(TemporalValues.EpochUnit.MICROS));
        parameters.add(new TemporalValues.CalendarTimestamp(lastDate.plusDays(1)).storageEpoch(TemporalValues.EpochUnit.MICROS));
        var codes = keys.stream().map(DailyBasicKey::tsCode).distinct().sorted().toList();
        parameters.addAll(codes);
        for (var key : keys) {
            clauses.add("(ts_code = ? AND trade_date = cast(? as TIMESTAMP))");
            parameters.add(key.tsCode());
            parameters.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        String sql = "SELECT ts_code, cast(trade_date as long) AS trade_date_micros, close, turnover_rate, turnover_rate_f, "
                + "volume_ratio, pe, pe_ttm, pb, ps, ps_ttm, dv_ratio, dv_ttm, total_share, float_share, free_share, total_mv, circ_mv FROM "
                + table + " WHERE trade_date >= cast(? AS TIMESTAMP) AND trade_date < cast(? AS TIMESTAMP)"
                + " AND ts_code IN (" + String.join(",", Collections.nCopies(codes.size(), "?")) + ")"
                + " AND (" + String.join(" OR ", clauses) + ") ORDER BY trade_date, ts_code LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, index) -> new DailyBasic(rs.getString("ts_code"),
                TemporalValues.CalendarTimestamp.fromStorageEpoch(rs.getLong("trade_date_micros"),
                        TemporalValues.EpochUnit.MICROS).date(),
                rs.getObject("close", Double.class), rs.getObject("turnover_rate", Double.class),
                rs.getObject("turnover_rate_f", Double.class), rs.getObject("volume_ratio", Double.class),
                rs.getObject("pe", Double.class), rs.getObject("pe_ttm", Double.class),
                rs.getObject("pb", Double.class), rs.getObject("ps", Double.class),
                rs.getObject("ps_ttm", Double.class), rs.getObject("dv_ratio", Double.class),
                rs.getObject("dv_ttm", Double.class), rs.getObject("total_share", Double.class),
                rs.getObject("float_share", Double.class), rs.getObject("free_share", Double.class),
                rs.getObject("total_mv", Double.class), rs.getObject("circ_mv", Double.class)), parameters.toArray());
    }

    @Override public boolean walSettled() { return QuestDbWriteChecks.walSettled(jdbc, table); }
    public String tableName() { return table; }
}
