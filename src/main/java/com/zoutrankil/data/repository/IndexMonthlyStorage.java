package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.IndexMonthly;
import com.zoutrankil.data.domain.IndexMonthlyDataset;
import com.zoutrankil.data.domain.IndexMonthlyKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Bounded full-table snapshots used by D022 non-DEDUP staged publication. */
public final class IndexMonthlyStorage {
    public static final int MAX_ROWS = 250_000;
    public record Identity(long id, String directory, long writerTxn) {
        public Identity { Objects.requireNonNull(directory); }
    }
    public record Snapshot(Identity identity, List<IndexMonthly> rows, String fingerprint, int bytes) {
        public Snapshot {
            Objects.requireNonNull(identity); rows = List.copyOf(rows);
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || bytes < 0)
                throw new IllegalArgumentException("Bounded D022 snapshot fingerprint required");
        }
    }

    private final JdbcTemplate jdbc;
    private final String table;

    public IndexMonthlyStorage(JdbcTemplate source, String table) {
        DatasetDefinition.identifier(table); this.table = table;
        jdbc = new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
        jdbc.setQueryTimeout(120); jdbc.setMaxRows(MAX_ROWS + 1);
    }

    public Identity preflight() {
        QuestDbWriteChecks.preflight(jdbc, table, IndexMonthlyDataset.isolatedWriteDefinition(table));
        var objects = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        var wal = jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?", table);
        if (objects.size() != 1 || wal.size() != 1
                || !(objects.getFirst().get("id") instanceof Number id)
                || !(objects.getFirst().get("directoryName") instanceof String directory)
                || !(wal.getFirst().get("writerTxn") instanceof Number txn))
            throw new IllegalStateException("Exact D022 WAL table identity required");
        return new Identity(id.longValue(), directory, txn.longValue());
    }

    public Snapshot snapshot() throws Exception { return snapshot("", List.of()); }
    public Snapshot outside(String code, LocalDate from, LocalDate to) throws Exception {
        requireWindow(code, from, to);
        return snapshot("NOT (ts_code=? AND trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP))",
                List.of(code, micros(from), micros(to.plusDays(1))));
    }
    public Snapshot window(String code, LocalDate from, LocalDate to) throws Exception {
        requireWindow(code, from, to);
        return snapshot("ts_code=? AND trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP)",
                List.of(code, micros(from), micros(to.plusDays(1))));
    }

    private Snapshot snapshot(String predicate, List<?> args) throws Exception {
        Identity before = preflight();
        String sql = "SELECT ts_code,cast(trade_date AS long) AS trade_date_micros,close,open,high,low,pre_close,change,pct_chg,vol,amount,layer,bucket,cast(update_time AS long) AS update_time_micros FROM \""
                + table + "\"" + (predicate.isBlank() ? "" : " WHERE " + predicate) + " ORDER BY trade_date,ts_code";
        var rows = jdbc.query(sql + " LIMIT " + (MAX_ROWS + 1), (rs, n) -> {
            Object trade = rs.getObject("trade_date_micros"), observed = rs.getObject("update_time_micros");
            if (!(trade instanceof Number t) || !(observed instanceof Number o))
                throw new SQLException("D022 physical timestamps are required");
            try {
                LocalDate day = TemporalValues.CalendarTimestamp.fromStorageEpoch(t.longValue(), TemporalValues.EpochUnit.MICROS).date();
                Instant update = TemporalValues.epoch(o.longValue(), TemporalValues.EpochUnit.MICROS, TemporalValues.Precision.MICROS);
                return new IndexMonthly(new IndexMonthlyKey(rs.getString("ts_code"), day), finite(rs,"close"),finite(rs,"open"),
                        finite(rs,"high"),finite(rs,"low"),finite(rs,"pre_close"),finite(rs,"change"),finite(rs,"pct_chg"),
                        finite(rs,"vol"),finite(rs,"amount"),rs.getString("layer"),rs.getString("bucket"),update);
            } catch (RuntimeException invalid) { throw new SQLException("Invalid D022 physical row", invalid); }
        }, args.toArray());
        if (rows.size() > MAX_ROWS) throw new IllegalStateException("D022 full-table snapshot exceeds 250000-row bound");
        var keys = new HashSet<IndexMonthlyKey>();
        for (var row : rows) if (!keys.add(row.key())) throw new IllegalStateException("D022 target contains duplicate natural keys");
        var digest = MessageDigest.getInstance("SHA-256"); int bytes = 0;
        for (var row : rows) {
            byte[] encoded = IndexMonthlyWritePort.CODEC.canonicalBytes(row);
            digest.update(encoded); digest.update((byte)'\n'); bytes = Math.addExact(bytes, encoded.length + 1);
            if (bytes > 256 * 1024 * 1024) throw new IllegalStateException("D022 snapshot exceeds 256 MiB canonical bound");
        }
        Identity after = preflight();
        if (before.id() != after.id() || !before.directory().equals(after.directory()) || before.writerTxn()!=after.writerTxn())
            throw new IllegalStateException("D022 table generation changed during snapshot");
        return new Snapshot(after, rows, HexFormat.of().formatHex(digest.digest()), bytes);
    }

    public static String physicalTargetId(JdbcTemplate jdbc, String table, Identity identity) {
        return StaticTargetIdentity.identify(jdbc, table, identity.id(), identity.directory());
    }
    public static boolean sameContent(Snapshot left, Snapshot right) {
        return left != null && right != null && left.rows().size() == right.rows().size()
                && left.fingerprint().equals(right.fingerprint());
    }
    private static Double finite(java.sql.ResultSet rs, String name) throws SQLException {
        Object value = rs.getObject(name); if (value == null) return null;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new SQLException("Invalid D022 metric " + name);
        return number.doubleValue();
    }
    private static long micros(LocalDate day) { return new TemporalValues.CalendarTimestamp(day).storageEpoch(TemporalValues.EpochUnit.MICROS); }
    private static void requireWindow(String code, LocalDate from, LocalDate to) {
        if (!com.zoutrankil.data.domain.policy.IndexMonthlyUniverse.validProviderCode(code)
                || from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("Bounded D022 code/month window required");
    }
}
