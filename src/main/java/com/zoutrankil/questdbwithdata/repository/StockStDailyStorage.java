package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.StockStDaily;
import com.zoutrankil.questdbwithdata.domain.StockStDailyDataset;
import com.zoutrankil.questdbwithdata.domain.StockStDailyKey;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import com.zoutrankil.questdbwithdata.service.StockStDailySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

/** Bounded streaming identity and content snapshots for D012 whole-window publication. */
public final class StockStDailyStorage {
    public static final int MAX_ROWS = 5_000_000;
    public record Identity(long id, String directory, long writerTxn) {}
    public record Content(long rows, String fingerprint) {
        public Content {
            if (rows < 0 || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Complete D012 content fingerprint required");
        }
    }
    public record Snapshot(Identity identity, Content content) {
        public Snapshot { Objects.requireNonNull(identity); Objects.requireNonNull(content); }
    }

    private final JdbcTemplate jdbc;
    private final String table;

    public StockStDailyStorage(JdbcTemplate source, String table) {
        DatasetDefinition.identifier(table);
        this.table = table;
        jdbc = new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
        jdbc.setQueryTimeout(120);
        jdbc.setMaxRows(MAX_ROWS + 1);
    }

    public Identity preflight() {
        QuestDbWriteChecks.preflight(jdbc, table, StockStDailyDataset.definition(table));
        var objects = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        var wal = jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?", table);
        if (objects.size() != 1 || wal.size() != 1
                || !(objects.getFirst().get("id") instanceof Number id)
                || !(objects.getFirst().get("directoryName") instanceof String directory)
                || !(wal.getFirst().get("writerTxn") instanceof Number txn))
            throw new IllegalStateException("Exact D012 table and settled WAL identity required");
        return new Identity(id.longValue(), directory, txn.longValue());
    }

    public Snapshot snapshot() throws Exception { return snapshot("", null, null); }

    public Content window(LocalDate fromInclusive, LocalDate toInclusive) throws Exception {
        requireWindow(fromInclusive, toInclusive);
        return snapshot("timestamp>=cast(? AS TIMESTAMP) AND timestamp<cast(? AS TIMESTAMP)",
                micros(fromInclusive), micros(toInclusive.plusDays(1))).content();
    }

    public Content outside(LocalDate fromInclusive, LocalDate toInclusive) throws Exception {
        requireWindow(fromInclusive, toInclusive);
        return snapshot("(timestamp<cast(? AS TIMESTAMP) OR timestamp>=cast(? AS TIMESTAMP))",
                micros(fromInclusive), micros(toInclusive.plusDays(1))).content();
    }

    private Snapshot snapshot(String predicate, Long first, Long second) throws Exception {
        Identity before = preflight();
        var digest = MessageDigest.getInstance("SHA-256");
        long[] count = {0};
        StockStDailyKey[] previous = {null};
        String sql = "SELECT ts_code,is_st,cast(timestamp AS long) AS timestamp_micros FROM \"" + table + "\""
                + (predicate.isBlank() ? "" : " WHERE " + predicate) + " ORDER BY timestamp,ts_code";
        PreparedStatementCreator creator = connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(120);
            statement.setFetchSize(512);
            statement.setMaxRows(MAX_ROWS + 1);
            if (first != null) statement.setLong(1, first);
            if (second != null) statement.setLong(2, second);
            return statement;
        };
        jdbc.query(creator, (ResultSet rows) -> {
            while (rows.next()) {
                if (++count[0] > MAX_ROWS) throw new SQLException("D012 physical row scan exceeds five-million bound");
                long timestamp = rows.getLong("timestamp_micros");
                if (rows.wasNull()) throw new SQLException("D012 physical timestamp is null");
                LocalDate date = TemporalValues.CalendarTimestamp.fromStorageEpoch(timestamp,
                        TemporalValues.EpochUnit.MICROS).date();
                int isSt = rows.getInt("is_st");
                if (rows.wasNull() || isSt != 1) throw new SQLException("D012 stores positive is_st=1 rows only");
                var value = new StockStDaily(rows.getString("ts_code"), date, isSt);
                if (previous[0] != null && compare(previous[0], value.key()) >= 0)
                    throw new SQLException("D012 physical rows contain duplicate or unordered business keys");
                previous[0] = value.key();
                try {
                    digest.update(StockStDailyWritePort.CODEC.canonicalBytes(value));
                    digest.update((byte) '\n');
                } catch (RuntimeException invalid) {
                    throw new SQLException("Invalid D012 physical row", invalid);
                }
            }
            return null;
        });
        Identity after = preflight();
        if (!before.equals(after)) throw new IllegalStateException("D012 physical identity changed during bounded scan");
        return new Snapshot(before, new Content(count[0], HexFormat.of().formatHex(digest.digest())));
    }

    public static boolean sameContent(Content left, Content right) {
        return left != null && right != null && left.rows() == right.rows()
                && left.fingerprint().equals(right.fingerprint());
    }

    public static String targetId(JdbcTemplate jdbc, String table, Identity identity) {
        return StaticTargetIdentity.identify(jdbc, table, identity.id(), identity.directory());
    }

    private static int compare(StockStDailyKey left, StockStDailyKey right) {
        int date = left.timestamp().compareTo(right.timestamp());
        return date != 0 ? date : left.tsCode().compareTo(right.tsCode());
    }
    private static long micros(LocalDate day) {
        return new TemporalValues.CalendarTimestamp(day).storageEpoch(TemporalValues.EpochUnit.MICROS);
    }
    private static void requireWindow(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        if (from.isAfter(to) || from.isBefore(StockStDailySource.HISTORY_ANCHOR))
            throw new IllegalArgumentException("Bounded D012 source window required");
    }
}
