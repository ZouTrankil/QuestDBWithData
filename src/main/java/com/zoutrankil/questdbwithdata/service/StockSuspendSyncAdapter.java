package com.zoutrankil.questdbwithdata.service;
import com.zoutrankil.questdbwithdata.repository.StockSuspendStaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.StockSuspendStorage;
import com.zoutrankil.questdbwithdata.repository.StockSuspendWritePort;
import com.zoutrankil.questdbwithdata.repository.ReferencePublicationJournal;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Executes one suspend_d date request at a time and emits receipts for both empty and nonempty dates. */
public final class StockSuspendSyncAdapter implements SyncJobRunner.Adapter<StockSuspend,StockSuspendKey> {
    private final StockSuspendSource source;
    private final StockSuspendWritePort originalPort;
    private volatile VerifiedBatchExecutor.Port<StockSuspend,StockSuspendKey> port;
    private final Path evidenceRoot;
    private final Path ledgerPath;
    private final String table;
    private final String runId;
    private final String logicalTargetId;
    private final String physicalTargetId;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = JobDefinitionJson.mapper();

    public StockSuspendSyncAdapter(TusharePageService pages, StockSuspendWritePort port, Path evidenceRoot,
                                   Path ledgerPath,String table,String runId,String logicalTargetId,
                                   String physicalTargetId,JdbcTemplate jdbc) {
        this.source = new StockSuspendSource(pages, evidenceRoot.resolve("source"));
        this.originalPort = Objects.requireNonNull(port);this.port=port;
        this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();this.table=Objects.requireNonNull(table);
        this.runId=Objects.requireNonNull(runId);this.logicalTargetId=Objects.requireNonNull(logicalTargetId);
        this.physicalTargetId=Objects.requireNonNull(physicalTargetId);this.jdbc=Objects.requireNonNull(jdbc);
    }

