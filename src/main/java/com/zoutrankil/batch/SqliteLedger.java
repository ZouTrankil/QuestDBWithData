package com.zoutrankil.batch;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** SQLite-backed metadata ledger. Metadata commits happen before any external side effect. */
public final class SqliteLedger {
    public record WriteIntentRecord(String batchId,String instanceId,String target,String owner,
                                    String sourceFingerprint,String artifact,long expectedRows) {}
    public record SourceProbeClaim(boolean acquired,Map<String,Object> row) {}
    public record SourceCertificateState(String job,BusinessState state,String requestJson,String evidenceJson) {}
    public record CoverageSegment(LocalDate start,LocalDate end,long months) {}
    public record MonthlyCoverage(String dataset,String definitionVersion,String scopeIdentity,
                                  List<CoverageSegment> verifiedSegments,long verifiedMonths) {}
    public record QuarterlyCoverage(String dataset,String definitionVersion,String scopeIdentity,
                                    List<QuarterCoverageSegment> verifiedSegments,long verifiedQuarters) {}
    public record QuarterCoverageSegment(LocalDate start,LocalDate end,long quarters) {}
    public record RecoveryCandidate(RunRequest request,BusinessState state) {}
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public SqliteLedger(DataSource dataSource) {
        this.dataSource = dataSource; this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }
    public String environmentNamespace() {
        jdbc.update("INSERT INTO runtime_identity(singleton,namespace) VALUES(1,?) ON CONFLICT(singleton) DO NOTHING",
                UUID.randomUUID().toString().replace("-","").substring(0,16));
        return jdbc.queryForObject("SELECT namespace FROM runtime_identity WHERE singleton=1",String.class);
    }
    /** The immutable intent and its durable target reservation are committed together. */
    public void reserveWriteIntent(WriteIntentRecord intent) {
        transactions.executeWithoutResult(tx -> {
            jdbc.update("""
                INSERT INTO write_intent(batch_id,instance_id,target,owner,source_fingerprint,artifact,expected_rows,delivery)
                VALUES(?,?,?,?,?,?,?,'INTENT') ON CONFLICT(batch_id) DO NOTHING
                """, intent.batchId(), intent.instanceId(), intent.target(), intent.owner(), intent.sourceFingerprint(), intent.artifact(), intent.expectedRows());
            var existing=jdbc.queryForMap("SELECT * FROM write_intent WHERE batch_id=?", intent.batchId());
            if (!intent.instanceId().equals(existing.get("instance_id")) || !intent.target().equals(existing.get("target"))
                    || !intent.owner().equals(existing.get("owner")) || !intent.sourceFingerprint().equals(existing.get("source_fingerprint"))
                    || !intent.artifact().equals(existing.get("artifact")) || intent.expectedRows()!=((Number)existing.get("expected_rows")).longValue())
                throw new IllegalArgumentException("Batch identity reused for different content");
            if ("VERIFIED".equals(existing.get("delivery"))) return;
            jdbc.update("INSERT INTO target_reservation(target,batch_id) VALUES(?,?) ON CONFLICT(target) DO NOTHING", intent.target(), intent.batchId());
            String owner=jdbc.queryForObject("SELECT batch_id FROM target_reservation WHERE target=?", String.class, intent.target());
            if (!intent.batchId().equals(owner)) throw new IllegalStateException("Target has unresolved sender: "+owner);
        });
    }
    public String writeDelivery(String batchId) {
        return jdbc.queryForObject("SELECT delivery FROM write_intent WHERE batch_id=?", String.class, batchId);
    }
    /** Call outside a surrounding transaction so UNKNOWN is committed before any send or flush. */
    public int claimWriteUnknown(String batchId) {
        return jdbc.update("UPDATE write_intent SET delivery='UNKNOWN',attempt=attempt+1,updated_at=current_timestamp WHERE batch_id=? AND delivery='INTENT'", batchId);
    }
    public void acknowledgeWrite(String batchId) {
        jdbc.update("UPDATE write_intent SET delivery='ACKNOWLEDGED',updated_at=current_timestamp WHERE batch_id=? AND delivery='UNKNOWN'", batchId);
    }
    public void blockWrite(String batchId,String proofJson) {
        jdbc.update("UPDATE write_intent SET delivery='BLOCKED',proof=? WHERE batch_id=?", proofJson, batchId);
    }
    /** Verification and release cannot become visible independently. */
    public void verifyWriteAndRelease(String batchId,String target,String proofJson) {
        transactions.executeWithoutResult(tx -> {
            jdbc.update("UPDATE write_intent SET delivery='VERIFIED',proof=?,updated_at=current_timestamp WHERE batch_id=?", proofJson, batchId);
            jdbc.update("DELETE FROM target_reservation WHERE target=? AND batch_id=?", target, batchId);
        });
    }
    public SourceProbeClaim claimSourceProbe(String requestId,String fingerprint,String requestJson) {
        int acquired=jdbc.update("INSERT INTO source_probe(request_id,request_fingerprint,request_json,state) VALUES(?,?,?,'RUNNING') ON CONFLICT(request_id) DO NOTHING",requestId,fingerprint,requestJson);
        var row=sourceProbe(requestId);
        if(!fingerprint.equals(row.get("request_fingerprint")))
            throw new IllegalArgumentException("Source probe idempotency key reused for different input");
        return new SourceProbeClaim(acquired!=0,row);
    }
    public void completeSourceProbe(String requestId,String state,String resultJson) {
        jdbc.update("UPDATE source_probe SET state=?,result_json=?,completed_at=current_timestamp WHERE request_id=?",state,resultJson,requestId);
    }
    public void failSourceProbe(String requestId,String errorType) {
        jdbc.update("UPDATE source_probe SET state='FAILED',error_type=?,completed_at=current_timestamp WHERE request_id=?",errorType,requestId);
    }
    public Map<String,Object> sourceProbe(String requestId) {
        return jdbc.queryForMap("SELECT * FROM source_probe WHERE request_id=?",requestId);
    }
    public Instant businessCreatedAt(String instanceId) {
        return jdbc.queryForObject("SELECT created_at FROM business_instance WHERE instance_id=?",
                (rs,index) -> rs.getTimestamp(1).toInstant(),instanceId);
    }
    public RunRequest register(RunRequest request) { return register(request,(previous,next) -> false); }
    public RunRequest register(RunRequest request,java.util.function.BiPredicate<RunRequest,RunRequest> sameSourceScope) {
        return transactions.execute(tx -> {
            if (request.supersedes() != null) {
                RunRequest old = request(request.supersedes());
                if (!old.job().equals(request.job()) || !old.logicalDate().equals(request.logicalDate())
                        || !old.rangeStart().equals(request.rangeStart()) || !old.rangeEnd().equals(request.rangeEnd()))
                    throw new IllegalArgumentException("Revision must supersede the same business scope");
            }
            jdbc.update("""
                INSERT INTO business_instance(instance_id,job,logical_date,definition_version,revision,
                  supersedes,revision_reason,input_identity,request_json,business_state)
                VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(instance_id) DO NOTHING
                """, request.instanceId(), request.job(), request.logicalDate(), request.definitionVersion(),
                    request.revision(), request.supersedes(), request.revisionReason(), request.inputIdentity(),
                    Json.write(request), BusinessState.WAITING_UPSTREAM.name());
            RunRequest existing = Json.read(jdbc.queryForObject("SELECT request_json FROM business_instance WHERE instance_id=?",
                    String.class,request.instanceId()),RunRequest.class);
            if (!Objects.equals(existing.supersedes(), request.supersedes())
                    || !Objects.equals(existing.revisionReason(), request.revisionReason()))
                throw new IllegalArgumentException("Revision identity changed");
            if (!existing.inputIdentity().equals(request.inputIdentity())) {
                var state=state(request.instanceId());
                boolean unstarted=request.job().startsWith("source_")
                        && Set.of(BusinessState.WAITING_SOURCE,BusinessState.WAITING_UPSTREAM,BusinessState.PARTIAL,BusinessState.BLOCKED).contains(state)
                        && existing.calendarVersion().equals(request.calendarVersion()) && existing.zone().equals(request.zone())
                        && jdbc.queryForObject("SELECT count(*) FROM write_intent WHERE instance_id=?",Long.class,request.instanceId())==0L;
                if(!unstarted || !sameSourceScope.test(existing,request))
                    throw new IllegalArgumentException("Input changed after write intent or scope changed: explicit revision required");
                jdbc.update("UPDATE business_instance SET input_identity=?,request_json=?,business_state='WAITING_UPSTREAM',reason=NULL,updated_at=current_timestamp WHERE instance_id=?",
                        request.inputIdentity(),Json.write(request),request.instanceId());
                audit(request.instanceId(),"source-input-rebound-before-write",existing.inputFingerprint()+" -> "+request.inputFingerprint());
                existing=request;
            }
            jdbc.update("INSERT INTO trigger_request(request_id,instance_id,request_json) VALUES(?,?,?) "
                    + "ON CONFLICT(request_id) DO NOTHING", request.requestId(), request.instanceId(), Json.write(request));
            var prior = jdbc.queryForMap("SELECT instance_id,request_json FROM trigger_request WHERE request_id=?", request.requestId());
            RunRequest priorRequest = Json.read((String) prior.get("request_json"), RunRequest.class);
            if (!request.instanceId().equals(prior.get("instance_id")) || !request.inputIdentity().equals(priorRequest.inputIdentity()))
                throw new IllegalArgumentException("Idempotency key reused for different input");
            return existing;
        });
    }
    public RunRequest request(String id) {
        return Json.read(jdbc.queryForObject("SELECT request_json FROM business_instance WHERE instance_id=?", String.class, id), RunRequest.class);
    }
    /** Returns the sole unsuperseded post-close revision for a date; competing revision branches fail closed. */
    public Optional<RecoveryCandidate> latestPostCloseCandidate(LocalDate date) {
        var rows=jdbc.query("""
                SELECT bi.request_json,bi.business_state FROM business_instance bi
                WHERE bi.job='post_close' AND bi.logical_date=?
                  AND NOT EXISTS (SELECT 1 FROM business_instance newer WHERE newer.supersedes=bi.instance_id)
                ORDER BY bi.created_at DESC,bi.instance_id DESC LIMIT 2
                """,(rs,index)->new RecoveryCandidate(Json.read(rs.getString(1),RunRequest.class),BusinessState.valueOf(rs.getString(2))),date);
        if(rows.size()>1) throw new IllegalStateException("Ambiguous unsuperseded post-close revisions for "+date);
        return rows.stream().findFirst();
    }
    public int recoveryAttempts(String instanceId) {
        return jdbc.queryForList("SELECT attempts FROM post_close_recovery_attempt WHERE instance_id=?",Integer.class,instanceId)
                .stream().findFirst().orElse(0);
    }
    public Instant nextRecoveryAttempt(String instanceId) {
        Long next=jdbc.queryForList("SELECT next_attempt_epoch_ms FROM post_close_recovery_attempt WHERE instance_id=?",Long.class,instanceId)
                .stream().findFirst().orElse(null);
        return next==null?null:Instant.ofEpochMilli(next);
    }
    /** Atomically persists a bounded attempt before its launch side effect. Duplicate Quartz fire IDs cannot claim twice. */
    public OptionalInt claimRecoveryAttempt(String instanceId,String triggerId,Instant now,Duration minimumInterval,int maximumAttempts) {
        return claimRecoveryAttempt(instanceId,triggerId,now,minimumInterval,maximumAttempts,false);
    }
    /** An explicit operator retry skips the existing cooldown, but starts the next automatic cooldown. */
    public OptionalInt claimMainStrategyOperatorRecoveryAttempt(String instanceId,String triggerId,Instant now,
                                                               Duration minimumInterval,int maximumAttempts) {
        if(triggerId==null||!triggerId.startsWith("manual:")||!"main_strategy_daily".equals(request(instanceId).job()))
            throw new IllegalArgumentException("Explicit main-strategy operator trigger required");
        if(!Set.of(BusinessState.WAITING_SOURCE,BusinessState.WAITING_UPSTREAM,BusinessState.BLOCKED,BusinessState.PARTIAL).contains(state(instanceId)))
            return OptionalInt.empty();
        return claimRecoveryAttempt(instanceId,triggerId,now,minimumInterval,maximumAttempts,true);
    }
    private OptionalInt claimRecoveryAttempt(String instanceId,String triggerId,Instant now,Duration minimumInterval,
                                            int maximumAttempts,boolean operatorRetry) {
        if(triggerId==null||triggerId.isBlank()||triggerId.length()>256||minimumInterval.isNegative()||maximumAttempts<1)
            throw new IllegalArgumentException("Invalid recovery claim");
        return transactions.execute(tx->{
            jdbc.update("INSERT INTO post_close_recovery_attempt(instance_id) VALUES(?) ON CONFLICT(instance_id) DO NOTHING",instanceId);
            int changed=jdbc.update("""
                    UPDATE post_close_recovery_attempt SET attempts=attempts+1,last_attempt_epoch_ms=?,next_attempt_epoch_ms=?
                    WHERE instance_id=? AND attempts<? AND (?=1 OR next_attempt_epoch_ms IS NULL OR next_attempt_epoch_ms<=?)
                      AND NOT EXISTS (SELECT 1 FROM post_close_recovery_trigger t WHERE t.instance_id=? AND t.trigger_id=?)
                    """,now.toEpochMilli(),now.plus(minimumInterval).toEpochMilli(),instanceId,maximumAttempts,operatorRetry?1:0,now.toEpochMilli(),instanceId,triggerId);
            if(changed!=1)return OptionalInt.empty();
            int attempt=jdbc.queryForObject("SELECT attempts FROM post_close_recovery_attempt WHERE instance_id=?",Integer.class,instanceId);
            jdbc.update("INSERT INTO post_close_recovery_trigger(instance_id,trigger_id,attempt_no,claimed_epoch_ms) VALUES(?,?,?,?)",
                    instanceId,triggerId,attempt,now.toEpochMilli());
            audit(instanceId,"post-close-recovery-claimed",triggerId+":attempt="+attempt);
            if(operatorRetry)audit(instanceId,"main-strategy-operator-recovery-claimed",triggerId+":attempt="+attempt+":nextAutomaticAttempt="+now.plus(minimumInterval));
            return OptionalInt.of(attempt);
        });
    }
    public Map<String,Object> detail(String id) {
        var result = new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM business_instance WHERE instance_id=?", id));
        result.put("steps", jdbc.queryForList("SELECT * FROM stage_result WHERE instance_id=? ORDER BY updated_at,stage", id));
        result.put("triggers", jdbc.queryForList("SELECT * FROM trigger_request WHERE instance_id=? ORDER BY received_at", id));
        result.put("auditEvents", jdbc.queryForList("SELECT action,detail,occurred_at FROM audit_event WHERE instance_id=? ORDER BY event_id", id));
        return result;
    }
    /** Latest per-source outcome for a logical date, including failed newer revisions that must supersede older certificates. */
    public Map<String,SourceCertificateState> latestSourceCertificates(LocalDate logicalDate) {
        var rows=jdbc.query("""
            SELECT bi.job,bi.business_state,bi.request_json,sr.evidence_json
            FROM business_instance bi LEFT JOIN stage_result sr
              ON sr.instance_id=bi.instance_id AND sr.stage='Source:'||substr(bi.job,8)
            WHERE bi.job LIKE 'source_%' AND bi.logical_date=?
            ORDER BY CAST(bi.revision AS INTEGER) DESC,bi.created_at DESC,bi.instance_id DESC
            """,(rs,index)->new SourceCertificateState(rs.getString(1),BusinessState.valueOf(rs.getString(2)),rs.getString(3),rs.getString(4)),logicalDate);
        var latest=new LinkedHashMap<String,SourceCertificateState>();
        for(var row:rows) latest.putIfAbsent(row.job().substring("source_".length()),row);
        return Collections.unmodifiableMap(latest);
    }
    public List<Map<String,Object>> recent() {
        return jdbc.queryForList("SELECT instance_id,job,logical_date,business_state,reason,batch_execution_id FROM business_instance ORDER BY created_at DESC LIMIT 100");
    }
    /** Read-only operator queue; resolution still requires inspection of the physical side effect. */
    public Map<String,List<Map<String,Object>>> reconciliationQueue() {
        var writes=jdbc.queryForList("""
            SELECT wi.batch_id,wi.instance_id,bi.job,bi.logical_date,wi.target,wi.owner,wi.source_fingerprint,
              wi.artifact,wi.expected_rows,wi.delivery,wi.attempt,wi.proof,wi.updated_at,
              CASE WHEN tr.batch_id IS NULL THEN 0 ELSE 1 END AS target_reserved
            FROM write_intent wi JOIN business_instance bi ON bi.instance_id=wi.instance_id
            LEFT JOIN target_reservation tr ON tr.batch_id=wi.batch_id
            WHERE wi.delivery IN ('INTENT','UNKNOWN','ACKNOWLEDGED','BLOCKED')
            ORDER BY wi.updated_at,wi.batch_id LIMIT 500
            """);
        var children=jdbc.queryForList("""
            SELECT instance_id,stage,child_id,input_identity,state,pid,process_started_at,heartbeat_at,
              started_at,finished_at,result_json,log_path,reason,updated_at
            FROM external_execution WHERE state IN ('STARTING','RUNNING','IN_DOUBT','BLOCKED')
            ORDER BY updated_at,child_id LIMIT 500
            """);
        var orphanBatch=jdbc.queryForList("""
            SELECT e.JOB_EXECUTION_ID AS execution_id,i.JOB_NAME AS job,e.STATUS AS technical_status,
              e.CREATE_TIME AS created_at,e.START_TIME AS started_at,e.END_TIME AS ended_at,
              e.EXIT_CODE AS exit_code,e.EXIT_MESSAGE AS exit_message
            FROM BATCH_JOB_EXECUTION e JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID=e.JOB_INSTANCE_ID
            WHERE (i.JOB_NAME='post_close' OR i.JOB_NAME='pre_open_acceptance' OR i.JOB_NAME LIKE 'source_%')
              AND NOT EXISTS (
                SELECT 1 FROM BATCH_JOB_EXECUTION_PARAMS p JOIN business_instance bi
                  ON bi.instance_id=p.PARAMETER_VALUE AND bi.job=i.JOB_NAME
                WHERE p.JOB_EXECUTION_ID=e.JOB_EXECUTION_ID AND p.PARAMETER_NAME='instance'
              )
            ORDER BY e.CREATE_TIME,e.JOB_EXECUTION_ID LIMIT 500
            """);
        return Map.of("writes",writes,"externalExecutions",children,"orphanBatchExecutions",orphanBatch);
    }
    public BusinessState state(String id) {
        return BusinessState.valueOf(jdbc.queryForObject("SELECT business_state FROM business_instance WHERE instance_id=?", String.class, id));
    }
    public void ping() { jdbc.queryForObject("SELECT 1", Integer.class); }
    public Map<String,Object> metrics() {
        return Map.of("businessInstancesByState",jdbc.queryForList("SELECT business_state AS state,count(*) AS count FROM business_instance GROUP BY business_state ORDER BY business_state"),
                "sourceProbesByState",jdbc.queryForList("SELECT state,count(*) AS count FROM source_probe GROUP BY state ORDER BY state"),
                "writeIntentsByDelivery",jdbc.queryForList("SELECT delivery AS state,count(*) AS count FROM write_intent GROUP BY delivery ORDER BY delivery"),
                "verifiedMonthlyObservations",jdbc.queryForObject("SELECT count(*) FROM monthly_source_coverage",Long.class),
                "verifiedQuarterlyObservations",jdbc.queryForObject("SELECT count(*) FROM quarterly_source_coverage",Long.class));
    }
    public void state(String id, BusinessState state, Long executionId, String reason) {
        transactions.executeWithoutResult(tx -> {
            int updated = jdbc.update("UPDATE business_instance SET business_state=?,batch_execution_id=COALESCE(?,batch_execution_id),reason=?,updated_at=current_timestamp WHERE instance_id=? AND business_state NOT IN ('VERIFIED','VERIFIED_EMPTY')",
                    state.name(), executionId, reason, id);
            if (updated == 0 && state != state(id)) throw new IllegalStateException("Successful evidence is immutable; revision required");
            audit(id, "business-state", state.name()+": "+Objects.toString(reason,""));
        });
    }
    public Map<String, BusinessState> stages(String id) {
        var result = new LinkedHashMap<String, BusinessState>();
        jdbc.query("SELECT stage,business_state FROM stage_result WHERE instance_id=?", rs -> {
            result.put(rs.getString(1), BusinessState.valueOf(rs.getString(2)));
        }, id);
        return result;
    }
    public void stage(RunRequest request, String stage, BusinessState state, CompletionEvidence evidence, String reason) {
        if (evidence != null && (!evidence.matches(request, stage) || evidence.state() != state))
            throw new IllegalArgumentException("Evidence identity/state mismatch");
        if (state.ready() && evidence == null) throw new IllegalArgumentException("Ready requires certificate");
        jdbc.update("""
            INSERT INTO stage_result(instance_id,stage,business_state,evidence_json,reason) VALUES(?,?,?,?,?)
            ON CONFLICT(instance_id,stage) DO UPDATE SET business_state=excluded.business_state,
              evidence_json=excluded.evidence_json,reason=excluded.reason,updated_at=current_timestamp
            WHERE stage_result.business_state NOT IN ('VERIFIED','VERIFIED_EMPTY')
            """, request.instanceId(), stage, state.name(), evidence == null ? null : Json.write(evidence), reason);
    }
    /** Atomically publishes periodic-source readiness and its observations, so watermarks cannot outrun certificates. */
    public void completeSource(RunRequest request,StageExecutor.Result result,Long executionId) {
        if(!request.job().startsWith("source_")) throw new IllegalArgumentException("Source job required");
        String dataset=request.job().substring("source_".length()),stage="Source:"+dataset;
        boolean monthly=SourceContract.MONTHLY_AGGREGATES.contains(dataset);
        boolean quarterly=SourceContract.QUARTERLY_AGGREGATES.contains(dataset);
        if(result.state().ready() && (result.evidence()==null||!result.evidence().matches(request,stage)))
            throw new IllegalArgumentException("Ready source evidence must match the registered business instance");
        if((monthly||quarterly)&&result.state().ready()&&!request.scopeIdentity().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Periodic source watermark requires a frozen scope identity");
        transactions.executeWithoutResult(tx -> {
            stage(request,stage,result.state(),result.evidence(),result.reason());
            state(request.instanceId(),result.state(),executionId,result.reason());
            if((!monthly&&!quarterly)||!result.state().ready()) return;
            var evidence=result.evidence();
            if(monthly) {
              var month=YearMonth.from(evidence.actualStart());var end=YearMonth.from(evidence.actualEnd());
              while(!month.isAfter(end)) {
                LocalDate observation=month.atDay(1);
                jdbc.update("""
                    INSERT INTO monthly_source_coverage(dataset,definition_version,scope_identity,observation_month,
                      instance_id,input_fingerprint,evidence_artifact,available_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    ON CONFLICT(dataset,definition_version,scope_identity,observation_month) DO UPDATE SET
                      instance_id=excluded.instance_id,input_fingerprint=excluded.input_fingerprint,
                      evidence_artifact=excluded.evidence_artifact,available_at=excluded.available_at,verified_at=current_timestamp
                    """,dataset,request.definitionVersion(),request.scopeIdentity(),observation,request.instanceId(),
                        request.inputFingerprint(),evidence.artifact(),Timestamp.from(evidence.availableAt()));
                month=month.plusMonths(1);
              }
            }
            if(quarterly) {
                var quarter=YearMonth.from(evidence.actualStart());var end=YearMonth.from(evidence.actualEnd());
                while(!quarter.isAfter(end)) {
                    LocalDate observation=quarter.atEndOfMonth();
                    jdbc.update("""
                        INSERT INTO quarterly_source_coverage(dataset,definition_version,scope_identity,observation_quarter_end,
                          instance_id,input_fingerprint,evidence_artifact,available_at)
                        VALUES(?,?,?,?,?,?,?,?)
                        ON CONFLICT(dataset,definition_version,scope_identity,observation_quarter_end) DO UPDATE SET
                          instance_id=excluded.instance_id,input_fingerprint=excluded.input_fingerprint,
                          evidence_artifact=excluded.evidence_artifact,available_at=excluded.available_at,verified_at=current_timestamp
                        """,dataset,request.definitionVersion(),request.scopeIdentity(),observation,request.instanceId(),
                            request.inputFingerprint(),evidence.artifact(),Timestamp.from(evidence.availableAt()));
                    quarter=quarter.plusMonths(3);
                }
            }
        });
    }
    /** Verified continuity is reported as separate ranges; later periods never bridge an earlier gap. */
    public List<MonthlyCoverage> monthlyCoverage() {
        var rows=jdbc.queryForList("""
            SELECT dataset,definition_version,scope_identity,observation_month FROM monthly_source_coverage
            ORDER BY dataset,definition_version,scope_identity,observation_month
            """);
        record Key(String dataset,String version,String scope) {}
        var grouped=new LinkedHashMap<Key,List<LocalDate>>();
        for(var row:rows) {
            var key=new Key((String)row.get("dataset"),(String)row.get("definition_version"),(String)row.get("scope_identity"));
            grouped.computeIfAbsent(key,ignored -> new ArrayList<>()).add(LocalDate.parse(row.get("observation_month").toString()));
        }
        var result=new ArrayList<MonthlyCoverage>();
        for(var entry:grouped.entrySet()) {
            var dates=entry.getValue();var ranges=new ArrayList<CoverageSegment>();LocalDate start=dates.getFirst(),prior=start;
            for(int i=1;i<dates.size();i++) {
                LocalDate date=dates.get(i);
                if(!date.equals(prior.plusMonths(1))) { ranges.add(new CoverageSegment(start,prior,monthsBetween(start,prior)));start=date; }
                prior=date;
            }
            ranges.add(new CoverageSegment(start,prior,monthsBetween(start,prior)));
            var key=entry.getKey();result.add(new MonthlyCoverage(key.dataset(),key.version(),key.scope(),List.copyOf(ranges),dates.size()));
        }
        return List.copyOf(result);
    }
    public List<QuarterlyCoverage> quarterlyCoverage() {
        var rows=jdbc.queryForList("SELECT dataset,definition_version,scope_identity,observation_quarter_end FROM quarterly_source_coverage ORDER BY dataset,definition_version,scope_identity,observation_quarter_end");
        record Key(String dataset,String version,String scope) {}
        var grouped=new LinkedHashMap<Key,List<LocalDate>>();
        for(var row:rows) { var key=new Key((String)row.get("dataset"),(String)row.get("definition_version"),(String)row.get("scope_identity"));grouped.computeIfAbsent(key,ignored->new ArrayList<>()).add(LocalDate.parse(row.get("observation_quarter_end").toString())); }
        var result=new ArrayList<QuarterlyCoverage>();
        for(var entry:grouped.entrySet()) {
            var dates=entry.getValue();var ranges=new ArrayList<QuarterCoverageSegment>();LocalDate start=dates.getFirst(),prior=start;
            for(int i=1;i<dates.size();i++) { LocalDate date=dates.get(i);if(!date.equals(YearMonth.from(prior).plusMonths(3).atEndOfMonth())) { ranges.add(new QuarterCoverageSegment(start,prior,quartersBetween(start,prior)));start=date; }prior=date; }
            ranges.add(new QuarterCoverageSegment(start,prior,quartersBetween(start,prior)));var key=entry.getKey();
            result.add(new QuarterlyCoverage(key.dataset(),key.version(),key.scope(),List.copyOf(ranges),dates.size()));
        }
        return List.copyOf(result);
    }
    private static long monthsBetween(LocalDate start,LocalDate end) {
        return YearMonth.from(start).until(YearMonth.from(end),java.time.temporal.ChronoUnit.MONTHS)+1;
    }
    private static long quartersBetween(LocalDate start,LocalDate end) { return YearMonth.from(start).until(YearMonth.from(end),java.time.temporal.ChronoUnit.MONTHS)/3+1; }
    public void audit(String id, String action, String detail) {
        jdbc.update("INSERT INTO audit_event(instance_id,action,detail) VALUES(?,?,?)", id, action, detail);
    }
    /** Local OS file locks are released by the kernel if a process exits. */
    public Optional<Guard> tryLock(String resource) throws SQLException {
        String database;
        try(Connection connection=dataSource.getConnection()) { database=connection.getMetaData().getURL(); }
        return SqliteOwnershipLock.tryAcquire(database,resource).map(Guard::new);
    }
    public static final class Guard implements AutoCloseable {
        private final SqliteOwnershipLock.Handle handle;
        private Guard(SqliteOwnershipLock.Handle handle) { this.handle=handle; }
        public void requireAlive() throws SQLException {
            handle.requireAlive();
        }
        @Override public void close() throws SQLException { handle.close(); }
    }
}
