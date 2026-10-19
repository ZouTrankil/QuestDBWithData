package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.stock.moneyflow.Moneyflow;
import com.zoutrankil.data.domain.stock.moneyflow.MoneyflowKey;
import com.zoutrankil.data.domain.stock.moneyflow.MoneyflowDataset;
import com.zoutrankil.data.flow.port.MoneyflowTarget;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Prove a stopped writer's prefix, complete only missing frozen rows under the retained lock,
 * then allow ordinary resume to revalidate this date and process subsequent dates. */
public final class MoneyflowRunRecovery {
    private MoneyflowRunRecovery() {}

    public static Map<String,Object> reconcile(Path ledgerPath,String runId,Path writerLog,
            MoneyflowTarget target) throws Exception {
        if(!runId.matches("moneyflow-[0-9a-f-]{36}") || !target.tableName().equals("moneyflow"))
            throw new IllegalArgumentException("Formal moneyflow run required");
        var json=JobDefinitionJson.mapper();
        byte[] logBytes=FileEvidenceStore.readBounded(writerLog,4*1024*1024,()->new IllegalArgumentException("Writer log too large"));
        String log=new String(logBytes,StandardCharsets.UTF_8);
        var pids=Pattern.compile("Starting QuestDataApplication using Java[^\\r\\n]* with PID ([0-9]+)").matcher(log);
        if(!pids.find())throw new IllegalArgumentException("Original writer PID missing");
        long pid=Long.parseLong(pids.group(1));
        boolean terminated=Pattern.compile("finished with non-zero exit value [1-9][0-9]*").matcher(log).find()
                || Pattern.compile("\\{\\s*\"status\"\\s*:\\s*\"FAILED\"\\s*,\\s*\"exitCode\"\\s*:\\s*[1-9][0-9]*\\s*\\}").matcher(log).find();
        if(pids.find() || !log.contains("moneyflow sync incomplete: IN_DOUBT; run="+runId) || !terminated)
            throw new IllegalArgumentException("Exactly one matching terminated writer required");
        stopped(pid);
        var ledger=SyncRunLedger.openExisting(ledgerPath);var run=Objects.requireNonNull(ledger.getRun(runId));
        var frozen=json.readTree(run.frozenJson());var definition=MoneyflowSyncJobOwner.DEFINITION;
        if(!definition.jobId().equals(run.jobId()) || definition.version()!=run.jobVersion()
                || !definition.equals(json.treeToValue(frozen.path("definition"),SyncJobDefinition.class))
                || !run.targetId().equals(target.targetId())
                || !run.targetId().equals(frozen.path("parameters").path("targetId").asText()))
            throw new IllegalStateException("Frozen definition or physical target differs");
        var from=LocalDate.parse(frozen.path("from").asText());var to=LocalDate.parse(frozen.path("to").asText());
        var locks=new DatasetIntervalLock(ledgerPath);var scope=new DatasetIntervalLock.Scope("moneyflow",from,to);
        var lease=locks.findOwned(runId,scope);
        if(lease==null || !lease.inDoubt())throw new IllegalStateException("Retained uncertain interval required");
        Path root=ledgerPath.toAbsolutePath().getParent().resolve("sync-evidence").resolve(runId).toRealPath();
        Path recovery=root.resolve("moneyflow-recovery");Files.createDirectories(recovery);
        if(Files.isSymbolicLink(recovery) || !recovery.toRealPath().startsWith(root))throw new IllegalStateException("Invalid recovery path");
        try(var channel=FileChannel.open(recovery.resolve("operation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var operation=channel.tryLock()) {
            if(operation==null)throw new IllegalStateException("Recovery already active");
            var entries=ledger.entries(runId,null,definition.budget().maxSlices()+3);
            if(entries.size()<3 || entries.size()>definition.budget().maxSlices()+2 || entries.stream().anyMatch(e->e.kind()==SyncRunLedger.Kind.SLICE
                    ? e.state()!=SyncRunState.IN_DOUBT && e.state()!=SyncRunState.VERIFIED
                    : e.state()!=SyncRunState.IN_DOUBT && e.state()!=SyncRunState.PARTIAL))
                throw new IllegalStateException("One uncertain attempt and date slice required");
            var uncertainSlices=new ArrayList<SyncRunLedger.Entry>();
            for(var entry:entries)if(entry.kind()==SyncRunLedger.Kind.SLICE
                    && ledger.events(entry.id(),-1,100).stream().anyMatch(event->event.state()==SyncRunState.IN_DOUBT))uncertainSlices.add(entry);
            if(uncertainSlices.size()!=1)throw new IllegalStateException("Exactly one uncertain date slice required");
            var slice=uncertainSlices.getFirst();
            var attempt=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.ATTEMPT).findFirst().orElseThrow();
            if(!runId.equals(attempt.parentId()) || !attempt.id().equals(slice.parentId())
                    || entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.RUN && runId.equals(e.id())).count()!=1)
                throw new IllegalStateException("Unexpected ledger ancestry");
            var events=ledger.events(slice.id(),-1,100);
            var fetched=events.stream().filter(e->e.state()==SyncRunState.FETCHED).toList();
            var uncertain=events.stream().filter(e->e.state()==SyncRunState.IN_DOUBT).toList();
            if(events.size()==100 || fetched.size()!=1 || uncertain.size()!=1
                    || events.stream().filter(e->e.state()==SyncRunState.SUBMITTED).count()!=1)
                throw new IllegalStateException("Unique durable submission required");
            var fetch=json.readTree(fetched.getFirst().payloadJson());
            var date=LocalDate.parse(fetch.path("cursor").asText(),java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            if(date.isBefore(from) || date.isAfter(to))throw new IllegalStateException("Source date outside frozen interval");
            Path source=Path.of(fetch.path("responseEvidence").asText()).toRealPath();
            if(!source.startsWith(root.resolve("source")))throw new IllegalStateException("Foreign source receipt");
            String sha=fetch.path("sourceFingerprint").asText();
            var page=MoneyflowSource.reopen(source,sha,date);
            if(page.rows().isEmpty() || page.rows().size()>(MoneyflowSource.API_ROW_CAP-1)
                    || fetch.path("returnedRows").asInt(-1)!=page.rows().size())throw new IllegalStateException("Invalid source size");
            var result=json.readTree(uncertain.getFirst().payloadJson()).path("writeResult");
            var receipts=result.path("receipts");var port=target.newWriter(run.targetId());var codec=port.codec();
            if(!"IN_DOUBT".equals(result.path("status").asText()) || !receipts.isArray() || receipts.isEmpty() || receipts.size()>20)
                throw new IllegalStateException("Bounded uncertain receipts required");
            int submitted=0,verified=0,index=0;
            for(var receipt:receipts) {
                int n=receipt.path("submittedRows").asInt(-1),v=receipt.path("verifiedRows").asInt(-1);
                if(receipt.path("batch").asInt(-1)!=++index || n<1 || n>250 || submitted+n>page.rows().size()
                        || v!=0 && v!=n || index<receipts.size() && v!=n)throw new IllegalStateException("Invalid submitted prefix");
                var hash=MessageDigest.getInstance("SHA-256");int bytes=0;
                for(var row:page.rows().subList(submitted,submitted+n)) {
                    byte[] canonical=codec.canonicalBytes(row);hash.update(ByteBuffer.allocate(4).putInt(canonical.length).array());hash.update(canonical);
                    bytes=Math.addExact(bytes,codec.estimatedTransportBytes(row,canonical));
                }
                if(!HexFormat.of().formatHex(hash.digest()).equals(receipt.path("digest").asText()) || bytes!=receipt.path("bytes").asInt(-1))
                    throw new IllegalStateException("Original submitted values differ from frozen source");
                submitted+=n;verified+=v;
            }
            if(submitted!=result.path("submittedRows").asInt(-1) || verified!=result.path("verifiedRows").asInt(-1))
                throw new IllegalStateException("Receipt totals differ");
            var prefix=page.rows().subList(0,submitted);
            readback(prefix,port);stopped(pid);MoneyflowSource.reopen(source,sha,date);
            if(!run.targetId().equals(target.targetId()))throw new IllegalStateException("Target changed");
            try(var prior=Files.newDirectoryStream(recovery,"*.intent.json")) {
                for(var file:prior) {
                    var intent=json.readTree(FileEvidenceStore.readBounded(file,1024*1024,()->new IllegalStateException("Oversized recovery intent")));
                    if(!runId.equals(intent.path("runId").asText()))throw new IllegalStateException("Foreign recovery intent");
                    stopped(intent.path("pid").asLong(-1));
                }
            }
            var expected=new LinkedHashMap<MoneyflowKey,Moneyflow>();
            for(var row:page.rows())expected.put(row.key(),row);
            var found=new HashSet<MoneyflowKey>();
            for(var row:port.readDate(date))if(!found.add(row.key()) || !expected.containsKey(row.key())
                    || !codec.equivalent(expected.get(row.key()),row))throw new IllegalStateException("Date contains unexpected, duplicate or different values");
            var missing=page.rows().stream().filter(row->!found.contains(row.key())).toList();
            if(slice.state()==SyncRunState.VERIFIED && !missing.isEmpty())throw new IllegalStateException("Previously recovered date changed");
            String operationId=UUID.randomUUID().toString();
            FileEvidenceStore.writeNewDurable(recovery.resolve(operationId+".intent.json"),json.writeValueAsBytes(Map.of(
                    "runId",runId,"pid",ProcessHandle.current().pid(),"targetId",run.targetId(),"sourceSha256",sha,
                    "missingKeys",missing.stream().map(Moneyflow::key).toList(),"writerLogSha256",FileEvidenceStore.sha256(logBytes))));
            if(!missing.isEmpty()) {
                long started=System.nanoTime();
                var guarded=new VerifiedBatchExecutor.Port<Moneyflow,MoneyflowKey>() {
                    public void preflight() throws Exception {
                        stopped(pid);
                        if(System.nanoTime()-started>=java.time.Duration.ofMinutes(10).toNanos())throw new IllegalStateException("Recovery deadline reached");
                        var current=ledger.get(slice.id());var owned=locks.findOwned(runId,scope);
                        if(current.revision()!=slice.revision() || current.state()!=SyncRunState.IN_DOUBT || owned==null || !owned.inDoubt())
                            throw new IllegalStateException("Recovery submission authority changed");
                        MoneyflowSource.reopen(source,sha,date);port.preflight();
                    }
                    public void send(List<Moneyflow> rows) throws Exception { preflight();port.send(rows); }
                    public List<Moneyflow> readback(List<MoneyflowKey> keys) throws Exception { return port.readback(keys); }
                    public boolean walSettled() throws Exception { return port.walSettled(); }
                    public boolean uncertainSenderStopped() throws Exception { return port.uncertainSenderStopped(); }
                };
                port.submissionRecorded(new VerifiedBatchExecutor.Submission(ledgerPath,runId,slice.id(),
                        events.stream().filter(e->e.state()==SyncRunState.SUBMITTED).findFirst().orElseThrow().revision(),sha));
                var written=new VerifiedBatchExecutor<>(new VerifiedBatchExecutor.Policy(250,1024*1024,20,
                        java.time.Duration.ofMinutes(3),java.time.Duration.ofMillis(100)),codec,guarded).execute(missing.iterator());
                FileEvidenceStore.writeNewDurable(recovery.resolve(operationId+".write.json"),json.writeValueAsBytes(written));
                if(written.status()!=VerifiedBatchExecutor.Status.VERIFIED)throw new IllegalStateException("Recovery write uncertain; retain interval lock");
            }
            readback(page.rows(),port);stopped(pid);MoneyflowSource.reopen(source,sha,date);
            if(!run.targetId().equals(target.targetId()))throw new IllegalStateException("Target changed");
            Path proof=recovery.resolve(operationId+".verified-date.json");int complete=page.rows().size();
            var report=new LinkedHashMap<String,Object>();
            report.put("runId",runId);report.put("targetId",run.targetId());report.put("sourceSha256",sha);
            report.put("sourceRows",complete);report.put("submittedRows",complete);report.put("originalSubmittedRows",submitted);report.put("writtenRows",missing.size());
            report.put("writerPid",pid);report.put("writerLogSha256",FileEvidenceStore.sha256(logBytes));report.put("receipts",receipts);
            report.put("sourceComplete",false);report.put("dateCheckpointReusable",true);report.put("proof",proof.toString());
            report.put("allSubmittedSlicesVerified",true);
            report.put("verification",Map.of("passed",true,"writerStopped",true,"expectedRows",complete,
                    "matchedRows",complete,"actualRows",complete,"missingKeys",0,"duplicateKeys",0,
                    "mismatchedRows",0,"sourceFingerprint",sha,"readbackEvidence",proof.toString()));
            FileEvidenceStore.writeNewDurable(proof,json.writeValueAsBytes(report));
            // Verify the whole date before exposing it as a checkpoint; the interval is still PARTIAL.
            var ordered=new ArrayList<>(entries);ordered.sort(Comparator.comparingInt(e->e.kind()==SyncRunLedger.Kind.SLICE?0:e.kind()==SyncRunLedger.Kind.ATTEMPT?1:2));
            for(var entry:ordered)if(entry.state()==SyncRunState.IN_DOUBT) {
                stopped(pid);ledger.transition(entry.id(),entry.revision(),entry.kind()==SyncRunLedger.Kind.SLICE?SyncRunState.VERIFIED:SyncRunState.PARTIAL,json.writeValueAsString(report));
            }
            readback(page.rows(),port);stopped(pid);MoneyflowSource.reopen(source,sha,date);
            if(!run.targetId().equals(target.targetId()))throw new IllegalStateException("Target changed; lock retained");
            locks.releaseAfterReconciliation(lease,true,true);
            return Map.of("runId",runId,"state","PARTIAL","verifiedRows",complete,"writtenRows",missing.size(),"lockReleased",true,"proof",proof.toString());
        }
    }
    private static void readback(List<Moneyflow> rows,com.zoutrankil.data.flow.port.MoneyflowWriteSession port) throws Exception {
        port.preflight();if(!port.walSettled())throw new IllegalStateException("Unsettled WAL");
        for(int start=0;start<rows.size();start+=250) {
            var expected=new LinkedHashMap<MoneyflowKey,Moneyflow>();
            for(var row:rows.subList(start,Math.min(start+250,rows.size())))if(expected.put(row.key(),row)!=null)throw new IllegalStateException("Duplicate source key");
            var actual=port.readback(List.copyOf(expected.keySet()));var seen=new HashSet<MoneyflowKey>();
            for(var row:actual)if(!seen.add(row.key()) || !expected.containsKey(row.key()) || !port.codec().equivalent(expected.get(row.key()),row))
                throw new IllegalStateException("Submitted key values differ or duplicate");
            if(!seen.equals(expected.keySet()))throw new IllegalStateException("Submitted rows missing");
        }
        port.preflight();if(!port.walSettled())throw new IllegalStateException("WAL changed");
    }
    private static void stopped(long pid) {
        if(pid<1 || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))throw new IllegalStateException("Original writer still present");
    }
}
