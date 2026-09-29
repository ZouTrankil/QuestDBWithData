package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.ThsMemberRow;
import com.zoutrankil.questdbwithdata.mapper.ThsMemberMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded board materialization plus streamed fingerprint of all unaffected boards. */
public final class ThsMemberBoardStorage {
    public static final int MAX_BOARD_ROWS = 10000, MAX_TOTAL_ROWS = 1000000;
    public record Identity(long id, String directory, long writerTxn) {}
    public record Snapshot(Identity identity, String board, List<ThsMemberRow> boardRows,
                           long otherRows, String otherFingerprint) {
        public Snapshot { boardRows = List.copyOf(boardRows); }
        public long totalRows() { return otherRows + boardRows.size(); }
        public String contentFingerprint() throws Exception {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(otherFingerprint.getBytes(StandardCharsets.UTF_8));
            digest.update(JobDefinitionJson.mapper().writeValueAsBytes(boardRows));
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    private record Fingerprint(long rows, String digest) {}

    private final JdbcTemplate jdbc;
    private final String table;
    private final ThsMemberMapper mapper = new ThsMemberMapper();

    public ThsMemberBoardStorage(JdbcTemplate source, String table) {
        DatasetDefinition.identifier(table);
        this.table = table;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
        this.jdbc.setQueryTimeout(120);
    }

    public Identity preflight() {
        QuestDbWriteChecks.preflight(jdbc, table, ThsMemberDataset.DEFINITION);
        var objects = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (objects.size() != 1 || !(objects.getFirst().get("id") instanceof Number id)
                || !(objects.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact physical THS member identity required");
        var wal = jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?", table);
        if (wal.size() != 1 || !(wal.getFirst().get("writerTxn") instanceof Number txn))
            throw new IllegalStateException("Exact settled THS member WAL transaction required");
        return new Identity(id.longValue(), directory, txn.longValue());
    }

    public Snapshot snapshot(String board) throws Exception {
        if (!ThsIndex.validCode(board)) throw new IllegalArgumentException("Exact THS board required");
        var identity = preflight();
        var rows = jdbc.query("SELECT ts_code,con_code,con_name,weight,in_date,out_date,is_new,"
                        + "cast(update_time AS long) AS update_micros FROM \"" + table + "\" "
                        + "WHERE ts_code=? ORDER BY con_code LIMIT " + (MAX_BOARD_ROWS + 1),
                (rs, index) -> decode(rs), board);
        if (rows.size() > MAX_BOARD_ROWS) throw new IllegalStateException("THS board exceeds bounded snapshot");
        var keys = new HashSet<ThsMember.Key>();
        for (var row : rows) if (!keys.add(mapper.fromStorage(row).key()))
            throw new IllegalStateException("Duplicate current THS member business key");
        var other = fingerprintOther(board);
        if (other.rows() + rows.size() > MAX_TOTAL_ROWS)
            throw new IllegalStateException("THS member table exceeds declared scan bound");
        if (!identity.equals(preflight())) throw new IllegalStateException("THS member identity changed while reading");
        return new Snapshot(identity, board, rows, other.rows(), other.digest());
    }

    private Fingerprint fingerprintOther(String board) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var count = new long[1];
        var prior = new String[1];
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT ts_code,con_code,con_name,weight,in_date,out_date,is_new,"
                    + "cast(update_time AS long) AS update_micros FROM \"" + table + "\" "
                    + "WHERE ts_code<>? ORDER BY ts_code,con_code,update_time");
            statement.setString(1, board);
            statement.setQueryTimeout(120);
            statement.setFetchSize(512);
            statement.setMaxRows(MAX_TOTAL_ROWS + 1);
            return statement;
        }, rs -> {
            while (rs.next()) {
                if (++count[0] > MAX_TOTAL_ROWS) throw new IllegalStateException("THS member scan exceeded bound");
                var row = decode(rs);
                mapper.fromStorage(row);
                String key = row.tsCode() + '\u0000' + row.conCode() + '\u0000' + row.updateTime();
                if (key.equals(prior[0])) throw new IllegalStateException("Duplicate physical THS member key");
                prior[0] = key;
                try { digest.update(JobDefinitionJson.mapper().writeValueAsBytes(row)); }
                catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    throw new SQLException("Cannot fingerprint THS member row", failure);
                }
                digest.update((byte) '\n');
            }
            return null;
        });
        return new Fingerprint(count[0], HexFormat.of().formatHex(digest.digest()));
    }

    private static ThsMemberRow decode(ResultSet rs) throws SQLException {
        long micros = rs.getLong("update_micros");
        if (rs.wasNull()) throw new SQLException("THS member observation required");
        var observed = Instant.ofEpochSecond(Math.floorDiv(micros, 1000000),
                Math.floorMod(micros, 1000000) * 1000);
        double rawWeight = rs.getDouble("weight");
        Double weight = rs.wasNull() ? null : rawWeight;
        return new ThsMemberRow(rs.getString("ts_code"), rs.getString("con_code"),
                rs.getString("con_name"), weight, rs.getString("in_date"), rs.getString("out_date"),
                rs.getString("is_new"), observed);
    }
}
