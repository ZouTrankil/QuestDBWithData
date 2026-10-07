package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** Explicit D024 bounded plan/run/resume surface; shared CLI/registry wiring remains coordinator-owned. */
@Service
public final class MoneyflowJobService {
    public static final String ISOLATED_TABLE_PREFIX=MoneyflowDataset.ISOLATED_PREFIX;
    public record Plan(SyncJobDefinition.FrozenRequest request,String targetId,LocalDate checkpointBefore,LocalDate checkpointAnchor,
            MoneyflowWritePort.TargetRange targetBefore,LocalDate requestedThrough,boolean bootstrap,boolean cappedByBudget){
        public Plan{Objects.requireNonNull(request);Objects.requireNonNull(targetId);Objects.requireNonNull(targetBefore);if(!targetId.equals(request.parameters().get("targetId")))throw new IllegalArgumentException("D024 physical target identity must be frozen");}}
    private final SyncJobRegistry jobs;private final TusharePageService pages;private final MoneyflowTradingDates calendar;private final JdbcTemplate jdbc;private final QuestDB questdb;private final Path ledgerPath;private final String table;
    @Autowired public MoneyflowJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,ExchangeCalendarReadRepository exchangeCalendar,JdbcTemplate jdbc,
            @Lazy QuestDB questdb,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String ledgerPath,
            @Value("${app.sync.moneyflow-table:java_d024_moneyflow_acceptance}")String table){this(jobs,pages,exchangeCalendar,jdbc,questdb,Path.of(ledgerPath),table);}
    public MoneyflowJobService(SyncJobRegistry jobs,TusharePageService pages,ExchangeCalendarReadRepository exchangeCalendar,JdbcTemplate jdbc,QuestDB questdb,Path ledgerPath,String table){
        this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);this.calendar=new MoneyflowTradingDates(exchangeCalendar);this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);this.ledgerPath=ledgerPath.toAbsolutePath().normalize();requireExecutionTableName(table);this.table=table;}
    public String tableName(){return table;}public static void requireIsolatedTableName(String table){MoneyflowDataset.requireIsolatedTable(table);}
    /** Permits the exact preexisting formal table without relaxing isolated-table DDL. */
    public static void requireExecutionTableName(String table){if(!"moneyflow".equals(table))requireIsolatedTableName(table);}
    private boolean formalTarget(){return "moneyflow".equals(table);}
    private void requireExecutionMode(Mode mode){if(formalTarget()&&mode!=Mode.BACKFILL)throw new IllegalArgumentException("Formal moneyflow requires explicit bounded BACKFILL; old rows do not establish incremental coverage");}
    public String targetId(){requireExecutionTableName(table);var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String directory))throw new IllegalStateException("Exact D024 target identity required");return StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory);}

    /** First incremental start is an explicit bounded bootstrap; later plans overlap the verified checkpoint by two calendar days. */
    public Plan planDetailed(Mode requestedMode,LocalDate bootstrapFrom,LocalDate requestedThrough,LocalDate logicalDate)throws Exception{
        Objects.requireNonNull(requestedThrough,"Explicit D024 requested-through date required");Objects.requireNonNull(logicalDate,"Frozen D024 logical date required");var now=ZonedDateTime.now(DailySyncEndDate.ZONE);requestedThrough=DailySyncEndDate.resolve(requestedThrough,now);if(requestedThrough.isAfter(logicalDate))requestedThrough=logicalDate;
        Mode mode=requestedMode==null?MoneyflowSyncJobOwner.DEFINITION.defaultMode():requestedMode;if(!MoneyflowSyncJobOwner.DEFINITION.supportedModes().contains(mode))throw new IllegalArgumentException("Unsupported D024 sync mode");
        requireExecutionMode(mode);
        String target=targetId();var port=new MoneyflowWritePort(table,target,jdbc,questdb);port.preflight();var targetBefore=port.readTargetRange();if(targetBefore.rows()>Integer.MAX_VALUE)throw new IllegalStateException("D024 isolated target exceeds frozen baseline count range");
        LocalDate from=bootstrapFrom,to=requestedThrough,before=null,anchor=null;boolean bootstrap=false,capped=false;
        var parameters=new LinkedHashMap<String,Object>();parameters.put("targetId",target);parameters.put("targetRowsBefore",Math.toIntExact(targetBefore.rows()));if(targetBefore.min()!=null)parameters.put("targetMinBefore",targetBefore.min());if(targetBefore.max()!=null)parameters.put("targetMaxBefore",targetBefore.max());
        if(mode==Mode.INCREMENTAL){if(targetBefore.max()!=null&&targetBefore.max().isAfter(logicalDate))throw new IllegalStateException("D024 incremental target contains rows after frozen logicalDate");var saved=MoneyflowCoverage.checkpoint(ledgerPath,target,calendar);if(saved.isEmpty()){
                if(bootstrapFrom==null)throw new IllegalArgumentException("D024 needs an explicit bounded bootstrap start before a receipt-backed checkpoint exists");if(!targetBefore.empty())throw new IllegalStateException("D024 target contains unverified rows without an incremental checkpoint");if(bootstrapFrom.isAfter(requestedThrough))throw new IllegalArgumentException("D024 bootstrap starts after requested-through");
                from=bootstrapFrom;anchor=bootstrapFrom;bootstrap=true;LocalDate max=from.plusDays(MoneyflowSyncJobOwner.MAX_WINDOW_DAYS-1);to=requestedThrough.isAfter(max)?max:requestedThrough;capped=to.isBefore(requestedThrough);
            }else{if(bootstrapFrom!=null)throw new IllegalArgumentException("D024 bootstrap start applies only before the first verified checkpoint");var checkpoint=saved.get();MoneyflowCoverage.validateExistingTarget(checkpoint,targetBefore,port);before=checkpoint.through();anchor=checkpoint.anchor();if(requestedThrough.isBefore(before))throw new IllegalArgumentException("D024 requested-through precedes verified checkpoint");from=before.minusDays(MoneyflowSyncJobOwner.REVISION_DAYS);if(from.isBefore(anchor))from=anchor;LocalDate max=from.plusDays(MoneyflowSyncJobOwner.MAX_WINDOW_DAYS-1);to=requestedThrough.isAfter(max)?max:requestedThrough;capped=to.isBefore(requestedThrough);if(to.isBefore(before))throw new IllegalStateException("D024 overlap window ends before its checkpoint");parameters.put("checkpointBefore",before);}
            parameters.put("checkpointAnchor",anchor);
        }else{
            if(bootstrapFrom==null||bootstrapFrom.isAfter(requestedThrough))throw new IllegalArgumentException("D024 BACKFILL requires explicit ordered --from/--to");
            from=bootstrapFrom;to=requestedThrough;
            if(ChronoUnit.DAYS.between(from,to)+1>MoneyflowSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D024 BACKFILL exceeds five calendar days");
            // A formal-table recovery certifies only these daily source receipts, never the unrelated old rows.
            if(!formalTarget()){
                var saved=MoneyflowCoverage.checkpoint(ledgerPath,target,calendar);
                if(saved.isEmpty())throw new IllegalStateException("D024 isolated BACKFILL requires an existing receipt-backed incremental checkpoint");
                var checkpoint=saved.get();MoneyflowCoverage.validateExistingTarget(checkpoint,targetBefore,port);anchor=checkpoint.anchor();
                if(from.isBefore(anchor)||to.isAfter(checkpoint.through()))throw new IllegalArgumentException("D024 isolated BACKFILL must stay inside verified checkpoint coverage");
                parameters.put("checkpointAnchor",anchor);
            }
        }
        if(ChronoUnit.DAYS.between(from,to)+1>MoneyflowSyncJobOwner.MAX_WINDOW_DAYS||to.isAfter(logicalDate))throw new IllegalArgumentException("D024 resolved window outside its finite/logical bound");var dates=calendar.read(from,to);if(dates.isEmpty())throw new IllegalArgumentException("D024 request window must include at least one open SSE/SZSE trading date");parameters.put("trade_dates",MoneyflowCoverage.encodeDates(dates));
        var request=jobs.prepare(MoneyflowSyncJobOwner.DEFINITION.jobId(),MoneyflowSyncJobOwner.DEFINITION.version(),mode,parameters,from,to,logicalDate);MoneyflowSyncAdapter.validateRequest(request);return new Plan(request,target,before,anchor,targetBefore,requestedThrough,bootstrap,capped);
    }
    public SyncJobRunner.Result run(Plan plan)throws Exception{return run(Objects.requireNonNull(plan).request());}
    public SyncJobRunner.Result run(SyncJobDefinition.FrozenRequest request)throws Exception{MoneyflowSyncAdapter.validateRequest(request);String target=frozenTarget(request);requireTarget(target,targetId());validateBaseline(request);return execute("moneyflow-"+UUID.randomUUID(),null,null,target,request);}
    public SyncJobRunner.Result resume(String priorRunId)throws Exception{var request=restorePlan(priorRunId);String target=frozenTarget(request);requireTarget(target,targetId());return execute("moneyflow-"+UUID.randomUUID(),priorRunId,priorRunId,target,request);}
    public SyncJobRunner.Result resume(SyncJobDefinition.FrozenRequest request,String priorRunId)throws Exception{if(!SyncRequestIdentity.fingerprint(request,frozenTarget(request)).equals(SyncRequestIdentity.fingerprint(restorePlan(priorRunId),frozenTarget(request))))throw new IllegalArgumentException("D024 resume requires the exact frozen request");return resume(priorRunId);}
    public SyncJobDefinition.FrozenRequest restorePlan(String runId)throws Exception{var ledger=SyncRunLedger.openReadOnly(ledgerPath);var saved=ledger.getRun(runId);if(!"data.moneyflow".equals(saved.jobId())||saved.jobVersion()!=MoneyflowSyncJobOwner.DEFINITION.version()||!saved.targetId().equals(targetId()))throw new IllegalArgumentException("Run does not belong to the current D024 isolated target");var request=FrozenRunRequest.restore(ledgerPath,runId,MoneyflowSyncJobOwner.DEFINITION).request();MoneyflowSyncAdapter.validateRequest(request);return request;}
    public SyncRunLedger.Entry status(String runId)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(runId);}public List<SyncRunLedger.Entry> entries(String runId,String afterId,int limit)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).entries(runId,afterId,limit);}public boolean cancel(String runId)throws Exception{return new SyncRunLedger(ledgerPath).requestCancellation(runId);}
    public SyncJobRunner.Result runAsGroupChild(String runId,String parentRunId,String priorRunId,String expectedTarget,SyncJobDefinition.FrozenRequest request)throws Exception{requireTarget(expectedTarget,targetId());if(!expectedTarget.equals(frozenTarget(request)))throw new IllegalStateException("D024 group child target differs from frozen request");return execute(runId,parentRunId,priorRunId,expectedTarget,request);}
    private void validateBaseline(SyncJobDefinition.FrozenRequest request)throws Exception{var port=new MoneyflowWritePort(table,targetId(),jdbc,questdb);var actual=port.readTargetRange();var p=request.parameters();if(actual.rows()!=((Integer)p.get("targetRowsBefore"))||!Objects.equals(actual.min(),p.get("targetMinBefore"))||!Objects.equals(actual.max(),p.get("targetMaxBefore")))throw new IllegalStateException("D024 target range/count changed after plan; replan before starting a new run");
        if(formalTarget()){requireFormalRecoveryRequest(request);return;}
        var saved=MoneyflowCoverage.checkpoint(ledgerPath,frozenTarget(request),calendar);if(request.mode()==Mode.INCREMENTAL){LocalDate expected=(LocalDate)p.get("checkpointBefore"),anchor=(LocalDate)p.get("checkpointAnchor");if(saved.isPresent()!=(expected!=null)||saved.isPresent()&&(!saved.get().through().equals(expected)||!saved.get().anchor().equals(anchor)))throw new IllegalStateException("D024 receipt-backed checkpoint changed after plan");}else if(saved.isEmpty()||request.from().isBefore(saved.get().anchor())||request.to().isAfter(saved.get().through()))throw new IllegalStateException("D024 BACKFILL must remain inside existing incremental receipt coverage");MoneyflowCoverage.validateExistingTarget(saved.orElse(null),actual,port);}
    private void requireFormalRecoveryRequest(SyncJobDefinition.FrozenRequest request){
        requireExecutionMode(request.mode());
        if(formalTarget()&&(request.parameters().containsKey("checkpointAnchor")||request.parameters().containsKey("checkpointBefore")))
            throw new IllegalArgumentException("Formal moneyflow bounded recovery cannot claim incremental checkpoint coverage");
    }
    private SyncJobRunner.Result execute(String runId,String parentId,String priorRunId,String target,SyncJobDefinition.FrozenRequest request)throws Exception{requireFormalRecoveryRequest(request);requireTarget(target,targetId());var ledger=new SyncRunLedger(ledgerPath);var adapter=new MoneyflowSyncAdapter(pages,calendar,new MoneyflowWritePort(table,target,jdbc,questdb),ledgerPath.getParent().resolve("sync-evidence").resolve(runId),target);var runner=new SyncJobRunner<Moneyflow,MoneyflowKey>(ledger,new DatasetIntervalLock(ledgerPath));return priorRunId==null?runner.run(runId,parentId,target,request,adapter,()->Thread.currentThread().isInterrupted()):runner.resume(runId,parentId,priorRunId,target,request,adapter,()->Thread.currentThread().isInterrupted());}
    private static String frozenTarget(SyncJobDefinition.FrozenRequest request){Object raw=request.parameters().get("targetId");if(!(raw instanceof String s))throw new IllegalArgumentException("Frozen D024 target identity required");return s;}
    private static void requireTarget(String expected,String actual){if(!Objects.equals(expected,actual))throw new IllegalStateException("D024 QuestDB target identity changed");}
}
