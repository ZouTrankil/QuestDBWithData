package com.zoutrankil.questdbwithdata.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.MarginAll;
import com.zoutrankil.questdbwithdata.domain.MarginAllKey;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity;
import com.zoutrankil.questdbwithdata.service.MarginAllSource;
import com.zoutrankil.questdbwithdata.service.MarginAllSyncJobOwner;
import com.zoutrankil.questdbwithdata.service.SyncJobRunner;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Builds a same-layout non-DEDUP full-table snapshot with one authoritative date window replaced. */
public final class MarginAllStaging {
    public record Prepared(String target,String stage,String logicalTargetId,String physicalTargetBefore,
            String stagePhysicalTarget,String runId,String requestFingerprint,LocalDate from,LocalDate to,
            MarginAllStorage.Snapshot before,MarginAllStorage.Snapshot outside,Path runEvidence) {}
    public record Verified(Prepared prepared,MarginAllStorage.Snapshot snapshot,
            List<MarginAll> authoritativeRows,List<Map<String,Object>> sourceReceipts,String receipt) {
        public Verified { authoritativeRows=List.copyOf(authoritativeRows);sourceReceipts=List.copyOf(sourceReceipts); }
    }
    private static final int MAX_INTENT_BYTES=4*1024*1024;
    private final JdbcTemplate jdbc;
    public MarginAllStaging(JdbcTemplate jdbc){this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(120);}

    public Prepared prepare(String target,String logicalTargetId,String physicalTargetId,String runId,
            SyncJobDefinition.FrozenRequest request,Path runEvidence,BooleanSupplier cancelled)throws Exception {
        com.zoutrankil.questdbwithdata.domain.MarginAllDataset.requireIsolatedTable(target);
        Objects.requireNonNull(request);check(cancelled);Path root=runEvidence.toAbsolutePath().normalize();
        Files.createDirectories(root);if(Files.isSymbolicLink(root))throw new IOException("D028 evidence root cannot be symlinked");
        String requestFingerprint=SyncRequestIdentity.fingerprint(request,logicalTargetId);
        if(!request.definition().equals(MarginAllSyncJobOwner.DEFINITION)
                ||!"margin_all".equals(request.definition().datasetId())
                ||!logicalTargetId.equals(request.parameters().get("targetId"))
                ||!physicalTargetId.equals(request.parameters().get("physicalTargetId"))
                ||request.from()==null||request.to()==null||request.from().isAfter(request.to())
                ||java.time.temporal.ChronoUnit.DAYS.between(request.from(),request.to())+1>MarginAllSyncJobOwner.MAX_WINDOW_DAYS
                ||request.to().isAfter(request.logicalDate()))throw new IllegalArgumentException("D028 complete frozen bounded request required");
        var targetStorage=new MarginAllStorage(jdbc,target);var before=targetStorage.snapshot();
        if(!MarginAllStorage.physicalTargetId(jdbc,target,before.identity()).equals(physicalTargetId))
            throw new IllegalStateException("D028 physical target changed before full-snapshot stage preparation");
        var outside=targetStorage.outside(request.from(),request.to());
        String stage=com.zoutrankil.questdbwithdata.domain.MarginAllDataset.ISOLATED_PREFIX+"stage_"+UUID.randomUUID().toString().replace("-","");
        var stageIntent=root.resolve("stage").resolve(stage+"-intent.json");Files.createDirectories(stageIntent.getParent());
        if(Files.isSymbolicLink(stageIntent.getParent())||Files.exists(stageIntent,LinkOption.NOFOLLOW_LINKS))throw new IOException("D028 unique stage-intent path required");
        var body=new LinkedHashMap<String,Object>();body.put("dataset","margin_all");body.put("phase","PREPARING");
        body.put("runId",runId);body.put("target",target);body.put("logicalTargetId",logicalTargetId);
        body.put("physicalTargetBefore",physicalTargetId);body.put("stage",stage);body.put("requestFingerprint",requestFingerprint);
        body.put("mode",request.mode());body.put("logicalDate",request.logicalDate());body.put("windowFrom",request.from());
        body.put("windowTo",request.to());body.put("before",proof(before));body.put("preservedOutside",proof(outside));body.put("dedup",false);
        writeNew(stageIntent,body);
        var existing=jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",stage);if(!existing.isEmpty())throw new IllegalStateException("D028 generated stage table already exists");
        String lower=request.from()+"T00:00:00.000000Z",upper=request.to().plusDays(1)+"T00:00:00.000000Z";
        String sql="CREATE TABLE \""+stage+"\" AS (SELECT trade_date,exchange_id,rzye,rzmre,rzche,rqye,rqmcl,rzrqye,rqyl FROM \""+target
                +"\" WHERE trade_date<cast('"+lower+"' AS TIMESTAMP) OR trade_date>=cast('"+upper+"' AS TIMESTAMP)) TIMESTAMP(trade_date) PARTITION BY YEAR WAL";
        jdbc.execute(sql);awaitWal(stage,cancelled);
        var copied=new MarginAllStorage(jdbc,stage).snapshot();
        if(!MarginAllStorage.sameRows(outside.rows(),copied.rows()))throw new IllegalStateException("D028 stage did not preserve exact rows outside the authoritative window");
        String stageId=MarginAllStorage.physicalTargetId(jdbc,stage,copied.identity());
        body.put("phase","READY");body.put("stagePhysicalTarget",stageId);body.put("stageOutside",proof(copied));replaceDurable(stageIntent,body);
        return new Prepared(target,stage,logicalTargetId,physicalTargetId,stageId,runId,requestFingerprint,
                request.from(),request.to(),before,outside,root);
    }

