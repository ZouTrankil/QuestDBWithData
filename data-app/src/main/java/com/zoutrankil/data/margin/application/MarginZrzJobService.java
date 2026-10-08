package com.zoutrankil.data.margin.application;

import com.zoutrankil.data.margin.port.MarginZrzTarget;

import com.zoutrankil.data.margin.domain.MarginZrzState;
import com.zoutrankil.data.margin.domain.MarginZrzState.*;
import com.zoutrankil.data.margin.domain.MarginZrzRows;
import com.zoutrankil.data.margin.port.MarginZrzTables;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.MarginZrz;
import com.zoutrankil.data.domain.MarginZrzDataset;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** Bounded manual historical plan/run/resume facade for the disabled D031 retired-source owner. */
@Service
public class MarginZrzJobService {
    /** Last business date present in the audited retired-source snapshot; historical requests may not drift forward. */
    public static final LocalDate LAST_AUDITED_SOURCE_DATE=LocalDate.of(2025,7,25);
    public record Plan(SyncJobDefinition.FrozenRequest request,String targetId,String physicalTargetId,
            LocalDate checkpointAnchor,LocalDate checkpointBefore,MarginZrzState.TargetRange physicalRange,
            boolean bootstrap,boolean cappedByBudget) {
        public Plan { Objects.requireNonNull(request);Objects.requireNonNull(targetId);Objects.requireNonNull(physicalTargetId);Objects.requireNonNull(physicalRange);
            if(!targetId.equals(request.parameters().get("targetId"))||!physicalTargetId.equals(request.parameters().get("physicalTargetId")))throw new IllegalArgumentException("Frozen D031 target identity mismatch"); }
    }
    private final SyncJobRegistry jobs;private final TusharePageService pages;private final MarginZrzTarget target;private final Path ledgerPath;private final String table;
    @Autowired public MarginZrzJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,MarginZrzTarget target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String ledgerPath){this(jobs,pages,target,Path.of(ledgerPath));}
    public MarginZrzJobService(SyncJobRegistry jobs,TusharePageService pages,MarginZrzTarget target,Path ledgerPath){
        this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);this.target=Objects.requireNonNull(target);this.ledgerPath=ledgerPath.toAbsolutePath().normalize();String table=target.tableName();MarginZrzDataset.requireIsolatedTable(table);this.table=table;}
    public String tableName(){return table;}
    public String targetId(){return target.logicalTargetId(table);}
    public String physicalTargetId(){var snapshot=snapshot();return target.physicalTargetId(table,snapshot.identity());}
    private MarginZrzState.Snapshot snapshot(){try{return target.open(table).snapshot();}catch(Exception e){throw new IllegalStateException("D031 full target snapshot failed",e);}}

    /** Incremental bootstrap is bounded to at most 366 days; initialized targets overlap the verified checkpoint by five days. */
    public Plan plan(Mode requestedMode,LocalDate requestedFrom,LocalDate requestedThrough,LocalDate logicalDate)throws Exception {
        Objects.requireNonNull(logicalDate,"Frozen D031 logical date required");
        Objects.requireNonNull(requestedThrough,"Explicit D031 historical --to date required");
        ZonedDateTime now=ZonedDateTime.now(DailySyncEndDate.ZONE);LocalDate completedCeiling=DailySyncEndDate.resolve(null,now);
        LocalDate frozenLogical=logicalDate;
        LocalDate to=DailySyncEndDate.resolve(requestedThrough,now);if(to.isAfter(completedCeiling))to=completedCeiling;if(to.isAfter(frozenLogical))to=frozenLogical;
        if(to.isAfter(LAST_AUDITED_SOURCE_DATE))throw new IllegalArgumentException("D031 is a retired historical source; explicit isolated requests must end on or before the last audited source date 2025-07-25");
        Mode mode=requestedMode==null?MarginZrzSyncJobOwner.DEFINITION.defaultMode():requestedMode;
        if(!MarginZrzSyncJobOwner.DEFINITION.supportedModes().contains(mode))throw new IllegalArgumentException("Unsupported D031 mode");
        if(to==null)throw new IllegalArgumentException("D031 completed --to date is required");
        if((mode==Mode.BACKFILL||mode==Mode.RECONCILE)&&(requestedFrom==null||requestedThrough==null))throw new IllegalArgumentException("D031 BACKFILL/RECONCILE requires explicit --from and --to");
        String logical=targetId();var port=target.newWriter(physicalTargetId());port.preflight();var full=snapshot();var range=port.readTargetRange();
        if(range.rows()!=full.rows().size())throw new IllegalStateException("D031 physical count differs from bounded full snapshot");
        String physical=target.physicalTargetId(table,full.identity());
        new MarginZrzPublication(target.newPublicationTables(),ledgerPath).requireNoPendingPublication();
        var saved=MarginZrzCoverage.checkpoint(ledgerPath,logical);if(saved!=null)MarginZrzCoverage.validateTarget(ledgerPath,logical,full);
        LocalDate from=requestedFrom,anchor=null,before=null;boolean bootstrap=false,capped=false;
        var parameters=new LinkedHashMap<String,Object>();parameters.put("targetId",logical);parameters.put("physicalTargetId",physical);
        parameters.put("targetRowsBefore",full.rows().size());parameters.put("targetFingerprint",full.fingerprint());if(range.min()!=null)parameters.put("targetMinBefore",range.min());if(range.max()!=null)parameters.put("targetMaxBefore",range.max());
        if(mode==Mode.INCREMENTAL){
            if(range.max()!=null&&range.max().isAfter(frozenLogical))throw new IllegalStateException("D031 isolated target has data after completed logical date");
            if(saved==null){
                if(!full.rows().isEmpty())throw new IllegalStateException("D031 nonempty target lacks same-target receipt-backed incremental coverage");
                if(requestedFrom==null)throw new IllegalArgumentException("D031 retired source requires explicit historical --from/--to on an empty isolated target");
                from=requestedFrom;anchor=from;bootstrap=true;
                if(from.isAfter(to))throw new IllegalArgumentException("D031 bootstrap starts after completed --to");
            }else{
                if(requestedFrom!=null)throw new IllegalArgumentException("D031 --from is used only for empty-target bootstrap; use BACKFILL for a later window");
                var checkpoint=saved;before=checkpoint.through();anchor=checkpoint.anchor();
                if(to.isBefore(before))throw new IllegalArgumentException("D031 end precedes receipt-backed checkpoint");
                from=before.minusDays(MarginZrzSyncJobOwner.REVISION_DAYS);if(from.isBefore(anchor))from=anchor;
                if(range.max()!=null&&range.max().isAfter(to))throw new IllegalStateException("D031 incremental cannot end before existing physical dates");
                parameters.put("checkpointBefore",before);
            }
            LocalDate max=from.plusDays(MarginZrzSyncJobOwner.MAX_WINDOW_DAYS-1L);if(to.isAfter(max)){to=max;capped=true;}
            parameters.put("checkpointAnchor",anchor);
        }else if(mode==Mode.BACKFILL||mode==Mode.RECONCILE){
            if(from==null||from.isAfter(to))throw new IllegalArgumentException("D031 explicit ordered historical window required");
            if(saved==null)throw new IllegalStateException("D031 historical repair requires an established incremental receipt chain");
            MarginZrzCoverage.validateTarget(ledgerPath,logical,full);var cp=saved;if(from.isBefore(cp.anchor())||to.isAfter(cp.through()))throw new IllegalArgumentException("D031 repair window must remain within receipt-backed incremental coverage");
            if(ChronoUnit.DAYS.between(from,to)+1>MarginZrzSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D031 repair window exceeds 366-day bound");
        }else throw new IllegalArgumentException("D031 supports bounded incremental/backfill/reconcile only");
        if(from==null||from.isAfter(to)||to.isAfter(frozenLogical)||ChronoUnit.DAYS.between(from,to)+1>MarginZrzSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D031 resolved window outside finite/logical bounds");
        // The owner is intentionally disabled for daily scheduling. Explicit manual historical requests still freeze against
        // its immutable definition, without passing through the registry's enabled-only automatic planning path.
        var request=MarginZrzSyncJobOwner.DEFINITION.freeze(mode,parameters,from,to,frozenLogical);
        MarginZrzSyncAdapter.validateRequest(request);adapter(table,physical,ledgerPath.getParent().resolve("sync-evidence").resolve("preflight"),logical).preflight(request);
        if(!logical.equals(targetId())||!physical.equals(physicalTargetId()))throw new IllegalStateException("D031 isolated target changed while planning");
        return new Plan(request,logical,physical,anchor,before,range,bootstrap,capped);
    }
    public SyncJobRunner.Result run(Plan plan)throws Exception{return runRequest(Objects.requireNonNull(plan).request(),null);}
    public SyncJobRunner.Result runRequest(SyncJobDefinition.FrozenRequest request,String parentId)throws Exception {
        MarginZrzSyncAdapter.validateRequest(request);String logical=frozenTarget(request),physical=(String)request.parameters().get("physicalTargetId");requireTarget(logical,physical);
        verifyBaseline(request);return execute("margin-zrz-"+UUID.randomUUID(),parentId,null,logical,physical,request);
    }
    public SyncJobRunner.Result runAsGroupChild(String runId,String parentRunId,String priorRunId,String expectedTarget,
            SyncJobDefinition.FrozenRequest request)throws Exception {
        String logical=frozenTarget(request),physical=(String)request.parameters().get("physicalTargetId");
        if(!Objects.equals(expectedTarget,logical))throw new IllegalStateException("D031 group child logical target differs from its frozen request");
        requireTarget(logical,physical);verifyBaseline(request);return execute(runId,parentRunId,priorRunId,logical,physical,request);
    }
    public SyncJobRunner.Result resume(String priorRunId)throws Exception {
        var restored=FrozenRunRequest.restore(ledgerPath,priorRunId,MarginZrzSyncJobOwner.DEFINITION);var request=restored.request();String logical=restored.targetId();
        if(new MarginZrzPublication(target.newPublicationTables(),ledgerPath).findForRun(priorRunId).isPresent())throw new IllegalStateException("D031 publication recovery must finish before source resume");
        if(MarginZrzStaging.hasStageIntent(ledgerPath.getParent().resolve("sync-evidence").resolve(priorRunId)))throw new IllegalStateException("D031 stage-only artifact must be reconciled/discarded before resume");
        String physical=(String)request.parameters().get("physicalTargetId");requireTarget(logical,physical);verifyBaseline(request);
        return execute("margin-zrz-"+UUID.randomUUID(),priorRunId,priorRunId,logical,physical,request);
    }
    public MarginZrzPublication.Result finishInterrupted(String runId,boolean writerStopped)throws Exception {
        var publication=new MarginZrzPublication(target.newPublicationTables(),ledgerPath);if(publication.findForRun(runId).isPresent())return publication.finish(runId,writerStopped);
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);String logical=run.targetId();return discardAndReconcile(runId,logical,writerStopped,publication);
    }
    private MarginZrzPublication.Result discardAndReconcile(String runId,String logical,boolean stopped,MarginZrzPublication publication)throws Exception {
        if(!stopped)throw new IllegalStateException("D031 writer-stopped proof required");
        String current=targetId();if(!logical.equals(current))throw new IllegalStateException("D031 recovery logical target differs");
        publication.discardStageOnly(table,runId,true);throw new IllegalStateException("D031 unpublished stage safely discarded; rerun the same frozen request through resume after discard reconciliation");
    }
    public SyncRunLedger.Entry status(String runId)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(runId);}
    public List<SyncRunLedger.Entry> entries(String runId,String afterId,int limit)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).entries(runId,afterId,limit);}
    public boolean cancel(String runId)throws Exception{return new SyncRunLedger(ledgerPath).requestCancellation(runId);}
    private void verifyBaseline(SyncJobDefinition.FrozenRequest request)throws Exception {
        String logical=frozenTarget(request);if(!logical.equals(targetId()))throw new IllegalStateException("D031 stable logical target changed");
        var actual=snapshot();var p=request.parameters();if(actual.rows().size()!=((Integer)p.get("targetRowsBefore"))||!actual.fingerprint().equals(p.get("targetFingerprint"))
                ||!target.physicalTargetId(table,actual.identity()).equals(p.get("physicalTargetId")))throw new IllegalStateException("D031 frozen physical baseline changed; replan or recover first");
        var cp=MarginZrzCoverage.checkpoint(ledgerPath,logical);
        if(request.mode()==Mode.INCREMENTAL){LocalDate expected=(LocalDate)p.get("checkpointBefore"),anchor=(LocalDate)p.get("checkpointAnchor");if((cp!=null)!=(expected!=null)||cp!=null&&(!cp.through().equals(expected)||!cp.anchor().equals(anchor)))throw new IllegalStateException("D031 incremental checkpoint changed after plan");}
        else if(cp==null||request.from().isBefore(cp.anchor())||request.to().isAfter(cp.through()))throw new IllegalStateException("D031 BACKFILL/RECONCILE must stay within receipt-backed incremental coverage");
        if(cp!=null)MarginZrzCoverage.validateTarget(ledgerPath,logical,actual);else if(!actual.rows().isEmpty())throw new IllegalStateException("D031 nonempty bootstrap target has no verified receipt chain");
    }
    private SyncJobRunner.Result execute(String runId,String parentId,String priorRunId,String logical,String physical,SyncJobDefinition.FrozenRequest request)throws Exception {
        requireTarget(logical,physical);var ledger=new SyncRunLedger(ledgerPath);var adapter=adapter(table,physical,ledgerPath.getParent().resolve("sync-evidence").resolve(runId),logical);var runner=new SyncJobRunner<MarginZrz,com.zoutrankil.data.domain.MarginZrzKey>(ledger,new DatasetIntervalLock(ledgerPath));
        return priorRunId==null?runner.run(runId,parentId,logical,request,adapter,()->Thread.currentThread().isInterrupted()):runner.resume(runId,parentId,priorRunId,logical,request,adapter,()->Thread.currentThread().isInterrupted());
    }
    private MarginZrzSyncAdapter adapter(String table,String physical,Path evidence,String logical)throws Exception{var port=target.newWriter(physical);return new MarginZrzSyncAdapter(new MarginZrzSource(pages,evidence.resolve("source")),port,new MarginZrzStaging(target.newStaging()),new MarginZrzPublication(target.newPublicationTables(),ledgerPath),evidence,target);}
    private void requireTarget(String logical,String physical){if(!Objects.equals(logical,targetId())||!Objects.equals(physical,physicalTargetId()))throw new IllegalStateException("D031 logical or physical isolated target generation changed");}
    private static String frozenTarget(SyncJobDefinition.FrozenRequest request){Object raw=request.parameters().get("targetId");if(!(raw instanceof String value))throw new IllegalArgumentException("Frozen D031 logical target required");return value;}
}
