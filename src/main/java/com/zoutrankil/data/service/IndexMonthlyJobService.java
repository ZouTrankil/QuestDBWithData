package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.mapper.IndexMonthlyMapper;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Explicit bounded planner/runner for one frozen monthly-provider code and an isolated target. */
@Service
public final class IndexMonthlyJobService {
    public static final String ISOLATED_TABLE_PREFIX="java_d022_index_monthly_";
    public record Plan(SyncJobDefinition.FrozenRequest request,String targetId,String physicalTargetId,String tsCode,
            LocalDate checkpointBefore,LocalDate checkpointAnchor,IndexMonthlyWritePort.TargetRange physicalRange,boolean bootstrap) {
        public Plan {
            Objects.requireNonNull(request);Objects.requireNonNull(targetId);Objects.requireNonNull(physicalTargetId);Objects.requireNonNull(tsCode);Objects.requireNonNull(physicalRange);
            if(!targetId.equals(request.parameters().get("targetId"))||!physicalTargetId.equals(request.parameters().get("physicalTargetId"))
                    ||!tsCode.equals(request.parameters().get("tsCode")))throw new IllegalArgumentException("Frozen D022 logical/physical target or code identity mismatch");
        }
    }
    private final SyncJobRegistry jobs;private final TusharePageService pages;private final JdbcTemplate jdbc;private final QuestDB questdb;
    private final Path ledgerPath;private final String table;
    @Autowired public IndexMonthlyJobService(@Lazy SyncJobRegistry jobs,TusharePageService pages,JdbcTemplate jdbc,@Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.index-monthly-table:java_d022_index_monthly_acceptance}") String table) {
        this(jobs,pages,jdbc,questdb,Path.of(ledgerPath),table);
    }
    public IndexMonthlyJobService(SyncJobRegistry jobs,TusharePageService pages,JdbcTemplate jdbc,QuestDB questdb,Path ledgerPath,String table) {
        this.jobs=Objects.requireNonNull(jobs);this.pages=Objects.requireNonNull(pages);this.jdbc=Objects.requireNonNull(jdbc);this.questdb=Objects.requireNonNull(questdb);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();requireIsolatedTableName(table);this.table=table;
    }
    public String tableName(){return table;}
    public static void requireIsolatedTableName(String table){IndexMonthlyDataset.requireIsolatedTableName(table);}
    public String targetId(){requireIsolatedTableName(table);return IndexMonthlyTargetIdentity.logical(jdbc,table);}
    public String physicalTargetId(){requireIsolatedTableName(table);var identity=new IndexMonthlyStorage(jdbc,table).preflight();return IndexMonthlyStorage.physicalTargetId(jdbc,table,identity);}

    /** Build one immutable code/month-window request. INCREMENTAL --from is accepted only for empty-target bootstrap. */
    public Plan plan(Mode requestedMode,String requestedCode,LocalDate from,LocalDate requestedThrough,LocalDate logicalDate)throws Exception {
        Objects.requireNonNull(logicalDate,"Frozen D022 logical date required");var index=resolve(requestedCode);
        LocalDate ceiling=completedMonthCeiling(logicalDate);
        if(requestedThrough!=null&&requestedThrough.isAfter(logicalDate))throw new IllegalArgumentException("D022 --to exceeds frozen logical date");
        LocalDate to=requestedThrough==null?ceiling:requestedThrough;
        if(to.isAfter(ceiling))throw new IllegalArgumentException("D022 --to includes an incomplete current month; month ceiling is "+ceiling);
        if(!isMonthEnd(to))throw new IllegalArgumentException("D022 --to must be the calendar month-end boundary for a complete monthly slice");
        var definition=IndexMonthlySyncJobOwner.DEFINITION;Mode mode=requestedMode==null?definition.defaultMode():requestedMode;
        if(!definition.supportedModes().contains(mode))throw new IllegalArgumentException("Unsupported D022 sync mode");
        if((mode==Mode.BACKFILL||mode==Mode.RECONCILE)&&(from==null||requestedThrough==null))throw new IllegalArgumentException("Bounded D022 BACKFILL/RECONCILE requires explicit --from and --to");
        if(from!=null&&!isMonthStart(from))throw new IllegalArgumentException("D022 --from must be the first calendar day of a complete month");
        if(from!=null&&from.isAfter(to))throw new IllegalArgumentException("D022 --from is after completed --to");
        var publisher=new IndexMonthlyPublication(jdbc,ledgerPath);publisher.requireNoPendingPublication();
        String target=targetId(),physicalTargetId=physicalTargetId();var port=new IndexMonthlyWritePort(table,physicalTargetId,jdbc,questdb);port.preflight();
        var physical=port.readExistingRange(index.providerCode());var saved=IndexMonthlyCoverage.checkpoint(ledgerPath,target,index.providerCode());
        IndexMonthlyCoverage.validateExistingTarget(ledgerPath,target,index.providerCode(),port);
        LocalDate resolvedFrom=from,anchor=null,before=null;boolean bootstrap=false;
        if(mode==Mode.INCREMENTAL) {
            if(saved.isEmpty()) {
                if(from==null)throw new IllegalArgumentException("Explicit bounded D022 --from bootstrap required without verified checkpoint");
                if(physical.min()!=null)throw new IllegalStateException("Nonempty D022 code target lacks receipt-backed incremental checkpoint");
                resolvedFrom=from;anchor=from;bootstrap=true;
            } else {
                if(from!=null)throw new IllegalArgumentException("D022 --from is bootstrap-only; use BACKFILL for explicit second window");
                var checkpoint=saved.get();before=checkpoint.through();anchor=checkpoint.anchor();
                if(to.isBefore(before))throw new IllegalArgumentException("D022 end precedes receipt-backed checkpoint");
                resolvedFrom=YearMonth.from(before).minusMonths(2).atDay(1);if(resolvedFrom.isBefore(anchor))resolvedFrom=anchor;
                if(physical.max()!=null&&physical.max().isAfter(ceiling))throw new IllegalStateException("D022 target contains a partial current-month observation beyond the completed-month ceiling");
            }
        } else if(mode==Mode.BACKFILL||mode==Mode.RECONCILE)resolvedFrom=Objects.requireNonNull(from);
        else throw new IllegalArgumentException("D022 supports only bounded INCREMENTAL, BACKFILL and RECONCILE");
        if(!isMonthStart(resolvedFrom))throw new IllegalArgumentException("Resolved D022 start is not a calendar month boundary");
        long days=ChronoUnit.DAYS.between(resolvedFrom,to)+1;if(days<1||days>definition.budget().maxWindowDays())throw new IllegalArgumentException("Resolved D022 window exceeds 3660-day/10-year bound");
        if(mode==Mode.INCREMENTAL&&physical.max()!=null&&physical.max().isAfter(to))throw new IllegalStateException("D022 incremental end precedes existing physical data for this code");
        String observedAt=Instant.now().truncatedTo(ChronoUnit.MICROS).toString();var parameters=new LinkedHashMap<String,Object>();
        parameters.put("targetId",target);parameters.put("physicalTargetId",physicalTargetId);parameters.put("tsCode",index.providerCode());parameters.put("observedAt",observedAt);
        if(mode==Mode.INCREMENTAL){parameters.put("checkpointAnchor",anchor);if(before!=null)parameters.put("checkpointBefore",before);}
        if(physical.min()!=null)parameters.put("targetMinBefore",physical.min());if(physical.max()!=null)parameters.put("targetMaxBefore",physical.max());
        var request=jobs.prepare(definition.jobId(),definition.version(),mode,parameters,resolvedFrom,to,logicalDate);
        adapter(port).preflight(request);if(!target.equals(targetId())||!physicalTargetId.equals(physicalTargetId()))throw new IllegalStateException("D022 isolated target identity changed while planning");
        return new Plan(request,target,physicalTargetId,index.providerCode(),before,anchor,physical,bootstrap);
    }
    public SyncJobRunner.Result run(Plan plan)throws Exception{return execute("index-monthly-"+UUID.randomUUID(),null,null,plan);}
    public SyncJobRunner.Result resume(String priorRunId)throws Exception {
        var restored=FrozenRunRequest.restore(ledgerPath,priorRunId,IndexMonthlySyncJobOwner.DEFINITION);var request=restored.request();var p=request.parameters();
        if(new IndexMonthlyPublication(jdbc,ledgerPath).findForRun(priorRunId).isPresent())
            throw new IllegalStateException("D022 unresolved publication must be finished before a source resume");
        if(IndexMonthlyStaging.hasStageIntent(ledgerPath.getParent().resolve("sync-evidence").resolve(priorRunId)))
            throw new IllegalStateException("D022 stage-only artifact must be reconciled by finishInterrupted before a source resume");
        var physical=new IndexMonthlyWritePort.TargetRange((LocalDate)p.get("targetMinBefore"),(LocalDate)p.get("targetMaxBefore"));
        return resume(new Plan(request,restored.targetId(),(String)p.get("physicalTargetId"),(String)p.get("tsCode"),(LocalDate)p.get("checkpointBefore"),(LocalDate)p.get("checkpointAnchor"),physical,!p.containsKey("checkpointBefore")),priorRunId);
    }
    public SyncJobRunner.Result resume(Plan plan,String priorRunId)throws Exception{return execute("index-monthly-"+UUID.randomUUID(),null,Objects.requireNonNull(priorRunId),plan);}
    public IndexMonthlyPublication.Result finishInterrupted(String runId,boolean writerStopped)throws Exception {
        if(!writerStopped)throw new IllegalStateException("D022 stopped-writer proof required before recovery");
        var publisher=new IndexMonthlyPublication(jdbc,ledgerPath);
        if(publisher.findForRun(runId).isPresent()) {
            var result=publisher.finish(runId,true);
            completeRecoveredLedger(runId,result);
            return result;
        }
        return finishStageOnly(runId,publisher);
    }
    private IndexMonthlyPublication.Result finishStageOnly(String runId,IndexMonthlyPublication publisher)throws Exception {
        var ledger=new SyncRunLedger(ledgerPath);var run=ledger.getRun(runId);var runEntry=ledger.get(runId);
        if(!Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT).contains(runEntry.state()))
            throw new IllegalStateException("D022 stage-only recovery requires a stopped, nonterminal RUNNING/IN_DOUBT run");
        if(!IndexMonthlySyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=IndexMonthlySyncJobOwner.DEFINITION.version())
            throw new IllegalStateException("D022 stage-only recovery run definition differs");
        var restored=restoreRecoverableRequest(run);
        String logicalTarget=run.targetId();
        String physicalTarget=(String)restored.parameters().get("physicalTargetId");
        if(!logicalTarget.equals(restored.parameters().get("targetId"))
                ||!logicalTarget.equals(targetId())||!physicalTarget.equals(physicalTargetId()))
            throw new IllegalStateException("D022 isolated logical/physical target generation changed before stage recovery");

        var children=allEntries(ledger,runId);
        var attempts=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).toList();
        var slices=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        if(attempts.size()!=1||slices.size()!=1)
            throw new IllegalStateException("D022 stage-only recovery requires exactly one attempt and one fetched slice");
        requireRecoverable(attempts.getFirst(),Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT,SyncRunState.ACKNOWLEDGED));
        var slice=slices.getFirst();
        requireRecoverable(slice,Set.of(SyncRunState.RUNNING,SyncRunState.FETCHED,SyncRunState.VALIDATED,
                SyncRunState.SUBMITTED,SyncRunState.ACKNOWLEDGED,SyncRunState.IN_DOUBT));
        var fetchedEvents=allEvents(ledger,slice.id()).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();
        if(fetchedEvents.size()!=1)throw new IllegalStateException("D022 stage-only recovery requires exactly one immutable FETCHED source event");
        var fetchedProof=JobDefinitionJson.mapper().readTree(fetchedEvents.getFirst().payloadJson());
        String sourceReceipt=requiredText(fetchedProof,"responseEvidence");
        String sourceFingerprint=requiredText(fetchedProof,"sourceFingerprint");
        String code=requiredText(fetchedProof,"cursor");
        int sourceRows=fetchedProof.path("returnedRows").asInt(-1);
        if(!code.equals(restored.parameters().get("tsCode"))||sourceRows<1
                ||sourceRows>IndexMonthlySource.CLIENT_ROW_CAP||!sourceFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("D022 FETCHED event differs from bounded frozen source scope");
        Instant observed=Instant.parse((String)restored.parameters().get("observedAt"));
        var page=IndexMonthlySource.reopen(Path.of(sourceReceipt),sourceFingerprint,code,restored.from(),restored.to(),observed);
        if(page.rows().size()!=sourceRows)throw new IllegalStateException("D022 FETCHED event row count differs from raw source receipt");
        var fetched=new SyncJobRunner.Page<>(page.rows(),page.sourceFingerprint(),page.responseEvidence(),page.cursor());
        Path evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);

        // Keep the same whole-dataset OS lock across frozen evidence checks, full stage comparison and both renames.
        try(var operation=publisher.beginStageRecoveryOperation(runId)) {
            var recovered=new IndexMonthlyStaging(jdbc).recover(table,logicalTarget,physicalTarget,runId,
                    restored,fetched,evidence.resolve("stage"));
            var result=publisher.publish(operation,runId,logicalTarget,physicalTarget,table,
                    recovered.prepared(),recovered.verified(),()->false);
            completeRecoveredLedger(runId,result);
            return result;
        }
    }
    private SyncJobDefinition.FrozenRequest restoreRecoverableRequest(SyncRunLedger.Run run)throws Exception {
        var json=JobDefinitionJson.mapper();var saved=json.readTree(run.frozenJson());
        var definition=json.treeToValue(saved.path("definition"),SyncJobDefinition.class);
        if(!definition.equals(IndexMonthlySyncJobOwner.DEFINITION))throw new IllegalStateException("D022 frozen definition changed before stage recovery");
        Map<String,Object> parameters=json.convertValue(saved.path("parameters"),new TypeReference<LinkedHashMap<String,Object>>(){});
        for(var spec:definition.parameters().entrySet())if(parameters.containsKey(spec.getKey())
                &&spec.getValue().type()==SyncJobDefinition.ParameterType.DATE)
            parameters.put(spec.getKey(),LocalDate.parse(saved.path("parameters").path(spec.getKey()).asText()));
        LocalDate from=saved.path("from").isNull()?null:LocalDate.parse(saved.path("from").asText());
        LocalDate to=saved.path("to").isNull()?null:LocalDate.parse(saved.path("to").asText());
        var mode=Mode.valueOf(saved.path("mode").asText());
        var logicalDate=LocalDate.parse(saved.path("logicalDate").asText());
        var request=definition.freeze(mode,parameters,from,to,logicalDate);
        if(!SyncRequestIdentity.fingerprint(request,run.targetId()).equals(SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId())))
            throw new IllegalStateException("D022 restored stage-only request differs from durable frozen identity");
        return request;
    }
    private static List<SyncRunLedger.Entry> allEntries(SyncRunLedger ledger,String runId)throws Exception {
        var result=new ArrayList<SyncRunLedger.Entry>();String after=null;
        while(true){var page=ledger.entries(runId,after,100);result.addAll(page);if(result.size()>1000)throw new IllegalStateException("D022 recovery ledger entry bound exceeded");
            if(page.size()<100)return List.copyOf(result);after=page.getLast().id();}
    }
    private static List<SyncRunLedger.Event> allEvents(SyncRunLedger ledger,String entryId)throws Exception {
        var result=new ArrayList<SyncRunLedger.Event>();long after=-1;
        while(true){var page=ledger.events(entryId,after,100);result.addAll(page);if(result.size()>1000)throw new IllegalStateException("D022 recovery event bound exceeded");
            if(page.size()<100)return List.copyOf(result);after=page.getLast().revision();}
    }
    private static void requireRecoverable(SyncRunLedger.Entry entry,Set<SyncRunState> allowed) {
        if(!allowed.contains(entry.state())||entry.state().terminal())
            throw new IllegalStateException("D022 stage-only recovery refuses ledger entry "+entry.id()+" in "+entry.state());
    }
    private static String requiredText(com.fasterxml.jackson.databind.JsonNode node,String field) {
        var value=node.path(field);if(!value.isTextual()||value.asText().isBlank())throw new IllegalStateException("D022 FETCHED source event lacks "+field);return value.asText();
    }
    private void completeRecoveredLedger(String runId,IndexMonthlyPublication.Result result)throws Exception {
        var ledger=new SyncRunLedger(ledgerPath);var run=ledger.getRun(runId);var runEntry=ledger.get(runId);
        if(!IndexMonthlySyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=IndexMonthlySyncJobOwner.DEFINITION.version())
            throw new IllegalStateException("D022 recovery run definition differs");
        var journal=result.entry().intent();var scope=JobDefinitionJson.mapper().readTree(journal.scope());
        var source=JobDefinitionJson.mapper().readTree(java.nio.file.Path.of(scope.path("stageReceipt").asText()).toFile());
        var request=restoreRecoverableRequest(run);
        int rows=source.path("sourceRows").asInt(-1);String fingerprint=source.path("sourceFingerprint").asText();
        if(rows<1||!fingerprint.matches("[0-9a-f]{64}")||result.layout()!=IndexMonthlyPublication.Layout.PUBLISHED)
            throw new IllegalStateException("D022 recovered publication lacks a complete nonempty source proof");
        var children=new ArrayList<SyncRunLedger.Entry>();String after=null;while(true){var page=ledger.entries(runId,after,100);
            for(var child:page)children.add(child);if(page.size()<100)break;after=page.getLast().id();}
        var slices=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        var attempts=children.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).toList();
        if(slices.size()!=1||attempts.size()!=1)throw new IllegalStateException("D022 recovery requires one slice and one attempt");
        var verification=Map.of("passed",true,"writerStopped",true,"expectedRows",rows,"actualRows",rows,
                "matchedRows",rows,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                "sourceFingerprint",fingerprint,"readbackEvidence","publication:"+journal.id()+";fullSnapshot:"+result.target().fingerprint());
        var proof=Map.of("sourceComplete",true,"returnedRows",rows,"submittedRows",rows,
                "responseEvidence",scope.path("sourceReceipt").asText(),"publicationId",journal.id(),"verification",verification);
        String payload=JobDefinitionJson.mapper().writeValueAsString(proof);
        for(var entry:List.of(slices.getFirst(),attempts.getFirst(),runEntry))completeRecoveredEntry(ledger,entry,payload);
        var locks=new DatasetIntervalLock(ledgerPath);var lease=locks.findOwned(runId,new DatasetIntervalLock.Scope(
                request.definition().datasetId(),request.from(),request.to()));
        if(lease!=null){if(!lease.inDoubt()){locks.retainInDoubt(lease);lease=locks.findOwned(runId,lease.scope());}
            locks.releaseAfterReconciliation(lease,true,true);}
    }
    private static void completeRecoveredEntry(SyncRunLedger ledger,SyncRunLedger.Entry initial,String proof)throws Exception {
        var current=ledger.get(initial.id());
        if(current.state()==SyncRunState.VERIFIED)return;
        if(current.state()==SyncRunState.SUBMITTED){ledger.transition(current.id(),current.revision(),SyncRunState.IN_DOUBT,"{\"publicationRecovery\":true}");current=ledger.get(current.id());}
        if(current.state()==SyncRunState.FETCHED) {
            ledger.transition(current.id(),current.revision(),SyncRunState.VALIDATED,"{\"publicationRecovery\":true}");
            current=ledger.get(current.id());
        }
        if(!Set.of(SyncRunState.RUNNING,SyncRunState.VALIDATED,SyncRunState.IN_DOUBT,SyncRunState.ACKNOWLEDGED).contains(current.state()))
            throw new IllegalStateException("D022 run ledger entry cannot be reconciled from "+current.state());
        ledger.transition(current.id(),current.revision(),SyncRunState.VERIFIED,proof);
    }
    public SyncJobRunner.Result runAsGroupChild(String childId,String parentId,String expectedTarget,SyncJobDefinition.FrozenRequest request)throws Exception {
        if(!targetId().equals(expectedTarget)||!expectedTarget.equals(request.parameters().get("targetId")))throw new IllegalStateException("D022 group target changed");
        var p=request.parameters();var physical=new IndexMonthlyWritePort.TargetRange((LocalDate)p.get("targetMinBefore"),(LocalDate)p.get("targetMaxBefore"));
        if(!Objects.equals(expectedTarget,p.get("targetId")))throw new IllegalStateException("D022 group logical target differs from frozen request");
        return execute(childId,parentId,null,new Plan(request,expectedTarget,(String)p.get("physicalTargetId"),(String)p.get("tsCode"),(LocalDate)p.get("checkpointBefore"),(LocalDate)p.get("checkpointAnchor"),physical,!p.containsKey("checkpointBefore")));
    }
    private SyncJobRunner.Result execute(String runId,String parentRunId,String priorRunId,Plan plan)throws Exception {
        if(!plan.request().definition().equals(IndexMonthlySyncJobOwner.DEFINITION)||!plan.targetId().equals(plan.request().parameters().get("targetId"))
                ||!plan.physicalTargetId().equals(plan.request().parameters().get("physicalTargetId"))||!plan.targetId().equals(targetId())
                ||!plan.physicalTargetId().equals(physicalTargetId()))
            throw new IllegalStateException("Frozen D022 plan or isolated target identity changed before run");
        var ledger=new SyncRunLedger(ledgerPath);var port=new IndexMonthlyWritePort(table,plan.physicalTargetId(),jdbc,questdb);var evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var runner=new SyncJobRunner<IndexMonthly,IndexMonthlyKey>(ledger,new DatasetIntervalLock(ledgerPath));
        java.util.function.BooleanSupplier cancelled=()->{if(Thread.currentThread().isInterrupted())return true;try{return ledger.cancellationRequested(runId);}catch(java.sql.SQLException failure){throw new IllegalStateException("Cannot read D022 cancellation state",failure);}};
        var adapter=new IndexMonthlySyncAdapter(new IndexMonthlySource(pages,new IndexMonthlyMapper(),evidence.resolve("source")),
                port,evidence,runId,ledgerPath,table,plan.targetId(),jdbc);
        return priorRunId==null?runner.run(runId,parentRunId,plan.targetId(),plan.request(),adapter,cancelled)
                :runner.resume(runId,parentRunId,priorRunId,plan.targetId(),plan.request(),adapter,cancelled);
    }
    private IndexMonthlySyncAdapter adapter(IndexMonthlyWritePort port){return adapter(port,ledgerPath.getParent().resolve("sync-evidence").resolve("d022-plan-source"),ledgerPath.getParent());}
    private IndexMonthlySyncAdapter adapter(IndexMonthlyWritePort port,Path sourceEvidence,Path completionEvidence){return new IndexMonthlySyncAdapter(new IndexMonthlySource(pages,new IndexMonthlyMapper(),sourceEvidence),port,completionEvidence);}
    private static IndexMonthlyUniverse.Index resolve(String code){var value=IndexMonthlyUniverse.resolveProvider(code);if(value==null)value=IndexMonthlyUniverse.resolveCanonical(code);if(value==null)throw new IllegalArgumentException("D022 code must belong to the frozen monthly-enabled Python universe");return value;}
    public static LocalDate completedMonthCeiling(LocalDate logicalDate){return YearMonth.from(Objects.requireNonNull(logicalDate)).minusMonths(1).atEndOfMonth();}
    private static boolean isMonthStart(LocalDate date){return date.getDayOfMonth()==1;}
    private static boolean isMonthEnd(LocalDate date){return date.equals(YearMonth.from(date).atEndOfMonth());}
}
