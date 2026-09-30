package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** Buffers complete source-date receipts, writes once to a DEDUP=false stage, then publishes atomically by journal. */
public final class DcIndexSyncAdapter implements SyncJobRunner.Adapter<DcIndex,DcIndexKey> {
    private final DcIndexSource source;private final DcIndexTradingDates calendar;private final DcIndexWritePort originalPort;
    private volatile VerifiedBatchExecutor.Port<DcIndex,DcIndexKey> port;
    private final Path evidenceRoot,ledgerPath;private final String table,runId,logicalTargetId,frozenPhysicalTargetId;private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public DcIndexSyncAdapter(TusharePageService pages,DcIndexTradingDates calendar,DcIndexWritePort port,Path evidenceRoot,
            Path ledgerPath,String table,String runId,String logicalTargetId,String frozenPhysicalTargetId,org.springframework.jdbc.core.JdbcTemplate jdbc){
        this.source=new DcIndexSource(pages,evidenceRoot.resolve("source"));this.calendar=Objects.requireNonNull(calendar);
        this.originalPort=Objects.requireNonNull(port);this.port=port;this.evidenceRoot=evidenceRoot.toAbsolutePath().normalize();this.ledgerPath=ledgerPath.toAbsolutePath().normalize();
        DatasetDefinition.identifier(table);this.table=table;this.runId=Objects.requireNonNull(runId);this.logicalTargetId=Objects.requireNonNull(logicalTargetId);
        this.frozenPhysicalTargetId=Objects.requireNonNull(frozenPhysicalTargetId);this.jdbc=Objects.requireNonNull(jdbc);
    }
    public static void validateRequest(SyncJobDefinition.FrozenRequest request){
        if(request==null||!request.definition().equals(DcIndexSyncJobOwner.DEFINITION)||!request.definition().datasetId().equals("dc_index")
                ||request.definition().datasetVersion()!=DcIndexDataset.DEFINITION.schemaVersion()||!Set.of(Mode.INCREMENTAL,Mode.BACKFILL).contains(request.mode())
                ||request.from()==null||request.to()==null||request.to().isAfter(request.logicalDate()))throw new IllegalArgumentException("Frozen bounded D023 request required");
        var p=request.parameters();Set<String> allowed=Set.of("targetId","physicalTargetId","trade_dates","checkpointAnchor","checkpointBefore","targetMinBefore","targetMaxBefore");
        if(!allowed.containsAll(p.keySet())||!p.keySet().containsAll(Set.of("targetId","physicalTargetId","trade_dates")))throw new IllegalArgumentException("Unexpected/missing D023 frozen parameters");
        for(String name:List.of("targetId","physicalTargetId"))if(!(p.get(name) instanceof String v)||!v.matches("static-v2-[0-9a-f]{64}"))throw new IllegalArgumentException("Frozen D023 "+name+" required");
        long days=ChronoUnit.DAYS.between(request.from(),request.to())+1;if(days<1||days>DcIndexSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("D023 window exceeds five calendar days");
        for(String field:List.of("checkpointAnchor","checkpointBefore","targetMinBefore","targetMaxBefore"))if(p.get(field)!=null&&!(p.get(field) instanceof LocalDate))throw new IllegalArgumentException("Invalid frozen D023 date "+field);
        LocalDate anchor=(LocalDate)p.get("checkpointAnchor"),before=(LocalDate)p.get("checkpointBefore");
        if(request.mode()==Mode.INCREMENTAL){if(anchor==null)throw new IllegalArgumentException("Incremental D023 request requires checkpoint anchor");
            if(before==null&&!request.from().equals(anchor))throw new IllegalArgumentException("D023 bootstrap anchor must be explicit source start");
            if(before!=null){LocalDate overlap=before.minusDays(DcIndexSyncJobOwner.REVISION_DAYS);if(overlap.isBefore(anchor))overlap=anchor;
                if(!request.from().equals(overlap)||before.isBefore(anchor)||before.isAfter(request.to()))throw new IllegalArgumentException("D023 incremental must re-read a bounded two-day overlap, clamped to its verified anchor");}
        }else if(anchor==null||before==null||before.isBefore(anchor)||request.from().isBefore(anchor)||request.to().isAfter(before))
            throw new IllegalArgumentException("D023 BACKFILL requires an existing checkpoint and must stay inside its verified range");
        Object dates=p.get("trade_dates");if(!(dates instanceof String encoded)||encoded.isBlank())throw new IllegalArgumentException("Frozen D023 trading-date sequence required");
        if(encoded.length()>128)throw new IllegalArgumentException("D023 date sequence exceeds bound");
    }
    public static List<LocalDate> decodeDates(SyncJobDefinition.FrozenRequest request){
        Object value=request.parameters().get("trade_dates");if(!(value instanceof String encoded)||encoded.equals("NONE")||encoded.isBlank())throw new IllegalArgumentException("D023 request must include at least one open trade date");
        var dates=Arrays.stream(encoded.split(",",-1)).map(s->{if(!s.matches("[0-9]{8}"))throw new IllegalArgumentException("D023 dates require YYYYMMDD");return LocalDate.parse(s,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);}).toList();
        if(dates.size()>DcIndexSyncJobOwner.MAX_WINDOW_DAYS||dates.stream().distinct().count()!=dates.size()||!dates.equals(dates.stream().sorted().toList())
                ||dates.stream().anyMatch(d->d.isBefore(request.from())||d.isAfter(request.to())))throw new IllegalArgumentException("D023 trade dates are duplicate, unordered or outside frozen window");return dates;
    }
    public static String encodeDates(List<LocalDate> dates){if(dates==null||dates.isEmpty()||dates.size()>5||dates.stream().distinct().count()!=dates.size()||!dates.equals(dates.stream().sorted().toList()))throw new IllegalArgumentException("D023 requires bounded unique ascending open dates");
        return String.join(",",dates.stream().map(d->d.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)).toList());}
    @Override public void preflight(SyncJobDefinition.FrozenRequest request){validateRequest(request);var dates=decodeDates(request);
        if(!dates.equals(calendar.read(request.from(),request.to())))throw new IllegalStateException("D023 exchange calendar changed after plan freeze");
        if(!frozenPhysicalTargetId.equals(DcIndexStorage.physicalTargetId(jdbc,table,new DcIndexStorage(jdbc,table).preflight())))throw new IllegalStateException("D023 physical target generation differs from the authorized execution generation");
        originalPort.preflight();}
    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,SyncJobRunner.PageConsumer<DcIndex> consumer,BooleanSupplier cancelled)throws Exception{
        var dates=decodeDates(request);var buffered=new ArrayList<SyncJobRunner.Page<DcIndex>>();var all=new ArrayList<DcIndex>();
        var fingerprints=new ArrayList<String>();int sourceRows=0;
        var publication=new DcIndexPublication(jdbc,ledgerPath,evidenceRoot,table,logicalTargetId,runId);
        try(var lock=publication.acquire()){
            var before=new DcIndexStorage(jdbc,table).snapshot();String physicalBefore=DcIndexStorage.physicalTargetId(jdbc,table,before.identity());
            if(!frozenPhysicalTargetId.equals(physicalBefore))throw new IllegalStateException("D023 target changed before daily source reads");
            var openDates=new HashSet<>(dates);for(var existing:before.rows())if(!existing.tradeDate().isBefore(request.from())&&!existing.tradeDate().isAfter(request.to())&&!openDates.contains(existing.tradeDate()))
                throw new IllegalStateException("D023 target has rows on a closed calendar date with no authoritative dc_index source receipt");
            for(LocalDate day:dates){check(cancelled);var page=source.fetch(day,cancelled);buffered.add(page);all.addAll(page.rows());sourceRows=Math.addExact(sourceRows,page.rows().size());fingerprints.add(page.sourceFingerprint());}
            if(buffered.size()!=dates.size()||sourceRows>DcIndexSyncJobOwner.DEFINITION.budget().maxRows())throw new IllegalStateException("D023 incomplete/overbudget daily source collection");
            var current=new DcIndexStorage(jdbc,table).snapshot();if(!before.equals(current))throw new IllegalStateException("D023 logical target changed during source collection");
            var prepared=DcIndexStaging.prepare(before,current,all,request.from(),request.to());
            var stage=new DcIndexStaging(jdbc,table).create(prepared,evidenceRoot.resolve("staging"),cancelled,runId,request,logicalTargetId,frozenPhysicalTargetId);
            String stageId=DcIndexStorage.physicalTargetId(jdbc,stage.table(),stage.outsideSnapshot().identity());
            this.port=originalPort.forTarget(stage.table(),stageId);
            for(var page:buffered){check(cancelled);consumer.accept(page);}
            String combined=combine(fingerprints);var completeStage=new DcIndexStaging(jdbc,table).verifyComplete(prepared,stage,combined,evidenceRoot.resolve("staging"),cancelled);
            Path complete=evidenceRoot.resolve("complete-window.json");
            var body=new LinkedHashMap<String,Object>();body.put("dataset","dc_index");body.put("endpoint","dc_index");body.put("mode",request.mode().name());
            body.put("fromInclusive",request.from().toString());body.put("toInclusive",request.to().toString());body.put("tradeDates",dates);
            body.put("completedDateSlices",buffered.size());body.put("sourceRows",sourceRows);body.put("returnedRows",sourceRows);body.put("submittedRows",sourceRows);
            body.put("sourceFingerprint",combined);body.put("sourceReceipts",buffered.stream().map(SyncJobRunner.Page::responseEvidence).toList());
            body.put("stage",completeStage.table());body.put("stageReceipt",completeStage.receipt());body.put("snapshotProof",DcIndexStaging.snapshotProof(completeStage.snapshot()));body.put("dedup",false);body.put("sourceComplete",true);body.put("complete",true);
            JobDefinitionJson.mapper().writeValue(complete.toFile(),body);
            try{var proof=publication.publishWindow(before,completeStage,all,request.from(),request.to(),frozenPhysicalTargetId,combined,complete.toString(),cancelled);
                JobDefinitionJson.mapper().writeValue(evidenceRoot.resolve("publication-window.json").toFile(),proof);
            }catch(Exception failure){try{markPublicationUncertain(request,buffered,combined,complete,failure);}catch(Exception proofFailure){failure.addSuppressed(proofFailure);}throw failure;}
            return new SyncJobRunner.SourceCompletion(buffered.size(),sourceRows,true,complete.toString());
        }
    }
    private void markPublicationUncertain(SyncJobDefinition.FrozenRequest request,List<SyncJobRunner.Page<DcIndex>> pages,
            String sourceFingerprint,Path complete,Exception cause)throws Exception{
        var journal=new ReferencePublicationJournal(ledgerPath,"dc_index");var found=journal.findForRun(runId);if(found.isEmpty()||found.get().state()==ReferencePublicationJournal.State.VERIFIED)return;
        if(!found.get().intent().initialTarget().equals(logicalTargetId)||!found.get().intent().target().equals(table))throw new IllegalStateException("D023 publication journal is outside frozen target");
        var ledger=new SyncRunLedger(ledgerPath);var entries=new ArrayList<SyncRunLedger.Entry>();String cursor=null;
        while(true){var page=ledger.entries(runId,cursor,1000);entries.addAll(page);if(page.size()<1000)break;cursor=page.getLast().id();}
        var attempts=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).toList();if(attempts.size()!=1)throw new IllegalStateException("D023 unresolved publication requires one run attempt");
        var byReceipt=new HashMap<String,SyncRunLedger.Entry>();var fetchedById=new HashMap<String,JsonNode>();
        for(var entry:entries)if(entry.kind()==SyncRunLedger.Kind.SLICE){var ev=ledger.events(entry.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();
            if(ev.size()!=1)throw new IllegalStateException("D023 slice lacks one fetch evidence event");JsonNode node=JobDefinitionJson.mapper().readTree(ev.getFirst().payloadJson());
            String receipt=node.path("responseEvidence").asText();if(receipt.isBlank()||byReceipt.putIfAbsent(receipt,entry)!=null)throw new IllegalStateException("D023 duplicate/missing slice receipt");fetchedById.put(entry.id(),node);}
        if(byReceipt.size()!=pages.size())throw new IllegalStateException("D023 publication recovery has incomplete slice receipts");
        for(var page:pages){var slice=byReceipt.get(page.responseEvidence());if(slice==null||!page.sourceFingerprint().equals(fetchedById.get(slice.id()).path("sourceFingerprint").asText()))throw new IllegalStateException("D023 slice differs from source page");
            if(page.rows().isEmpty()&&slice.state()==SyncRunState.FETCHED){var proof=Map.of("sourceComplete",true,"returnedRows",0,"submittedRows",0,"responseEvidence",page.responseEvidence(),
                    "verification",Map.of("passed",true,"writerStopped",true,"expectedRows",0,"actualRows",0,"matchedRows",0,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,"readbackEvidence",complete.toString(),"sourceFingerprint",page.sourceFingerprint()));
                ledger.transition(slice.id(),slice.revision(),SyncRunState.VERIFIED_EMPTY,JobDefinitionJson.mapper().writeValueAsString(proof));}
            else if(!page.rows().isEmpty()&&slice.state()!=SyncRunState.VERIFIED)throw new IllegalStateException("D023 nonempty stage slice was not verified before publication");}
        int count=pages.stream().mapToInt(p->p.rows().size()).sum();var proof=Map.of("sourceComplete",true,"returnedRows",count,"submittedRows",count,"responseEvidence",complete.toString(),
                "publicationId",found.get().intent().id(),"failure",cause.getClass().getSimpleName(),"verification",Map.of("passed",true,"writerStopped",true,
                        "expectedRows",count,"actualRows",count,"matchedRows",count,"mismatchedRows",0,"duplicateKeys",0,"missingKeys",0,
                        "readbackEvidence",complete.toString(),"sourceFingerprint",sourceFingerprint));String text=JobDefinitionJson.mapper().writeValueAsString(proof);
        var run=ledger.get(runId);if(run.state()==SyncRunState.RUNNING)ledger.transition(runId,run.revision(),SyncRunState.IN_DOUBT,text);else if(run.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("D023 run is no longer active");
        var attempt=ledger.get(attempts.getFirst().id());if(attempt.state()==SyncRunState.RUNNING)ledger.transition(attempt.id(),attempt.revision(),SyncRunState.IN_DOUBT,text);else if(attempt.state()!=SyncRunState.IN_DOUBT)throw new IllegalStateException("D023 attempt is no longer active");
        var locks=new DatasetIntervalLock(ledgerPath);var lease=locks.findOwned(runId,new DatasetIntervalLock.Scope("dc_index",request.from(),request.to()));if(lease!=null&&!lease.inDoubt())locks.retainInDoubt(lease);
    }
    private static String combine(List<String> parts)throws Exception{var d=MessageDigest.getInstance("SHA-256");for(String part:parts){d.update(part.getBytes(java.nio.charset.StandardCharsets.UTF_8));d.update((byte)0);}return HexFormat.of().formatHex(d.digest());}
    private static void check(BooleanSupplier cancel){if(cancel.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("D023 date slice cancelled");}
    @Override public VerifiedBatchExecutor.Codec<DcIndex,DcIndexKey> codec(){return DcIndexWritePort.CODEC;}
    @Override public VerifiedBatchExecutor.Port<DcIndex,DcIndexKey> port(){return port;}
    /** Keep a stopped run/attempt in doubt if any run-owned D023 stage or publication may need reconciliation. */
    @Override public boolean recoveryRequired(String candidateRunId)throws Exception{
        if(!runId.equals(candidateRunId))throw new IllegalStateException("D023 recovery check run identity changed");
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var state=ledger.get(candidateRunId).state();
        if(state.terminal())return false;
        if(new ReferencePublicationJournal(ledgerPath,"dc_index").findForRun(candidateRunId).isPresent())return true;
        return DcIndexStaging.hasStageIntent(evidenceRoot.resolve("staging"));
    }
}
