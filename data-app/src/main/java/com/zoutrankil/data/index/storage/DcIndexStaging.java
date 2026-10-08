package com.zoutrankil.data.index.storage;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.domain.DcIndexState.*;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Creates an exact-schema DEDUP=false stage containing only preserved out-of-window rows. */
public final class DcIndexStaging implements DcIndexStagingPort {
    private static final long MAX_INTENT_BYTES=16L*1024*1024;



    private final JdbcTemplate jdbc;private final String target;
    public DcIndexStaging(JdbcTemplate jdbc,String target){DatasetDefinition.identifier(target);this.target=target;this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());this.jdbc.setQueryTimeout(30);}
    public static Prepared prepare(DcIndexState.Snapshot before,DcIndexState.Snapshot current,List<DcIndex> rows,LocalDate from,LocalDate to)throws Exception{
        if(before==null||current==null||from==null||to==null||from.isAfter(to)||!before.equals(current))throw new IllegalArgumentException("Ordered frozen window and unchanged D023 physical target required");
        LocalDate exclusive=to.plusDays(1);var incoming=DcIndexStorage.sourceUnique(rows);
        for(var row:incoming)if(row.tradeDate().isBefore(from)||row.tradeDate().isAfter(to))throw new IllegalArgumentException("dc_index source escaped frozen replacement date window");
        var expected=new ArrayList<DcIndex>(DcIndexStorage.outside(before.rows(),from,exclusive));expected.addAll(incoming);
        if(expected.size()>DcIndexStorage.MAX_ROWS||DcIndexStorage.canonical(expected).length>DcIndexStorage.MAX_BYTES)throw new IllegalStateException("dc_index staged full snapshot exceeds bounded storage limit");
        return new Prepared(before,current,from,to,incoming,canonicalRows(expected));
    }
    public Verified create(Prepared prepared,Path evidenceFolder,BooleanSupplier cancelled,String runId,
            SyncJobDefinition.FrozenRequest request,String logicalTargetId,String physicalTargetId)throws Exception{
        if(runId==null||runId.isBlank()||request==null||logicalTargetId==null||physicalTargetId==null)throw new IllegalArgumentException("Frozen D023 run/target identity required for staging");
        check(cancelled);Files.createDirectories(evidenceFolder);String stage="java_dc_index_stage_"+UUID.randomUUID().toString().replace("-","");
        var beforeIdentity=prepared.before().identity();String actualBefore=DcIndexStorage.physicalTargetId(jdbc,target,beforeIdentity);
        if(!physicalTargetId.equals(actualBefore)||!logicalTargetId.matches("static-v2-[0-9a-f]{64}"))throw new IllegalStateException("D023 stage baseline differs from frozen physical/logical target");
        String requestFingerprint=SyncRequestIdentity.fingerprint(request,logicalTargetId);
        var json=JobDefinitionJson.mapper();Path intent=evidenceFolder.resolve(stage+"-intent.json");
        FileEvidenceStore.writeNew(intent,json.writeValueAsBytes(Map.ofEntries(
                Map.entry("dataset","dc_index"),Map.entry("target",target),Map.entry("stage",stage),Map.entry("runId",runId),
                Map.entry("logicalTargetId",logicalTargetId),Map.entry("physicalTargetBefore",actualBefore),
                Map.entry("beforeId",beforeIdentity.id()),Map.entry("beforeDirectory",beforeIdentity.directory()),
                Map.entry("requestFingerprint",requestFingerprint),Map.entry("mode",request.mode().name()),Map.entry("logicalDate",request.logicalDate().toString()),
                Map.entry("fromInclusive",prepared.fromInclusive().toString()),Map.entry("toInclusive",prepared.toInclusive().toString()),
                Map.entry("windowRule","authoritative closed calendar-date replacement; stage initially contains only rows outside window"),
                Map.entry("dedup",false),Map.entry("beforeFingerprint",prepared.before().fingerprint()),
                Map.entry("sourceRows",prepared.source().size()))));
        String columns=String.join(",",DcIndexDataset.DEFINITION.columns().stream().map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList());
        String create="CREATE TABLE \""+stage+"\" ("+columns+") TIMESTAMP(\"trade_date\") PARTITION BY YEAR WAL";
        jdbc.execute(create);awaitWal(stage,cancelled);new DcIndexStorage(jdbc,stage).preflight();
        var sourceColumns=String.join(",",DcIndexDataset.DEFINITION.storageColumns().stream().map(s->"\""+s+"\"").toList());
        LocalDate toExclusive=prepared.toInclusive().plusDays(1);
        String from=timestamp(prepared.fromInclusive()),to=timestamp(toExclusive);
        jdbc.execute("INSERT INTO \""+stage+"\" ("+sourceColumns+") SELECT "+sourceColumns+" FROM \""+target
                +"\" WHERE trade_date < cast('"+from+"' AS TIMESTAMP) OR trade_date >= cast('"+to+"' AS TIMESTAMP)");
        awaitWal(stage,cancelled);var actual=new DcIndexStorage(jdbc,stage).snapshot();
        var expectedOutside=DcIndexStorage.outside(prepared.before().rows(),prepared.fromInclusive(),toExclusive);
        if(!sameRows(expectedOutside,actual.rows()))throw new IllegalStateException("D023 DEDUP=false stage did not exactly preserve all out-of-window rows/multiplicities");
        Path receipt=evidenceFolder.resolve(stage+"-outside-verified.json");
        String stagePhysical=DcIndexStorage.physicalTargetId(jdbc,stage,actual.identity());
        String formalPhysical=DcIndexStorage.physicalTargetId(jdbc,target,actual.identity());
        FileEvidenceStore.writeNew(receipt,json.writeValueAsBytes(Map.ofEntries(Map.entry("proofVersion",2),Map.entry("dataset","dc_index"),
                Map.entry("target",target),Map.entry("stage",stage),Map.entry("dedup",false),
                Map.entry("stagePhysicalTarget",stagePhysical),Map.entry("physicalTargetAfter",formalPhysical),
                Map.entry("stageId",actual.identity().id()),Map.entry("stageDirectory",actual.identity().directory()),
                Map.entry("snapshotProof",snapshotProof(actual)),Map.entry("outsideRows",actual.rows().size()),
                Map.entry("windowFrom",prepared.fromInclusive().toString()),Map.entry("windowTo",prepared.toInclusive().toString()))));
        return new Verified(stage,actual,receipt.toString());
    }
    public Complete verifyComplete(Prepared prepared,Verified stage,String sourceFingerprint,Path evidenceFolder,BooleanSupplier cancelled)throws Exception{
        check(cancelled);var actual=new DcIndexStorage(jdbc,stage.table()).snapshot();
        if(!actual.identity().equals(stage.outsideSnapshot().identity()))throw new IllegalStateException("D023 stage physical identity changed after outside rows were verified");
        if(!sameRows(prepared.expected(),actual.rows()))throw new IllegalStateException("D023 DEDUP=false stage full snapshot differs from preserved outside plus authoritative source window");
        Path receipt=evidenceFolder.resolve(stage.table()+"-complete-"+sourceFingerprint+".json");
        var body=Map.ofEntries(Map.entry("dataset","dc_index"),Map.entry("target",target),Map.entry("stage",stage.table()),
                Map.entry("dedup",false),Map.entry("fromInclusive",prepared.fromInclusive().toString()),
                Map.entry("toInclusive",prepared.toInclusive().toString()),Map.entry("sourceFingerprint",sourceFingerprint),
                Map.entry("sourceRows",prepared.source().size()),Map.entry("proofVersion",2),
                Map.entry("stagePhysicalTarget",DcIndexStorage.physicalTargetId(jdbc,stage.table(),actual.identity())),
                Map.entry("physicalTargetAfter",DcIndexStorage.physicalTargetId(jdbc,target,actual.identity())),Map.entry("snapshotProof",snapshotProof(actual)),
                Map.entry("outsideRows",DcIndexStorage.outside(prepared.before().rows(),prepared.fromInclusive(),prepared.toInclusive().plusDays(1)).size()),
                Map.entry("complete",true));
        persist(receipt,JobDefinitionJson.mapper().writeValueAsBytes(body));
        return new Complete(stage.table(),actual,prepared.source().size(),sourceFingerprint,receipt.toString());
    }
    private static void persist(Path path,byte[] bytes)throws Exception{try{FileEvidenceStore.writeNew(path,bytes);}
        catch(FileAlreadyExistsException exists){var json=JobDefinitionJson.mapper();if(!json.readTree(Files.readAllBytes(path)).equals(json.readTree(bytes)))throw new IllegalStateException("Conflicting deterministic D023 stage receipt",exists);}}
    /** Detect run-local stage side effects so the runner retains its interval lease until reconciliation. */
    public static boolean hasStageIntent(Path stageFolder)throws java.io.IOException{
        Path folder=stageFolder.toAbsolutePath().normalize();if(!Files.exists(folder,LinkOption.NOFOLLOW_LINKS))return false;
        if(Files.isSymbolicLink(folder)||!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException("D023 stage evidence folder is not a regular directory");
        int found=0;try(DirectoryStream<Path> files=Files.newDirectoryStream(folder,"*-intent.json")){for(Path file:files){
            if(++found>1||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_INTENT_BYTES)
                throw new java.io.IOException("D023 stage intent set is ambiguous or outside its file bound");}}
        return found==1;
    }
    public static boolean sameRows(List<DcIndex> left,List<DcIndex> right)throws Exception{return Arrays.equals(DcIndexStorage.canonical(left),DcIndexStorage.canonical(right));}
    public static Map<String,Object> snapshotProof(DcIndexState.Snapshot s){return Map.of("id",s.identity().id(),"directory",s.identity().directory(),"fingerprint",s.fingerprint(),"rowCount",s.rows().size(),"bytes",s.bytes());}
    private static List<DcIndex> canonicalRows(List<DcIndex> rows){return rows.stream().sorted(Comparator.comparing(DcIndex::tradeDate).thenComparing(DcIndex::tsCode)
            .thenComparing(r->HexFormat.of().formatHex(DcIndexWritePort.CODEC.canonicalBytes(r)))).toList();}
    private static String timestamp(LocalDate day){return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC).format(day.atStartOfDay(ZoneOffset.UTC).toInstant());}
    private void awaitWal(String table,BooleanSupplier cancelled)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(!QuestDbWriteChecks.walSettled(jdbc,table)){check(cancelled);if(System.nanoTime()>deadline)throw new IllegalStateException("D023 WAL unresolved; keep stage and do not publish");Thread.sleep(50);}}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("D023 staging cancelled; stage is retained");}
}
