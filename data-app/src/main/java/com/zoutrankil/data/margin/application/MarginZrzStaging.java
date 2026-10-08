package com.zoutrankil.data.margin.application;

import com.zoutrankil.data.margin.port.MarginZrzStagingPort;

import com.zoutrankil.data.margin.domain.MarginZrzState;
import com.zoutrankil.data.margin.domain.MarginZrzState.*;
import com.zoutrankil.data.margin.domain.MarginZrzRows;
import com.zoutrankil.data.margin.port.MarginZrzTables;

import com.zoutrankil.data.repository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginZrz;
import com.zoutrankil.data.domain.MarginZrzKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.margin.application.MarginZrzSource;
import com.zoutrankil.data.margin.application.MarginZrzSyncJobOwner;
import com.zoutrankil.data.service.SyncJobRunner;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Builds a same-layout non-DEDUP full-table snapshot with one authoritative date window replaced. */
public final class MarginZrzStaging {


    private static final int MAX_INTENT_BYTES=4*1024*1024;
    private final MarginZrzStagingPort tables;
    public MarginZrzStaging(MarginZrzStagingPort tables){this.tables=Objects.requireNonNull(tables);}

    public Prepared prepare(String target,String logicalTargetId,String physicalTargetId,String runId,
            SyncJobDefinition.FrozenRequest request,Path runEvidence,BooleanSupplier cancelled)throws Exception {
        com.zoutrankil.data.domain.MarginZrzDataset.requireIsolatedTable(target);
        Objects.requireNonNull(request);check(cancelled);Path root=runEvidence.toAbsolutePath().normalize();
        Files.createDirectories(root);if(Files.isSymbolicLink(root))throw new IOException("D031 evidence root cannot be symlinked");
        String requestFingerprint=SyncRequestIdentity.fingerprint(request,logicalTargetId);
        if(!request.definition().equals(MarginZrzSyncJobOwner.DEFINITION)
                ||!"margin_zrz".equals(request.definition().datasetId())
                ||!logicalTargetId.equals(request.parameters().get("targetId"))
                ||!physicalTargetId.equals(request.parameters().get("physicalTargetId"))
                ||request.from()==null||request.to()==null||request.from().isAfter(request.to())
                ||java.time.temporal.ChronoUnit.DAYS.between(request.from(),request.to())+1>MarginZrzSyncJobOwner.MAX_WINDOW_DAYS
                ||request.to().isAfter(request.logicalDate()))throw new IllegalArgumentException("D031 complete frozen bounded request required");
        var targetStorage=tables.open(target);var before=targetStorage.snapshot();
        if(!tables.physicalTargetId(target,before.identity()).equals(physicalTargetId))
            throw new IllegalStateException("D031 physical target changed before full-snapshot stage preparation");
        var outside=targetStorage.outside(request.from(),request.to());
        String stage=com.zoutrankil.data.domain.MarginZrzDataset.ISOLATED_PREFIX+"stage_"+UUID.randomUUID().toString().replace("-","");
        var stageIntent=root.resolve("stage").resolve(stage+"-intent.json");Files.createDirectories(stageIntent.getParent());
        if(Files.isSymbolicLink(stageIntent.getParent())||Files.exists(stageIntent,LinkOption.NOFOLLOW_LINKS))throw new IOException("D031 unique stage-intent path required");
        var body=new LinkedHashMap<String,Object>();body.put("dataset","margin_zrz");body.put("phase","PREPARING");
        body.put("runId",runId);body.put("target",target);body.put("logicalTargetId",logicalTargetId);
        body.put("physicalTargetBefore",physicalTargetId);body.put("stage",stage);body.put("requestFingerprint",requestFingerprint);
        body.put("mode",request.mode());body.put("logicalDate",request.logicalDate());body.put("windowFrom",request.from());
        body.put("windowTo",request.to());body.put("before",proof(before));body.put("preservedOutside",proof(outside));body.put("dedup",false);
        writeNew(stageIntent,body);
        int existing=tables.tableCount(stage);if(existing!=0)throw new IllegalStateException("D031 generated stage table already exists");
        String lower=request.from()+"T00:00:00.000000Z",upper=request.to().plusDays(1)+"T00:00:00.000000Z";
        tables.createOutsideStage(stage,target,lower,upper);awaitWal(stage,cancelled);
        var copied=tables.open(stage).snapshot();
        if(!MarginZrzRows.sameRows(outside.rows(),copied.rows()))throw new IllegalStateException("D031 stage did not preserve exact rows outside the authoritative window");
        String stageId=tables.physicalTargetId(stage,copied.identity());
        body.put("phase","READY");body.put("stagePhysicalTarget",stageId);body.put("stageOutside",proof(copied));replaceDurable(stageIntent,body);
        return new Prepared(target,stage,logicalTargetId,physicalTargetId,stageId,runId,requestFingerprint,
                request.from(),request.to(),before,outside,root);
    }

