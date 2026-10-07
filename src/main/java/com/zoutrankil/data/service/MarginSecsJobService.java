package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.MarginSecs;
import com.zoutrankil.data.domain.MarginSecsDataset;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.MarginSecsStorage;
import com.zoutrankil.data.repository.MarginSecsWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import io.questdb.client.QuestDB;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** D030 bounded per-table plan/run/resume facade. */
@Service
public final class MarginSecsJobService {
    public static final String ISOLATED_TABLE_PREFIX=MarginSecsDataset.ISOLATED_PREFIX;
    public record Plan(SyncJobDefinition.FrozenRequest request,String targetId,String physicalTargetId,
            LocalDate checkpointBefore,LocalDate checkpointAnchor,MarginSecsStorage.TargetRange targetRange,boolean bootstrap,int checkedRows) {
        public Plan { Objects.requireNonNull(request);Objects.requireNonNull(targetId);Objects.requireNonNull(physicalTargetId);Objects.requireNonNull(targetRange);
            if(!targetId.equals(request.parameters().get("targetId"))||!physicalTargetId.equals(request.parameters().get("physicalTargetId"))||checkedRows<0)
                throw new IllegalArgumentException("D030 plan must freeze both target identities and bounded checks"); }
    }
    private final SyncJobRegistry jobs;private final TusharePageService pages;private final ExchangeCalendarReadRepository calendars;
    private final JdbcTemplate jdbc;private final QuestDB questdb;private final Path ledgerPath;private final String table;
    @Autowired public MarginSecsJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,ExchangeCalendarReadRepository calendars,
            JdbcTemplate jdbc,@Lazy QuestDB questdb,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String ledgerPath,
            @Value("${app.sync.margin-secs-table:java_d030_margin_secs_acceptance}")String table){
        this(jobs,pages,calendars,jdbc,questdb,Path.of(ledgerPath),table);
    }
    public MarginSecsJobService(SyncJobRegistry jobs,TusharePageService pages,ExchangeCalendarReadRepository calendars,JdbcTemplate jdbc,
            QuestDB questdb,Path ledgerPath,String table){this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);
        this.calendars=Objects.requireNonNull(calendars);this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();MarginSecsDataset.requireIsolatedTable(table);this.table=table;}
    public String datasetId(){return "margin_secs";}public String tableName(){return table;}
    public static void requireIsolatedTableName(String value){MarginSecsDataset.requireIsolatedTable(value);}
    public String targetId(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String dir))
            throw new IllegalStateException("Exact isolated D030 target identity required");return StaticTargetIdentity.identify(jdbc,table,id.longValue(),dir);}
    public String physicalTargetId(){return targetId();}

    /** Bootstrap is explicit; established incrementals re-fetch five calendar days of revisions. */
    public Plan plan(Mode requestedMode,LocalDate bootstrapFrom,LocalDate requestedTo,LocalDate logicalDate)throws Exception {
        Objects.requireNonNull(logicalDate,"Frozen D030 logical date required");var definition=MarginSecsSyncJobOwner.DEFINITION;
        Mode mode=requestedMode==null?definition.defaultMode():requestedMode;if(!definition.supportedModes().contains(mode))throw new IllegalArgumentException("Unsupported D030 sync mode");
        if(requestedTo!=null&&requestedTo.isAfter(logicalDate))throw new IllegalArgumentException("D030 --to exceeds frozen logical date");
        LocalDate completed=DailySyncEndDate.resolve(null,ZonedDateTime.now(DailySyncEndDate.ZONE));
        if(requestedTo!=null&&requestedTo.isAfter(completed))throw new IllegalArgumentException("D030 --to exceeds completed source date");
        LocalDate to=requestedTo==null?completed:requestedTo;if(to.isAfter(logicalDate))to=logicalDate;if(to.isAfter(completed))to=completed;
        if((mode==Mode.BACKFILL||mode==Mode.RECONCILE)&&(bootstrapFrom==null||requestedTo==null))throw new IllegalArgumentException("D030 repairs require explicit --from and --to");
        String logical=targetId(),physical=physicalTargetId();var port=new MarginSecsWritePort(table,physical,jdbc,questdb);port.preflight();
        var snapshot=new MarginSecsStorage(jdbc,table).snapshot();var range=port.readTargetRange();
        if(snapshot.rows().size()!=range.rows())throw new IllegalStateException("D030 bounded snapshot/count mismatch");
        if(!snapshot.rows().isEmpty()&&(range.max().isAfter(logicalDate)||range.max().isAfter(completed)))throw new IllegalStateException("D030 target extends beyond the completed source ceiling");
        var checkpoint=MarginSecsCoverage.checkpoint(ledgerPath,logical);int checked=MarginSecsCoverage.validateTarget(ledgerPath,logical,snapshot);
        LocalDate from=bootstrapFrom,anchor=null,before=null;boolean bootstrap=false;
        if(mode==Mode.INCREMENTAL){
            if(checkpoint==null){if(bootstrapFrom==null)throw new IllegalArgumentException("D030 first incremental run requires explicit --from");
                if(!snapshot.rows().isEmpty())throw new IllegalStateException("D030 nonempty target without receipt-backed incremental checkpoint fails closed");
                from=bootstrapFrom;anchor=from;bootstrap=true;
            }else{
                if(bootstrapFrom!=null)throw new IllegalArgumentException("D030 --from is bootstrap-only after checkpoint; use BACKFILL for explicit history");
                if(to.isBefore(checkpoint.through()))throw new IllegalArgumentException("D030 requested end precedes verified checkpoint");
                before=checkpoint.through();anchor=checkpoint.anchor();from=before.minusDays(MarginSecsSyncJobOwner.REVISION_DAYS-1L);if(from.isBefore(anchor))from=anchor;
            }
        }else{
            from=Objects.requireNonNull(bootstrapFrom);if(checkpoint==null)throw new IllegalStateException("D030 historical correction requires a receipt-backed incremental checkpoint");
            anchor=checkpoint.anchor();if(from.isBefore(anchor)||to.isAfter(checkpoint.through()))throw new IllegalArgumentException("D030 repair must remain within verified incremental coverage");
        }
        long span=ChronoUnit.DAYS.between(from,to)+1;if(from.isAfter(to)||span<1||span>MarginSecsSyncJobOwner.MAX_WINDOW_DAYS||to.isAfter(logicalDate)||to.isAfter(completed))
            throw new IllegalArgumentException("D030 resolved window exceeds finite/completed bounds");
        if(mode==Mode.INCREMENTAL&&!snapshot.rows().isEmpty()&&range.max().isAfter(to))throw new IllegalStateException("D030 incremental end precedes existing target rows");
        var calendar=new MarginSecsTradingDates(calendars).read(from,to);if(calendar.openDates().isEmpty())throw new IllegalArgumentException("D030 frozen window has no verified SSE open date");
        var params=new LinkedHashMap<String,Object>();params.put("targetId",logical);params.put("physicalTargetId",physical);params.put("targetRowsBefore",snapshot.rows().size());
        params.put("targetFingerprint",snapshot.fingerprint());if(!range.empty()){params.put("targetMinBefore",range.min());params.put("targetMaxBefore",range.max());}
        if(mode==Mode.INCREMENTAL){params.put("checkpointAnchor",anchor);if(before!=null)params.put("checkpointBefore",before);}
        params.put("calendarFingerprint",calendar.fingerprint());params.put("calendarDays",String.join(",",calendar.encodedCalendarDays()));
        params.put("tradeDates",String.join(",",calendar.openDates().stream().map(d->d.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)).toList()));
        var request=jobs.prepare(definition.jobId(),definition.version(),mode,params,from,to,logicalDate);
        var evidence=ledgerPath.getParent().resolve("sync-evidence").resolve("d030-plan-preflight");
        new MarginSecsSyncAdapter(new MarginSecsSource(pages,evidence.resolve("source")),calendars,port,jdbc,evidence).preflight(request);
        if(!logical.equals(targetId())||!physical.equals(physicalTargetId()))throw new IllegalStateException("D030 target changed during planning");
        return new Plan(request,logical,physical,before,anchor,range,bootstrap,checked);
    }
    public SyncJobRunner.Result run(Plan plan)throws Exception{return execute("margin-secs-"+UUID.randomUUID(),null,null,Objects.requireNonNull(plan));}
    public SyncJobRunner.Result resume(String priorRunId)throws Exception {
        var restored=FrozenRunRequest.restore(ledgerPath,priorRunId,MarginSecsSyncJobOwner.DEFINITION);var state=SyncRunLedger.openReadOnly(ledgerPath).get(priorRunId);
        if(state.state()==SyncRunState.VERIFIED||state.state()==SyncRunState.VERIFIED_EMPTY)throw new IllegalStateException("D030 run already completed");
        if(state.state()==SyncRunState.IN_DOUBT)throw new IllegalStateException("D030 unknown write outcome requires explicit reconciliation; source replay is blocked");
        var plan=restorePlan(restored.request(),restored.targetId());MarginSecsCoverage.validateTarget(ledgerPath,restored.targetId(),new MarginSecsStorage(jdbc,table).snapshot());
        return execute("margin-secs-"+UUID.randomUUID(),null,priorRunId,plan);
    }
    public SyncJobRunner.Result runAsGroupChild(String childRunId,String parentRunId,String expectedTarget,SyncJobDefinition.FrozenRequest request)throws Exception {
        if(!Objects.equals(expectedTarget,targetId())||!Objects.equals(expectedTarget,request.parameters().get("targetId")))throw new IllegalStateException("D030 group target differs from frozen request");
        return execute(childRunId,parentRunId,null,restorePlan(request,expectedTarget));
    }
    private Plan restorePlan(SyncJobDefinition.FrozenRequest request,String logical){var p=request.parameters();
        LocalDate min=(LocalDate)p.get("targetMinBefore"),max=(LocalDate)p.get("targetMaxBefore");int rows=(Integer)p.get("targetRowsBefore");
        return new Plan(request,logical,(String)p.get("physicalTargetId"),(LocalDate)p.get("checkpointBefore"),(LocalDate)p.get("checkpointAnchor"),
                new MarginSecsStorage.TargetRange(min,max,rows),p.get("checkpointBefore")==null,0);}
    private SyncJobRunner.Result execute(String runId,String parent,String priorRunId,Plan plan)throws Exception {
        MarginSecsSyncAdapter.validateRequest(plan.request());if(!plan.targetId().equals(targetId())||!plan.physicalTargetId().equals(physicalTargetId()))throw new IllegalStateException("Frozen D030 target identity changed");
        if(plan.request().mode()!=Mode.INCREMENTAL)validateCorrection(plan.request(),plan.targetId());
        var port=new MarginSecsWritePort(table,plan.physicalTargetId(),jdbc,questdb);
        if(priorRunId==null){var now=new MarginSecsStorage(jdbc,table).snapshot();if(!Objects.equals(now.rows().size(),plan.request().parameters().get("targetRowsBefore"))
                    ||!now.fingerprint().equals(plan.request().parameters().get("targetFingerprint")))throw new IllegalStateException("D030 frozen target baseline changed after planning");}
        Path evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);var ledger=new SyncRunLedger(ledgerPath);
        var adapter=new MarginSecsSyncAdapter(new MarginSecsSource(pages,evidence.resolve("source")),calendars,port,jdbc,evidence);
        var runner=new SyncJobRunner<MarginSecs,com.zoutrankil.data.domain.MarginSecsKey>(ledger,new DatasetIntervalLock(ledgerPath));
        java.util.function.BooleanSupplier cancelled=()->{if(Thread.currentThread().isInterrupted())return true;try{return ledger.cancellationRequested(runId);}catch(java.sql.SQLException failure){throw new IllegalStateException("Cannot read D030 cancellation state",failure);}};
        return priorRunId==null?runner.run(runId,parent,plan.targetId(),plan.request(),adapter,cancelled):runner.resume(runId,parent,priorRunId,plan.targetId(),plan.request(),adapter,cancelled);
    }
    private void validateCorrection(SyncJobDefinition.FrozenRequest request,String target)throws Exception {
        var checkpoint=MarginSecsCoverage.checkpoint(ledgerPath,target);if(checkpoint==null||request.from().isBefore(checkpoint.anchor())||request.to().isAfter(checkpoint.through()))
            throw new IllegalStateException("D030 BACKFILL/RECONCILE must stay within same-target receipt-backed incremental coverage");
    }
    public SyncRunLedger.Entry status(String id)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).get(id);}
    public List<SyncRunLedger.Entry> entries(String runId,String after,int limit)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).entries(runId,after,limit);}
    public boolean cancel(String runId)throws Exception{return new SyncRunLedger(ledgerPath).requestCancellation(runId);}
}
