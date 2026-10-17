package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.stock.port.DailyWriteSession;


import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.domain.table.DailyRow;
import com.zoutrankil.data.stock.mapper.DailyMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Finite QWP writes followed by exact full-value lookup of every (ts_code, trade_date) key. */
public final class DailyWritePort implements DailyWriteSession {
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = DailyDataset.MAX_BATCH_BYTES;
    public static final int MAX_DAILY_ROWS = 10000;
    public static final int MAX_EXISTING_DATES = 10000;
    private static final int MAX_READBACK_KEYS = 250;
    private static final List<String> SELECT_COLUMNS = DailySemantics.V1.storageFields();

    public static final VerifiedBatchExecutor.Codec<DailyMarketBar, DailyMarketBar.Key> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                @Override public DailyMarketBar.Key key(DailyMarketBar row) { return row.key(); }
                @Override public byte[] canonicalBytes(DailyMarketBar row) {
                    try {
                        var bytes = new ByteArrayOutputStream(128);
                        try (var output = new DataOutputStream(bytes)) {
                            writeString(output, row.tsCode());
                            writeString(output, row.tradeDate().toString());
                            writeDouble(output, row.open()); writeDouble(output, row.high());
                            writeDouble(output, row.low()); writeDouble(output, row.close());
                            writeDouble(output, row.preClose()); writeDouble(output, row.change());
                            writeDouble(output, row.pctChg()); writeDouble(output, row.vol());
                            writeDouble(output, row.amount()); writeDouble(output, row.ahVol());
                            writeDouble(output, row.ahAmount());
                        }
                        return bytes.toByteArray();
                    } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
                }
                @Override public int estimatedTransportBytes(DailyMarketBar row, byte[] canonical) {
                    return Math.addExact(Math.multiplyExact(canonical.length, 4), 96);
                }
            };

    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final DailyMapper mapper = new DailyMapper();

    public DailyWritePort(String table, JdbcTemplate jdbc, QuestDB questdb) {
        com.zoutrankil.data.domain.DatasetDefinition.identifier(table);
        if(!"daily".equals(table)) IsolatedTablePolicy.DAILY.require(table);
        this.table = table;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(20);
        this.questdb = Objects.requireNonNull(questdb);
    }

    public void preflight() { QuestDbWriteChecks.preflight(jdbc, table, DailyDataset.DEFINITION); }

    @Override public void send(List<DailyMarketBar> rows) {
        if (rows.isEmpty() || rows.size() > MAX_BATCH_ROWS) {
            throw new IllegalArgumentException("One nonempty daily batch of at most 250 rows required");
        }
        var batch = DatasetWritePreparation.prepare(DailyDataset.DEFINITION, rows, mapper::values,
                new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        long transportBytes = rows.stream().mapToLong(row -> CODEC.estimatedTransportBytes(row,
                CODEC.canonicalBytes(row))).sum();
        if (batch.empty() || transportBytes > MAX_BATCH_BYTES) {
            throw new IllegalArgumentException("Daily batch exceeds the bounded normalized/wire budget");
        }
        try (Sender sender = questdb.borrowSender()) {
            for (var value : rows) {
                var row = sender.table(table).symbol("ts_code", value.tsCode());
                if (value.open() != null) row.doubleColumn("open", value.open());
                if (value.high() != null) row.doubleColumn("high", value.high());
                if (value.low() != null) row.doubleColumn("low", value.low());
                if (value.close() != null) row.doubleColumn("close", value.close());
                if (value.preClose() != null) row.doubleColumn("pre_close", value.preClose());
                if (value.change() != null) row.doubleColumn("change", value.change());
                if (value.pctChg() != null) row.doubleColumn("pct_chg", value.pctChg());
                if (value.vol() != null) row.doubleColumn("vol", value.vol());
                if (value.amount() != null) row.doubleColumn("amount", value.amount());
                if (value.ahVol() != null) row.doubleColumn("ah_vol", value.ahVol());
                if (value.ahAmount() != null) row.doubleColumn("ah_amount", value.ahAmount());
                row.at(new TemporalValues.CalendarTimestamp(value.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10000)) {
                throw new IllegalStateException("Daily QWP acknowledgement unknown; reconcile before replay");
            }
        }
    }

    @Override public List<DailyMarketBar> readback(List<DailyMarketBar.Key> keys) {
        if (keys.isEmpty()) return List.of();
        if (keys.size() > MAX_READBACK_KEYS || new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalArgumentException("At most 250 unique daily keys may be verified at once");
        }
        var clauses = new ArrayList<String>(keys.size());
        var parameters = new ArrayList<Object>(keys.size() * 3 + 2);
        // Keep exact pairs; the outer interval/code predicates let QuestDB prune partitions and symbols.
        var firstDate = keys.stream().map(DailyMarketBar.Key::tradeDate).min(Comparator.naturalOrder()).orElseThrow();
        var lastDate = keys.stream().map(DailyMarketBar.Key::tradeDate).max(Comparator.naturalOrder()).orElseThrow();
        parameters.add(new TemporalValues.CalendarTimestamp(firstDate).storageEpoch(TemporalValues.EpochUnit.MICROS));
        parameters.add(new TemporalValues.CalendarTimestamp(lastDate.plusDays(1)).storageEpoch(TemporalValues.EpochUnit.MICROS));
        var codes = keys.stream().map(DailyMarketBar.Key::tsCode).distinct().sorted().toList();
        parameters.addAll(codes);
        for (var key : keys) {
            clauses.add("(ts_code=? AND trade_date=cast(? as TIMESTAMP))");
            parameters.add(key.tsCode());
            parameters.add(new TemporalValues.CalendarTimestamp(key.tradeDate())
                    .storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        String projection = String.join(", ", SELECT_COLUMNS.stream().map(column -> column.equals("trade_date")
                ? "cast(trade_date as long) AS trade_date_micros" : column).toList());
        String sql = "SELECT " + projection + " FROM " + table
                + " WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP)"
                + " AND ts_code IN (" + String.join(",", Collections.nCopies(codes.size(), "?")) + ")"
                + " AND (" + String.join(" OR ", clauses) + ")"
                + " ORDER BY ts_code,trade_date LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, index) -> mapper.fromStorage(readRow(rs)), parameters.toArray());
    }

    @Override public boolean walSettled() { return QuestDbWriteChecks.walSettled(jdbc, table); }

    /** One full date scan is used to validate an incremental checkpoint before it can be reused. */
    public List<DailyMarketBar> readDate(LocalDate day) {
        LocalDate next = day.plusDays(1);
        // This path is only used for the finite checkpoint overlap, bounded by the D002 inventory cap.
        String projection = String.join(", ", SELECT_COLUMNS.stream().map(column -> column.equals("trade_date")
                ? "cast(trade_date as long) AS trade_date_micros" : column).toList());
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT " + projection + " FROM " + table
                    + " WHERE trade_date>=cast(? as TIMESTAMP) AND trade_date<cast(? as TIMESTAMP) "
                    + "ORDER BY ts_code,trade_date LIMIT " + (MAX_DAILY_ROWS + 1));
            statement.setQueryTimeout(20);
            statement.setMaxRows(MAX_DAILY_ROWS + 1);
            statement.setFetchSize(MAX_DAILY_ROWS + 1);
            statement.setObject(1, new TemporalValues.CalendarTimestamp(day).storageEpoch(TemporalValues.EpochUnit.MICROS));
            statement.setObject(2, new TemporalValues.CalendarTimestamp(next).storageEpoch(TemporalValues.EpochUnit.MICROS));
            return statement;
        }, (rs, index) -> {
            if (index >= MAX_DAILY_ROWS) throw new IllegalStateException("Daily checkpoint scan reached the D002 inventory bound");
            return mapper.fromStorage(readRow(rs));
        });
    }

    /** Bounded scan of distinct persisted business dates, used to reconcile the target interval with ledger coverage. */
    public List<LocalDate> readExistingDates() {
        String sql = "SELECT cast(trade_date as long) AS trade_date_micros FROM " + table
                + " GROUP BY trade_date ORDER BY trade_date LIMIT " + (MAX_EXISTING_DATES + 1);
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(20);
            statement.setMaxRows(MAX_EXISTING_DATES + 1);
            statement.setFetchSize(MAX_EXISTING_DATES + 1);
            return statement;
        }, (rs, index) -> {
            if (index >= MAX_EXISTING_DATES)
                throw new IllegalStateException("Daily target interval exceeds the 10000-date reconciliation bound");
            long epochMicros = rs.getLong("trade_date_micros");
            if (rs.wasNull()) throw new SQLException("Required daily trade_date is null");
            return TemporalValues.CalendarTimestamp.fromStorageEpoch(epochMicros, TemporalValues.EpochUnit.MICROS).date();
        });
    }

    private static DailyRow readRow(ResultSet rs) throws SQLException {
        long epochMicros = rs.getLong("trade_date_micros");
        if (rs.wasNull()) throw new SQLException("Required daily trade_date is null");
        return new DailyRow(rs.getString("ts_code"), TemporalValues.epoch(epochMicros,
                TemporalValues.EpochUnit.MICROS, TemporalValues.Precision.MICROS),
                nullableDouble(rs, "open"), nullableDouble(rs, "high"), nullableDouble(rs, "low"),
                nullableDouble(rs, "close"), nullableDouble(rs, "pre_close"), nullableDouble(rs, "change"),
                nullableDouble(rs, "pct_chg"), nullableDouble(rs, "vol"), nullableDouble(rs, "amount"),
                nullableDouble(rs, "ah_vol"), nullableDouble(rs, "ah_amount"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Double.class);
    }

    private static void writeString(DataOutputStream output, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeDouble(DataOutputStream output, Double value) throws java.io.IOException {
        output.writeBoolean(value != null);
        if (value != null) output.writeLong(Double.doubleToLongBits(value));
    }
    @Override public VerifiedBatchExecutor.Codec<DailyMarketBar, DailyMarketBar.Key> codec() { return CODEC; }
}
