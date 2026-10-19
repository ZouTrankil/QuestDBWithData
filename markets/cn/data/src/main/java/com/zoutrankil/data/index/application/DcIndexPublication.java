package com.zoutrankil.data.index.application;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.domain.DcIndexState.*;

import com.zoutrankil.data.index.storage.DcIndexTargetTransitionStore;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;

import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

/** D023 finite-window table swap. Every stage and backup keeps YEAR/WAL/DEDUP=false schema. */
public final class DcIndexPublication {
    public enum Layout { ORIGINAL, OLD_MOVED, PUBLISHED, CONFLICT }
    public record Proof(String logicalTargetId,String physicalTargetBefore,String physicalTargetAfter,
            LocalDate fromInclusive,LocalDate toInclusive,int expectedRows,int actualRows,String sourceFingerprint,
            String fullTargetFingerprint,String evidence,boolean writerStopped,boolean passed){}
    private final DcIndexTables tables;private final Path ledgerPath,evidenceRoot;private final String table,logicalTargetId,runId;
    private final ReferencePublicationJournal journal;private final DatasetIntervalLock locks;
    private final DcIndexTargetTransitionStore transitions;
    public DcIndexPublication(DcIndexTables tables,Path ledgerPath,Path evidenceRoot,String table,String logicalTargetId,String runId)throws Exception{
        DatasetDefinition.identifier(table);this.tables=Objects.requireNonNull(tables);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();this.evidenceRoot=evidenceRoot.toAbsolutePath().normalize();
        this.table=table;this.logicalTargetId=Objects.requireNonNull(logicalTargetId);this.runId=Objects.requireNonNull(runId);
        this.journal=new ReferencePublicationJournal(this.ledgerPath,"dc_index");this.locks=new DatasetIntervalLock(this.ledgerPath);
        this.transitions=new DcIndexTargetTransitionStore(this.ledgerPath);transitions.initialize();
    }
    public Lock acquire()throws Exception{
        return acquire(null);
    }
    public Lock acquireForRecovery(String recoveringRun)throws Exception{
        if(recoveringRun==null||recoveringRun.isBlank())throw new IllegalArgumentException("Frozen D023 recovery run required");return acquire(recoveringRun);
    }
    private Lock acquire(String recoveringRun)throws Exception{
        Path lockPath=ledgerPath.resolveSibling(ledgerPath.getFileName()+".dc-index-publication.lock");Files.createDirectories(lockPath.getParent());
        FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try{FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){lock=null;}if(lock==null)throw new IllegalStateException("Another D023 table replacement is active");
            requireNoPendingPublication(ledgerPath,recoveringRun);verifyTargetLineage(logicalTargetId,currentPhysical(),recoveringRun);return new Lock(channel,lock);
        }catch(Exception e){channel.close();throw e;}}
    public record Lock(FileChannel channel,FileLock lock)implements AutoCloseable{@Override public void close()throws Exception{try{lock.release();}finally{channel.close();}}}

    public Proof publishWindow(DcIndexState.Snapshot before,DcIndexState.Complete stage,List<DcIndex> source,
            LocalDate from,LocalDate to,String expectedPhysical,String sourceFingerprint,String completionPath,
            BooleanSupplier cancelled)throws Exception{return publishWindow(before,stage,source,from,to,expectedPhysical,sourceFingerprint,completionPath,cancelled,false);}
    /** Recovery may publish only while holding this run's retained IN_DOUBT interval lease. */
    public Proof publishWindow(DcIndexState.Snapshot before,DcIndexState.Complete stage,List<DcIndex> source,
            LocalDate from,LocalDate to,String expectedPhysical,String sourceFingerprint,String completionPath,
            BooleanSupplier cancelled,boolean recovery)throws Exception{
        check(cancelled);if(!logicalTargetId.equals(tables.logicalTargetId(table)))throw new IllegalStateException("D023 logical target changed");
        var current=tables.open(table).snapshot();String actualPhysical=tables.physicalTargetId(table,current.identity());
        if(!before.equals(current)||!expectedPhysical.equals(actualPhysical))throw new IllegalStateException("D023 physical target changed after planning/source capture");
        if(!stage.table().startsWith("java_dc_index_stage_"))throw new IllegalArgumentException("D023 isolated stage required");
        Path complete=Path.of(completionPath).toAbsolutePath().normalize();requireEvidence(complete,"complete-window.json");
        JsonNode completeJson=JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(complete, DcIndexSource.MAX_EVIDENCE_BYTES, () -> new IllegalStateException("D023 recovery evidence outside run bound")));
        if(!completeJson.path("complete").asBoolean(false)||!sourceFingerprint.equals(completeJson.path("sourceFingerprint").asText())
                ||!stage.table().equals(completeJson.path("stage").asText())||!completeJson.path("dedup").isBoolean()||completeJson.path("dedup").asBoolean())
            throw new IllegalStateException("D023 completion proof differs from the DEDUP=false staged window");
        var stageNow=tables.open(stage.table()).snapshot();
        if(!stage.snapshot().equals(stageNow))throw new IllegalStateException("D023 stage physical identity/content changed before publication");
        var expectedSource=DcIndexRows.sourceUnique(source);var inWindow=stageNow.rows().stream().filter(r->!r.tradeDate().isBefore(from)&&!r.tradeDate().isAfter(to)).toList();
        if(!DcIndexRows.sameRows(expectedSource,inWindow))throw new IllegalStateException("D023 stage window does not match its source rows");
        var runLease=locks.findOwned(runId,new DatasetIntervalLock.Scope("dc_index",from,to));
        if(runLease==null||runLease.inDoubt()!=recovery)throw new IllegalStateException("D023 interval lease is not in the required active/recovery state before publication");
        String backup="java_dc_index_backup_"+UUID.randomUUID().toString().replace("-","");
        var scopeValues=new LinkedHashMap<String,Object>(Map.of("logicalTargetId",logicalTargetId,
                "physicalTargetBefore",expectedPhysical,"fromInclusive",from.toString(),"toInclusive",to.toString(),
                "sourceFingerprint",sourceFingerprint,"completeEvidence",complete.toString(),"completeEvidenceSha256",sha(FileEvidenceStore.readBounded(complete, DcIndexSource.MAX_EVIDENCE_BYTES, () -> new IllegalStateException("D023 recovery evidence outside run bound"))),
                "stageDirectory",stageNow.identity().directory(),"stagePhysicalTarget",tables.physicalTargetId(stage.table(),stageNow.identity()),"dedup",false));
        scopeValues.put("proofVersion",2);var intentScope=JobDefinitionJson.mapper().writeValueAsString(scopeValues);
        var intent=new ReferencePublicationJournal.Intent("dc-index-publication-"+UUID.randomUUID(),"dc_index",runId,
                table,backup,stage.table(),logicalTargetId,current.identity().id(),current.identity().directory(),stageNow.identity().id(),
                current.fingerprint(),stageNow.fingerprint(),intentScope);
        var entry=journal.create(intent);
        try{check(cancelled);requireLease(from,to,recovery);rename(table,backup);entry=journal.advance(entry,State.OLD_MOVED);
            check(cancelled);requireLease(from,to,recovery);rename(stage.table(),table);entry=journal.advance(entry,State.PUBLISHED);
            var after=verifyPublished(entry);recordTransition(entry,expectedPhysical,tables.physicalTargetId(table,after.identity()),from,to);
            return new Proof(logicalTargetId,expectedPhysical,tables.physicalTargetId(table,after.identity()),from,to,
                    expectedSource.size(),inWindow.size(),sourceFingerprint,after.fingerprint(),complete.toString(),true,true);
        }catch(Exception failure){markInDoubt(runId,failure);throw new IllegalStateException("D023 publication is uncertain; recover journal before another run",failure);}
    }
    public Layout inspect(String publicationRun)throws Exception{
        var intent=journal.forRun(publicationRun).intent();if(!intent.dataset().equals("dc_index")||!intent.target().equals(table))throw new IllegalStateException("D023 journal identity mismatch");
        var target=optional(intent.target());var backup=optional(intent.backup());var stage=optional(intent.stage());
        if(matches(target,intent.originalId(),intent.originalDirectory(),intent.beforeFingerprint())&&backup==null
                &&matches(stage,intent.replacementId(),null,intent.afterFingerprint()))return Layout.ORIGINAL;
        if(target==null&&matches(backup,intent.originalId(),intent.originalDirectory(),intent.beforeFingerprint())
                &&matches(stage,intent.replacementId(),null,intent.afterFingerprint()))return Layout.OLD_MOVED;
        if(matches(target,intent.replacementId(),null,intent.afterFingerprint())
                &&matches(backup,intent.originalId(),intent.originalDirectory(),intent.beforeFingerprint())&&stage==null)return Layout.PUBLISHED;
        return Layout.CONFLICT;
    }
    /** Restart-safe finish of an already verified stage publication intent. */
    public void finish(String publicationRun,boolean writerStopped)throws Exception{
        if(!writerStopped)throw new IllegalStateException("Stopped D023 writer proof required");try(var lock=acquireRecoveryLock()){
            requireNoOtherPending(publicationRun);
            var entry=journal.forRun(publicationRun);var intent=entry.intent();var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(publicationRun);
            if(!run.jobId().equals("data.dc_index")||!run.targetId().equals(intent.initialTarget())||!intent.initialTarget().matches("static-v2-[0-9a-f]{64}"))
                throw new IllegalStateException("D023 publication intent does not bind its saved run/logical target");
            var scope=JobDefinitionJson.mapper().readTree(intent.scope());validateCompletionEvidence(scope,intent);
            Layout layout=inspect(publicationRun);if(layout==Layout.CONFLICT)throw new IllegalStateException("Conflicting D023 target/backup/stage layout; automatic rename refused");
            // Reopen every frozen daily source receipt and compare the still-staged (or already-published)
            // full snapshot before the first recovery rename. A hashed completion manifest alone is not
            // enough to authorize a delayed publication if its individual source receipts have disappeared.
            verifyRecoveryInputs(scope,intent,layout);
            if(entry.state()==State.VERIFIED){if(layout!=Layout.PUBLISHED)throw new IllegalStateException("Verified D023 publication drifted");var after=verifyPublished(entry);
                String physical=tables.physicalTargetId(table,after.identity());var scopeJson=JobDefinitionJson.mapper().readTree(intent.scope());
                ensureTransition(entry,requiredText(scopeJson,"physicalTargetBefore"),physical,LocalDate.parse(scopeJson.path("fromInclusive").asText()),LocalDate.parse(scopeJson.path("toInclusive").asText()));
                setTransitionVerified(publicationRun);return;}
            if(entry.state()!=State.IN_DOUBT)entry=journal.advance(entry,State.IN_DOUBT);entry=journal.advance(entry,State.RESUMING);
            if(layout==Layout.ORIGINAL){rename(intent.target(),intent.backup());rename(intent.stage(),intent.target());}
            else if(layout==Layout.OLD_MOVED)rename(intent.stage(),intent.target());
            entry=journal.advance(entry,State.PUBLISHED);var after=verifyPublished(entry);
            LocalDate from=LocalDate.parse(scope.path("fromInclusive").asText()),to=LocalDate.parse(scope.path("toInclusive").asText());
            String physical=tables.physicalTargetId(table,after.identity());recordTransition(entry,scope.path("physicalTargetBefore").asText(),physical,from,to);
            if(journal.forRun(publicationRun).state()!=State.VERIFIED)throw new IllegalStateException("D023 publication journal did not close after verified transition");
        }}
    public static void finish(Path ledgerPath,DcIndexTarget backend,String runId,boolean writerStopped)throws Exception{
        var intent=new ReferencePublicationJournal(ledgerPath,"dc_index").forRun(runId).intent();var run=SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);
        var service=new DcIndexPublication(backend.newPublicationTables(),ledgerPath,ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId),backend.tableName(),intent.initialTarget(),runId);
        service.finish(runId,writerStopped);
    }
    public static boolean recoverIfPresent(Path ledgerPath,DcIndexTarget backend,String runId,boolean writerStopped)throws Exception{
        if(!Files.isRegularFile(ledgerPath)||new ReferencePublicationJournal(ledgerPath,"dc_index").findForRun(runId).isEmpty())return false;
        finish(ledgerPath,backend,runId,writerStopped);return true;
    }
    public static void requireNoPendingPublication(Path ledgerPath)throws Exception{requireNoPendingPublication(ledgerPath,null);}
    private static void requireNoPendingPublication(Path ledgerPath,String exceptStageRun)throws Exception{
        if(!Files.isRegularFile(ledgerPath))return;new ReferencePublicationJournal(ledgerPath,"dc_index");
        var store=new DcIndexTargetTransitionStore(ledgerPath);store.initialize();var pending=store.pendingIfPresent();
        if(pending.publicationRunId()!=null)throw new IllegalStateException("Unresolved D023 publication for run "+pending.publicationRunId());
        if(pending.transitionRunId()!=null)throw new IllegalStateException("Unverified D023 physical transition for run "+pending.transitionRunId());
        requireNoPendingStageOnly(ledgerPath,exceptStageRun);
    }
    public static boolean authorizesResume(Path path,String runId,String logical,String before,String current)throws Exception{
        if(!Files.isRegularFile(path))return false;var found=new DcIndexTargetTransitionStore(path).forRun(runId,logical);
        if(found.isEmpty())return false;var entry=found.get();var intent=entry.intent();
        boolean ok=before.equals(intent.previousPhysicalId())&&current.equals(intent.nextPhysicalId())&&"VERIFIED".equals(entry.state());
        return ok&&new ReferencePublicationJournal(path,"dc_index").forRun(runId).state()==State.VERIFIED;
    }
    public static void verifyCurrentTarget(Path path,DcIndexTarget backend,String logical,String physical)throws Exception{
        if(!Files.isRegularFile(path))return;requireNoPendingPublication(path);if(!logical.equals(backend.targetId()))throw new IllegalStateException("D023 logical database/table identity changed");
        String prior=null;for(var entry:new DcIndexTargetTransitionStore(path).lineage(logical)){var intent=entry.intent();
            if(!"VERIFIED".equals(entry.state())||prior!=null&&!prior.equals(intent.previousPhysicalId()))throw new IllegalStateException("D023 physical lineage is incomplete/discontinuous");
            if(new ReferencePublicationJournal(path,"dc_index").forRun(intent.runId()).state()!=State.VERIFIED)throw new IllegalStateException("D023 transition lacks verified publication journal");prior=intent.nextPhysicalId();}
        if(prior!=null&&!prior.equals(physical))throw new IllegalStateException("D023 physical target changed outside journaled replacement");
        if(prior==null){String historical=lastVerifiedPhysical(path,logical);if(historical!=null&&!historical.equals(physical))throw new IllegalStateException("D023 physical generation changed outside a journaled publication");}
    }

    private DcIndexState.Snapshot verifyPublished(ReferencePublicationJournal.Entry entry)throws Exception{
        if(inspect(entry.intent().runId())!=Layout.PUBLISHED)throw new IllegalStateException("D023 physical layout is not the journaled published replacement");
        var after=tables.open(table).snapshot();if(after.identity().id()!=entry.intent().replacementId()||!after.fingerprint().equals(entry.intent().afterFingerprint()))throw new IllegalStateException("D023 published complete snapshot differs from staged snapshot");
        var scope=JobDefinitionJson.mapper().readTree(entry.intent().scope());if(!requiredText(scope,"stageDirectory").equals(after.identity().directory())
                ||!stageIdentityMatches(scope,entry.intent().stage(),after))
            throw new IllegalStateException("D023 published physical stage identity/directory differs from its durable intent");
        validateCompletionEvidence(scope,entry.intent());return after;
    }
    private void validateCompletionEvidence(JsonNode scope,ReferencePublicationJournal.Intent intent)throws Exception{
        Path file=Path.of(requiredText(scope,"completeEvidence")).toAbsolutePath().normalize();requireEvidence(file,"complete-window.json");
        byte[] bytes=FileEvidenceStore.readBounded(file, DcIndexSource.MAX_EVIDENCE_BYTES, () -> new IllegalStateException("D023 recovery evidence outside run bound"));if(!sha(bytes).equals(requiredText(scope,"completeEvidenceSha256")))throw new IllegalStateException("D023 completion receipt changed since publication intent");
        JsonNode proof=JobDefinitionJson.mapper().readTree(bytes);if(!proof.path("complete").asBoolean(false)||!proof.path("sourceComplete").asBoolean(false)
                ||!proof.path("dedup").isBoolean()||proof.path("dedup").asBoolean()
                ||!intent.stage().equals(proof.path("stage").asText())||!intent.afterFingerprint().equals(proof.path("snapshotProof").path("fingerprint").asText())
                ||!requiredText(scope,"sourceFingerprint").equals(proof.path("sourceFingerprint").asText()))throw new IllegalStateException("D023 complete receipt is not an exact verified DEDUP=false stage proof");
    }
    private void verifyRecoveryInputs(JsonNode scope,ReferencePublicationJournal.Intent intent,Layout layout)throws Exception{
        var json=JobDefinitionJson.mapper();var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(runId);
        if(!run.jobId().equals("data.dc_index")||run.jobVersion()!=DcIndexSyncJobOwner.DEFINITION.version()
                ||!run.targetId().equals(logicalTargetId)||!intent.runId().equals(runId))throw new IllegalStateException("D023 recovery run differs from frozen publication owner");
        JsonNode frozen=json.readTree(run.frozenJson()),params=frozen.path("parameters");
        LocalDate from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());
        if(!logicalTargetId.equals(params.path("targetId").asText())
                ||!requiredText(scope,"physicalTargetBefore").equals(params.path("physicalTargetId").asText())
                ||!from.toString().equals(requiredText(scope,"fromInclusive"))||!to.toString().equals(requiredText(scope,"toInclusive")))
            throw new IllegalStateException("D023 publication scope differs from its frozen run request");
        Path complete=Path.of(requiredText(scope,"completeEvidence")).toAbsolutePath().normalize();JsonNode proof=json.readTree(FileEvidenceStore.readBounded(complete, DcIndexSource.MAX_EVIDENCE_BYTES, () -> new IllegalStateException("D023 recovery evidence outside run bound")));
        String encoded=params.path("trade_dates").asText();if(encoded.isBlank())throw new IllegalStateException("D023 frozen source-date list is empty");
        List<LocalDate> dates=Arrays.stream(encoded.split(",",-1)).map(v->{if(!v.matches("[0-9]{8}"))throw new IllegalStateException("D023 frozen source date is invalid");return LocalDate.parse(v,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);}).toList();
        if(dates.size()>DcIndexSyncJobOwner.MAX_WINDOW_DAYS||dates.stream().distinct().count()!=dates.size()
                ||!dates.equals(dates.stream().sorted().toList())||dates.stream().anyMatch(d->d.isBefore(from)||d.isAfter(to))
                ||!json.valueToTree(dates).equals(proof.path("tradeDates"))||!from.toString().equals(proof.path("fromInclusive").asText())
                ||!to.toString().equals(proof.path("toInclusive").asText())||proof.path("completedDateSlices").asInt(-1)!=dates.size()
                ||!proof.path("sourceReceipts").isArray()||proof.path("sourceReceipts").size()!=dates.size())
            throw new IllegalStateException("D023 completion receipt does not cover the exact frozen source-date sequence");

        var sliceEntries=ledger.entries(runId,null,1000).stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        if(sliceEntries.size()!=dates.size())throw new IllegalStateException("D023 run lacks one source slice per frozen trade date");
        var fetchedByDate=new HashMap<LocalDate,JsonNode>();
        for(var slice:sliceEntries){var events=ledger.events(slice.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();
            if(events.size()!=1)throw new IllegalStateException("D023 recovery slice lacks one immutable FETCHED event");JsonNode event=json.readTree(events.getFirst().payloadJson());
            String cursor=event.path("cursor").asText();if(!cursor.matches("[0-9]{8}"))throw new IllegalStateException("D023 recovery cursor is not a BASIC date");
            LocalDate date=LocalDate.parse(cursor,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            if(!dates.contains(date)||fetchedByDate.putIfAbsent(date,event)!=null)throw new IllegalStateException("D023 recovery contains duplicate/out-of-range daily source events");}
        if(!fetchedByDate.keySet().equals(new HashSet<>(dates)))throw new IllegalStateException("D023 recovery source event set differs from frozen dates");
        var sourceRows=new ArrayList<DcIndex>();var receiptFingerprints=new ArrayList<String>();
        for(int i=0;i<dates.size();i++){LocalDate date=dates.get(i);JsonNode event=fetchedByDate.get(date);String receiptText=requiredText(event,"responseEvidence");
            if(!receiptText.equals(proof.path("sourceReceipts").get(i).asText()))throw new IllegalStateException("D023 completion source-receipt order differs from fetched slices");
            Path receipt=Path.of(receiptText).toAbsolutePath().normalize();Path rawRoot=evidenceRoot.resolve("source").toAbsolutePath().normalize();
            if(!receipt.startsWith(rawRoot)||!Files.isRegularFile(receipt)||!receipt.toRealPath().startsWith(rawRoot.toRealPath())
                    ||Files.size(receipt)>DcIndexSource.MAX_EVIDENCE_BYTES)throw new IllegalStateException("D023 daily receipt is absent/outside its bounded source folder");
            String fingerprint=requiredText(event,"sourceFingerprint");var page=DcIndexSource.reopen(receipt,fingerprint,date);
            if(page.rows().size()!=event.path("returnedRows").asInt(-1))throw new IllegalStateException("D023 daily receipt row count differs from immutable FETCHED event");
            sourceRows.addAll(page.rows());receiptFingerprints.add(fingerprint);}
        String combined=combine(receiptFingerprints);if(!combined.equals(requiredText(scope,"sourceFingerprint"))
                ||!combined.equals(requiredText(proof,"sourceFingerprint"))||sourceRows.size()!=proof.path("sourceRows").asInt(-1)
                ||sourceRows.size()!=proof.path("returnedRows").asInt(-1)||sourceRows.size()!=proof.path("submittedRows").asInt(-1))
            throw new IllegalStateException("D023 daily receipts differ from the complete source totals/fingerprint");

        String snapshotTable=layout==Layout.PUBLISHED?table:intent.stage();var snapshot=tables.open(snapshotTable).snapshot();
        if(snapshot.identity().id()!=intent.replacementId()||!snapshot.fingerprint().equals(intent.afterFingerprint())
                ||!requiredText(scope,"stageDirectory").equals(snapshot.identity().directory())
                ||!stageIdentityMatches(scope,intent.stage(),snapshot))
            throw new IllegalStateException("D023 recovery stage/published full snapshot differs from immutable journal");
        var window=snapshot.rows().stream().filter(r->!r.tradeDate().isBefore(from)&&!r.tradeDate().isAfter(to)).toList();
        if(!DcIndexRows.sameRows(DcIndexRows.sourceUnique(sourceRows),window))throw new IllegalStateException("D023 recovery stage window differs from reopened daily source receipts");
    }
    private static String combine(List<String> values)throws Exception{var d=MessageDigest.getInstance("SHA-256");for(String v:values){d.update(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));d.update((byte)0);}return HexFormat.of().formatHex(d.digest());}
    private void requireEvidence(Path path,String filename)throws Exception{
        if(!path.startsWith(evidenceRoot)||!path.getFileName().toString().equals(filename)||!Files.isRegularFile(path)||Files.size(path)>DcIndexSource.MAX_EVIDENCE_BYTES)
            throw new IllegalStateException("D023 publication evidence is absent, oversized or outside its frozen run directory");
        Path root=evidenceRoot.toRealPath(),real=path.toRealPath();if(!real.startsWith(root))throw new IllegalStateException("D023 evidence resolves outside its run directory");
    }
    private void verifyTargetLineage(String logical,String current,String exceptStageRun)throws Exception{
        requireNoPendingPublication(ledgerPath,exceptStageRun);String prior=null;
        for(var entry:transitions.lineage(logical)){var intent=entry.intent();
            if(!"VERIFIED".equals(entry.state())||prior!=null&&!prior.equals(intent.previousPhysicalId()))throw new IllegalStateException("D023 physical identity lineage incomplete");prior=intent.nextPhysicalId();}
        if(prior!=null&&!prior.equals(current))throw new IllegalStateException("D023 physical target changed outside publication lineage");
    }
    private String currentPhysical(){var identity=tables.open(table).preflight();return tables.physicalTargetId(table,identity);}
    private void requireLease(LocalDate from,LocalDate to,boolean recovery){var lease=locks.findOwned(runId,new DatasetIntervalLock.Scope("dc_index",from,to));if(lease==null||lease.inDoubt()!=recovery)throw new IllegalStateException("D023 date-window lease missing or not in required publication state");}
    private void requireNoOtherPending(String currentRun)throws Exception{
        String publication=transitions.firstUnverifiedPublication(currentRun);
        if(publication!=null)throw new IllegalStateException("Another D023 publication is unresolved: "+publication);
        String transition=transitions.firstUnverifiedTransition(currentRun);
        if(transition!=null)throw new IllegalStateException("Another D023 physical transition is unresolved: "+transition);
        requireNoPendingStageOnly(ledgerPath,currentRun);}
    private static void requireNoPendingStageOnly(Path ledgerPath,String exceptRun)throws Exception{
        Path root=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence");if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("D023 sync-evidence root is not a regular directory");
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);int scanned=0;
        try(DirectoryStream<Path> runs=Files.newDirectoryStream(root)){for(Path runDir:runs){if(++scanned>10_000)throw new IllegalStateException("D023 run evidence inventory exceeds bound");
                if(Files.isSymbolicLink(runDir))throw new IllegalStateException("D023 run evidence directory is symlinked");if(!Files.isDirectory(runDir,LinkOption.NOFOLLOW_LINKS))continue;
                String runId=runDir.getFileName().toString();if(Objects.equals(exceptRun,runId))continue;Path stageDir=runDir.resolve("staging");if(!Files.exists(stageDir,LinkOption.NOFOLLOW_LINKS))continue;
                if(Files.isSymbolicLink(stageDir)||!Files.isDirectory(stageDir,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("D023 stage evidence path is not a regular directory");
                Path found=null;try(DirectoryStream<Path> files=Files.newDirectoryStream(stageDir,"java_dc_index_stage_*-intent.json")){for(Path file:files){
                        if(found!=null||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>DcIndexSource.MAX_EVIDENCE_BYTES)
                            throw new IllegalStateException("Ambiguous or unbounded D023 stage-only intent for run "+runId);found=file;}}
                if(found==null)continue;var json=JobDefinitionJson.mapper().readTree(FileEvidenceStore.readBounded(found, DcIndexSource.MAX_EVIDENCE_BYTES, () -> new IllegalStateException("D023 recovery evidence outside run bound")));
                if(!"dc_index".equals(json.path("dataset").asText())||!json.path("stage").asText().matches("java_dc_index_stage_[0-9a-f]{32}"))
                    throw new IllegalStateException("Malformed D023 stage-only intent for run "+runId);
                var state=ledger.get(runId).state();var saved=ledger.getRun(runId);
                var publication=new ReferencePublicationJournal(ledgerPath,"dc_index").findForRun(runId);
                if(!saved.jobId().equals("data.dc_index")
                        ||!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(state)
                        ||publication.isEmpty()||publication.get().state()!=State.VERIFIED
                        ||!publication.get().intent().stage().equals(json.path("stage").asText())
                        ||!publication.get().intent().target().equals(json.path("target").asText())
                        ||!publication.get().intent().initialTarget().equals(saved.targetId()))
                    throw new IllegalStateException("Unresolved D023 stage-only recovery for run "+runId);
            }}
    }
    private boolean stageIdentityMatches(JsonNode scope,String frozenStage,DcIndexState.Snapshot snapshot){
        int version=scope.path("proofVersion").asInt(1);
        if(version<1||version>2)throw new IllegalStateException("Unsupported D023 publication proof version");
        if(version==1&&!scope.has("stagePhysicalTarget"))return true;
        return requiredText(scope,"stagePhysicalTarget").equals(tables.physicalTargetId(frozenStage,snapshot.identity()));
    }
    private void recordTransition(ReferencePublicationJournal.Entry entry,String before,String after,LocalDate from,LocalDate to)throws Exception{
        ensureTransition(entry,before,after,from,to);var latest=journal.forRun(runId);if(latest.state()!=State.VERIFIED)journal.advance(latest,State.VERIFIED);setTransitionVerified(runId);
    }
    private void ensureTransition(ReferencePublicationJournal.Entry entry,String before,String after,LocalDate from,LocalDate to)throws Exception{
        var intent=new DcIndexTargetTransitionStore.Intent(entry.intent().id(),runId,logicalTargetId,before,after,from.toString(),to.toString());
        var saved=transitions.forPublication(entry.intent().id());
        if(saved.isPresent()){if(!intent.equals(saved.get().intent()))throw new IllegalStateException("D023 physical transition changed during recovery");return;}
        transitions.insertPending(intent);
    }
    private void setTransitionVerified(String run)throws Exception{transitions.markVerified(run);}
    private static String requiredText(JsonNode json,String field){JsonNode v=json.path(field);if(!v.isTextual()||v.asText().isBlank())throw new IllegalStateException("D023 publication scope lacks "+field);return v.asText();}
    private static String lastVerifiedPhysical(Path path,String logical)throws Exception{
        var ledger=SyncRunLedger.openReadOnly(path);String after=null,latest=null,latestAt=null;int scanned=0;
        while(true){var page=ledger.history("data.dc_index",after,100);for(var summary:page){if(++scanned>10_000)throw new IllegalStateException("D023 physical identity history exceeds bound");
                if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(summary.state())||!logical.equals(summary.targetId()))continue;
                JsonNode frozen=JobDefinitionJson.mapper().readTree(ledger.getRun(summary.id()).frozenJson());String physical=frozen.path("parameters").path("physicalTargetId").asText();
                if(!physical.matches("static-v2-[0-9a-f]{64}"))throw new IllegalStateException("Verified D023 run lacks its frozen physical target generation");
                if(latestAt==null||summary.updatedAt().compareTo(latestAt)>0){latestAt=summary.updatedAt();latest=physical;}}
            if(page.size()<100)break;after=page.getLast().id();}
        return latest;
    }
    private DcIndexState.Snapshot optional(String tableName)throws Exception{return tableSnapshot(tableName);}
    private static boolean matches(DcIndexState.Snapshot snap,long id,String directory,String fingerprint){return snap!=null&&snap.identity().id()==id&&(directory==null||directory.equals(snap.identity().directory()))&&fingerprint.equals(snap.fingerprint());}
    private DcIndexState.Snapshot tableSnapshot(String name)throws Exception{return tables.snapshotIfPresent(name);}
    private void rename(String from,String to){tables.rename(from,to);}
    private void markInDoubt(String run,Exception failure){try{var e=journal.forRun(run);if(e.state()!=State.VERIFIED&&e.state()!=State.IN_DOUBT)journal.advance(e,State.IN_DOUBT);}catch(Exception e){failure.addSuppressed(e);}}
    private FileLockHolder acquireRecoveryLock()throws Exception{Path path=ledgerPath.resolveSibling(ledgerPath.getFileName()+".dc-index-publication.lock");Files.createDirectories(path.getParent());FileChannel c=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);try{FileLock l;try{l=c.tryLock();}catch(OverlappingFileLockException busy){l=null;}if(l==null)throw new IllegalStateException("D023 publication active");return new FileLockHolder(c,l);}catch(Exception e){c.close();throw e;}}
    private record FileLockHolder(FileChannel channel,FileLock lock)implements AutoCloseable{@Override public void close()throws Exception{try{lock.release();}finally{channel.close();}}}
    private static String sha(byte[] b)throws Exception{return FileEvidenceStore.sha256(b);}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("D023 publication cancelled");}
}
