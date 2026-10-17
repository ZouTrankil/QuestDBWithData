package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.application.GlobalObservationCoverage;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.factor.application.FactorObservationWritePort;
import io.questdb.client.QuestDB;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Existing SyncJobRunner/VerifiedBatchExecutor integration seam for raw observations. */
public final class GlobalObservationWriteSession
        implements FactorObservationWritePort {
    private final QuestDbGlobalObservationStore store;
    private final JdbcTemplate jdbc;
    private long preflightNanos,sendNanos,readbackNanos,walNanos;
    private long pages,attemptedDeltaRows,sendReturnedDeltaRows,sendExceptionDeltaRows,readbacks;
    /** Send-attempt counters are not unique physical writes; readback/ledger owns verification. */
    public java.util.Map<String,Long> timings() {
        return java.util.Map.of("preflightNanos",preflightNanos,"sendInclusiveNanos",sendNanos,
                "readbackNanos",readbackNanos,"walNanos",walNanos,"pages",pages,
                "attemptedDeltaRows",attemptedDeltaRows,"sendReturnedDeltaRows",sendReturnedDeltaRows,
                "sendExceptionDeltaRows",sendExceptionDeltaRows,"readbacks",readbacks);
    }
    public GlobalObservationWriteSession(JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(20);
        this.store = new QuestDbGlobalObservationStore(this.jdbc, questdb);
    }
    public static final VerifiedBatchExecutor.Codec<GlobalFactorObservation, GlobalObservationCoverage.Key> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                @Override public GlobalObservationCoverage.Key key(GlobalFactorObservation row) {
                    return GlobalObservationCoverage.key(row);
                }
                @Override public byte[] canonicalBytes(GlobalFactorObservation row) {
                    try {
                        return JobDefinitionJson.mapper().writeValueAsBytes(Arrays.asList(
                                row.signalDate().toString(), row.entityType(), row.entityId(), row.factorId(),
                                row.definitionVersion(), row.factorValue(), row.availableAt().toString(),
                                row.qualityStatus(), row.scope(), row.l2SourceLineageDigest()));
                    } catch (Exception failure) {
                        throw new IllegalArgumentException("Cannot encode raw observation", failure);
                    }
                }
                @Override public int estimatedTransportBytes(GlobalFactorObservation row, byte[] canonical) {
                    return Math.addExact(Math.multiplyExact(canonical.length,4),128);
                }
            };
    @Override public VerifiedBatchExecutor.Codec<GlobalFactorObservation, GlobalObservationCoverage.Key> codec() { return CODEC; }
    @Override public String targetIdentity() { return store.targetIdentity(); }
    @Override public void preflight() {
        long start=System.nanoTime();try { store.preflight(); } finally {preflightNanos+=System.nanoTime()-start;}
    }
    @Override public void send(List<GlobalFactorObservation> rows) throws Exception {
        long start=System.nanoTime();
        try {
        if(rows==null || rows.isEmpty() || rows.size()>2000)
            throw new IllegalArgumentException("Expected 1..2000 observations");
        // The runner owns the interval lock here. Reconcile even when no reusable ledger checkpoint exists.
        var keys=rows.stream().map(GlobalObservationCoverage::key).toList();
        var delta=GlobalObservationCoverage.compare(rows,readback(keys));
        if(!delta.writes().isEmpty()) {
            int count=delta.writes().size();attemptedDeltaRows+=count;
            try {
                store.send(delta.writes());sendReturnedDeltaRows+=count;
            } catch(Exception failure) {
                // A visibility timeout can follow a successful transport. Preserve that uncertainty
                // even when the outer executor subsequently verifies the page without resending it.
                sendExceptionDeltaRows+=count;throw failure;
            }
        }
        pages++;
        // The executor still rereads and verifies every requested row, including the unchanged ones.
        } finally {sendNanos+=System.nanoTime()-start;}
    }
    @Override public boolean uncertainSenderStopped() { return store.uncertainSenderStopped(); }
    @Override public int recoveryReadbackBatchRows() { return 2000; }
    @Override public boolean walSettled() {
        long start=System.nanoTime();try {return QuestDbWriteChecks.walSettled(jdbc,"factor_observation");}
        finally {walNanos+=System.nanoTime()-start;}
    }
    @Override public List<GlobalFactorObservation> readback(List<GlobalObservationCoverage.Key> keys) {
        if (keys.isEmpty()) return List.of();
        if (keys.size()>2000 || new HashSet<>(keys).size()!=keys.size())
            throw new IllegalArgumentException("Readback requires at most 2000 unique business keys");
        // Preserve duplicates for the shared executor to detect.
        long start=System.nanoTime();try {return store.readKeys(keys);}
        finally {readbacks++;readbackNanos+=System.nanoTime()-start;}
    }
}
