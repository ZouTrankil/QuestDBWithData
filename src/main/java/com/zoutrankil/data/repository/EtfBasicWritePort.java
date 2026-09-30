package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.EtfBasicMapper;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

/** Bounded typed QWP writer and full physical-row readback for the isolated D013 target. */
public final class EtfBasicWritePort implements VerifiedBatchExecutor.Port<EtfBasic, EtfBasicKey> {
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<EtfBasic, EtfBasicKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public EtfBasicKey key(EtfBasic row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(EtfBasic row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new EtfBasicMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot encode etf_basic row", failure); }
        }
        @Override public int estimatedTransportBytes(EtfBasic row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 256);
        }
    };

    private final String table;
    private final String expectedTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final EtfBasicMapper mapper = new EtfBasicMapper();
    private volatile boolean uncertainSenderStopped;

    public EtfBasicWritePort(String table, String expectedTargetId, JdbcTemplate jdbc, QuestDB questdb) {
        EtfBasicDataset.requireIsolatedTable(table);
        if (expectedTargetId == null || !expectedTargetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen etf_basic target identity required");
        this.table = table; this.expectedTargetId = expectedTargetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(MAX_BATCH_ROWS + 1);
        this.questdb = Objects.requireNonNull(questdb);
    }

    @Override public void preflight() {
        EtfBasicDataset.requireIsolatedTable(table);
        QuestDbWriteChecks.preflight(jdbc, table, EtfBasicDataset.definition(table));
        requireExpectedTarget();
    }

    @Override public void send(List<EtfBasic> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("Nonempty etf_basic batch of at most 250 rows required");
        preflight(); uncertainSenderStopped = false;
        var prepared = DatasetWritePreparation.prepare(EtfBasicDataset.definition(table), rows, mapper::values,
                new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        if (prepared.empty()) throw new IllegalArgumentException("Empty etf_basic write must not reach QWP");
        long transportBytes = 0;
        for (var row : rows) transportBytes = Math.addExact(transportBytes,
                CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
        if (transportBytes > MAX_BATCH_BYTES) throw new IllegalArgumentException("etf_basic transport byte budget exceeded before send");

        Sender sender = questdb.borrowSender(); boolean flushAttempted = false;
        try {
            for (var value : rows) {
                var line = sender.table(table).symbol("ts_code", value.tsCode());
                if (value.status() != null) line.symbol("status", value.status());
                if (value.market() != null) line.symbol("market", value.market());
                if (value.name() != null) line.stringColumn("name", value.name());
                if (value.management() != null) line.stringColumn("management", value.management());
                if (value.custodian() != null) line.stringColumn("custodian", value.custodian());
                if (value.fundType() != null) line.stringColumn("fund_type", value.fundType());
                if (value.foundDate() != null) line.stringColumn("found_date", EtfBasicMapper.basicDate(value.foundDate()));
                if (value.dueDate() != null) line.stringColumn("due_date", EtfBasicMapper.basicDate(value.dueDate()));
                if (value.listDate() != null) line.stringColumn("list_date", EtfBasicMapper.basicDate(value.listDate()));
                if (value.issueDate() != null) line.stringColumn("issue_date", EtfBasicMapper.basicDate(value.issueDate()));
                if (value.delistDate() != null) line.stringColumn("delist_date", EtfBasicMapper.basicDate(value.delistDate()));
                if (value.issueAmount() != null) line.doubleColumn("issue_amount", value.issueAmount());
                if (value.managementFee() != null) line.doubleColumn("m_fee", value.managementFee());
                if (value.custodianFee() != null) line.doubleColumn("c_fee", value.custodianFee());
                if (value.durationYear() != null) line.doubleColumn("duration_year", value.durationYear());
                if (value.parValue() != null) line.doubleColumn("p_value", value.parValue());
                if (value.minimumAmount() != null) line.doubleColumn("min_amount", value.minimumAmount());
                if (value.expectedReturn() != null) line.doubleColumn("exp_return", value.expectedReturn());
                if (value.benchmark() != null) line.stringColumn("benchmark", value.benchmark());
                if (value.investType() != null) line.stringColumn("invest_type", value.investType());
                if (value.type() != null) line.stringColumn("type", value.type());
                if (value.trustee() != null) line.stringColumn("trustee", value.trustee());
                if (value.purchaseStartDate() != null) line.stringColumn("purc_startdate", EtfBasicMapper.basicDate(value.purchaseStartDate()));
                if (value.redemptionStartDate() != null) line.stringColumn("redm_startdate", EtfBasicMapper.basicDate(value.redemptionStartDate()));
                line.timestampColumn("update_time", value.updateTime()).at(EtfBasicDataset.technicalTimestamp());
            }
            long sequence = sender.flushAndGetSequence(); flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("etf_basic QWP acknowledgement unknown; reconcile exact keys before replay");
        } catch (Exception failure) {
            flushAttempted = true; throw failure;
        } finally {
            try { sender.close(); uncertainSenderStopped = flushAttempted; }
            catch (Exception closeFailure) { uncertainSenderStopped = false; throw closeFailure; }
        }
    }

    @Override public List<EtfBasic> readback(List<EtfBasicKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_ROWS
                || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique complete etf_basic keys required for readback");
        requireExpectedTarget();
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code=? AND timestamp=cast(? AS TIMESTAMP))");
            parameters.add(key.tsCode());
            parameters.add(TemporalValues.epochValue(key.timestamp(), TemporalValues.EpochUnit.MICROS));
        }
        var projection = new ArrayList<String>();
        for (var column : EtfBasicDataset.DEFINITION.columns()) {
            String name = column.storageName();
            projection.add(Set.of("timestamp", "update_time").contains(name)
                    ? "cast(\"" + name + "\" as long) AS \"" + name + "_micros\""
                    : "\"" + name + "\"");
        }
        String sql = "SELECT " + String.join(",", projection) + " FROM \"" + table + "\" WHERE "
                + String.join(" OR ", clauses) + " ORDER BY ts_code,timestamp LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, (rs, index) -> physical(rs), parameters.toArray());
    }

    private EtfBasic physical(ResultSet rs) throws SQLException {
        Object timestamp = rs.getObject("timestamp_micros");
        Object update = rs.getObject("update_time_micros");
        if (!(timestamp instanceof Number timestampMicros) || !(update instanceof Number updateMicros))
            throw new SQLException("etf_basic physical timestamps required");
        var values = new LinkedHashMap<String, Object>();
        for (var column : EtfBasicDataset.DEFINITION.columns()) {
            String name = column.logicalName();
            if (name.equals("timestamp")) {
                values.put(name, new TemporalValues.TechnicalTimestamp(
                        TemporalValues.epoch(timestampMicros.longValue(), TemporalValues.EpochUnit.MICROS,
                                TemporalValues.Precision.MICROS), column.temporal().meaning()));
            } else if (name.equals("update_time")) {
                values.put(name, TemporalValues.epoch(updateMicros.longValue(), TemporalValues.EpochUnit.MICROS,
                        TemporalValues.Precision.MICROS));
            } else if (column.temporal() != null) {
                String raw = rs.getString(column.storageName());
                values.put(name, raw == null || column.legacyNullSentinels().contains(raw) ? null
                        : TemporalValues.businessDate(raw, TemporalValues.DateFormat.BASIC));
            } else if (column.storageType() == DatasetDefinition.StorageType.DOUBLE) {
                Object raw = rs.getObject(column.storageName());
                if (raw != null && !(raw instanceof Number)) throw new SQLException("Non-numeric etf_basic value: " + name);
                Double number = raw == null ? null : ((Number) raw).doubleValue();
                values.put(name, number == null || !Double.isFinite(number) ? null : number);
            } else values.put(name, rs.getString(column.storageName()));
        }
        try { return mapper.fromValues(new DatasetValues(values)); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid physical etf_basic row", invalid); }
    }

    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }
    public String tableName() { return table; }

    private void requireExpectedTarget() {
        EtfBasicDataset.requireIsolatedTable(table);
        var identity = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (identity.size() != 1 || !(identity.getFirst().get("id") instanceof Number id)
                || !(identity.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(com.zoutrankil.data.service.StaticTargetIdentity
                        .identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("etf_basic physical target identity changed during write verification");
    }

}