    public static void validateRequest(FrozenRequest request) {
        if (request == null || !request.definition().equals(StockSuspendSyncJobOwner.DEFINITION)
                || !request.definition().datasetId().equals(StockSuspendDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion() != StockSuspendDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL).contains(request.mode())
                || request.from() == null || request.to() == null
                || request.parameters().keySet().stream().anyMatch(k -> !Set.of("targetId", "physicalTargetId", "checkpointAnchor",
                        "checkpointBefore", "targetMinBefore", "targetMaxBefore").contains(k)))
            throw new IllegalArgumentException("Frozen bounded stk_suspend request required");
        Object target = request.parameters().get("targetId");
        if (!(target instanceof String text) || !text.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen logical stk_suspend target identity required");
        Object physical = request.parameters().get("physicalTargetId");
        if(!(physical instanceof String id)||!id.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen physical stk_suspend generation identity required");
        long days = java.time.temporal.ChronoUnit.DAYS.between(request.from(), request.to()) + 1;
        if (days < 1 || days > StockSuspendSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("stk_suspend request exceeds its five-calendar-day source budget");
        for (String key : List.of("checkpointAnchor", "checkpointBefore", "targetMinBefore", "targetMaxBefore")) {
            Object value = request.parameters().get(key);
            if (value != null && !(value instanceof LocalDate))
                throw new IllegalArgumentException("Frozen stk_suspend planning date required: " + key);
        }
        LocalDate anchor = (LocalDate) request.parameters().get("checkpointAnchor");
        LocalDate before = (LocalDate) request.parameters().get("checkpointBefore");
        if (request.mode() == Mode.INCREMENTAL) {
            if (anchor == null) throw new IllegalArgumentException("Incremental stk_suspend requires a frozen checkpoint anchor");
            if (before == null) {
                if (!anchor.equals(request.from()))
                    throw new IllegalArgumentException("First incremental bootstrap anchor must equal its explicit start");
            } else if (before.isBefore(anchor) || !request.from().equals(before.minusDays(StockSuspendSyncJobOwner.REVISION_DAYS))
                    || before.isAfter(request.to())) {
                throw new IllegalArgumentException("Incremental overlap must re-read two days before the verified checkpoint");
            }
        } else if (anchor != null || before != null) {
            throw new IllegalArgumentException("BACKFILL cannot carry incremental checkpoint metadata");
        }
        LocalDate min = (LocalDate) request.parameters().get("targetMinBefore");
        LocalDate max = (LocalDate) request.parameters().get("targetMaxBefore");
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalArgumentException("Frozen target range must contain both ordered bounds");
        if (max != null && max.isAfter(request.logicalDate()))
            throw new IllegalArgumentException("Frozen target range exceeds logicalDate");
    }

    @Override public void preflight(FrozenRequest request) {
        validateRequest(request);
        originalPort.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<StockSuspend> consumer, BooleanSupplier cancelled) throws Exception {
        var receipts = new ArrayList<String>();var pageEvidence=new ArrayList<Path>();var sourceFingerprints=new ArrayList<String>();
        var bufferedPages=new ArrayList<SyncJobRunner.Page<StockSuspend>>();
        int[] totals = {0, 0};
        var allRows=new ArrayList<StockSuspend>();
        var publication=new StockSuspendPublication(jdbc,ledgerPath,evidenceRoot,table,logicalTargetId,runId);
        try(var publicationLock=publication.acquire()) {
        var before=new StockSuspendStorage(jdbc,table).snapshot();
        if(!physicalTargetId.equals(StockSuspendStorage.physicalTargetId(jdbc,table,before.identity())))
            throw new IllegalStateException("stk_suspend physical target differs from the frozen generation");
        for (LocalDate date = request.from(); !date.isAfter(request.to()); date = date.plusDays(1)) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("stk_suspend date slice cancelled");
            var result = source.fetch(new StockSuspendSource.Query(date), cancelled);
            Path pageReceipt=evidenceRoot.resolve("page-"+date+".json");
            var pageJson=json.createObjectNode();pageJson.put("evidenceType","stk_suspend_daily_source");
            pageJson.put("endpoint","suspend_d");pageJson.put("tradeDate",date.toString());
            pageJson.put("sourceFingerprint",result.fingerprint());pageJson.put("sourceReceipt",result.receipt());
            pageJson.put("sourceComplete",true);json.writeValue(pageReceipt.toFile(),pageJson);
            bufferedPages.add(new SyncJobRunner.Page<>(result.rows(), result.fingerprint(), pageReceipt.toString(), null));
            receipts.add(result.receipt()); totals[0]++;
            totals[1] = Math.addExact(totals[1], result.rows().size());
            allRows.addAll(result.rows());
            pageEvidence.add(pageReceipt);sourceFingerprints.add(result.fingerprint());
        }
        if (totals[0] != java.time.temporal.ChronoUnit.DAYS.between(request.from(), request.to()) + 1)
            throw new IllegalStateException("stk_suspend completion is missing a daily source slice");
        Files.createDirectories(evidenceRoot);
        String sourceFingerprint=combineFingerprints(sourceFingerprints);
        var current=new StockSuspendStorage(jdbc,table).snapshot();
        if(!before.equals(current))throw new IllegalStateException("stk_suspend logical target changed while source pages were being buffered");
        var prepared=StockSuspendStaging.prepare(before,current,allRows,request.from(),request.to());
        var stage=new StockSuspendStaging(jdbc,table).write(prepared,evidenceRoot.resolve("publication"),cancelled);
        String stagePhysical=StockSuspendStorage.physicalTargetId(jdbc,stage.table(),stage.snapshot().identity());
        this.port=originalPort.forTarget(stage.table(),stagePhysical);
        for(var page:bufferedPages){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("stk_suspend staged page verification cancelled");
            consumer.accept(page);
        }
        // The stable per-run path is frozen into the publication intent so a stopped
        // process can finish the complete evidence chain after a rename interruption.
        Path complete = evidenceRoot.resolve("complete-" + runId + ".json").normalize();
        if (!complete.startsWith(evidenceRoot)) throw new IllegalStateException("stk_suspend completion evidence escaped its run directory");
        StockSuspendPublication.WindowProof windowProof;
        try {
            windowProof=publication.publishWindow(before,stage,allRows,request.from(),request.to(),physicalTargetId,
                    sourceFingerprint,complete.toString(),cancelled);
        } catch (Exception publicationFailure) {
            try { markPublicationUncertain(request,stage,bufferedPages,sourceFingerprint,publicationFailure); }
            catch (Exception proofFailure) { publicationFailure.addSuppressed(proofFailure); }
            throw publicationFailure;
        }
        var verification=Map.of("passed",windowProof.passed(),"writerStopped",windowProof.writerStopped(),
                "expectedRows",windowProof.expectedRows(),"actualRows",windowProof.actualRows(),
                "matchedRows",windowProof.matchedRows(),"mismatchedRows",windowProof.mismatchedRows(),
                "duplicateKeys",windowProof.duplicateKeys(),"missingKeys",windowProof.missingKeys(),
                "readbackEvidence",windowProof.readbackEvidence(),"sourceFingerprint",windowProof.sourceFingerprint());
        var publicationEvidence=Map.of("logicalTargetId",windowProof.logicalTargetId(),
                "physicalTargetBefore",windowProof.physicalTargetBefore(),"physicalTargetAfter",windowProof.physicalTargetAfter(),
                "fromInclusive",windowProof.fromInclusive().toString(),"toInclusive",windowProof.toInclusive().toString(),
                "replacementPublished",windowProof.replacementPublished(),"fullTargetFingerprint",windowProof.fullTargetFingerprint(),
                "verification",verification);
        String responseEvidence=String.join(";",receipts);
        json.writeValue(complete.toFile(),Map.ofEntries(Map.entry("endpoint","suspend_d"),Map.entry("mode",request.mode().name()),
                Map.entry("fromInclusive",request.from().toString()),Map.entry("toInclusive",request.to().toString()),
                Map.entry("completedDateSlices",totals[0]),Map.entry("sourceRows",totals[1]),Map.entry("returnedRows",totals[1]),
                Map.entry("submittedRows",totals[1]),Map.entry("responseEvidence",responseEvidence),Map.entry("sliceReceipts",receipts),
                Map.entry("sourceComplete",true),Map.entry("complete",true),Map.entry("publication",publicationEvidence)));
        for(Path pageReceipt:pageEvidence) {
            var pageJson=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(pageReceipt.toFile());
            pageJson.put("completionEvidence",complete.toString());pageJson.set("publication",json.valueToTree(publicationEvidence));
            json.writeValue(pageReceipt.toFile(),pageJson);
        }
        return new SyncJobRunner.SourceCompletion(totals[0], totals[1], true, complete.toString());
        }
    }

    private static String combineFingerprints(List<String> values)throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        for(String value:values){digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);}
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** Preserve the live run and its interval lease when a durable replacement intent is unresolved. */
    private void markPublicationUncertain(FrozenRequest request,StockSuspendStaging.Verified stage,
            List<SyncJobRunner.Page<StockSuspend>> pages,String combinedFingerprint,
            Exception publicationFailure)throws Exception {
        var publications=new ReferencePublicationJournal(ledgerPath,"stk_suspend");
        var found=publications.findForRun(runId);
        if(found.isEmpty()||found.get().state()==ReferencePublicationJournal.State.VERIFIED)return;
        var intent=found.get().intent();
        var scope=json.readTree(intent.scope());
        if(!"stk_suspend".equals(intent.dataset())||!runId.equals(intent.runId())||!table.equals(intent.target())
                ||!logicalTargetId.equals(intent.initialTarget())
                ||!physicalTargetId.equals(scope.path("physicalTargetBefore").asText())
                ||!request.from().toString().equals(scope.path("fromInclusive").asText())
                ||!request.to().toString().equals(scope.path("toInclusive").asText())
                ||!stage.receipt().equals(scope.path("stageReceipt").asText())
                ||!combinedFingerprint.equals(scope.path("sourceFingerprint").asText()))
            throw new IllegalStateException("Unresolved D011 publication intent differs from the frozen stage/source scope");

        var ledger=new SyncRunLedger(ledgerPath);
        var entries=new ArrayList<SyncRunLedger.Entry>();String cursor=null;
        while(true){var page=ledger.entries(runId,cursor,1000);entries.addAll(page);if(page.size()<1000)break;cursor=page.getLast().id();}
        var attempts=entries.stream().filter(entry->entry.kind()==SyncRunLedger.Kind.ATTEMPT
                &&runId.equals(entry.parentId())&&runId.equals(entry.runId())).toList();
        if(attempts.size()!=1)throw new IllegalStateException("Uncertain D011 publication lacks one generic attempt");
        var byEvidence=new HashMap<String,SyncRunLedger.Entry>();
        var fetchedById=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        for(var entry:entries){
            if(entry.kind()!=SyncRunLedger.Kind.SLICE)continue;
            var events=ledger.events(entry.id(),-1,100).stream().filter(event->event.state()==SyncRunState.FETCHED).toList();
            if(events.size()!=1)throw new IllegalStateException("Uncertain D011 publication slice lacks one FETCHED receipt");
            var fetched=json.readTree(events.getFirst().payloadJson());
            if(!fetched.path("responseEvidence").isTextual()||!fetched.path("sourceFingerprint").isTextual()
                    ||fetched.path("returnedRows").asInt(-1)<0
                    ||byEvidence.putIfAbsent(fetched.path("responseEvidence").asText(),entry)!=null)
                throw new IllegalStateException("Uncertain D011 publication slice evidence is duplicate or incomplete");
            fetchedById.put(entry.id(),fetched);
        }
        if(byEvidence.size()!=pages.size())throw new IllegalStateException("Uncertain D011 publication is missing emitted page receipts");
        int sourceRows=0;
        for(var page:pages){
            var entry=byEvidence.get(page.responseEvidence());
            if(entry==null)throw new IllegalStateException("Uncertain D011 publication page lacks its FETCHED ledger entry");
            var fetched=fetchedById.get(entry.id());
            if(fetched.path("returnedRows").asInt(-1)!=page.rows().size()
                    ||!page.sourceFingerprint().equals(fetched.path("sourceFingerprint").asText()))
                throw new IllegalStateException("Uncertain D011 publication page differs from its immutable FETCHED event");
            sourceRows=Math.addExact(sourceRows,page.rows().size());
            if(page.rows().isEmpty()){
                var current=ledger.get(entry.id());
                if(current.state()==SyncRunState.FETCHED){
                    var strict=Map.of("sourceComplete",true,"returnedRows",0,"submittedRows",0,
                            "responseEvidence",page.responseEvidence(),"publicationId",intent.id(),
                            "verification",Map.of("writerStopped",true,"passed",true,"expectedRows",0,
                                    "actualRows",0,"matchedRows",0,"mismatchedRows",0,"duplicateKeys",0,
                                    "missingKeys",0,"readbackEvidence",stage.receipt(),
                                    "sourceFingerprint",page.sourceFingerprint()));
                    ledger.transition(entry.id(),current.revision(),SyncRunState.VERIFIED_EMPTY,
                            json.writeValueAsString(strict));
                }else if(current.state()!=SyncRunState.VERIFIED_EMPTY){
                    throw new IllegalStateException("Empty D011 source slice is not safely reconcilable");
                }
            }else if(entry.state()!=SyncRunState.VERIFIED){
                throw new IllegalStateException("Nonempty D011 stage slice was not already verified");
            }
        }
        var verification=Map.of("passed",true,"writerStopped",true,"expectedRows",sourceRows,
                "actualRows",sourceRows,"matchedRows",sourceRows,"mismatchedRows",0,
                "duplicateKeys",0,"missingKeys",0,"readbackEvidence",stage.receipt(),
                "sourceFingerprint",combinedFingerprint);
        var runProof=new LinkedHashMap<String,Object>();runProof.put("sourceComplete",true);
        runProof.put("returnedRows",sourceRows);runProof.put("submittedRows",sourceRows);
        runProof.put("responseEvidence",stage.receipt());runProof.put("verification",verification);
        runProof.put("publicationId",intent.id());runProof.put("publicationFailure",publicationFailure.getClass().getSimpleName());
        String proofJson=json.writeValueAsString(runProof);
        var run=ledger.get(runId);
        if(run.state()==SyncRunState.RUNNING)ledger.transition(runId,run.revision(),SyncRunState.IN_DOUBT,proofJson);
        else if(run.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("D011 run is no longer active during publication recovery");
        var attempt=ledger.get(attempts.getFirst().id());
        if(attempt.state()==SyncRunState.RUNNING)ledger.transition(attempt.id(),attempt.revision(),SyncRunState.IN_DOUBT,proofJson);
        else if(attempt.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("D011 attempt is no longer active during publication recovery");
    }


    @Override public VerifiedBatchExecutor.Codec<StockSuspend,StockSuspendKey> codec() { return StockSuspendWritePort.CODEC; }
    @Override public VerifiedBatchExecutor.Port<StockSuspend,StockSuspendKey> port() { return port; }
}
