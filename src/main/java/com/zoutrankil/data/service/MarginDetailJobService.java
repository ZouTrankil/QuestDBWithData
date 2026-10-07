package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** Isolated D029 plan/run/resume/status facade; shared CLI and group wiring remain coordinator-owned. */
@Service
public final class MarginDetailJobService {
    public static final LocalDate CONFIG_BOOTSTRAP_START=LocalDate.of(2026,1,1);
    public record Plan(SyncJobDefinition.FrozenRequest request,String targetId,LocalDate checkpointAnchor,LocalDate checkpointBefore,
            MarginDetailWritePort.TargetRange physicalRange,boolean bootstrap,boolean cappedByBudget){
        public Plan{Objects.requireNonNull(request);Objects.requireNonNull(targetId);Objects.requireNonNull(physicalRange);if(!targetId.equals(request.parameters().get("targetId")))throw new IllegalArgumentException("D029 physical target identity must be frozen");}}
    private final SyncJobRegistry jobs;private final TusharePageService pages;private final MarginDetailTradingDates calendar;private final JdbcTemplate jdbc;private final QuestDB questdb;private final Path ledgerPath;private final String table;
    @Autowired public MarginDetailJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,ExchangeCalendarReadRepository exchangeCalendar,JdbcTemplate jdbc,
            @Lazy QuestDB questdb,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String ledgerPath,
            @Value("${app.sync.margin-detail-table:java_d029_margin_detail_acceptance}")String table){this(jobs,pages,exchangeCalendar,jdbc,questdb,Path.of(ledgerPath),table);}
    public MarginDetailJobService(SyncJobRegistry jobs,TusharePageService pages,ExchangeCalendarReadRepository exchangeCalendar,JdbcTemplate jdbc,QuestDB questdb,Path ledgerPath,String table){
        this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);this.calendar=new MarginDetailTradingDates(exchangeCalendar);this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);this.ledgerPath=ledgerPath.toAbsolutePath().normalize();MarginDetailDataset.requireWriteTable(table);this.table=table;}
    public String tableName(){return table;}
    /** Static identity freezes endpoint/database, table name, table id and directory generation. */
    public String targetId(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String directory))throw new IllegalStateException("Exact D029 isolated target identity required");return StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory);}

    /** Default daily plan is incremental, one SSE-session behind logical today, with seven calendar days of revision overlap. */
    public Plan planDetailed(Mode requestedMode,LocalDate requestedFrom,LocalDate requestedThrough,LocalDate logicalDate)throws Exception{
        Objects.requireNonNull(logicalDate,"Frozen D029 logical date required");LocalDate ceiling=calendar.sourceCeiling(logicalDate);LocalDate through=requestedThrough==null?ceiling:requestedThrough;if(through.isAfter(ceiling))through=ceiling;
        Mode mode=requestedMode==null?MarginDetailSyncJobOwner.DEFINITION.defaultMode():requestedMode;if(!MarginDetailSyncJobOwner.DEFINITION.supportedModes().contains(mode))throw new IllegalArgumentException("Unsupported D029 sync mode");
        if(mode==Mode.BACKFILL&&(requestedFrom==null||requestedThrough==null))throw new IllegalArgumentException("D029 BACKFILL requires explicit --from and --to");
        boolean sourceRepair=MarginDetailDataset.FORMAL_TABLE.equals(table);if(sourceRepair&&mode!=Mode.BACKFILL)throw new IllegalArgumentException("Formal margin_detail admits only explicit bounded BACKFILL source repair");
        if(sourceRepair&&(requestedFrom.isAfter(through)||ChronoUnit.DAYS.between(requestedFrom,through)+1>MarginDetailSyncJobOwner.MAX_WINDOW_DAYS))throw new IllegalArgumentException("Formal margin_detail repair requires an ordered window of at most 14 calendar days");
        String target=targetId();var port=new MarginDetailWritePort(table,target,jdbc,questdb);port.preflight();var physical=port.readTargetRange();var saved=sourceRepair?Optional.<MarginDetailCoverage.Coverage>empty():MarginDetailCoverage.checkpoint(ledgerPath,target,calendar);
        if(!sourceRepair){if(saved.isPresent())MarginDetailCoverage.validateExistingTarget(saved.get(),physical,port);else if(!physical.empty()||!port.readExistingDates().isEmpty())throw new IllegalStateException("Nonempty D029 target lacks same-identity receipt-backed checkpoint");}
        LocalDate from=requestedFrom,anchor=null,before=null;boolean bootstrap=false,capped=false;
        var parameters=new LinkedHashMap<String,Object>();parameters.put("targetId",target);parameters.put("targetRowsBefore",Long.toString(physical.rows()));if(physical.min()!=null){parameters.put("targetMinBefore",physical.min());parameters.put("targetMaxBefore",physical.max());}
        if(mode==Mode.INCREMENTAL){
            if(saved.isEmpty()){
                if(!physical.empty())throw new IllegalStateException("D029 initial target must be empty until an incremental receipt checkpoint exists");
                from=requestedFrom==null?CONFIG_BOOTSTRAP_START:requestedFrom;anchor=from;bootstrap=true;
            }else{
                if(requestedFrom!=null)throw new IllegalArgumentException("D029 --from is only for empty-target bootstrap; use BACKFILL to repair a covered date range");
                var cp=saved.get();anchor=cp.anchor();before=cp.through();if(through.isBefore(before))throw new IllegalArgumentException("D029 requested-through precedes verified checkpoint");
                from=before.minusDays(MarginDetailSyncJobOwner.REVISION_DAYS);if(from.isBefore(anchor))from=anchor;parameters.put("checkpointBefore",before);
            }
            parameters.put("checkpointAnchor",anchor);
        }else{
            if(requestedFrom==null||requestedFrom.isAfter(through))throw new IllegalArgumentException("D029 BACKFILL requires an explicit ordered historical window");
            from=requestedFrom;if(sourceRepair){anchor=from;parameters.put("checkpointAnchor",anchor);}
            else{if(saved.isEmpty())throw new IllegalStateException("D029 BACKFILL requires a receipt-backed incremental checkpoint");var cp=saved.get();anchor=cp.anchor();before=cp.through();
                if(requestedFrom.isBefore(anchor)||through.isAfter(before))throw new IllegalArgumentException("D029 BACKFILL must remain inside verified incremental coverage");
                parameters.put("checkpointAnchor",anchor);parameters.put("checkpointBefore",before);}
        }
        if(from==null||from.isAfter(through))throw new IllegalArgumentException("D029 resolved window is empty/reversed");LocalDate windowCap=from.plusDays(MarginDetailSyncJobOwner.MAX_WINDOW_DAYS-1L);if(through.isAfter(windowCap)){through=windowCap;capped=true;}
        if(through.isAfter(ceiling)||ChronoUnit.DAYS.between(from,through)+1>MarginDetailSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D029 resolved window exceeds publication/finite bounds");
        var dates=calendar.read(from,through);if(dates.isEmpty())throw new IllegalArgumentException("D029 request window must include an SSE-open source date");parameters.put("trade_dates",MarginDetailCoverage.encodeDates(dates));
        var request=jobs.prepare(MarginDetailSyncJobOwner.DEFINITION.jobId(),MarginDetailSyncJobOwner.DEFINITION.version(),mode,parameters,from,through,logicalDate);
        MarginDetailSyncAdapter.validateRequest(request);new MarginDetailSyncAdapter(new MarginDetailSource(pages,ledgerPath.getParent().resolve("sync-evidence").resolve("preflight-source")),calendar,port,
                ledgerPath.getParent().resolve("sync-evidence").resolve("preflight"),target,false).preflight(request);
        if(!target.equals(targetId()))throw new IllegalStateException("D029 isolated target changed while planning");return new Plan(request,target,anchor,before,physical,bootstrap,capped);
    }

    public SyncJobRunner.Result run(Plan plan)throws Exception{return run(Objects.requireNonNull(plan).request());}
    public SyncJobRunner.Result run(SyncJobDefinition.FrozenRequest request)throws Exception{MarginDetailSyncAdapter.validateRequest(request);String target=frozenTarget(request);requireTarget(target,targetId());validateBaseline(request);return execute("margin-detail-"+UUID.randomUUID(),null,null,target,request,false);}
    public SyncJobRunner.Result resume(String priorRunId)throws Exception{var request=restorePlan(priorRunId);String target=frozenTarget(request);requireTarget(target,targetId());return execute("margin-detail-"+UUID.randomUUID(),priorRunId,priorRunId,target,request,true);}
    public SyncJobRunner.Result resume(SyncJobDefinition.FrozenRequest request,String priorRunId)throws Exception{if(!SyncRequestIdentity.fingerprint(request,frozenTarget(request)).equals(SyncRequestIdentity.fingerprint(restorePlan(priorRunId),frozenTarget(request))))throw new IllegalArgumentException("D029 resume requires the exact frozen request");return resume(priorRunId);}
    public SyncJobDefinition.FrozenRequest restorePlan(String runId)throws Exception{var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);if(!MarginDetailSyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=MarginDetailSyncJobOwner.DEFINITION.version()||!run.targetId().equals(targetId()))throw new IllegalArgumentException("Run does not belong to current D029 isolated target");var request=FrozenRunRequest.restore(ledgerPath,runId,MarginDetailSyncJobOwner.DEFINITION).request();MarginDetailSyncAdapter.validateRequest(request);return request;}
    public SyncRunLedger.Entry status(String runId)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(runId);}
    public List<SyncRunLedger.Entry> entries(String runId,String afterId,int limit)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).entries(runId,afterId,limit);}
    public boolean cancel(String runId)throws Exception{return new SyncRunLedger(ledgerPath).requestCancellation(runId);}
    public SyncJobRunner.Result runAsGroupChild(String runId,String parentRunId,String priorRunId,String expectedTarget,SyncJobDefinition.FrozenRequest request)throws Exception{
        if(!Objects.equals(expectedTarget,targetId())||!expectedTarget.equals(frozenTarget(request)))throw new IllegalStateException("D029 group child target differs from frozen request");if(priorRunId==null)validateBaseline(request);return execute(runId,parentRunId,priorRunId,expectedTarget,request,priorRunId!=null);}

    private void validateBaseline(SyncJobDefinition.FrozenRequest request)throws Exception{var port=new MarginDetailWritePort(table,targetId(),jdbc,questdb);var actual=port.readTargetRange();var parameters=request.parameters();if(actual.rows()!=Long.parseLong((String)parameters.get("targetRowsBefore"))||!Objects.equals(actual.min(),parameters.get("targetMinBefore"))||!Objects.equals(actual.max(),parameters.get("targetMaxBefore")))throw new IllegalStateException("D029 target range/count changed after plan");
        if(requireRepairTarget(request))return;
        var saved=MarginDetailCoverage.checkpoint(ledgerPath,frozenTarget(request),calendar);if(request.mode()==Mode.INCREMENTAL){LocalDate before=(LocalDate)parameters.get("checkpointBefore"),anchor=(LocalDate)parameters.get("checkpointAnchor");if(saved.isPresent()!=(before!=null)||saved.isPresent()&&(!saved.get().through().equals(before)||!saved.get().anchor().equals(anchor)))throw new IllegalStateException("D029 receipt-backed checkpoint changed after plan");}
        else if(saved.isEmpty()||request.from().isBefore(saved.get().anchor())||request.to().isAfter(saved.get().through()))throw new IllegalStateException("D029 BACKFILL must remain inside same-target verified coverage");MarginDetailCoverage.validateExistingTarget(saved.orElse(null),actual,port);}
    private SyncJobRunner.Result execute(String runId,String parentId,String priorRunId,String target,SyncJobDefinition.FrozenRequest request,boolean resume)throws Exception{
        MarginDetailSyncAdapter.validateRequest(request);requireRepairTarget(request);requireTarget(target,targetId());var ledger=new SyncRunLedger(ledgerPath);var port=new MarginDetailWritePort(table,target,jdbc,questdb);var adapter=new MarginDetailSyncAdapter(new MarginDetailSource(pages,ledgerPath.getParent().resolve("sync-evidence").resolve(runId).resolve("source")),calendar,port,
                ledgerPath.getParent().resolve("sync-evidence").resolve(runId),target,resume);var runner=new SyncJobRunner<MarginDetail,MarginDetailKey>(ledger,new DatasetIntervalLock(ledgerPath));
        return priorRunId==null?runner.run(runId,parentId,target,request,adapter,()->Thread.currentThread().isInterrupted()):runner.resume(runId,parentId,priorRunId,target,request,adapter,()->Thread.currentThread().isInterrupted());}
    private static String frozenTarget(SyncJobDefinition.FrozenRequest request){Object value=request.parameters().get("targetId");if(!(value instanceof String text))throw new IllegalArgumentException("Frozen D029 target identity required");return text;}
    /** An absent prior checkpoint on BACKFILL certifies only this repair window, never legacy table coverage. */
    private boolean requireRepairTarget(SyncJobDefinition.FrozenRequest request){boolean formal=MarginDetailDataset.FORMAL_TABLE.equals(table);boolean repair=request.mode()==Mode.BACKFILL&&request.parameters().get("checkpointBefore")==null;
        if(formal&&(!repair||!request.from().equals(request.parameters().get("checkpointAnchor"))))throw new IllegalArgumentException("Formal margin_detail requires an explicit source-certified BACKFILL repair window");
        if(repair&&!formal)throw new IllegalArgumentException("D029 isolated BACKFILL requires a receipt-backed prior checkpoint");return repair;}
    private static void requireTarget(String expected,String actual){if(!Objects.equals(expected,actual))throw new IllegalStateException("D029 QuestDB physical target identity changed");}
}