    /** Verify raw receipts and every physical stage row before publication is journaled. */
    public Verified verify(Prepared prepared,List<SyncJobRunner.Page<MarginZrz>> pages,
            BooleanSupplier cancelled)throws Exception {
        Objects.requireNonNull(prepared);Objects.requireNonNull(pages);check(cancelled);
        if(pages.size()!=MarginZrzSyncJobOwner.MAX_SOURCE_SLICES)throw new IllegalArgumentException("D031 exactly one complete bounded source range required");
        var expected=new ArrayList<MarginZrz>();var seen=new HashSet<MarginZrzKey>();var receiptRefs=new ArrayList<Map<String,Object>>();
        for(var page:pages){
            String expectedCursor=prepared.from().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)+".."+prepared.to().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            if(page==null||!expectedCursor.equals(page.cursor()))throw new IllegalArgumentException("D031 source receipt must identify the exact frozen inclusive date range");
            LocalDate from=prepared.from(),to=prepared.to();
            Path receipt=requireEvidenceFile(prepared.runEvidence(),page.responseEvidence());
            var reopened=MarginZrzSource.reopen(receipt,page.sourceFingerprint(),from,to);
            if(!MarginZrzRows.sameRows(reopened.rows(),page.rows()))throw new IllegalStateException("D031 typed slice differs from immutable raw receipt");
            for(var row:page.rows())if(row.tradeDate().isBefore(from)||row.tradeDate().isAfter(to)||!seen.add(row.key()))throw new IllegalStateException("D031 source has a row outside its range or duplicate natural key");
            expected.addAll(page.rows());receiptRefs.add(Map.of("fromInclusive",from,"toInclusive",to,"rows",page.rows().size(),
                    "sourceFingerprint",page.sourceFingerprint(),"responseEvidence",receipt.toString()));
        }
        check(cancelled);var targetNow=tables.open(prepared.target()).snapshot();
        if(!sameIdentityAndContent(targetNow,prepared.before())
                ||!tables.physicalTargetId(prepared.target(),targetNow.identity()).equals(prepared.physicalTargetBefore()))
            throw new IllegalStateException("D031 original target changed during staged source collection");
        var actual=tables.open(prepared.stage()).snapshot();
        if(!tables.physicalTargetId(prepared.stage(),actual.identity()).equals(prepared.stagePhysicalTarget()))
            throw new IllegalStateException("D031 staged physical generation changed");
        var outsideNow=tables.open(prepared.stage()).outside(prepared.from(),prepared.to());
        var windowNow=tables.open(prepared.stage()).window(prepared.from(),prepared.to());
        if(!MarginZrzRows.sameRows(prepared.outside().rows(),outsideNow.rows())
                ||!MarginZrzRows.sameRows(MarginZrzRows.ordered(expected),windowNow.rows()))
            throw new IllegalStateException("D031 full staged snapshot differs from preserved target plus authoritative source window");
        var body=new LinkedHashMap<String,Object>();body.put("dataset","margin_zrz");body.put("runId",prepared.runId());
        body.put("target",prepared.target());body.put("logicalTargetId",prepared.logicalTargetId());
        body.put("physicalTargetBefore",prepared.physicalTargetBefore());body.put("stage",prepared.stage());
        body.put("stagePhysicalTarget",prepared.stagePhysicalTarget());body.put("requestFingerprint",prepared.requestFingerprint());
        body.put("windowFrom",prepared.from());body.put("windowTo",prepared.to());body.put("dedup",false);
        body.put("before",proof(prepared.before()));body.put("preservedOutside",proof(outsideNow));body.put("authoritativeWindow",proof(windowNow));
        body.put("sourceRows",expected.size());body.put("sourceReceipts",receiptRefs);body.put("sourceComplete",true);
        body.put("stageAfter",proof(actual));
        Path receipt=prepared.runEvidence().resolve("stage").resolve(prepared.stage()+"-verified.json");writeNew(receipt,body);
        awaitWal(prepared.stage(),cancelled);
        return new Verified(prepared,actual,List.copyOf(expected),receiptRefs,receipt.toString());
    }

    public static boolean hasStageIntent(Path runEvidence)throws IOException {
        Path stage=runEvidence.toAbsolutePath().normalize().resolve("stage");
        if(!Files.exists(stage,LinkOption.NOFOLLOW_LINKS))return false;
        if(Files.isSymbolicLink(stage)||!Files.isDirectory(stage,LinkOption.NOFOLLOW_LINKS))throw new IOException("D031 stage evidence directory invalid");
        try(DirectoryStream<Path> files=Files.newDirectoryStream(stage,"*-intent.json")){for(Path file:files){
            if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_INTENT_BYTES)throw new IOException("D031 stage intent invalid");
            JsonNode intent=JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(file,MAX_INTENT_BYTES,()->new IOException("D031 stage intent invalid")));
            if("margin_zrz".equals(intent.path("dataset").asText())&&!"DISCARDED".equals(intent.path("phase").asText()))return true;
        }return false;}
    }

    /** Drop only an unpublished stage after a stopped writer and exact unchanged-target proof. */
    public static void discardUnpublished(MarginZrzTables tables,String target,String runId,Path runEvidence,Path ledgerPath,
            boolean writerStopped)throws Exception {
        if(!writerStopped)throw new IllegalStateException("D031 stopped-writer proof required before discarding a stage");
        Path stageRoot=runEvidence.toAbsolutePath().normalize().resolve("stage");
        if(Files.isSymbolicLink(stageRoot)||!Files.isDirectory(stageRoot,LinkOption.NOFOLLOW_LINKS))throw new IOException("D031 run stage root is absent or invalid");
        var intents=new ArrayList<Path>();try(DirectoryStream<Path> files=Files.newDirectoryStream(stageRoot,"*-intent.json")){for(Path path:files){if(intents.size()>0)throw new IOException("Multiple D031 stage intents require manual reconciliation");intents.add(path);}}
        if(intents.size()!=1)throw new IllegalStateException("D031 stage-only recovery requires exactly one intent");
        Path intentPath=intents.getFirst();if(Files.isSymbolicLink(intentPath)||!Files.isRegularFile(intentPath,LinkOption.NOFOLLOW_LINKS)||Files.size(intentPath)>MAX_INTENT_BYTES)throw new IOException("D031 stage intent is invalid");
        var json=JobDefinitionJson.mapper();JsonNode intent=json.readTree(FileEvidenceStore.readBounded(intentPath,MAX_INTENT_BYTES,()->new IOException("D031 stage intent is invalid")));
        if(!"margin_zrz".equals(intent.path("dataset").asText())||!runId.equals(intent.path("runId").asText())
                ||!target.equals(intent.path("target").asText()))throw new IllegalStateException("D031 stage intent belongs to another run/target");
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);
        if(!MarginZrzSyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=MarginZrzSyncJobOwner.DEFINITION.version()
                ||!run.targetId().equals(intent.path("logicalTargetId").asText())
                ||!intent.path("requestFingerprint").asText().equals(SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId())))
            throw new IllegalStateException("D031 stage intent is not bound to the durable frozen run request");
        JsonNode frozen=json.readTree(run.frozenJson());
        if(!frozen.path("from").asText().equals(intent.path("windowFrom").asText())
                ||!frozen.path("to").asText().equals(intent.path("windowTo").asText())
                ||!frozen.path("logicalDate").asText().equals(intent.path("logicalDate").asText())
                ||!frozen.path("mode").asText().equals(intent.path("mode").asText())
                ||!frozen.path("parameters").path("physicalTargetId").asText().equals(intent.path("physicalTargetBefore").asText()))
            throw new IllegalStateException("D031 stage request/date/mode/physical target differs from frozen request");
        if("DISCARDED".equals(intent.path("phase").asText()))return;
        if(!Set.of("PREPARING","READY").contains(intent.path("phase").asText()))throw new IllegalStateException("D031 stage intent phase is not discardable");
        JsonNode before=intent.path("before"),identity=before.path("identity");
        var current=tables.open(target).snapshot();
        String targetId=tables.physicalTargetId(target,current.identity());
        if(!identity.path("id").isIntegralNumber()||identity.path("id").longValue()!=current.identity().id()
                ||!identity.path("directory").asText().equals(current.identity().directory())
                ||!identity.path("writerTxn").isIntegralNumber()||identity.path("writerTxn").longValue()!=current.identity().writerTxn()
                ||before.path("rows").asInt(-1)!=current.rows().size()||!before.path("fingerprint").asText().equals(current.fingerprint())
                ||!targetId.equals(intent.path("physicalTargetBefore").asText()))
            throw new IllegalStateException("D031 original target changed; unpublished stage cannot be discarded as a safe restart");
        String stage=intent.path("stage").asText("");
        if(!stage.matches("java_d031_margin_zrz_stage_[0-9a-f]{32}"))throw new IllegalStateException("D031 stage name is outside its namespace");
        var discard=tables.newDiscardSession();int named=discard.namedStageCount(stage);
        if(named>1)throw new IllegalStateException("D031 stage name is ambiguous");
        if(named==1){
            var staged=tables.open(stage).snapshot();String stageId=tables.physicalTargetId(stage,staged.identity());
            String recorded=intent.path("stagePhysicalTarget").asText("");
            if(!recorded.isBlank()&&!recorded.equals(stageId))throw new IllegalStateException("D031 stage physical generation changed before discard");
            discard.dropStage(stage);
            if(discard.stageExists(stage))throw new IllegalStateException("D031 stage drop did not settle");
        }
        var body=json.convertValue(intent,LinkedHashMap.class);body.put("phase","DISCARDED");body.put("discardedWithStoppedWriter",true);
        replaceDurable(intentPath,body);
    }
    private static Path requireEvidenceFile(Path runRoot,String raw)throws IOException {
        Path root=runRoot.toAbsolutePath().normalize().toRealPath();Path candidate=Path.of(raw).toAbsolutePath().normalize();
        if(!candidate.startsWith(root)||Files.isSymbolicLink(candidate)||!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(candidate)<1||Files.size(candidate)>MarginZrzSource.MAX_EVIDENCE_BYTES)
            throw new IOException("D031 source receipt missing, symlinked, escaped or over size bound");
        Path resolved=candidate.toRealPath();if(!resolved.startsWith(root))throw new IOException("D031 source receipt escaped run evidence root");return resolved;
    }
    public static Map<String,Object> proof(MarginZrzState.Snapshot snapshot){return Map.of("identity",snapshot.identity(),"rows",snapshot.rows().size(),"fingerprint",snapshot.fingerprint(),"bytes",snapshot.bytes());}
    private static boolean sameIdentityAndContent(MarginZrzState.Snapshot a,MarginZrzState.Snapshot b){
        return MarginZrzRows.sameContent(a,b)&&a.identity().id()==b.identity().id()
                &&a.identity().directory().equals(b.identity().directory())&&a.identity().writerTxn()==b.identity().writerTxn();
    }
    private void awaitWal(String table,BooleanSupplier cancelled)throws Exception {tables.awaitWal(table,cancelled);}
    private static void writeNew(Path path,Object body)throws Exception {
        Files.createDirectories(path.getParent());byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(body);
        if(bytes.length<1||bytes.length>MAX_INTENT_BYTES)throw new IllegalArgumentException("D031 stage proof exceeds 4 MiB");
        FileEvidenceStore.writeNewDurable(path,bytes);
    }
    private static void replaceDurable(Path path,Object body)throws Exception {
        Files.createDirectories(path.getParent());byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(body);
        if(bytes.length<1||bytes.length>MAX_INTENT_BYTES)throw new IllegalArgumentException("D031 stage proof exceeds 4 MiB");
        try{FileEvidenceStore.replaceDurable(path,bytes);}
        catch(AtomicMoveNotSupportedException unsupported){throw new IOException("D031 READY intent requires atomic durable replace",unsupported);}
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("D031 stage operation cancelled; retain artifacts");}
}
