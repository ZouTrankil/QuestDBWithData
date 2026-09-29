package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Explicit plan/run/resume/status/cancel entry for one bounded stock-factor job. */
@Service
public final class StockFactorJobService {
    public record Plan(FrozenRequest request,String targetId,LocalDate checkpointBefore,LocalDate checkpointAnchor,
                       LocalDate targetMinDate,LocalDate targetMaxDate,LocalDate requestedThrough,
                       boolean bootstrap,boolean cappedByBudget) {
        public Plan {
            Objects.requireNonNull(request);Objects.requireNonNull(targetId);
            if(!targetId.equals(request.parameters().get("targetId")))
                throw new IllegalArgumentException("stk_factor plan target must be frozen in its request");
        }
    }
    record BootstrapWindow(LocalDate from,LocalDate to,boolean cappedByBudget) {}
    private record TargetRange(LocalDate min,LocalDate max) {
        private TargetRange {
            if((min==null)!=(max==null) || min!=null && min.isAfter(max))
                throw new IllegalStateException("Invalid stk_factor target date range");
        }
    }
    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final String table;
    private final Path ledgerPath;
    @Autowired
    public StockFactorJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.stk-factor-table:stk_factor}") String table) {
        this(jobs,pages,jdbc,questdb,Path.of(ledgerPath),table);
    }
    public StockFactorJobService(SyncJobRegistry jobs,TusharePageService pages,JdbcTemplate jdbc,
            QuestDB questdb,Path ledgerPath,String table) {
        this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);
        this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();DatasetDefinition.identifier(table);this.table=table;
    }
    public String tableName() { return table; }
    /**
     * Resolves a default incremental plan from verified contiguous history. With no such history,
     * the caller must supply an explicit bounded bootstrap start. The upper bound also catches up
     * any newer date already present in the scoped physical target.
     */
    public Plan planDetailed(Mode requestedMode,LocalDate bootstrapFrom,LocalDate requestedThrough,
                             LocalDate logicalDate,String tsCode) {
        Objects.requireNonNull(requestedThrough,"stk_factor upper date required");
        Objects.requireNonNull(logicalDate,"stk_factor logical date required");
        var definition=StockFactorSyncJobOwner.DEFINITION;
        Mode mode=requestedMode==null?definition.defaultMode():requestedMode;
        if(!definition.supportedModes().contains(mode)) throw new IllegalArgumentException("Unsupported stk_factor mode");
        if(requestedThrough.isAfter(logicalDate)) throw new IllegalArgumentException("stk_factor window cannot exceed its frozen logical date");
        if(tsCode!=null && !tsCode.matches("[0-9]{6}\\.(?:SZ|SH|BJ)"))
            throw new IllegalArgumentException("Exact Tushare A-share code required");

        String target=targetId();
        TargetRange physical=readTargetRange(tsCode);
        requireSameTarget(target,targetId());
        LocalDate from=bootstrapFrom,to=requestedThrough,checkpointBefore=null,anchor=null;
        boolean bootstrap=false,capped=false;
        var parameters=new LinkedHashMap<String,Object>();
        parameters.put("targetId",target);
        if(tsCode!=null) parameters.put("tsCode",tsCode);
        if(physical.min()!=null) parameters.put("targetMinBefore",physical.min());
        if(physical.max()!=null) parameters.put("targetMaxBefore",physical.max());

        if(mode==Mode.INCREMENTAL) {
            if(physical.max()!=null && physical.max().isAfter(logicalDate))
                throw new IllegalStateException("stk_factor target contains a date after logicalDate; advance logicalDate before planning");
            Optional<StockFactorCoverage.Coverage> saved;
            try { saved=StockFactorCoverage.checkpoint(ledgerPath,target,tsCode); }
            catch(Exception failure) { throw new IllegalStateException("Cannot establish stk_factor verified checkpoint",failure); }
            if(saved.isEmpty()) {
                if(bootstrapFrom==null)
                    throw new IllegalArgumentException("Explicit bounded bootstrap start required before a verified stk_factor checkpoint exists");
                if(bootstrapFrom.isAfter(requestedThrough)) throw new IllegalArgumentException("Bootstrap start is after requested end");
                var window=resolveBootstrapWindow(bootstrapFrom,requestedThrough,physical.max(),logicalDate,
                        definition.budget().maxWindowDays());
                from=window.from();to=window.to();capped=window.cappedByBudget();
                anchor=bootstrapFrom;bootstrap=true;
            } else {
                if(bootstrapFrom!=null)
                    throw new IllegalArgumentException("Bootstrap start applies only before a verified checkpoint; use bounded BACKFILL for another range");
                var coverage=saved.get();checkpointBefore=coverage.through();anchor=coverage.anchor();
                if(requestedThrough.isBefore(checkpointBefore))
                    throw new IllegalArgumentException("Requested incremental end is before its verified checkpoint");
                parameters.put("checkpointBefore",checkpointBefore);
                from=checkpointBefore.minusDays(definition.revisionDays());
                LocalDate targetThrough=physical.max()!=null && physical.max().isAfter(requestedThrough)
                        ?physical.max():requestedThrough;
                if(targetThrough.isAfter(logicalDate))
                    throw new IllegalStateException("Existing stk_factor date exceeds requested logicalDate");
                LocalDate lastAllowed=from.plusDays(definition.budget().maxWindowDays()-1L);
                to=targetThrough.isAfter(lastAllowed)?lastAllowed:targetThrough;
                capped=to.isBefore(targetThrough);
                if(to.isBefore(checkpointBefore))
                    throw new IllegalStateException("Resolved stk_factor overlap window ends before its checkpoint");
            }
            parameters.put("checkpointAnchor",anchor);
        } else {
            if(bootstrapFrom==null || bootstrapFrom.isAfter(requestedThrough))
                throw new IllegalArgumentException("Explicit bounded from/to required for stk_factor backfill");
        }
        long days=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;
        if(days<1 || days>definition.budget().maxWindowDays())
            throw new IllegalArgumentException("Resolved stk_factor window exceeds the five-calendar-day budget");
        FrozenRequest request=jobs.prepare(definition.jobId(),definition.version(),mode,parameters,from,to,logicalDate);
        StockFactorSyncAdapter.validateRequest(request);
        return new Plan(request,target,checkpointBefore,anchor,physical.min(),physical.max(),requestedThrough,bootstrap,capped);
    }

    /** Compatibility entry returning the frozen request while preserving the same default planner. */
    public FrozenRequest plan(Mode mode,LocalDate from,LocalDate to,LocalDate logicalDate,String tsCode) {
        return planDetailed(mode,from,to,logicalDate,tsCode).request();
    }

    private TargetRange readTargetRange(String tsCode) {
        String sql="SELECT cast(min(trade_date) AS long) AS min_micros,"
                +"cast(max(trade_date) AS long) AS max_micros FROM \""+table+"\""
                +(tsCode==null?"":" WHERE ts_code=?");
        return jdbc.query(sql,rs->{
            if(!rs.next()) throw new IllegalStateException("QuestDB did not return the stk_factor date range aggregate");
            Object min=rs.getObject("min_micros"),max=rs.getObject("max_micros");
            if(min==null && max==null) return new TargetRange(null,null);
            if(!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB stk_factor range aggregate is not a timestamp epoch");
            var minDate=com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(),com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            var maxDate=com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(),com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            return new TargetRange(minDate,maxDate);
        },tsCode==null?new Object[]{}:new Object[]{tsCode});
    }
    public String targetId() {
        var port=new StockFactorWritePort(table,jdbc,questdb);port.preflight();
        var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact stk_factor QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory);
    }
    public SyncJobRunner.Result run(FrozenRequest request) throws Exception {
        validate(request);String run="stk-factor-"+UUID.randomUUID();
        return execute(run,null,null,request,frozenTargetId(request));
    }
    public SyncJobRunner.Result resume(FrozenRequest request,String priorRun) throws Exception {
        validate(request);var ledger=SyncRunLedger.openReadOnly(ledgerPath);var prior=ledger.getRun(priorRun);
        if(!Set.of(SyncRunState.FAILED,SyncRunState.CANCELLED,SyncRunState.PARTIAL).contains(ledger.get(priorRun).state())
                || !prior.jobId().equals(request.definition().jobId()) || prior.jobVersion()!=request.definition().version()
                || !SyncRequestIdentity.fingerprint(prior.frozenJson(),prior.targetId()).equals(SyncRequestIdentity.fingerprint(request,prior.targetId()))
                || new DatasetIntervalLock(ledgerPath).findOwned(priorRun,
                        new DatasetIntervalLock.Scope("stk_factor",request.from(),request.to()))!=null)
            throw new IllegalStateException("Reconcile uncertain stk_factor writes before resuming");
        String expectedTarget=frozenTargetId(request);
        requireSameTarget(expectedTarget,prior.targetId());
        requireSameTarget(expectedTarget,targetId());
        String run="stk-factor-"+UUID.randomUUID();return execute(run,priorRun,priorRun,request,expectedTarget);
    }
    public SyncJobRunner.Result resume(String priorRun) throws Exception {
        var saved=FrozenRunRequest.restore(ledgerPath,priorRun,StockFactorSyncJobOwner.DEFINITION);
        return resume(saved.request(),priorRun);
    }

    public SyncRunLedger.Entry status(String runId) throws Exception { return SyncRunLedger.openReadOnly(ledgerPath).get(runId); }
    public List<SyncRunLedger.Entry> entries(String runId,String afterId,int limit) throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).entries(runId,afterId,limit);
    }
    public boolean cancel(String runId) throws Exception { return new SyncRunLedger(ledgerPath).requestCancellation(runId); }
    private SyncJobRunner.Result execute(String run,String parent,String prior,FrozenRequest request,String expectedTarget) throws Exception {
        String actualTarget=targetId();requireSameTarget(expectedTarget,actualTarget);
        var ledger=new SyncRunLedger(ledgerPath);
        var port=new StockFactorWritePort(table,jdbc,questdb,1024*1024,java.time.Duration.ofSeconds(10),expectedTarget);
        var adapter=new StockFactorSyncAdapter(pages,port,
                ledgerPath.getParent().resolve("sync-evidence").resolve(run));
        var runner=new SyncJobRunner<StockFactor,StockFactorKey>(ledger,new DatasetIntervalLock(ledgerPath));
        return prior==null?runner.run(run,parent,expectedTarget,request,adapter,()->Thread.currentThread().isInterrupted())
                :runner.resume(run,parent,prior,expectedTarget,request,adapter,()->Thread.currentThread().isInterrupted());
    }
    private static void validate(FrozenRequest request) {
        StockFactorSyncAdapter.validateRequest(request);
    }
    private static String frozenTargetId(FrozenRequest request) {
        Object value=request.parameters().get("targetId");
        if(!(value instanceof String target)) throw new IllegalArgumentException("Frozen stk_factor target identity required");
        return target;
    }
    static void requireSameTarget(String expected,String actual) {
        if(expected==null || actual==null || !expected.equals(actual))
            throw new IllegalStateException("stk_factor physical target identity differs from frozen plan");
    }
    static BootstrapWindow resolveBootstrapWindow(LocalDate bootstrapFrom,LocalDate requestedThrough,
                                                   LocalDate physicalMax,LocalDate logicalDate,int maxWindowDays) {
        Objects.requireNonNull(bootstrapFrom);Objects.requireNonNull(requestedThrough);Objects.requireNonNull(logicalDate);
        if(maxWindowDays<1 || bootstrapFrom.isAfter(requestedThrough))
            throw new IllegalArgumentException("Explicit valid stk_factor bootstrap window required");
        if(requestedThrough.isAfter(logicalDate) || physicalMax!=null && physicalMax.isAfter(logicalDate))
            throw new IllegalStateException("stk_factor bootstrap ceiling exceeds logicalDate");
        LocalDate targetThrough=physicalMax!=null && physicalMax.isAfter(requestedThrough)?physicalMax:requestedThrough;
        LocalDate lastAllowed=bootstrapFrom.plusDays(maxWindowDays-1L);
        LocalDate to=targetThrough.isAfter(lastAllowed)?lastAllowed:targetThrough;
        return new BootstrapWindow(bootstrapFrom,to,to.isBefore(targetThrough));
    }
}
