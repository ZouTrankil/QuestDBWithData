package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.IndexWeight;
import com.zoutrankil.data.domain.IndexWeightDataset;
import com.zoutrankil.data.domain.IndexWeightKey;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.IndexWeightMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** D021 WAL writer and exact-key/full-field reader for a frozen isolated target. */
public final class IndexWeightWritePort implements VerifiedBatchExecutor.Port<IndexWeight,IndexWeightKey> {
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_READBACK_ROWS = 20_000;
    public static final int MAX_EXISTING_ROWS_PER_SNAPSHOT = 2_500;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public record TargetRange(LocalDate min, LocalDate max) {
        public TargetRange {
            if ((min == null) != (max == null) || min != null && min.isAfter(max))
                throw new IllegalArgumentException("Invalid D021 target date range");
        }
    }
    public static final VerifiedBatchExecutor.Codec<IndexWeight,IndexWeightKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public IndexWeightKey key(IndexWeight row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(IndexWeight row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new IndexWeightMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot encode D021 row", failure); }
        }
        @Override public int estimatedTransportBytes(IndexWeight row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 192);
        }
    };
    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final IndexWeightMapper mapper = new IndexWeightMapper();
    private volatile boolean uncertainSenderStopped;

    public IndexWeightWritePort(String table, String targetId, JdbcTemplate jdbc, QuestDB questdb) {
        IsolatedTablePolicy.INDEX_WEIGHT.require(table);
        if (targetId == null || !targetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D021 isolated target identity required");
        this.table = table; this.expectedTargetId = targetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(MAX_READBACK_ROWS + 1);
        this.questdb = Objects.requireNonNull(questdb);
    }
    @Override public void preflight() {
        requireExpectedTarget(); QuestDbWriteChecks.preflight(jdbc, table, IndexWeightDataset.definition(table));
        requireExpectedTarget();
    }
    @Override public void send(List<IndexWeight> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("Nonempty D021 batch of at most 250 rows required");
        preflight(); uncertainSenderStopped = false;
        DatasetWritePreparation.prepare(IndexWeightDataset.definition(table), rows, mapper::values,
                new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        long bytes = 0;
        for (var row : rows) {
            if (row == null) throw new IllegalArgumentException("Null D021 row");
            bytes = Math.addExact(bytes, CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
            if (bytes > MAX_BATCH_BYTES) throw new IllegalArgumentException("D021 batch exceeds one MiB before send");
        }
        boolean flushAttempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var row : rows) {
                var line = sender.table(table).symbol("index_code", row.indexCode())
                        .symbol("con_code", row.conCode());
                if (row.exchange() != null) line.symbol("exchange", row.exchange());
                if (row.indexName() != null) line.stringColumn("index_name", row.indexName());
                if (row.indexNameEn() != null) line.stringColumn("index_name_en", row.indexNameEn());
                if (row.conName() != null) line.stringColumn("con_name", row.conName());
                if (row.conNameEn() != null) line.stringColumn("con_name_en", row.conNameEn());
                if (row.exchangeEn() != null) line.stringColumn("exchange_en", row.exchangeEn());
                line.doubleColumn("weight", row.weight()).timestampColumn("update_time", row.updateTime())
                        .at(new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("D021 QWP ACK is unknown; reconcile exact keys before replay");
        } catch (Exception failure) { flushAttempted = true; throw failure; }
        finally { uncertainSenderStopped = flushAttempted; }
    }
    @Override public List<IndexWeight> readback(List<IndexWeightKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique D021 keys required for readback");
        requireExpectedTarget(); var clauses = new ArrayList<String>(); var args = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(index_code = ? AND con_code = ? AND trade_date = cast(? AS TIMESTAMP))");
            args.add(key.indexCode()); args.add(key.conCode()); args.add(dateMicros(key.tradeDate()));
        }
        return jdbc.query(selectColumns() + " WHERE " + String.join(" OR ", clauses)
                + " ORDER BY trade_date,index_code,con_code LIMIT " + (keys.size() + 1),
                (rs, row) -> physical(rs), args.toArray());
    }
    /** Read an authoritative index/date snapshot to detect stale physical keys before an upsert. */
    public List<IndexWeight> readSnapshot(String indexCode, LocalDate tradeDate) {
        if (indexCode == null || !indexCode.matches("[0-9]{6}")) throw new IllegalArgumentException("Six digit D021 index required");
        Objects.requireNonNull(tradeDate); requireExpectedTarget();
        var rows = jdbc.query(selectColumns() + " WHERE index_code = ? AND trade_date = cast(? AS TIMESTAMP) "
                + "ORDER BY con_code LIMIT " + (MAX_EXISTING_ROWS_PER_SNAPSHOT + 1), (rs, row) -> physical(rs),
                indexCode, dateMicros(tradeDate));
        if (rows.size() > MAX_EXISTING_ROWS_PER_SNAPSHOT)
            throw new IllegalStateException("D021 physical index/date group exceeds bounded snapshot-read cap");
        return List.copyOf(rows);
    }
    /** Exact source comparison window for bounded historical plan slices. */
    public List<IndexWeight> readRange(String indexCode, LocalDate fromInclusive, LocalDate toInclusive) {
        if (indexCode == null || !indexCode.matches("[0-9]{6}") || fromInclusive == null || toInclusive == null
                || fromInclusive.isAfter(toInclusive)) throw new IllegalArgumentException("Increasing D021 range required");
        requireExpectedTarget();
        long toExclusive = dateMicros(toInclusive.plusDays(1));
        var rows = jdbc.query(selectColumns() + " WHERE index_code = ? AND trade_date >= cast(? AS TIMESTAMP) "
                + "AND trade_date < cast(? AS TIMESTAMP) ORDER BY trade_date,con_code LIMIT "
                + (MAX_READBACK_ROWS + 1), (rs, row) -> physical(rs), indexCode, dateMicros(fromInclusive), toExclusive);
        if (rows.size() > MAX_READBACK_ROWS) throw new IllegalStateException("D021 physical range exceeds bounded reconciliation cap");
        return List.copyOf(rows);
    }
    public TargetRange readExistingRange() {
        requireExpectedTarget();
        String sql = "SELECT cast(min(trade_date) AS long) AS min_micros, cast(max(trade_date) AS long) AS max_micros FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return D021 target date range");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return new TargetRange(null, null);
            if (!(min instanceof Number lo) || !(max instanceof Number hi)) throw new SQLException("Invalid D021 target date range");
            return new TargetRange(date(lo.longValue()), date(hi.longValue()));
        });
    }
    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }

    private IndexWeight physical(ResultSet rs) throws SQLException {
        Object tradeDate = rs.getObject("trade_date_micros"), updateTime = rs.getObject("update_time_micros");
        if (!(tradeDate instanceof Number t) || !(updateTime instanceof Number u)) throw new SQLException("D021 timestamps required");
        try {
            return new IndexWeight(new IndexWeightKey(rs.getString("index_code"), rs.getString("con_code"), date(t.longValue())),
                    rs.getString("index_name"), rs.getString("index_name_en"), rs.getString("con_name"),
                    rs.getString("con_name_en"), rs.getString("exchange"), rs.getString("exchange_en"),
                    finite(rs, "weight"), TemporalValues.epoch(u.longValue(), TemporalValues.EpochUnit.MICROS,
                            TemporalValues.Precision.MICROS));
        } catch (RuntimeException invalid) { throw new SQLException("Invalid D021 physical row", invalid); }
    }
    private static Double finite(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new SQLException("Invalid D021 number: " + column);
        return n.doubleValue();
    }
    private String selectColumns() {
        return "SELECT index_code,con_code,cast(trade_date AS long) AS trade_date_micros,index_name,index_name_en,"
                + "con_name,con_name_en,exchange,exchange_en,weight,cast(update_time AS long) AS update_time_micros FROM \"" + table + "\"";
    }
    private void requireExpectedTarget() {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory)
                || !StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory).equals(expectedTargetId))
            throw new IllegalStateException("D021 isolated physical target identity changed");
    }
    private static long dateMicros(LocalDate date) {
        return Math.multiplyExact(date.atStartOfDay().toEpochSecond(ZoneOffset.UTC), 1_000_000L);
    }
    private static LocalDate date(long micros) {
        return LocalDateTime.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                (int) Math.floorMod(micros, 1_000_000L) * 1_000, ZoneOffset.UTC).toLocalDate();
    }
}
