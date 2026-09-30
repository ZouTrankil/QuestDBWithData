package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.StockSuspendMapper;
import com.zoutrankil.data.service.StaticTargetIdentity;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** Typed WAL writer with complete-key and all-column readback; no schema creation or repair. */
public final class StockSuspendWritePort implements VerifiedBatchExecutor.Port<StockSuspend,StockSuspendKey> {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questDb;
    private final StockSuspendMapper mapper = new StockSuspendMapper();
    private final Duration acknowledgementTimeout;
    private final String expectedTargetId;
    private volatile boolean uncertainSenderStopped;

    public StockSuspendWritePort(String table, JdbcTemplate jdbc, QuestDB questDb, String expectedTargetId) {
        DatasetDefinition.identifier(table);
        this.table = table;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(251);
        this.questDb = Objects.requireNonNull(questDb);
        this.acknowledgementTimeout = Duration.ofSeconds(10);
        this.expectedTargetId = Objects.requireNonNull(expectedTargetId);
    }

    /** Creates a writer bound to a separately frozen D011 stage-table generation. */
    public StockSuspendWritePort forTarget(String stageTable,String stagePhysicalTargetId) {
        return new StockSuspendWritePort(stageTable,jdbc,questDb,stagePhysicalTargetId);
    }

    public static final VerifiedBatchExecutor.Codec<StockSuspend,StockSuspendKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public StockSuspendKey key(StockSuspend row) {
            if (row == null || row.key() == null) throw new IllegalArgumentException("Complete stock suspension key required");
            return row.key();
        }
        @Override public byte[] canonicalBytes(StockSuspend row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new StockSuspendMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot canonicalize stock suspension", failure); }
        }
        @Override public int estimatedTransportBytes(StockSuspend row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 128);
        }
    };

    @Override public void preflight() {
        QuestDbWriteChecks.preflight(jdbc, table, StockSuspendDataset.definition(table));
        requireExpectedTarget();
    }

    @Override public void send(List<StockSuspend> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > 250)
            throw new IllegalArgumentException("Nonempty bounded stock suspension batch required");
        preflight(); uncertainSenderStopped = false;
        long estimated = 0;
        for (var value : rows) {
            estimated = Math.addExact(estimated, CODEC.estimatedTransportBytes(value, CODEC.canonicalBytes(value)));
            if (estimated > 1024 * 1024) throw new IllegalArgumentException("Stock suspension batch exceeds 1 MiB");
        }
        Sender sender = questDb.borrowSender(); boolean flushAttempted = false;
        try {
            for (var value : rows) {
                var date = new TemporalValues.CalendarTimestamp(value.tradeDate());
                sender.table(table).symbol("ts_code", value.tsCode()).longColumn("is_suspended", value.isSuspended())
                        .at(date.storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); flushAttempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, acknowledgementTimeout.toMillis()))
                throw new IllegalStateException("QWP acknowledgement unknown; reconcile exact keys before replay");
        } catch (Exception failure) {
            flushAttempted = true; throw failure;
        } finally {
            try { sender.close(); uncertainSenderStopped = flushAttempted; }
            catch (Exception closeFailure) { uncertainSenderStopped = false; throw closeFailure; }
        }
    }

    @Override public List<StockSuspend> readback(List<StockSuspendKey> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        requireExpectedTarget();
        if (keys.size() > 250 || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 250 unique full stock suspension keys per readback");
        var clauses = new ArrayList<String>(); var params = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code=? AND timestamp=cast(? AS TIMESTAMP))");
            params.add(key.tsCode());
            params.add(new TemporalValues.CalendarTimestamp(key.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        String sql = "SELECT \"ts_code\",\"is_suspended\",cast(\"timestamp\" as long) AS timestamp_micros FROM \""
                + table + "\" WHERE " + String.join(" OR ", clauses) + " ORDER BY ts_code,timestamp LIMIT " + (keys.size() + 1);
        return jdbc.query(sql, this::physical, params.toArray());
    }

    private StockSuspend physical(ResultSet rs, int rowNum) throws SQLException {
        Object raw = rs.getObject("timestamp_micros");
        Object suspended = rs.getObject("is_suspended");
        if (!(raw instanceof Number micros) || !(suspended instanceof Number flag))
            throw new SQLException("Physical stock suspension date and LONG state required");
        LocalDate date = TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(), TemporalValues.EpochUnit.MICROS).date();
        try { return new StockSuspend(new StockSuspendKey(rs.getString("ts_code"), date), flag.longValue()); }
        catch (RuntimeException invalid) { throw new SQLException("Invalid physical stock suspension row", invalid); }
    }

    @Override public boolean walSettled() { requireExpectedTarget(); return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return uncertainSenderStopped; }

    private void requireExpectedTarget() {
        var identity = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (identity.size() != 1 || !(identity.getFirst().get("id") instanceof Number id)
                || !(identity.getFirst().get("directoryName") instanceof String directory)
                || !expectedTargetId.equals(StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory)))
            throw new IllegalStateException("stk_suspend physical target identity changed during write verification");
    }
}