    /** Verify raw receipts and every physical stage row before publication is journaled. */
    public Verified verify(Prepared prepared,List<SyncJobRunner.Page<MarginAll>> pages,
            BooleanSupplier cancelled)throws Exception {
        Objects.requireNonNull(prepared);Objects.requireNonNull(pages);check(cancelled);
        if(pages.isEmpty()||pages.size()>MarginAllSyncJobOwner.MAX_SOURCE_SLICES)throw new IllegalArgumentException("D028 complete bounded source slice list required");
        var expected=new ArrayList<MarginAll>();var seen=new HashSet<MarginAllKey>();var receiptRefs=new ArrayList<Map<String,Object>>();
        LocalDate next=prepared.from();
        for(var page:pages){
            if(page==null||page.cursor()==null||!page.cursor().matches("[0-9]{8}"))throw new IllegalArgumentException("D028 source slice must identify exactly one YYYYMMDD date");
            LocalDate from=LocalDate.parse(page.cursor(),java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            LocalDate to=from;
            if(!from.equals(next)||to.isAfter(prepared.to()))
                throw new IllegalStateException("D028 source receipts are not a contiguous bounded cover of the frozen window");
            Path receipt=requireEvidenceFile(prepared.runEvidence(),page.responseEvidence());
            var reopened=MarginAllSource.reopen(receipt,page.sourceFingerprint(),from);
            if(!MarginAllStorage.sameRows(reopened.rows(),page.rows()))throw new IllegalStateException("D028 typed slice differs from immutable raw receipt");
            for(var row:page.rows())if(!row.tradeDate().equals(from)||!seen.add(row.key()))throw new IllegalStateException("D028 source has a row outside its date or duplicate natural key across slices");
            expected.addAll(page.rows());receiptRefs.add(Map.of("fromInclusive",from,"toInclusive",to,"rows",page.rows().size(),
                    "sourceFingerprint",page.sourceFingerprint(),"responseEvidence",receipt.toString()));
            next=to.plusDays(1);
        }
        if(!next.equals(prepared.to().plusDays(1)))throw new IllegalStateException("D028 source slices do not cover complete frozen request");
        check(cancelled);var targetNow=new MarginAllStorage(jdbc,prepared.target()).snapshot();
        if(!sameIdentityAndContent(targetNow,prepared.before())
                ||!MarginAllStorage.physicalTargetId(jdbc,prepared.target(),targetNow.identity()).equals(prepared.physicalTargetBefore()))
            throw new IllegalStateException("D028 original target changed during staged source collection");
        var actual=new MarginAllStorage(jdbc,prepared.stage()).snapshot();
        if(!MarginAllStorage.physicalTargetId(jdbc,prepared.stage(),actual.identity()).equals(prepared.stagePhysicalTarget()))
            throw new IllegalStateException("D028 staged physical generation changed");
        var outsideNow=new MarginAllStorage(jdbc,prepared.stage()).outside(prepared.from(),prepared.to());
        var windowNow=new MarginAllStorage(jdbc,prepared.stage()).window(prepared.from(),prepared.to());
        if(!MarginAllStorage.sameRows(prepared.outside().rows(),outsideNow.rows())
                ||!MarginAllStorage.sameRows(MarginAllStorage.ordered(expected),windowNow.rows()))
            throw new IllegalStateException("D028 full staged snapshot differs from preserved target plus authoritative source window");
        var body=new LinkedHashMap<String,Object>();body.put("dataset","margin_all");body.put("runId",prepared.runId());
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
        if(Files.isSymbolicLink(stage)||!Files.isDirectory(stage,LinkOption.NOFOLLOW_LINKS))throw new IOException("D028 stage evidence directory invalid");
        try(DirectoryStream<Path> files=Files.newDirectoryStream(stage,"*-intent.json")){for(Path file:files){
            if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_INTENT_BYTES)throw new IOException("D028 stage intent invalid");
            JsonNode intent=JobDefinitionJson.mapper().readTree(Files.readAllBytes(file));
            if("margin_all".equals(intent.path("dataset").asText())&&!"DISCARDED".equals(intent.path("phase").asText()))return true;
        }return false;}
    }

