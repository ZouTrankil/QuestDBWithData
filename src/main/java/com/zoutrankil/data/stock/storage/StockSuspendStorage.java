package com.zoutrankil.data.stock.storage;
import com.zoutrankil.data.stock.domain.StockSuspendState;
import com.zoutrankil.data.stock.domain.StockSuspendState.*;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/** Bounded full-table snapshot used only by D011's scoped, journaled replacement. */
public final class StockSuspendStorage {
    public static final int MAX_ROWS = 100_000;
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final String table;

    public StockSuspendStorage(JdbcTemplate source, String table) {
        DatasetDefinition.identifier(table); this.table = table;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(source).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(MAX_ROWS + 1);
    }

    public Identity preflight() {
        QuestDbWriteChecks.preflight(jdbc, table, StockSuspendDataset.definition(table));
        var values = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (values.size() != 1 || !(values.getFirst().get("id") instanceof Number id)
                || !(values.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact physical stk_suspend target identity required");
        return new Identity(id.longValue(), directory);
    }

    private long writerTxn() {
        var values = jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?", table);
        if (values.size() != 1 || !(values.getFirst().get("writerTxn") instanceof Number txn))
            throw new IllegalStateException("stk_suspend WAL transaction identity unavailable");
        return txn.longValue();
    }

    public Snapshot snapshot() throws Exception {
        Identity before = preflight(); long txn = writerTxn();
        var rows = jdbc.query("SELECT \"ts_code\",\"is_suspended\",cast(\"timestamp\" AS long) AS timestamp_micros FROM \""
                + table + "\" ORDER BY \"timestamp\",\"ts_code\" LIMIT " + (MAX_ROWS + 1),
                (rs, rowNum) -> physical(rs, rowNum));
        if (rows.size() > MAX_ROWS) throw new IllegalStateException("stk_suspend full snapshot exceeds 100,000 rows");
        byte[] canonical = canonical(rows);
        if (canonical.length > MAX_BYTES) throw new IllegalStateException("stk_suspend snapshot exceeds 32 MiB");
        Identity after = preflight();
        if (!before.equals(after) || txn != writerTxn()) throw new IllegalStateException("stk_suspend changed while snapshot was read");
        return new Snapshot(before, rows, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)), canonical.length);
    }

    public static List<StockSuspend> outside(List<StockSuspend> rows, LocalDate fromInclusive, LocalDate toExclusive) {
        return rows.stream().filter(row -> row.tradeDate().isBefore(fromInclusive) || !row.tradeDate().isBefore(toExclusive)).toList();
    }

    public static List<StockSuspend> sortedUnique(Collection<StockSuspend> rows) {
        if (rows == null || rows.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Null stk_suspend row");
        var sorted = rows.stream().sorted(Comparator.comparing(StockSuspend::tradeDate).thenComparing(StockSuspend::tsCode)).toList();
        var keys = new HashSet<StockSuspendKey>();
        for (var row : sorted) if (!keys.add(row.key())) throw new IllegalArgumentException("Duplicate stk_suspend full key");
        return sorted;
    }

    public static byte[] canonical(List<StockSuspend> rows) throws Exception {
        var digestInput = new java.io.ByteArrayOutputStream();
        try (var data = new java.io.DataOutputStream(digestInput)) {
            for (var row : sortedUnique(rows)) {
                byte[] value = StockSuspendWritePort.CODEC.canonicalBytes(row);
                data.writeInt(value.length); data.write(value);
                if (digestInput.size() > MAX_BYTES) throw new IllegalStateException("stk_suspend snapshot exceeds 32 MiB");
            }
        }
        return digestInput.toByteArray();
    }

    private static StockSuspend physical(ResultSet rs, int rowNum) throws SQLException {
        Object raw = rs.getObject("timestamp_micros"), value = rs.getObject("is_suspended");
        if (!(raw instanceof Number micros) || !(value instanceof Number flag))
            throw new SQLException("Physical stk_suspend timestamp and LONG value required");
        try {
            LocalDate date = TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(), TemporalValues.EpochUnit.MICROS).date();
            return new StockSuspend(new StockSuspendKey(rs.getString("ts_code"), date), flag.longValue());
        } catch (RuntimeException invalid) { throw new SQLException("Invalid physical stk_suspend row", invalid); }
    }

    public static String physicalTargetId(JdbcTemplate jdbc, String table, Identity identity) {
        return StaticTargetIdentity.identify(jdbc, table, identity.id(), identity.directory());
    }
}
