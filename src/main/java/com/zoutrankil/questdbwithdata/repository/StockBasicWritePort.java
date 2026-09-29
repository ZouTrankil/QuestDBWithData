package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.StockBasic;
import com.zoutrankil.questdbwithdata.domain.StockBasicDataset;
import com.zoutrankil.questdbwithdata.domain.StockBasicSnapshot;
import com.zoutrankil.questdbwithdata.domain.StockBasicSnapshotKey;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Physical QWP adapter for the existing Java sample; tests can use an owned isolated table. */
public final class StockBasicWritePort implements VerifiedBatchExecutor.Port<StockBasicSnapshot, StockBasicSnapshotKey> {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questDb;
    private final int maxBatchBytes;
    private final Duration acknowledgementTimeout;

    public StockBasicWritePort(String table, JdbcTemplate jdbc, QuestDB questDb) {
        this(table, jdbc, questDb, 1024 * 1024, Duration.ofSeconds(10));
    }

    public StockBasicWritePort(String table, JdbcTemplate jdbc, QuestDB questDb,
                               int maxBatchBytes, Duration acknowledgementTimeout) {
        DatasetDefinition.identifier(table);
        if (maxBatchBytes < 1 || acknowledgementTimeout == null || acknowledgementTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("Finite application batch and acknowledgement budgets required");
        }
        this.table = table;
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(20);
        this.jdbc.setMaxRows(10_001);
        this.questDb = questDb;
        this.maxBatchBytes = maxBatchBytes;
        this.acknowledgementTimeout = acknowledgementTimeout;
    }

