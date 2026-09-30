package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.MarginAll;
import com.zoutrankil.questdbwithdata.domain.MarginAllDataset;
import com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.MarginAllStorage;
import com.zoutrankil.questdbwithdata.repository.MarginAllStaging;
import com.zoutrankil.questdbwithdata.repository.MarginAllWritePort;
import com.zoutrankil.questdbwithdata.repository.ReferencePublicationJournal;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import io.questdb.client.QuestDB;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode;

/** Bounded plan/run/resume facade for D028; default empty-target bootstrap follows Python's 2026-01-01 fallback. */
@Service
public class MarginAllJobService {
    public static final LocalDate BOOTSTRAP_START=LocalDate.of(2026,1,1);
    public record Plan(SyncJobDefinition.FrozenRequest request,String targetId,String physicalTargetId,
            LocalDate checkpointAnchor,LocalDate checkpointBefore,MarginAllWritePort.TargetRange physicalRange,
            boolean bootstrap,boolean cappedByBudget) {
        public Plan { Objects.requireNonNull(request);Objects.requireNonNull(targetId);Objects.requireNonNull(physicalTargetId);Objects.requireNonNull(physicalRange);
            if(!targetId.equals(request.parameters().get("targetId"))||!physicalTargetId.equals(request.parameters().get("physicalTargetId")))throw new IllegalArgumentException("Frozen D028 target identity mismatch"); }
    }
    private final SyncJobRegistry jobs;private final TusharePageService pages;private final JdbcTemplate jdbc;private final QuestDB questdb;private final Path ledgerPath;private final String table;
    @Autowired public MarginAllJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String ledgerPath,
            @Value("${app.sync.margin-all-table:java_d028_margin_all_acceptance}")String table){this(jobs,pages,jdbc,questdb,Path.of(ledgerPath),table);}
    public MarginAllJobService(SyncJobRegistry jobs,TusharePageService pages,JdbcTemplate jdbc,QuestDB questdb,Path ledgerPath,String table){
        this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);this.ledgerPath=ledgerPath.toAbsolutePath().normalize();MarginAllDataset.requireIsolatedTable(table);this.table=table;}
    public String tableName(){return table;}
    public String targetId(){return MarginAllTargetIdentity.logical(jdbc,table);}
    public String physicalTargetId(){var snapshot=snapshot();return MarginAllStorage.physicalTargetId(jdbc,table,snapshot.identity());}
    private MarginAllStorage.Snapshot snapshot(){try{return new MarginAllStorage(jdbc,table).snapshot();}catch(Exception e){throw new IllegalStateException("D028 full target snapshot failed",e);}}

    /** Incremental bootstrap is bounded to at most 366 days; initialized targets overlap the verified checkpoint by five days. */
    public Plan plan(Mode requestedMode,LocalDate requestedFrom,LocalDate requestedThrough,LocalDate logicalDate)throws Exception {
        Objects.requireNonNull(logicalDate,"Frozen D028 logical date required");
        ZonedDateTime now=ZonedDateTime.now(DailySyncEndDate.ZONE);LocalDate completedCeiling=DailySyncEndDate.resolve(null,now);
        LocalDate frozenLogical=logicalDate;
        LocalDate to=DailySyncEndDate.resolve(requestedThrough,now);if(to.isAfter(completedCeiling))to=completedCeiling;if(to.isAfter(frozenLogical))to=frozenLogical;
        Mode mode=requestedMode==null?MarginAllSyncJobOwner.DEFINITION.defaultMode():requestedMode;
        if(!MarginAllSyncJobOwner.DEFINITION.supportedModes().contains(mode))throw new IllegalArgumentException("Unsupported D028 mode");
        if(to==null)throw new IllegalArgumentException("D028 completed --to date is required");
        if((mode==Mode.BACKFILL||mode==Mode.RECONCILE)&&(requestedFrom==null||requestedThrough==null))throw new IllegalArgumentException("D028 BACKFILL/RECONCILE requires explicit --from and --to");
        String logical=targetId();var port=new MarginAllWritePort(table,physicalTargetId(),jdbc,questdb);port.preflight();var full=snapshot();var range=port.readTargetRange();
        if(range.rows()!=full.rows().size())throw new IllegalStateException("D028 physical count differs from bounded full snapshot");
        String physical=MarginAllStorage.physicalTargetId(jdbc,table,full.identity());
        new MarginAllPublication(jdbc,ledgerPath).requireNoPendingPublication();
        var saved=MarginAllCoverage.checkpoint(ledgerPath,logical);if(saved!=null)MarginAllCoverage.validateTarget(ledgerPath,logical,full);
        LocalDate from=requestedFrom,anchor=null,before=null;boolean bootstrap=false,capped=false;
        var parameters=new LinkedHashMap<String,Object>();parameters.put("targetId",logical);parameters.put("physicalTargetId",physical);
        parameters.put("targetRowsBefore",full.rows().size());parameters.put("targetFingerprint",full.fingerprint());if(range.min()!=null)parameters.put("targetMinBefore",range.min());if(range.max()!=null)parameters.put("targetMaxBefore",range.max());
        if(mode==Mode.INCREMENTAL){
            if(range.max()!=null&&range.max().isAfter(frozenLogical))throw new IllegalStateException("D028 isolated target has data after completed logical date");
            if(saved==null){
                if(!full.rows().isEmpty())throw new IllegalStateException("D028 nonempty target lacks same-target receipt-backed incremental coverage");
                from=requestedFrom==null?BOOTSTRAP_START:requestedFrom;anchor=from;bootstrap=true;
                if(from.isAfter(to))throw new IllegalArgumentException("D028 bootstrap starts after completed --to");
            }else{
                if(requestedFrom!=null)throw new IllegalArgumentException("D028 --from is used only for empty-target bootstrap; use BACKFILL for a later window");
                var checkpoint=saved;before=checkpoint.through();anchor=checkpoint.anchor();
                if(to.isBefore(before))throw new IllegalArgumentException("D028 end precedes receipt-backed checkpoint");
                from=before.minusDays(MarginAllSyncJobOwner.REVISION_DAYS);if(from.isBefore(anchor))from=anchor;
                if(range.max()!=null&&range.max().isAfter(to))throw new IllegalStateException("D028 incremental cannot end before existing physical dates");
                parameters.put("checkpointBefore",before);
            }
            LocalDate max=from.plusDays(MarginAllSyncJobOwner.MAX_WINDOW_DAYS-1L);if(to.isAfter(max)){to=max;capped=true;}
            parameters.put("checkpointAnchor",anchor);
        }else if(mode==Mode.BACKFILL||mode==Mode.RECONCILE){
            if(from==null||from.isAfter(to))throw new IllegalArgumentException("D028 explicit ordered historical window required");
            if(saved==null)throw new IllegalStateException("D028 historical repair requires an established incremental receipt chain");
            MarginAllCoverage.validateTarget(ledgerPath,logical,full);var cp=saved;if(from.isBefore(cp.anchor())||to.isAfter(cp.through()))throw new IllegalArgumentException("D028 repair window must remain within receipt-backed incremental coverage");
            if(ChronoUnit.DAYS.between(from,to)+1>MarginAllSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D028 repair window exceeds 366-day bound");
        }else throw new IllegalArgumentException("D028 supports bounded incremental/backfill/reconcile only");
        if(from==null||from.isAfter(to)||to.isAfter(frozenLogical)||ChronoUnit.DAYS.between(from,to)+1>MarginAllSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D028 resolved window outside finite/logical bounds");
        var request=jobs.prepare(MarginAllSyncJobOwner.DEFINITION.jobId(),MarginAllSyncJobOwner.DEFINITION.version(),mode,parameters,from,to,frozenLogical);
        MarginAllSyncAdapter.validateRequest(request);adapter(table,physical,ledgerPath.getParent().resolve("sync-evidence").resolve("preflight"),logical).preflight(request);
        if(!logical.equals(targetId())||!physical.equals(physicalTargetId()))throw new IllegalStateException("D028 isolated target changed while planning");
        return new Plan(request,logical,physical,anchor,before,range,bootstrap,capped);
    }
    public SyncJobRunner.Result run(Plan plan)throws Exception{return runRequest(Objects.requireNonNull(plan).request(),null);}
    public SyncJobRunner.Result runRequest(SyncJobDefinition.FrozenRequest request,String parentId)throws Exception {
        MarginAllSyncAdapter.validateRequest(request);String logical=frozenTarget(request),physical=(String)request.parameters().get("physicalTargetId");requireTarget(logical,physical);
        verifyBaseline(request);return execute("margin-all-"+UUID.randomUUID(),parentId,null,logical,physical,request);
    }
    public SyncJobRunner.Result runAsGroupChild(String runId,String parentRunId,String priorRunId,String expectedTarget,
            SyncJobDefinition.FrozenRequest request)throws Exception {
        String logical=frozenTarget(request),physical=(String)request.parameters().get("physicalTargetId");
        if(!Objects.equals(expectedTarget,logical))throw new IllegalStateException("D028 group child logical target differs from its frozen request");
        requireTarget(logical,physical);verifyBaseline(request);return execute(runId,parentRunId,priorRunId,logical,physical,request);
    }
    public SyncJobRunner.Result resume(String priorRunId)throws Exception {
        var restored=FrozenRunRequest.restore(ledgerPath,priorRunId,MarginAllSyncJobOwner.DEFINITION);var request=restored.request();String logical=restored.targetId();
        if(new MarginAllPublication(jdbc,ledgerPath).findForRun(priorRunId).isPresent())throw new IllegalStateException("D028 publication recovery must finish before source resume");
        if(MarginAllStaging.hasStageIntent(ledgerPath.getParent().resolve("sync-evidence").resolve(priorRunId)))throw new IllegalStateException("D028 stage-only artifact must be reconciled/discarded before resume");
        String physical=(String)request.parameters().get("physicalTargetId");requireTarget(logical,physical);verifyBaseline(request);
        return execute("margin-all-"+UUID.randomUUID(),priorRunId,priorRunId,logical,physical,request);
    }
    public MarginAllPublication.Result finishInterrupted(String runId,boolean writerStopped)throws Exception {
        var publication=new MarginAllPublication(jdbc,ledgerPath);if(publication.findForRun(runId).isPresent())return publication.finish(runId,writerStopped);
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);String logical=run.targetId();return discardAndReconcile(runId,logical,writerStopped,publication);
    }
    private MarginAllPublication.Result discardAndReconcile(String runId,String logical,boolean stopped,MarginAllPublication publication)throws Exception {
        if(!stopped)throw new IllegalStateException("D028 writer-stopped proof required");
        String current=targetId();if(!logical.equals(current))throw new IllegalStateException("D028 recovery logical target differs");
        publication.discardStageOnly(table,runId,true);throw new IllegalStateException("D028 unpublished stage safely discarded; rerun the same frozen request through resume after discard reconciliation");
    }
    public SyncRunLedger.Entry status(String runId)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(runId);}
    public List<SyncRunLedger.Entry> entries(String runId,String afterId,int limit)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).entries(runId,afterId,limit);}
    public boolean cancel(String runId)throws Exception{return new SyncRunLedger(ledgerPath).requestCancellation(runId);}
    private void verifyBaseline(SyncJobDefinition.FrozenRequest request)throws Exception {
        String logical=frozenTarget(request);if(!logical.equals(targetId()))throw new IllegalStateException("D028 stable logical target changed");
        var actual=snapshot();var p=request.parameters();if(actual.rows().size()!=((Integer)p.get("targetRowsBefore"))||!actual.fingerprint().equals(p.get("targetFingerprint"))
                ||!MarginAllStorage.physicalTargetId(jdbc,table,actual.identity()).equals(p.get("physicalTargetId")))throw new IllegalStateException("D028 frozen physical baseline changed; replan or recover first");
        var cp=MarginAllCoverage.checkpoint(ledgerPath,logical);
        if(request.mode()==Mode.INCREMENTAL){LocalDate expected=(LocalDate)p.get("checkpointBefore"),anchor=(LocalDate)p.get("checkpointAnchor");if((cp!=null)!=(expected!=null)||cp!=null&&(!cp.through().equals(expected)||!cp.anchor().equals(anchor)))throw new IllegalStateException("D028 incremental checkpoint changed after plan");}
        else if(cp==null||request.from().isBefore(cp.anchor())||request.to().isAfter(cp.through()))throw new IllegalStateException("D028 BACKFILL/RECONCILE must stay within receipt-backed incremental coverage");
        if(cp!=null)MarginAllCoverage.validateTarget(ledgerPath,logical,actual);else if(!actual.rows().isEmpty())throw new IllegalStateException("D028 nonempty bootstrap target has no verified receipt chain");
    }
    private SyncJobRunner.Result execute(String runId,String parentId,String priorRunId,String logical,String physical,SyncJobDefinition.FrozenRequest request)throws Exception {
        requireTarget(logical,physical);var ledger=new SyncRunLedger(ledgerPath);var adapter=adapter(table,physical,ledgerPath.getParent().resolve("sync-evidence").resolve(runId),logical);var runner=new SyncJobRunner<MarginAll,com.zoutrankil.questdbwithdata.domain.MarginAllKey>(ledger,new DatasetIntervalLock(ledgerPath));
        return priorRunId==null?runner.run(runId,parentId,logical,request,adapter,()->Thread.currentThread().isInterrupted()):runner.resume(runId,parentId,priorRunId,logical,request,adapter,()->Thread.currentThread().isInterrupted());
    }
    private MarginAllSyncAdapter adapter(String table,String physical,Path evidence,String logical)throws Exception{var port=new MarginAllWritePort(table,physical,jdbc,questdb);return new MarginAllSyncAdapter(new MarginAllSource(pages,evidence.resolve("source")),port,new MarginAllStaging(jdbc),new MarginAllPublication(jdbc,ledgerPath),evidence,jdbc);}
    private void requireTarget(String logical,String physical){if(!Objects.equals(logical,targetId())||!Objects.equals(physical,physicalTargetId()))throw new IllegalStateException("D028 logical or physical isolated target generation changed");}
    private static String frozenTarget(SyncJobDefinition.FrozenRequest request){Object raw=request.parameters().get("targetId");if(!(raw instanceof String value))throw new IllegalArgumentException("Frozen D028 logical target required");return value;}
}