    /** Drop only an unpublished stage after a stopped writer and exact unchanged-target proof. */
    public static void discardUnpublished(JdbcTemplate source,String target,String runId,Path runEvidence,Path ledgerPath,
            boolean writerStopped)throws Exception {
        if(!writerStopped)throw new IllegalStateException("D028 stopped-writer proof required before discarding a stage");
        Path stageRoot=runEvidence.toAbsolutePath().normalize().resolve("stage");
        if(Files.isSymbolicLink(stageRoot)||!Files.isDirectory(stageRoot,LinkOption.NOFOLLOW_LINKS))throw new IOException("D028 run stage root is absent or invalid");
        var intents=new ArrayList<Path>();try(DirectoryStream<Path> files=Files.newDirectoryStream(stageRoot,"*-intent.json")){for(Path path:files){if(intents.size()>0)throw new IOException("Multiple D028 stage intents require manual reconciliation");intents.add(path);}}
        if(intents.size()!=1)throw new IllegalStateException("D028 stage-only recovery requires exactly one intent");
        Path intentPath=intents.getFirst();if(Files.isSymbolicLink(intentPath)||!Files.isRegularFile(intentPath,LinkOption.NOFOLLOW_LINKS)||Files.size(intentPath)>MAX_INTENT_BYTES)throw new IOException("D028 stage intent is invalid");
        var json=JobDefinitionJson.mapper();JsonNode intent=json.readTree(Files.readAllBytes(intentPath));
        if(!"margin_all".equals(intent.path("dataset").asText())||!runId.equals(intent.path("runId").asText())
                ||!target.equals(intent.path("target").asText()))throw new IllegalStateException("D028 stage intent belongs to another run/target");
        var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);
        if(!MarginAllSyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=MarginAllSyncJobOwner.DEFINITION.version()
                ||!target.equals(intent.path("logicalTargetId").asText())
                ||!intent.path("requestFingerprint").asText().equals(SyncRequestIdentity.fingerprint(run.frozenJson(),run.targetId())))
            throw new IllegalStateException("D028 stage intent is not bound to the durable frozen run request");
        JsonNode frozen=json.readTree(run.frozenJson());
        if(!frozen.path("from").asText().equals(intent.path("windowFrom").asText())
                ||!frozen.path("to").asText().equals(intent.path("windowTo").asText())
                ||!frozen.path("logicalDate").asText().equals(intent.path("logicalDate").asText())
                ||!frozen.path("mode").asText().equals(intent.path("mode").asText())
                ||!frozen.path("parameters").path("physicalTargetId").asText().equals(intent.path("physicalTargetBefore").asText()))
            throw new IllegalStateException("D028 stage request/date/mode/physical target differs from frozen request");
        if("DISCARDED".equals(intent.path("phase").asText()))return;
        if(!Set.of("PREPARING","READY").contains(intent.path("phase").asText()))throw new IllegalStateException("D028 stage intent phase is not discardable");
        JsonNode before=intent.path("before"),identity=before.path("identity");
        var current=new MarginAllStorage(source,target).snapshot();
        String targetId=MarginAllStorage.physicalTargetId(source,target,current.identity());
        if(!identity.path("id").isIntegralNumber()||identity.path("id").longValue()!=current.identity().id()
                ||!identity.path("directory").asText().equals(current.identity().directory())
                ||!identity.path("writerTxn").isIntegralNumber()||identity.path("writerTxn").longValue()!=current.identity().writerTxn()
                ||before.path("rows").asInt(-1)!=current.rows().size()||!before.path("fingerprint").asText().equals(current.fingerprint())
                ||!targetId.equals(intent.path("physicalTargetBefore").asText()))
            throw new IllegalStateException("D028 original target changed; unpublished stage cannot be discarded as a safe restart");
        String stage=intent.path("stage").asText("");
        if(!stage.matches("java_d028_margin_all_stage_[0-9a-f]{32}"))throw new IllegalStateException("D028 stage name is outside its namespace");
        var jdbc=new JdbcTemplate(source.getDataSource());var named=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",stage);
        if(named.size()>1)throw new IllegalStateException("D028 stage name is ambiguous");
        if(named.size()==1){
            var staged=new MarginAllStorage(source,stage).snapshot();String stageId=MarginAllStorage.physicalTargetId(source,stage,staged.identity());
            String recorded=intent.path("stagePhysicalTarget").asText("");
            if(!recorded.isBlank()&&!recorded.equals(stageId))throw new IllegalStateException("D028 stage physical generation changed before discard");
            jdbc.execute("DROP TABLE \""+stage+"\"");
            if(!jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",stage).isEmpty())throw new IllegalStateException("D028 stage drop did not settle");
        }
        var body=json.convertValue(intent,LinkedHashMap.class);body.put("phase","DISCARDED");body.put("discardedWithStoppedWriter",true);
        replaceDurable(intentPath,body);
    }
    private static Path requireEvidenceFile(Path runRoot,String raw)throws IOException {
        Path root=runRoot.toAbsolutePath().normalize().toRealPath();Path candidate=Path.of(raw).toAbsolutePath().normalize();
        if(!candidate.startsWith(root)||Files.isSymbolicLink(candidate)||!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(candidate)<1||Files.size(candidate)>MarginAllSource.MAX_EVIDENCE_BYTES)
            throw new IOException("D028 source receipt missing, symlinked, escaped or over size bound");
        Path resolved=candidate.toRealPath();if(!resolved.startsWith(root))throw new IOException("D028 source receipt escaped run evidence root");return resolved;
    }
    public static Map<String,Object> proof(MarginAllStorage.Snapshot snapshot){return Map.of("identity",snapshot.identity(),"rows",snapshot.rows().size(),"fingerprint",snapshot.fingerprint(),"bytes",snapshot.bytes());}
    private static boolean sameIdentityAndContent(MarginAllStorage.Snapshot a,MarginAllStorage.Snapshot b){
        return MarginAllStorage.sameContent(a,b)&&a.identity().id()==b.identity().id()
                &&a.identity().directory().equals(b.identity().directory())&&a.identity().writerTxn()==b.identity().writerTxn();
    }
    private void awaitWal(String table,BooleanSupplier cancelled)throws Exception {
        long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();while(!QuestDbWriteChecks.walSettled(jdbc,table)){
            check(cancelled);if(System.nanoTime()>deadline)throw new IllegalStateException("D028 stage WAL unresolved; retain it for recovery");Thread.sleep(50);}
    }
    private static void writeNew(Path path,Object body)throws Exception {
        Files.createDirectories(path.getParent());byte[] bytes=JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(body);
        if(bytes.length<1||bytes.length>MAX_INTENT_BYTES)throw new IllegalArgumentException("D028 stage proof exceeds 4 MiB");
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
    }
    private static void replaceDurable(Path path,Object body)throws Exception {
        Path tmp=path.resolveSibling(path.getFileName()+".tmp-"+UUID.randomUUID());writeNew(tmp,body);
        try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){throw new IOException("D028 READY intent requires atomic durable replace",unsupported);}
        finally{Files.deleteIfExists(tmp);}
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("D028 stage operation cancelled; retain artifacts");}
}
