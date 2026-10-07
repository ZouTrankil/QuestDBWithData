package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MoneyflowThs;
import com.zoutrankil.data.domain.MoneyflowThsDataset;
import com.zoutrankil.data.domain.MoneyflowThsKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.MoneyflowThsMapper;
import com.zoutrankil.data.service.MoneyflowThsSource;
import com.zoutrankil.data.service.MoneyflowThsSyncJobOwner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** D025 bounded ILP writer. ACK is followed by full-key/all-column WAL readback. */
public final class MoneyflowThsWritePort implements VerifiedBatchExecutor.Port<MoneyflowThs, MoneyflowThsKey> {
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_DATE_ROWS = MoneyflowThsSource.API_ROW_CAP;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> NUMERIC_FIELDS = List.of("pct_change", "latest", "net_amount", "net_d5_amount",
            "buy_lg_amount", "buy_lg_amount_rate", "buy_md_amount", "buy_md_amount_rate", "buy_sm_amount", "buy_sm_amount_rate");
    public static final VerifiedBatchExecutor.Codec<MoneyflowThs, MoneyflowThsKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public MoneyflowThsKey key(MoneyflowThs row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(MoneyflowThs row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new MoneyflowThsMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot canonicalize D025 row", failure); }
        }
        @Override public int estimatedTransportBytes(MoneyflowThs row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 128);
        }
    };

    private final String table;
    private final String targetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final MoneyflowThsMapper mapper = new MoneyflowThsMapper();
    private volatile boolean uncertainSenderStopped;

    public MoneyflowThsWritePort(String table, String targetId, JdbcTemplate jdbc, QuestDB questdb) {
        MoneyflowThsDataset.requireIsolatedTable(table);
        if (targetId == null || !targetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D025 isolated physical target identity required");
        this.table = table; this.targetId = targetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(MAX_DATE_ROWS + 1);
        this.questdb = Objects.requireNonNull(questdb);
    }

    @Override public void preflight() {
        requireTarget();
        QuestDbWriteChecks.preflight(jdbc, table, MoneyflowThsDataset.definition(table));
        requireTarget();
    }

    @Override public void send(List<MoneyflowThs> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("D025 requires a nonempty batch of at most 250 rows");
        preflight(); uncertainSenderStopped = false;
        DatasetWritePreparation.prepare(MoneyflowThsDataset.definition(table), rows, mapper::values,
                new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        long estimated = 0;
        for (var row : rows) {
            byte[] bytes = CODEC.canonicalBytes(row);
            estimated = Math.addExact(estimated, CODEC.estimatedTransportBytes(row, bytes));
            if (estimated > MAX_BATCH_BYTES) throw new IllegalArgumentException("D025 batch exceeds 1 MiB before sending");
        }
        boolean attempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var row : rows) {
                var line = sender.table(table).symbol("ts_code", row.tsCode());
                if (row.name() != null) line.stringColumn("name", row.name());
                var values = mapper.values(row).asMap();
                for (String field : NUMERIC_FIELDS) {
                    Double value = (Double) values.get(field);
                    if (value != null) line.doubleColumn(field, value);
                }
                line.at(new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); attempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("D025 QWP ACK is unknown; reconcile exact keys before replay");
        } catch (Exception failure) { attempted = true; throw failure; }
        finally { uncertainSenderStopped = attempted; }
    }

    @Override public List<MoneyflowThs> readback(List<MoneyflowThsKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("D025 exact readback requires 1..250 unique keys");
        requireTarget(); var clauses = new ArrayList<String>(); var args = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");
            args.add(key.tsCode()); args.add(micros(key.tradeDate()));
        }
        var result = jdbc.query(select() + " WHERE " + String.join(" OR ", clauses)
                + " ORDER BY trade_date,ts_code LIMIT " + (keys.size() + 1), this::physical, args.toArray());
        if (result.size() > keys.size()) throw new IllegalStateException("D025 exact-key query returned duplicates/extras");
        return List.copyOf(result);
    }

    public List<MoneyflowThs> readDate(LocalDate date) {
        Objects.requireNonNull(date); requireTarget();
        var rows = jdbc.query(select() + " WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT "
                + (MAX_DATE_ROWS + 1), this::physical, micros(date));
        if (rows.size() > MAX_DATE_ROWS) throw new IllegalStateException("D025 physical date exceeds the source's 6000-row cap");
        return List.copyOf(rows);
    }

    public List<LocalDate> readExistingDates() {
        requireTarget();
        var dates = jdbc.query("SELECT DISTINCT cast(trade_date AS long) AS trade_micros FROM \"" + table
                        + "\" ORDER BY trade_micros LIMIT 10001",
                (rs, row) -> date(((Number) rs.getObject("trade_micros")).longValue()));
        if (dates.size() > 10_000) throw new IllegalStateException("D025 target date inventory exceeds 10000 dates");
        return List.copyOf(dates);
    }

    public TargetRange readTargetRange() {
        requireTarget();
        return jdbc.query("SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros FROM \""
                + table + "\"", rs -> {
                    if (!rs.next()) throw new SQLException("D025 target range unavailable");
                    Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
                    if (min == null && max == null) return new TargetRange(null, null);
                    if (!(min instanceof Number a) || !(max instanceof Number b)) throw new SQLException("D025 invalid target date range");
                    return new TargetRange(date(a.longValue()), date(b.longValue()));
                });
    }

    @Override public boolean walSettled() { requireTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }

    public record TargetRange(LocalDate min, LocalDate max) {
        public TargetRange {
            if ((min == null) != (max == null) || min != null && min.isAfter(max))
                throw new IllegalArgumentException("Invalid D025 physical target range");
        }
        public boolean empty() { return min == null; }
    }

    private MoneyflowThs physical(ResultSet rs, int row) throws SQLException {
        Object rawDate = rs.getObject("trade_micros");
        if (!(rawDate instanceof Number timestamp)) throw new SQLException("D025 trade_date is required");
        var values = new LinkedHashMap<String,Object>(); values.put("ts_code", rs.getString("ts_code"));
        values.put("trade_date", date(timestamp.longValue())); values.put("name", rs.getString("name"));
        for (String field : NUMERIC_FIELDS) {
            Object raw = rs.getObject(field);
            if (raw != null && (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())))
                throw new SQLException("D025 invalid physical numeric field " + field);
            values.put(field, raw == null ? null : ((Number) raw).doubleValue());
        }
        try { return mapper.fromValues(new com.zoutrankil.data.domain.DatasetValues(values)); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid D025 physical row", invalid); }
    }

    private String select() {
        return "SELECT ts_code,cast(trade_date AS long) AS trade_micros,name,pct_change,latest,net_amount,net_d5_amount,"
                + "buy_lg_amount,buy_lg_amount_rate,buy_md_amount,buy_md_amount_rate,buy_sm_amount,buy_sm_amount_rate FROM \""
                + table + "\"";
    }
    private void requireTarget() {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory)
                || !targetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("D025 isolated physical target identity changed");
    }
    private static long micros(LocalDate date) {
        return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);
    }
    private static LocalDate date(long micros) throws SQLException {
        try { return TemporalValues.CalendarTimestamp.fromStorageEpoch(micros, TemporalValues.EpochUnit.MICROS).date(); }
        catch (RuntimeException invalid) { throw new SQLException("D025 invalid business timestamp", invalid); }
    }
}