    public static final VerifiedBatchExecutor.Codec<StockBasicSnapshot, StockBasicSnapshotKey> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                @Override public StockBasicSnapshotKey key(StockBasicSnapshot row) {
                    if (row == null || row.stock() == null || row.snapshotTimestamp() == null
                            || row.stock().tsCode() == null || row.stock().tsCode().isBlank()) {
                        throw new IllegalArgumentException("Complete stock basic snapshot key required before send");
                    }
                    TemporalValues.requirePrecision(row.snapshotTimestamp(), TemporalValues.Precision.MICROS);
                    return row.key();
                }
                @Override public byte[] canonicalBytes(StockBasicSnapshot row) {
                    try {
                        var bytes = new ByteArrayOutputStream();
                        var out = new DataOutputStream(bytes);
                        out.writeLong(TemporalValues.epochValue(row.snapshotTimestamp(), TemporalValues.EpochUnit.MICROS));
                        StockBasic stock = row.stock();
                        writeString(out, stock.tsCode()); writeString(out, stock.symbol());
                        writeString(out, stock.name()); writeString(out, stock.area());
                        writeString(out, stock.industry());
                        writeString(out, stock.listDate() == null ? null
                                : TemporalValues.formatDate(stock.listDate(), TemporalValues.DateFormat.BASIC));
                        out.flush();
                        return bytes.toByteArray();
                    } catch (IOException error) { throw new IllegalStateException(error); }
                }
                @Override public int estimatedTransportBytes(StockBasicSnapshot row, byte[] canonical) {
                    // Conservative application budget, not a measurement of QWP frames or network bytes.
                    return Math.addExact(Math.multiplyExact(canonical.length, 4), 256);
                }
            };

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(encoded.length); out.write(encoded);
    }

    @Override public void preflight() {
        var expected = new HashMap<String, String>();
        for (var column : StockBasicDataset.DEFINITION.columns()) {
            expected.put(column.storageName(), column.storageType().name());
        }
        var actualKeys = new java.util.HashSet<String>();
        Map<String, String> actual = jdbc.query("SELECT \"column\", \"type\", \"upsertKey\" FROM table_columns('" + table + "')",
                rs -> {
                    var found = new HashMap<String, String>();
                    while (rs.next()) {
                        found.put(rs.getString(1), rs.getString(2));
                        if (rs.getBoolean(3)) actualKeys.add(rs.getString(1));
                    }
                    return found;
                });
        if (actual == null || !actual.equals(expected)
                || !actualKeys.equals(new java.util.HashSet<>(StockBasicDataset.DEFINITION.dedupKey()))) {
            throw new IllegalStateException("Stock basic target schema differs from declared columns");
        }
        var metadata = jdbc.queryForList("SELECT designatedTimestamp, partitionBy FROM tables() WHERE table_name = ?", table);
        if (metadata.size() != 1
                || !StockBasicDataset.DEFINITION.designatedTimestamp().equals(metadata.getFirst().get("designatedTimestamp"))
                || !StockBasicDataset.DEFINITION.partition().name().equals(metadata.getFirst().get("partitionBy"))) {
            throw new IllegalStateException("Stock basic designated timestamp or partition differs from definition");
        }
        if (!walSettled()) throw new IllegalStateException("Stock basic target WAL not settled before send");
    }

    @Override public void send(List<StockBasicSnapshot> rows) {
        if (rows.isEmpty() || rows.size() > 10_000) throw new IllegalArgumentException("Finite nonempty write batch required");
        long estimatedBytes = 0;
        for (var row : rows) {
            byte[] canonical = CODEC.canonicalBytes(row);
            estimatedBytes += CODEC.estimatedTransportBytes(row, canonical);
            if (estimatedBytes > maxBatchBytes)
                throw new IllegalArgumentException("Application batch byte budget exceeded before send");
        }
        try (Sender sender = questDb.borrowSender()) {
            for (StockBasicSnapshot snapshot : rows) {
                StockBasic stock = snapshot.stock();
                var row = sender.table(table).symbol("ts_code", stock.tsCode());
                if (stock.symbol() != null) row.symbol("symbol", stock.symbol());
                if (stock.name() != null) row.stringColumn("name", stock.name());
                if (stock.area() != null) row.symbol("area", stock.area());
                if (stock.industry() != null) row.symbol("industry", stock.industry());
                if (stock.listDate() != null) row.stringColumn("list_date",
                        TemporalValues.formatDate(stock.listDate(), TemporalValues.DateFormat.BASIC));
                row.at(snapshot.snapshotTimestamp());
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, acknowledgementTimeout.toMillis())) {
                throw new IllegalStateException("QWP acknowledgement not confirmed; reconcile before replay");
            }
        }
    }

    @Override public List<StockBasicSnapshot> readback(List<StockBasicSnapshotKey> keys) {
        if (keys.isEmpty()) return List.of();
        var clauses = new ArrayList<String>();
        var params = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(snapshot_ts = cast(? as TIMESTAMP) AND ts_code = ?)");
            params.add(TemporalValues.epochValue(key.snapshotTimestamp(), TemporalValues.EpochUnit.MICROS));
            params.add(key.tsCode());
        }
        String sql = "SELECT cast(snapshot_ts as long) AS epoch_micros, ts_code, symbol, name, area, industry, list_date "
                + "FROM " + table + " WHERE " + String.join(" OR ", clauses)
                + " ORDER BY snapshot_ts, ts_code LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, n) -> {
            long micros = rs.getLong("epoch_micros");
            Instant timestamp = TemporalValues.epoch(micros, TemporalValues.EpochUnit.MICROS,
                    TemporalValues.Precision.MICROS);
            String rawDate = rs.getString("list_date");
            return new StockBasicSnapshot(timestamp, new StockBasic(rs.getString("ts_code"),
                    rs.getString("symbol"), rs.getString("name"), rs.getString("area"),
                    rs.getString("industry"), rawDate == null || rawDate.isBlank() ? null
                    : TemporalValues.businessDate(rawDate, TemporalValues.DateFormat.BASIC)));
        }, params.toArray());
    }

    @Override public boolean walSettled() {
        var rows = jdbc.queryForList("SELECT walEnabled, table_suspended, wal_pending_row_count, table_txn, wal_txn "
                + "FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1) return false;
        var row = rows.getFirst();
        var walRows = jdbc.queryForList("SELECT suspended, writerTxn, sequencerTxn, bufferedTxnSize "
                + "FROM wal_tables() WHERE name = ?", table);
        if (walRows.size() != 1) return false;
        var wal = walRows.getFirst();
        boolean tableCountersAgree = row.get("table_txn") == null && row.get("wal_txn") == null
                || row.get("table_txn") instanceof Number tableTxn
                && row.get("wal_txn") instanceof Number walTxn
                && tableTxn.longValue() == walTxn.longValue();
        return Boolean.TRUE.equals(row.get("walEnabled"))
                && Boolean.FALSE.equals(row.get("table_suspended"))
                && row.get("wal_pending_row_count") instanceof Number pending && pending.longValue() == 0
                && tableCountersAgree
                && Boolean.FALSE.equals(wal.get("suspended"))
                && wal.get("bufferedTxnSize") instanceof Number buffered && buffered.longValue() == 0
                && wal.get("writerTxn") instanceof Number writer
                && wal.get("sequencerTxn") instanceof Number sequencer
                && writer.longValue() == sequencer.longValue();
    }
}
