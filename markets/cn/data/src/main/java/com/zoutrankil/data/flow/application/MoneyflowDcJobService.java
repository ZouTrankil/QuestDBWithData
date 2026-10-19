package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.flow.domain.MoneyflowDcTargetRange;

import com.zoutrankil.data.flow.port.MoneyflowDcTarget;

import com.zoutrankil.data.flow.port.MoneyflowDcWriteSession;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** Explicit D026 isolated plan/run/resume surface; shared CLI/registry wiring remains coordinator-owned. */
@Service
public final class MoneyflowDcJobService {
    public static final String ISOLATED_TABLE_PREFIX=MoneyflowDcDataset.ISOLATED_PREFIX;
    public record Plan(SyncJobDefinition.FrozenRequest request,String targetId,LocalDate checkpointBefore,LocalDate checkpointAnchor,
            MoneyflowDcTargetRange targetBefore,LocalDate requestedThrough,boolean bootstrap,boolean cappedByBudget){
        public Plan{Objects.requireNonNull(request);Objects.requireNonNull(targetId);Objects.requireNonNull(targetBefore);if(!targetId.equals(request.parameters().get("targetId")))throw new IllegalArgumentException("D026 physical target identity must be frozen");}}
    private final SyncJobRegistry jobs;private final TusharePageService pages;private final MoneyflowDcTradingDates calendar;private final MoneyflowDcTarget target;private final Path ledgerPath;private final String table;
    @Autowired
    public MoneyflowDcJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,
            ExchangeCalendarReadPort calendars,MoneyflowDcTarget target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jobs,pages,calendars,target,Path.of(ledgerPath));
    }
    public MoneyflowDcJobService(SyncJobRegistry jobs,TusharePageService pages,
            ExchangeCalendarReadPort calendars,MoneyflowDcTarget target,Path ledgerPath) {
        this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);
        this.calendar=new MoneyflowDcTradingDates(calendars);this.target=Objects.requireNonNull(target);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();
        String table=target.tableName();MoneyflowDcDataset.requireIsolatedTable(table);this.table=table;
    }
    public String tableName(){return table;}public static void requireIsolatedTableName(String table){MoneyflowDcDataset.requireIsolatedTable(table);}
    public String targetId(){return target.targetId();}

    /** First incremental start is an explicit bounded bootstrap; later plans overlap the verified checkpoint by two calendar days. */
    public Plan planDetailed(Mode requestedMode,LocalDate bootstrapFrom,LocalDate requestedThrough,LocalDate logicalDate)throws Exception{
        Objects.requireNonNull(requestedThrough,"Explicit D026 requested-through date required");Objects.requireNonNull(logicalDate,"Frozen D026 logical date required");LocalDate completedCeiling=DailySyncEndDate.resolve(null,ZonedDateTime.now(DailySyncEndDate.ZONE));if(requestedThrough.isAfter(completedCeiling))requestedThrough=completedCeiling;if(requestedThrough.isAfter(logicalDate))requestedThrough=logicalDate;
        Mode mode=requestedMode==null?MoneyflowDcSyncJobOwner.DEFINITION.defaultMode():requestedMode;if(!MoneyflowDcSyncJobOwner.DEFINITION.supportedModes().contains(mode))throw new IllegalArgumentException("Unsupported D026 sync mode");
        String target=targetId();var port=this.target.newWriter(target);port.preflight();var targetBefore=port.readTargetRange();if(targetBefore.rows()>Integer.MAX_VALUE)throw new IllegalStateException("D026 isolated target exceeds frozen baseline count range");
        LocalDate from=bootstrapFrom,to=requestedThrough,before=null,anchor=null;boolean bootstrap=false,capped=false;
        var parameters=new LinkedHashMap<String,Object>();parameters.put("targetId",target);parameters.put("targetRowsBefore",Math.toIntExact(targetBefore.rows()));if(targetBefore.min()!=null)parameters.put("targetMinBefore",targetBefore.min());if(targetBefore.max()!=null)parameters.put("targetMaxBefore",targetBefore.max());
        if(mode==Mode.INCREMENTAL){if(targetBefore.max()!=null&&targetBefore.max().isAfter(logicalDate))throw new IllegalStateException("D026 incremental target contains rows after frozen logicalDate");var saved=MoneyflowDcCoverage.checkpoint(ledgerPath,target,calendar);if(saved.isEmpty()){
                if(bootstrapFrom==null)throw new IllegalArgumentException("D026 needs an explicit bounded bootstrap start before a receipt-backed checkpoint exists");if(bootstrapFrom.isBefore(MoneyflowDcSource.EARLIEST_SOURCE_DATE))throw new IllegalArgumentException("D026 bootstrap cannot precede the documented 2023-09-11 source start");if(!targetBefore.empty())throw new IllegalStateException("D026 target contains unverified rows without an incremental checkpoint");if(bootstrapFrom.isAfter(requestedThrough))throw new IllegalArgumentException("D026 bootstrap starts after requested-through");
                from=bootstrapFrom;anchor=bootstrapFrom;bootstrap=true;LocalDate max=from.plusDays(MoneyflowDcSyncJobOwner.MAX_WINDOW_DAYS-1);to=requestedThrough.isAfter(max)?max:requestedThrough;capped=to.isBefore(requestedThrough);
            }else{if(bootstrapFrom!=null)throw new IllegalArgumentException("D026 bootstrap start applies only before the first verified checkpoint");var checkpoint=saved.get();MoneyflowDcCoverage.validateExistingTarget(checkpoint,targetBefore,port);before=checkpoint.through();anchor=checkpoint.anchor();if(requestedThrough.isBefore(before))throw new IllegalArgumentException("D026 requested-through precedes verified checkpoint");from=before.minusDays(MoneyflowDcSyncJobOwner.REVISION_DAYS);if(from.isBefore(anchor))from=anchor;LocalDate max=from.plusDays(MoneyflowDcSyncJobOwner.MAX_WINDOW_DAYS-1);to=requestedThrough.isAfter(max)?max:requestedThrough;capped=to.isBefore(requestedThrough);if(to.isBefore(before))throw new IllegalStateException("D026 overlap window ends before its checkpoint");parameters.put("checkpointBefore",before);}
            parameters.put("checkpointAnchor",anchor);
        }else{if(bootstrapFrom==null||bootstrapFrom.isAfter(requestedThrough))throw new IllegalArgumentException("D026 BACKFILL requires explicit ordered --from/--to");from=bootstrapFrom;to=requestedThrough;if(ChronoUnit.DAYS.between(from,to)+1>MoneyflowDcSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D026 BACKFILL exceeds five calendar days");var saved=MoneyflowDcCoverage.checkpoint(ledgerPath,target,calendar);if(saved.isEmpty())throw new IllegalStateException("D026 BACKFILL requires an existing receipt-backed incremental checkpoint");var checkpoint=saved.get();MoneyflowDcCoverage.validateExistingTarget(checkpoint,targetBefore,port);anchor=checkpoint.anchor();if(from.isBefore(anchor)||to.isAfter(checkpoint.through()))throw new IllegalArgumentException("D026 BACKFILL on an initialized target must stay inside verified checkpoint coverage");parameters.put("checkpointAnchor",anchor);}
        if(ChronoUnit.DAYS.between(from,to)+1>MoneyflowDcSyncJobOwner.MAX_WINDOW_DAYS||to.isAfter(logicalDate))throw new IllegalArgumentException("D026 resolved window outside its finite/logical bound");var dates=calendar.read(from,to);if(dates.isEmpty())throw new IllegalArgumentException("D026 request window must include at least one open SSE/SZSE trading date");parameters.put("trade_dates",MoneyflowDcCoverage.encodeDates(dates));
        var request=jobs.prepare(MoneyflowDcSyncJobOwner.DEFINITION.jobId(),MoneyflowDcSyncJobOwner.DEFINITION.version(),mode,parameters,from,to,logicalDate);MoneyflowDcSyncAdapter.validateRequest(request);return new Plan(request,target,before,anchor,targetBefore,requestedThrough,bootstrap,capped);
    }
    public SyncJobRunner.Result run(Plan plan)throws Exception{return run(Objects.requireNonNull(plan).request());}
    public SyncJobRunner.Result run(SyncJobDefinition.FrozenRequest request)throws Exception{MoneyflowDcSyncAdapter.validateRequest(request);String target=frozenTarget(request);requireTarget(target,targetId());validateBaseline(request);return execute("moneyflow_dc-"+UUID.randomUUID(),null,null,target,request);}
    public SyncJobRunner.Result resume(String priorRunId)throws Exception{var request=restorePlan(priorRunId);String target=frozenTarget(request);requireTarget(target,targetId());return execute("moneyflow_dc-"+UUID.randomUUID(),priorRunId,priorRunId,target,request);}
    public SyncJobRunner.Result resume(SyncJobDefinition.FrozenRequest request,String priorRunId)throws Exception{if(!SyncRequestIdentity.fingerprint(request,frozenTarget(request)).equals(SyncRequestIdentity.fingerprint(restorePlan(priorRunId),frozenTarget(request))))throw new IllegalArgumentException("D026 resume requires the exact frozen request");return resume(priorRunId);}
    public SyncJobDefinition.FrozenRequest restorePlan(String runId)throws Exception{var ledger=SyncRunLedger.openReadOnly(ledgerPath);var saved=ledger.getRun(runId);if(!"data.moneyflow_dc".equals(saved.jobId())||saved.jobVersion()!=MoneyflowDcSyncJobOwner.DEFINITION.version()||!saved.targetId().equals(targetId()))throw new IllegalArgumentException("Run does not belong to the current D026 isolated target");var request=FrozenRunRequest.restore(ledgerPath,runId,MoneyflowDcSyncJobOwner.DEFINITION).request();MoneyflowDcSyncAdapter.validateRequest(request);return request;}
    public SyncRunLedger.Entry status(String runId)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(runId);}public List<SyncRunLedger.Entry> entries(String runId,String afterId,int limit)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).entries(runId,afterId,limit);}public boolean cancel(String runId)throws Exception{return new SyncRunLedger(ledgerPath).requestCancellation(runId);}
    public SyncJobRunner.Result runAsGroupChild(String runId,String parentRunId,String priorRunId,String expectedTarget,SyncJobDefinition.FrozenRequest request)throws Exception{requireTarget(expectedTarget,targetId());if(!expectedTarget.equals(frozenTarget(request)))throw new IllegalStateException("D026 group child target differs from frozen request");return execute(runId,parentRunId,priorRunId,expectedTarget,request);}
    private void validateBaseline(SyncJobDefinition.FrozenRequest request)throws Exception{var port=this.target.newWriter(targetId());var actual=port.readTargetRange();var p=request.parameters();if(actual.rows()!=((Integer)p.get("targetRowsBefore"))||!Objects.equals(actual.min(),p.get("targetMinBefore"))||!Objects.equals(actual.max(),p.get("targetMaxBefore")))throw new IllegalStateException("D026 target range/count changed after plan; replan before starting a new run");
        var saved=MoneyflowDcCoverage.checkpoint(ledgerPath,frozenTarget(request),calendar);if(request.mode()==Mode.INCREMENTAL){LocalDate expected=(LocalDate)p.get("checkpointBefore"),anchor=(LocalDate)p.get("checkpointAnchor");if(saved.isPresent()!=(expected!=null)||saved.isPresent()&&(!saved.get().through().equals(expected)||!saved.get().anchor().equals(anchor)))throw new IllegalStateException("D026 receipt-backed checkpoint changed after plan");}else{if(saved.isEmpty()||request.from().isBefore(saved.get().anchor())||request.to().isAfter(saved.get().through()))throw new IllegalStateException("D026 BACKFILL must remain inside existing incremental receipt coverage");}MoneyflowDcCoverage.validateExistingTarget(saved.orElse(null),actual,port);}
    private SyncJobRunner.Result execute(String runId,String parentId,String priorRunId,String target,SyncJobDefinition.FrozenRequest request)throws Exception{requireTarget(target,targetId());var ledger=new SyncRunLedger(ledgerPath);var adapter=new MoneyflowDcSyncAdapter(pages,calendar,this.target.newWriter(target),ledgerPath.getParent().resolve("sync-evidence").resolve(runId),target);var runner=new SyncJobRunner<MoneyflowDc,MoneyflowDcKey>(ledger,new DatasetIntervalLock(ledgerPath));return priorRunId==null?runner.run(runId,parentId,target,request,adapter,()->Thread.currentThread().isInterrupted()):runner.resume(runId,parentId,priorRunId,target,request,adapter,()->Thread.currentThread().isInterrupted());}
    private static String frozenTarget(SyncJobDefinition.FrozenRequest request){Object raw=request.parameters().get("targetId");if(!(raw instanceof String s))throw new IllegalArgumentException("Frozen D026 target identity required");return s;}
    private static void requireTarget(String expected,String actual){if(!Objects.equals(expected,actual))throw new IllegalStateException("D026 QuestDB target identity changed");}
}
