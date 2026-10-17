package com.zoutrankil.data.index.application;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.domain.DcIndexState.*;


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
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Explicit D023 plan/run/resume/status API; all targets must be D023-prefixed isolated tables. */
@Service
public class DcIndexJobService {
    public record Plan(FrozenRequest request,String targetId,String physicalTargetId,LocalDate checkpointBefore,
            LocalDate checkpointAnchor,LocalDate targetMinDate,LocalDate targetMaxDate,LocalDate requestedThrough,
            boolean bootstrap,boolean cappedByBudget){
        public Plan{Objects.requireNonNull(request);Objects.requireNonNull(targetId);Objects.requireNonNull(physicalTargetId);
            if(!targetId.equals(request.parameters().get("targetId"))||!physicalTargetId.equals(request.parameters().get("physicalTargetId")))throw new IllegalArgumentException("D023 identities must be frozen in the request");}}

    private final SyncJobRegistry jobs;private final TusharePageService pages;private final DcIndexTradingDates calendar;private final DcIndexTarget backend;private final Path ledgerPath;private final String table;
    @Autowired public DcIndexJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,ExchangeCalendarReadPort exchangeCalendar,
            DcIndexTarget backend,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String ledgerPath){this(jobs,pages,exchangeCalendar,backend,Path.of(ledgerPath));}
    public DcIndexJobService(SyncJobRegistry jobs,TusharePageService pages,ExchangeCalendarReadPort exchangeCalendar,DcIndexTarget backend,Path ledgerPath){
        this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);this.calendar=new DcIndexTradingDates(exchangeCalendar);this.backend=Objects.requireNonNull(backend);this.ledgerPath=ledgerPath.toAbsolutePath().normalize();
        String table=backend.tableName();DcIndexDataset.requireIsolatedTable(table);this.table=table;}
    public String tableName(){return table;}
    public void requireNoPendingPublication()throws Exception{DcIndexPublication.requireNoPendingPublication(ledgerPath);}

    /** Five-calendar-day max; incremental starts two days before receipt-backed coverage. */
    public Plan planDetailed(Mode requestedMode,LocalDate bootstrapFrom,LocalDate requestedThrough,LocalDate logicalDate)throws Exception{
        Objects.requireNonNull(requestedThrough,"Explicit D023 requested-through date required");Objects.requireNonNull(logicalDate,"Frozen D023 logical date required");
        if(requestedThrough.isAfter(logicalDate))throw new IllegalArgumentException("D023 range exceeds logicalDate");
        Mode mode=requestedMode==null?DcIndexSyncJobOwner.DEFINITION.defaultMode():requestedMode;if(!DcIndexSyncJobOwner.DEFINITION.supportedModes().contains(mode))throw new IllegalArgumentException("Unsupported D023 sync mode");
        String logical=targetId(),physical=physicalTargetId();DcIndexPublication.verifyCurrentTarget(ledgerPath,backend,logical,physical);
        DateRange target=readRange();requireTarget(logical,targetId());requirePhysical(physical,physicalTargetId());
        if(target.max()!=null&&target.max().isAfter(logicalDate))throw new IllegalStateException("D023 target contains a date after frozen logicalDate");
        LocalDate from=bootstrapFrom,to=requestedThrough,before=null,anchor=null;boolean bootstrap=false,capped=false;
        var parameters=new LinkedHashMap<String,Object>();parameters.put("targetId",logical);parameters.put("physicalTargetId",physical);
        if(target.min()!=null)parameters.put("targetMinBefore",target.min());if(target.max()!=null)parameters.put("targetMaxBefore",target.max());
        if(mode==Mode.INCREMENTAL){var saved=DcIndexCoverage.checkpoint(ledgerPath,logical,calendar);
            if(saved.isEmpty()){
                if(bootstrapFrom==null)throw new IllegalArgumentException("D023 needs an explicit bounded bootstrap start before a verified checkpoint exists");
                if(target.min()!=null)throw new IllegalStateException("D023 isolated target contains unverified data without a checkpoint; backfill/repair must be explicit");
                if(bootstrapFrom.isAfter(requestedThrough))throw new IllegalArgumentException("D023 bootstrap start exceeds requested through date");
                from=bootstrapFrom;anchor=bootstrapFrom;bootstrap=true;LocalDate last=from.plusDays(DcIndexSyncJobOwner.MAX_WINDOW_DAYS-1L);to=requestedThrough.isAfter(last)?last:requestedThrough;capped=to.isBefore(requestedThrough);
            }else{
                var coverage=saved.get();before=coverage.through();anchor=coverage.anchor();if(requestedThrough.isBefore(before))throw new IllegalArgumentException("D023 requested-through precedes its verified checkpoint");
                from=before.minusDays(DcIndexSyncJobOwner.REVISION_DAYS);if(from.isBefore(anchor))from=anchor;LocalDate targetThrough=target.max()!=null&&target.max().isAfter(requestedThrough)?target.max():requestedThrough;
                if(targetThrough.isAfter(logicalDate))throw new IllegalStateException("D023 existing range exceeds logicalDate");LocalDate max=from.plusDays(DcIndexSyncJobOwner.MAX_WINDOW_DAYS-1L);to=targetThrough.isAfter(max)?max:targetThrough;capped=to.isBefore(targetThrough);
                if(to.isBefore(before))throw new IllegalStateException("D023 overlap window ends before its checkpoint");parameters.put("checkpointBefore",before);
            }
            parameters.put("checkpointAnchor",anchor);
        }else{
            if(bootstrapFrom==null||bootstrapFrom.isAfter(requestedThrough))throw new IllegalArgumentException("D023 BACKFILL requires explicit ordered from/to");
            var saved=DcIndexCoverage.checkpoint(ledgerPath,logical,calendar).orElseThrow(()->new IllegalStateException("D023 BACKFILL requires an existing receipt-backed incremental chain"));
            DcIndexCoverage.validateExistingTarget(saved,calendar,backend.newWriter(physical));
            from=bootstrapFrom;to=requestedThrough;if(from.isBefore(saved.anchor())||to.isAfter(saved.through()))throw new IllegalArgumentException("D023 BACKFILL must stay within the existing receipt-backed checkpoint");
            if(ChronoUnit.DAYS.between(from,to)+1>DcIndexSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D023 BACKFILL exceeds five calendar days");
            anchor=saved.anchor();before=saved.through();parameters.put("checkpointBefore",before);parameters.put("checkpointAnchor",anchor);
        }
        if(ChronoUnit.DAYS.between(from,to)+1>DcIndexSyncJobOwner.MAX_WINDOW_DAYS||to.isAfter(logicalDate))throw new IllegalArgumentException("D023 resolved window is outside finite/logical bounds");
        var tradeDates=calendar.read(from,to);String encoded=DcIndexSyncAdapter.encodeDates(tradeDates);parameters.put("trade_dates",encoded);
        FrozenRequest frozen=jobs.prepare(DcIndexSyncJobOwner.DEFINITION.jobId(),DcIndexSyncJobOwner.DEFINITION.version(),mode,parameters,from,to,logicalDate);
        DcIndexSyncAdapter.validateRequest(frozen);return new Plan(frozen,logical,physical,before,anchor,target.min(),target.max(),requestedThrough,bootstrap,capped);
    }
    public SyncJobRunner.Result run(Plan plan)throws Exception{return run(Objects.requireNonNull(plan).request());}
    public SyncJobRunner.Result run(FrozenRequest request)throws Exception{
        DcIndexSyncAdapter.validateRequest(request);String logical=frozenLogical(request),physical=frozenPhysical(request);
        requireTarget(logical,targetId());requirePhysical(physical,physicalTargetId());DcIndexPublication.verifyCurrentTarget(ledgerPath,backend,logical,physical);
        validateBaseline(request);return execute("dc-index-"+UUID.randomUUID(),null,null,request,logical,physical);
    }
    public FrozenRequest restorePlan(String runId)throws Exception{
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var saved=ledger.getRun(runId);if(!saved.jobId().equals("data.dc_index")||saved.jobVersion()!=DcIndexSyncJobOwner.DEFINITION.version()||!saved.targetId().equals(targetId()))throw new IllegalArgumentException("Run does not belong to this D023 logical target");
        var request=FrozenRunRequest.restore(ledgerPath,runId,DcIndexSyncJobOwner.DEFINITION).request();DcIndexSyncAdapter.validateRequest(request);return request;
    }
    public SyncJobRunner.Result resume(String priorRunId)throws Exception{
        FrozenRequest request=restorePlan(priorRunId);String logical=frozenLogical(request),oldPhysical=frozenPhysical(request);requireTarget(logical,targetId());
        boolean publication=DcIndexPublication.recoverIfPresent(ledgerPath,backend,priorRunId,true);
        if(publication){DcIndexRunRecovery.finishInterrupted(backend,ledgerPath,priorRunId,true);var prior=SyncRunLedger.openReadOnly(ledgerPath).get(priorRunId);
            if(prior.state()==SyncRunState.VERIFIED||prior.state()==SyncRunState.VERIFIED_EMPTY){var node=JobDefinitionJson.mapper().readTree(prior.payloadJson());int rows=node.path("verification").path("expectedRows").asInt(node.path("returnedRows").asInt(0));return new SyncJobRunner.Result(priorRunId,prior.state(),rows,rows,null,0);}}
        if(DcIndexStageEvidence.hasStageIntent(ledgerPath.getParent().resolve("sync-evidence").resolve(priorRunId).resolve("staging")))
            throw new IllegalStateException("D023 run has an unpublished stage; call finishPublication with stopped-writer proof before resuming");
        String current=physicalTargetId();
        if(!oldPhysical.equals(current)&&!DcIndexPublication.authorizesResume(ledgerPath,priorRunId,logical,oldPhysical,current))throw new IllegalStateException("D023 resume target generation changed without verified own publication");
        if(oldPhysical.equals(current))DcIndexPublication.verifyCurrentTarget(ledgerPath,backend,logical,current);
        return execute("dc-index-"+UUID.randomUUID(),priorRunId,priorRunId,request,logical,current);
    }
    public SyncJobRunner.Result resume(FrozenRequest request,String priorRunId)throws Exception{
        if(!SyncRequestIdentity.fingerprint(request,frozenLogical(request)).equals(SyncRequestIdentity.fingerprint(restorePlan(priorRunId),frozenLogical(request))))throw new IllegalArgumentException("D023 resume requires exact saved frozen request");
        return resume(priorRunId);
    }
    public SyncRunLedger.Entry status(String runId)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(runId);}
    public List<SyncRunLedger.Entry> entries(String runId,String afterId,int limit)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).entries(runId,afterId,limit);}
    public boolean cancel(String runId)throws Exception{return new SyncRunLedger(ledgerPath).requestCancellation(runId);}
    public void finishPublication(String runId,boolean writerStopped)throws Exception{
        var journal=new ReferencePublicationJournal(ledgerPath,"dc_index");
        if(journal.findForRun(runId).isPresent())DcIndexPublication.finish(ledgerPath,backend,runId,writerStopped);
        else DcIndexRunRecovery.finishStageOnly(backend,ledgerPath,runId,writerStopped);
        DcIndexRunRecovery.finishInterrupted(backend,ledgerPath,runId,writerStopped);
    }

    private void validateBaseline(FrozenRequest request)throws Exception{
        var range=readRange();if(!Objects.equals(range.min(),request.parameters().get("targetMinBefore"))||!Objects.equals(range.max(),request.parameters().get("targetMaxBefore")))throw new IllegalStateException("D023 target date range changed after plan; preview again");
        if(!request.parameters().containsKey("checkpointAnchor")&&!request.parameters().containsKey("checkpointBefore"))return;var saved=DcIndexCoverage.checkpoint(ledgerPath,frozenLogical(request),calendar);LocalDate expected=(LocalDate)request.parameters().get("checkpointBefore");
        if(saved.isPresent()!=(expected!=null)||saved.isPresent()&&(!saved.get().through().equals(expected)||!saved.get().anchor().equals(request.parameters().get("checkpointAnchor"))))throw new IllegalStateException("D023 checkpoint changed after plan");
        var port=backend.newWriter(physicalTargetId());DcIndexCoverage.validateExistingTarget(saved.orElse(null),calendar,port);
    }
    private SyncJobRunner.Result execute(String run,String parent,String prior,FrozenRequest request,String logical,String physical)throws Exception{
        requireTarget(logical,targetId());requirePhysical(physical,physicalTargetId());var ledger=new SyncRunLedger(ledgerPath);var port=backend.newWriter(physical);
        var evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(run);var adapter=new DcIndexSyncAdapter(pages,calendar,port,evidence,ledgerPath,table,run,logical,physical,backend);
        var runner=new SyncJobRunner<DcIndex,DcIndexKey>(ledger,new DatasetIntervalLock(ledgerPath));
        return prior==null?runner.run(run,parent,logical,request,adapter,()->Thread.currentThread().isInterrupted())
                :runner.resume(run,parent,prior,logical,request,adapter,()->Thread.currentThread().isInterrupted());
    }
    public String targetId(){return backend.targetId();}
    public String physicalTargetId(){return backend.physicalTargetId();}
    private DateRange readRange(){return backend.range();}
    private static String frozenLogical(FrozenRequest r){Object v=r.parameters().get("targetId");if(!(v instanceof String s))throw new IllegalArgumentException("Frozen D023 logical target required");return s;}
    private static String frozenPhysical(FrozenRequest r){Object v=r.parameters().get("physicalTargetId");if(!(v instanceof String s))throw new IllegalArgumentException("Frozen D023 physical target required");return s;}
    private static void requireTarget(String expected,String actual){if(!Objects.equals(expected,actual))throw new IllegalStateException("D023 logical endpoint/table differs from frozen plan");}
    private static void requirePhysical(String expected,String actual){if(!Objects.equals(expected,actual))throw new IllegalStateException("D023 physical table generation differs from frozen plan");}
}
