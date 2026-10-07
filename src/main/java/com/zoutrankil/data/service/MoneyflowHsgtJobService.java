package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MoneyflowHsgt;
import com.zoutrankil.data.domain.MoneyflowHsgtDataset;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.MoneyflowHsgtStorage;
import com.zoutrankil.data.repository.MoneyflowHsgtStaging;
import com.zoutrankil.data.repository.MoneyflowHsgtWritePort;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import io.questdb.client.QuestDB;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** Bounded D027 plan/run/resume API; resume re-fetches the frozen window after safe stage-only cleanup. */
@Service
public class MoneyflowHsgtJobService {
    public static final String ISOLATED_TABLE_PREFIX=MoneyflowHsgtDataset.ISOLATED_PREFIX;
    public record Plan(SyncJobDefinition.FrozenRequest request,String targetId,String physicalTargetId,
            LocalDate checkpointBefore,LocalDate checkpointAnchor,MoneyflowHsgtStorage.Snapshot targetBefore,boolean bootstrap){
        public Plan{Objects.requireNonNull(request);Objects.requireNonNull(targetId);Objects.requireNonNull(physicalTargetId);Objects.requireNonNull(targetBefore);
            if(!targetId.equals(request.parameters().get("targetId"))||!physicalTargetId.equals(request.parameters().get("physicalTargetId")))
                throw new IllegalArgumentException("D027 logical and physical target identities must be frozen");}
    }
    private final SyncJobRegistry jobs;private final TusharePageService pages;private final JdbcTemplate jdbc;private final QuestDB questdb;
    private final Path ledgerPath;private final String table;
    @Autowired public MoneyflowHsgtJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String ledgerPath,
            @Value("${app.sync.moneyflow-hsgt-table:java_d027_moneyflow_hsgt_acceptance}")String table){
        this(jobs,pages,jdbc,questdb,Path.of(ledgerPath),table);
    }
    public MoneyflowHsgtJobService(SyncJobRegistry jobs,TusharePageService pages,JdbcTemplate jdbc,QuestDB questdb,Path ledgerPath,String table){
        this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();MoneyflowHsgtDataset.requireAdmittedTable(table);this.table=table;
    }
    public String datasetId(){return "moneyflow_hsgt";}public String tableName(){return table;}
    public static void requireIsolatedTableName(String value){MoneyflowHsgtDataset.requireIsolatedTable(value);}
    public static void requireAdmittedTableName(String value){MoneyflowHsgtDataset.requireAdmittedTable(value);}
    private void requireAdmittedMode(Mode mode,LocalDate from,LocalDate to){
        if(!"moneyflow_hsgt".equals(table))return;
        if((mode!=Mode.BACKFILL&&mode!=Mode.RECONCILE)||from==null||to==null||from.isAfter(to)
                ||ChronoUnit.DAYS.between(from,to)+1>MoneyflowHsgtSource.MAX_RANGE_DAYS)
            throw new IllegalArgumentException("Formal moneyflow_hsgt requires explicit source-certified BACKFILL/RECONCILE of at most 31 days");
    }
    public String targetId(){return MoneyflowHsgtTargetIdentity.logical(jdbc,table);}
    public String physicalTargetId(){try{var snapshot=new MoneyflowHsgtStorage(jdbc,table).snapshot();return MoneyflowHsgtStorage.physicalTargetId(jdbc,table,snapshot.identity());}
        catch(Exception failure){throw new IllegalStateException("Cannot prove D027 physical target identity",failure);}}

    /** A first INCREMENTAL requires explicit --from; --to defaults to the completed Shanghai calendar day. */
    public Plan plan(Mode requestedMode,LocalDate bootstrapFrom,LocalDate requestedTo,LocalDate logicalDate)throws Exception {
        Objects.requireNonNull(logicalDate,"D027 frozen logical date required");
        LocalDate completed=DailySyncEndDate.resolve(null,ZonedDateTime.now(DailySyncEndDate.ZONE));
        if(requestedTo!=null&&requestedTo.isAfter(logicalDate))throw new IllegalArgumentException("D027 --to exceeds frozen logical date");
        if(requestedTo!=null&&requestedTo.isAfter(completed))throw new IllegalArgumentException("D027 --to includes an incomplete source date");
        LocalDate to=requestedTo==null?completed:requestedTo;if(to.isAfter(logicalDate))to=logicalDate;
        var definition=MoneyflowHsgtSyncJobOwner.DEFINITION;Mode mode=requestedMode==null?definition.defaultMode():requestedMode;
        if(!definition.supportedModes().contains(mode))throw new IllegalArgumentException("Unsupported D027 sync mode");
        requireAdmittedMode(mode,bootstrapFrom,requestedTo);
        if((mode==Mode.BACKFILL||mode==Mode.RECONCILE)&&(bootstrapFrom==null||requestedTo==null))
            throw new IllegalArgumentException("D027 BACKFILL/RECONCILE require explicit --from and --to");
        var publisher=new MoneyflowHsgtPublication(jdbc,ledgerPath);publisher.requireNoPendingPublication();
        String logical=targetId(),physical=physicalTargetId();var before=new MoneyflowHsgtStorage(jdbc,table).snapshot();
        if(before.rows().size()>MoneyflowHsgtStorage.MAX_ROWS)throw new IllegalStateException("D027 target exceeds full snapshot bound");
        var range=targetRange(before.rows());
        if(!before.rows().isEmpty()&&(range.max().isAfter(logicalDate)||range.max().isAfter(completed)))
            throw new IllegalStateException("D027 target contains a date beyond the frozen completed-source ceiling");
        var checkpoint=MoneyflowHsgtCoverage.checkpoint(ledgerPath,logical);
        if(!"moneyflow_hsgt".equals(table))MoneyflowHsgtCoverage.validateTarget(ledgerPath,logical,before);
        else {
            var keys=new java.util.HashSet<com.zoutrankil.data.domain.MoneyflowHsgtKey>();
            for(var row:before.rows())if(!keys.add(row.key()))throw new IllegalStateException("Formal moneyflow_hsgt has duplicate existing business dates");
        }
        LocalDate from=bootstrapFrom,anchor=null,checkpointBefore=null;boolean bootstrap=false;
        if(mode==Mode.INCREMENTAL){
            if(checkpoint==null){
                if(bootstrapFrom==null)throw new IllegalArgumentException("D027 needs a user-supplied --from for its first bounded bootstrap");
                if(!before.rows().isEmpty())throw new IllegalStateException("D027 nonempty target lacks receipt-backed incremental coverage");
                from=bootstrapFrom;anchor=from;bootstrap=true;
            }else{
                if(bootstrapFrom!=null)throw new IllegalArgumentException("D027 --from is bootstrap-only after checkpoint; use BACKFILL");
                checkpointBefore=checkpoint.through();anchor=checkpoint.anchor();
                if(to.isBefore(checkpointBefore))throw new IllegalArgumentException("D027 requested end precedes verified checkpoint");
                from=checkpointBefore.minusDays(MoneyflowHsgtSyncJobOwner.REVISION_DAYS-1L);if(from.isBefore(anchor))from=anchor;
            }
        }else{
            from=Objects.requireNonNull(bootstrapFrom);
            if(!"moneyflow_hsgt".equals(table)){
                if(checkpoint==null)throw new IllegalStateException("D027 bounded correction requires a receipt-backed incremental checkpoint");
                anchor=checkpoint.anchor();if(from.isBefore(anchor)||to.isAfter(checkpoint.through()))throw new IllegalArgumentException("D027 BACKFILL/RECONCILE must remain inside verified coverage");
            }
        }
        long span=ChronoUnit.DAYS.between(from,to)+1;
        if(from.isAfter(to)||span<1||span>MoneyflowHsgtSyncJobOwner.MAX_WINDOW_DAYS||to.isAfter(completed)||to.isAfter(logicalDate))
            throw new IllegalArgumentException("D027 resolved range exceeds finite completed-date bounds");
        if(mode==Mode.INCREMENTAL&&!before.rows().isEmpty()&&range.max().isAfter(to))
            throw new IllegalStateException("D027 incremental end precedes target data; use bounded RECONCILE");
        var params=new LinkedHashMap<String,Object>();params.put("targetId",logical);params.put("physicalTargetId",physical);
        params.put("targetRowsBefore",before.rows().size());params.put("targetFingerprint",before.fingerprint());
        if(range.min()!=null){params.put("targetMinBefore",range.min());params.put("targetMaxBefore",range.max());}
        if(mode==Mode.INCREMENTAL){params.put("checkpointAnchor",anchor);if(checkpointBefore!=null)params.put("checkpointBefore",checkpointBefore);}
        var request=jobs.prepare(definition.jobId(),definition.version(),mode,params,from,to,logicalDate);
        new MoneyflowHsgtSyncAdapter(new MoneyflowHsgtSource(pages,ledgerPath.getParent().resolve("sync-evidence").resolve("d027-plan-source")),
                new MoneyflowHsgtWritePort(table,physical,jdbc,questdb),new com.zoutrankil.data.repository.MoneyflowHsgtStaging(jdbc),publisher,
                ledgerPath.getParent().resolve("sync-evidence").resolve("d027-plan-source"),jdbc).preflight(request);
        if(!logical.equals(targetId())||!physical.equals(physicalTargetId()))throw new IllegalStateException("D027 target changed during plan");
        return new Plan(request,logical,physical,checkpointBefore,anchor,before,bootstrap);
    }
    public SyncJobRunner.Result run(Plan plan)throws Exception{return execute("moneyflow-hsgt-"+UUID.randomUUID(),null,null,Objects.requireNonNull(plan));}
    public SyncJobRunner.Result resume(String priorRunId)throws Exception {
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(priorRunId);var state=SyncRunLedger.openReadOnly(ledgerPath).get(priorRunId);
        if(!MoneyflowHsgtSyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=MoneyflowHsgtSyncJobOwner.DEFINITION.version()
                ||!targetId().equals(run.targetId()))throw new IllegalArgumentException("Run does not belong to current D027 logical target");
        if(state.state()==SyncRunState.VERIFIED||state.state()==SyncRunState.VERIFIED_EMPTY)
            throw new IllegalStateException("D027 run already completed; plan the next incremental request");
        var publisher=new MoneyflowHsgtPublication(jdbc,ledgerPath);
        if(publisher.findForRun(priorRunId).isPresent())throw new IllegalStateException("D027 journaled publication must be completed through finishInterrupted before another source request");
        var restored=FrozenRunRequest.restore(ledgerPath,priorRunId,MoneyflowHsgtSyncJobOwner.DEFINITION);
        var request=restored.request();String targetPhysical=(String)request.parameters().get("physicalTargetId");
        var current=new MoneyflowHsgtStorage(jdbc,table).snapshot();
        if(!targetPhysical.equals(MoneyflowHsgtStorage.physicalTargetId(jdbc,table,current.identity()))
                ||!current.fingerprint().equals(request.parameters().get("targetFingerprint")))
            throw new IllegalStateException("D027 frozen baseline changed; plan a new request after reconciling the prior run");
        Path evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(priorRunId);
        if(MoneyflowHsgtStaging.hasStageIntent(evidence))publisher.discardStageOnly(table,priorRunId,true);
        MoneyflowHsgtCoverage.Checkpoint checkpoint=MoneyflowHsgtCoverage.checkpoint(ledgerPath,targetId());
        var plan=new Plan(request,targetId(),targetPhysical,(LocalDate)request.parameters().get("checkpointBefore"),
                (LocalDate)request.parameters().get("checkpointAnchor"),current,checkpoint==null);
        // A prior partially verified stage is discarded only after proving target unchanged; replay the exact frozen window in a fresh ledger run.
        return execute("moneyflow-hsgt-"+UUID.randomUUID(),null,null,plan);
    }
    public MoneyflowHsgtPublication.Result finishInterrupted(String runId,boolean writerStopped)throws Exception {
        if(new MoneyflowHsgtPublication(jdbc,ledgerPath).findForRun(runId).isEmpty())MoneyflowHsgtRunRecovery.finishStageOnly(jdbc,ledgerPath,table,runId,writerStopped);
        var result=new MoneyflowHsgtPublication(jdbc,ledgerPath).finish(runId,writerStopped);completeRecoveredLedger(runId,result);return result;
    }
    public LedgerReadModels.Entry status(String runId)throws Exception{return LedgerReadModels.entry(SyncRunLedger.openReadOnly(ledgerPath).get(runId));}
    public List<SyncRunLedger.Entry> entries(String runId,String afterId,int limit)throws Exception{return SyncRunLedger.openReadOnly(ledgerPath).entries(runId,afterId,limit);}
    public boolean cancel(String runId)throws Exception{return new SyncRunLedger(ledgerPath).requestCancellation(runId);}
    public SyncJobRunner.Result runAsGroupChild(String childRunId,String parentRunId,String expectedTarget,SyncJobDefinition.FrozenRequest request)throws Exception {
        if(!targetId().equals(expectedTarget)||!expectedTarget.equals(request.parameters().get("targetId")))throw new IllegalStateException("D027 group child logical target changed");
        var p=request.parameters();var before=new MoneyflowHsgtStorage(jdbc,table).snapshot();
        var plan=new Plan(request,expectedTarget,(String)p.get("physicalTargetId"),(LocalDate)p.get("checkpointBefore"),(LocalDate)p.get("checkpointAnchor"),before,!p.containsKey("checkpointBefore"));
        return execute(childRunId,parentRunId,null,plan);
    }
    private SyncJobRunner.Result execute(String runId,String parentRunId,String priorRunId,Plan plan)throws Exception {
        requireAdmittedMode(plan.request().mode(),plan.request().from(),plan.request().to());
        if(!plan.request().definition().equals(MoneyflowHsgtSyncJobOwner.DEFINITION)||!plan.targetId().equals(targetId())
                ||!plan.physicalTargetId().equals(physicalTargetId())||!plan.physicalTargetId().equals(plan.request().parameters().get("physicalTargetId")))
            throw new IllegalStateException("Frozen D027 plan or target generation changed before execution");
        var current=new MoneyflowHsgtStorage(jdbc,table).snapshot();if(!MoneyflowHsgtStorage.sameContent(current,plan.targetBefore())
                ||current.identity().id()!=plan.targetBefore().identity().id()
                ||!current.identity().directory().equals(plan.targetBefore().identity().directory())
                ||current.identity().writerTxn()!=plan.targetBefore().identity().writerTxn())
            throw new IllegalStateException("D027 target changed after planning; re-plan before source/write operations");
        var ledger=new SyncRunLedger(ledgerPath);var port=new MoneyflowHsgtWritePort(table,plan.physicalTargetId(),jdbc,questdb);
        Path evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var adapter=new MoneyflowHsgtSyncAdapter(new MoneyflowHsgtSource(pages,evidence.resolve("source")),port,
                new com.zoutrankil.data.repository.MoneyflowHsgtStaging(jdbc),new MoneyflowHsgtPublication(jdbc,ledgerPath),evidence,jdbc);
        var runner=new SyncJobRunner<MoneyflowHsgt,com.zoutrankil.data.domain.MoneyflowHsgtKey>(ledger,new DatasetIntervalLock(ledgerPath));
        java.util.function.BooleanSupplier cancelled=()->{if(Thread.currentThread().isInterrupted())return true;try{return ledger.cancellationRequested(runId);}catch(java.sql.SQLException failure){throw new IllegalStateException("Cannot read D027 cancellation state",failure);}};
        return priorRunId==null?runner.run(runId,parentRunId,plan.targetId(),plan.request(),adapter,cancelled)
                :runner.resume(runId,parentRunId,priorRunId,plan.targetId(),plan.request(),adapter,cancelled);
    }
    private void completeRecoveredLedger(String runId,MoneyflowHsgtPublication.Result publication)throws Exception {
        var ledger=new SyncRunLedger(ledgerPath);var run=ledger.getRun(runId);var runEntry=ledger.get(runId);
        if(!MoneyflowHsgtSyncJobOwner.DEFINITION.jobId().equals(run.jobId())||!targetId().equals(run.targetId()))throw new IllegalStateException("D027 recovery run binding differs");
        JsonNode scope=JobDefinitionJson.mapper().readTree(publication.entry().intent().scope());
        Path stageReceipt=Path.of(scope.path("stageReceipt").asText());JsonNode stage=JobDefinitionJson.mapper().readTree(stageReceipt.toFile());
        int rows=stage.path("sourceRows").asInt(-1);var receiptList=stage.path("sourceReceipts");
        if(rows<0||!receiptList.isArray()||publication.layout()!=MoneyflowHsgtPublication.Layout.PUBLISHED)throw new IllegalStateException("D027 recovery receipt incomplete");
        var children=new ArrayList<SyncRunLedger.Entry>();String after=null;while(true){var page=ledger.entries(runId,after,100);children.addAll(page);if(page.size()<100)break;after=page.getLast().id();if(children.size()>1000)throw new IllegalStateException("D027 recovery children exceed bound");}
        var slices=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();var attempts=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).toList();
        if(slices.size()!=receiptList.size()||attempts.size()!=1||slices.stream().anyMatch(e->e.state()!=SyncRunState.VERIFIED&&e.state()!=SyncRunState.VERIFIED_EMPTY))
            throw new IllegalStateException("D027 recovery requires every raw slice to have completed typed verification");
        String fingerprint=sourceFingerprint(receiptList);int stateRows=rows;var verification=Map.of("passed",true,"writerStopped",true,"expectedRows",stateRows,"actualRows",stateRows,
                "matchedRows",stateRows,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"sourceFingerprint",fingerprint,
                "readbackEvidence","publication:"+publication.entry().intent().id()+";snapshot:"+publication.target().fingerprint());
        String evidence=JobDefinitionJson.mapper().writeValueAsString(Map.of("sourceComplete",true,"returnedRows",stateRows,"submittedRows",stateRows,
                "responseEvidence",stageReceipt.toString(),"publicationId",publication.entry().intent().id(),"verification",verification));
        SyncRunState end=stateRows==0?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED;
        for(var entry:List.of(attempts.getFirst(),runEntry)){var cur=ledger.get(entry.id());if(cur.state()==end)continue;
            if(cur.state().terminal()||cur.state()==SyncRunState.IN_DOUBT||cur.state()==SyncRunState.RUNNING||cur.state()==SyncRunState.ACKNOWLEDGED){
                if(cur.state()==SyncRunState.RUNNING||cur.state()==SyncRunState.ACKNOWLEDGED){ledger.transition(cur.id(),cur.revision(),SyncRunState.IN_DOUBT,"{\"d027PublicationRecovery\":true}");cur=ledger.get(cur.id());}
                ledger.transition(cur.id(),cur.revision(),end,evidence);
            }else throw new IllegalStateException("D027 ledger recovery cannot transition from "+cur.state());}
        var locks=new DatasetIntervalLock(ledgerPath);var frozen=JobDefinitionJson.mapper().readTree(run.frozenJson());
        var lease=locks.findOwned(runId,new DatasetIntervalLock.Scope("moneyflow_hsgt",LocalDate.parse(frozen.path("from").asText()),LocalDate.parse(frozen.path("to").asText())));
        if(lease!=null){if(!lease.inDoubt()){locks.retainInDoubt(lease);lease=locks.findOwned(runId,lease.scope());}locks.releaseAfterReconciliation(lease,true,true);}
    }
    private static String sourceFingerprint(JsonNode refs)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");for(JsonNode item:refs){String part=item.path("sourceFingerprint").asText("");
            if(!part.matches("[0-9a-f]{64}"))throw new IllegalStateException("D027 source fingerprint missing from stage proof");
            digest.update(part.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MoneyflowHsgtWritePort.TargetRange targetRange(List<MoneyflowHsgt> rows){
        if(rows.isEmpty())return new MoneyflowHsgtWritePort.TargetRange(null,null,0);
        return new MoneyflowHsgtWritePort.TargetRange(rows.stream().map(MoneyflowHsgt::tradeDate).min(LocalDate::compareTo).orElseThrow(),
                rows.stream().map(MoneyflowHsgt::tradeDate).max(LocalDate::compareTo).orElseThrow(),rows.size());
    }
}
