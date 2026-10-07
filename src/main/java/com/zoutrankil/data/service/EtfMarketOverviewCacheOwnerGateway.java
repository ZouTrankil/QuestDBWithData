package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded process boundary to the sole original Python read-through owner. */
public class EtfMarketOverviewCacheOwnerGateway {
    public static final int MAX_JSON_BYTES=1024*1024;
    private static final int MAX_LOG_BYTES=2*1024*1024;
    private static final ObjectMapper JSON=JobDefinitionJson.mapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public record Config(Path pythonExecutable, Path bridgeScript, Path artifactRoot, Path privateRoot,
                         long expectedPid, Duration timeout, int maxSourceRows) {
        public Config {
            Objects.requireNonNull(pythonExecutable); Objects.requireNonNull(bridgeScript);
            Objects.requireNonNull(artifactRoot); Objects.requireNonNull(privateRoot); Objects.requireNonNull(timeout);
            pythonExecutable=pythonExecutable.toAbsolutePath().normalize(); bridgeScript=bridgeScript.toAbsolutePath().normalize();
            artifactRoot=artifactRoot.toAbsolutePath().normalize(); privateRoot=privateRoot.toAbsolutePath().normalize();
            if (expectedPid<0 || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(3))>0
                    || maxSourceRows<1 || maxSourceRows>50000) throw new IllegalArgumentException("Finite private gateway configuration required");
        }
    }
    public record OwnerResult(Path requestPath,Path responsePath,Path intentPath,String responseSha256,
                              boolean processStopped,long hits,long misses,String status) {}
    @FunctionalInterface public interface ProcessLauncher { Process start(List<String> argv,Path log) throws IOException; }
    private record Invocation(Path request,Path response,Path intent,Path started,Path stopped,Path log,String id) {}
    protected final Config config;
    private final ProcessLauncher launcher;
    public EtfMarketOverviewCacheOwnerGateway(Config config) {
        this(config,(argv,log)->new ProcessBuilder(argv).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start());
    }
    public EtfMarketOverviewCacheOwnerGateway(Config config,ProcessLauncher launcher) {
        this.config=Objects.requireNonNull(config);this.launcher=Objects.requireNonNull(launcher);
    }
    public Config config() { return config; }

    public EtfMarketOverviewCachePublicationEnvelope preview(LocalDate date) throws Exception {
        requireConfigured();Objects.requireNonNull(date);
        var call=invocation("preview");var request=request("preview",date,call.id);
        newJson(call.request,request);invoke(call,()->false);
        var bytes=readBounded(call.response);var value=JSON.readTree(bytes);
        return parsePreview(value,date,call.id,call.response,sha(bytes));
    }

    /** Called once only after the real shared SQLite revision4 SUBMITTED event. */
    public synchronized OwnerResult publish(EtfMarketOverviewCachePublicationEnvelope envelope,
            VerifiedBatchExecutor.Submission submission,BooleanSupplier cancelled) throws Exception {
        requireConfigured();Objects.requireNonNull(cancelled);
        validateSubmission(envelope,submission);
        if (cancelled.getAsBoolean()) throw new IllegalStateException("Cancelled before owner process launch");
        requireNoEarlierIntent(submission);
        var previewBytes=readBounded(envelope.previewPath());
        if (!sha(previewBytes).equals(envelope.previewSha256())) throw new IllegalStateException("Immutable preview changed");
        var original=JSON.readTree(previewBytes);
        var checked=parsePreview(original,envelope.tradeDate(),string(original,"invocation_id"),envelope.previewPath(),envelope.previewSha256());
        if (!samePublication(checked,envelope)) throw new IllegalStateException("Envelope differs from original preview artifact");
        var call=invocation("publish");var intent=JSON.createObjectNode();
        intent.put("protocol_version",1);intent.put("invocation_id",call.id);intent.put("dataset_id",EtfMarketOverviewCachePublicationEnvelope.DATASET);
        intent.put("trade_date",envelope.tradeDate().toString());intent.put("preview_path",envelope.previewPath().toAbsolutePath().normalize().toString());
        intent.put("preview_sha256",envelope.previewSha256());intent.put("ledger_path",submission.ledgerPath().toString());
        intent.put("run_id",submission.runId());intent.put("slice_id",submission.sliceId());intent.put("revision",submission.revision());
        intent.put("source_fingerprint",envelope.sourceFingerprint());intent.put("sources_fingerprint",envelope.sourcesFingerprint());
        intent.put("targets_fingerprint",envelope.targetsFingerprint());intent.put("target_id",envelope.targetId());
        intent.set("expected_cache_records",original.get("expected_cache_records"));intent.set("expected_receipt",original.get("expected_receipt"));
        intent.set("target",target());
        claimSubmission(submission,call);
        var evidence=JSON.readTree(envelope.responseEvidence());
        for(String field:List.of("prepared_input_path","prepared_input_sha256"))
            if(evidence.has(field)) intent.set(field,evidence.get(field));
        newJson(call.intent,intent);
        var request=request("publish",envelope.tradeDate(),call.id);request.put("intent_path",call.intent.toString());
        request.put("intent_sha256",sha(readBounded(call.intent)));newJson(call.request,request);
        invoke(call,cancelled); // A terminal response alone cannot prove process termination.
        byte[] bytes=readBounded(call.response);var value=JSON.readTree(bytes);
        requireCommon(value,"publish",envelope.tradeDate(),call.id);
        if (!"VERIFIED_READTHROUGH".equals(string(value,"status"))) throw new IOException("Owner publication unresolved: "+string(value,"status"));
        if (!bool(value,"owner_invoked") || !bool(value,"owner_sender_stopped") || !bool(value,"exact_owner_digest_verified")
                || !bool(value,"source_unchanged") || !value.path("source_snapshots_after").equals(original.path("source_snapshots"))
                || !string(value,"source_fingerprint").equals(envelope.sourceFingerprint()) || !string(value,"target_id").equals(envelope.targetId()))
            throw new IOException("Owner result lacks exact source and stopped-sender proof");
        if (!parseCache(value.get("actual_cache_records"),envelope.tradeDate(),envelope.sourceVersion()).equals(Optional.ofNullable(envelope.cache()))
                || !Objects.equals(parseReceipt(value.get("actual_receipt")),envelope.receipt()))
            throw new IOException("Owner result differs from original full cache and receipt values");
        long hits=nonnegative(value,"actual_owner_hits"),misses=nonnegative(value,"actual_owner_misses");
        if (hits!=nonnegative(original,"expected_owner_hits") || misses!=nonnegative(original,"expected_owner_misses") || hits+misses!=1)
            throw new IOException("Owner result differs from frozen hit/miss preview");
        validateAfterTargets(original,value);
        var submissions=value.get("owner_submissions");if (submissions==null || !submissions.isArray() || submissions.size()>2) throw new IOException("Unbounded owner submissions");
        var seen=new HashSet<String>();
        for (var row:submissions) {
            if (!EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES.contains(string(row,"table")) || !seen.add(string(row,"table"))
                    || nonnegative(row,"planned_rows")!=1 || nonnegative(row,"attempted_rows")!=1 || nonnegative(row,"acknowledged_rows")!=1
                    || !"ACKNOWLEDGED".equals(string(row,"ack"))) throw new IOException("Unknown or repeated owner submission remains unresolved");
        }
        if (hits==1 && !submissions.isEmpty() || misses==1 && (!seen.contains(EtfMarketOverviewCachePublicationEnvelope.COVERAGE)
                || (envelope.cache()!=null)!=seen.contains(EtfMarketOverviewCachePublicationEnvelope.CACHE))) throw new IOException("Actual original-owner submissions differ");
        if (envelope.cache()==null && !bool(value,"cache_key_absent_after")) throw new IOException("Empty receipt has a cache row");
        return new OwnerResult(call.request,call.response,call.intent,sha(bytes),true,hits,misses,"VERIFIED_READTHROUGH");
    }

    /** Read-only cross-process proof. Missing, conflicting, live or incomplete records fail closed. */
    public boolean writerStopped(Path ledgerPath,String runId) throws Exception {
        requireConfigured();var ledger=ledgerPath.toAbsolutePath().normalize();boolean found=false;
        var claimed=new HashSet<Path>();
        Path claimRoot=claimRoot(ledger);
        if(!Files.isDirectory(claimRoot))return false;
        try(var inventory=Files.list(claimRoot)){
            var files=inventory.filter(path->path.getFileName().toString().endsWith(".claim.json")).limit(1001).toList();
            if(files.size()>1000)throw new IOException("Finite submission claim inventory exceeded");
            for(Path file:files){var claim=JSON.readTree(readBounded(file));
                if(!ledger.equals(Path.of(string(claim,"ledger_path")).toAbsolutePath().normalize())||!runId.equals(string(claim,"run_id")))continue;
                Path intent=Path.of(string(claim,"intent_path")).toAbsolutePath().normalize();
                if(!intent.startsWith(config.artifactRoot)||!Files.isRegularFile(intent))return false;
                var body=JSON.readTree(readBounded(intent));
                for(String field:List.of("invocation_id","run_id","slice_id"))if(!string(claim,field).equals(string(body,field)))return false;
                if(nonnegative(claim,"revision")!=4||nonnegative(body,"revision")!=4)return false;
                claimed.add(intent);
            }
        }
        for (Path intentPath:intents()) {
            var intent=JSON.readTree(readBounded(intentPath));
            if (!ledger.toString().equals(intent.path("ledger_path").asText()) || !runId.equals(intent.path("run_id").asText())) continue;
            if(!claimed.remove(intentPath.toAbsolutePath().normalize()))return false;
            found=true;var id=string(intent,"invocation_id");String prefix=intentPath.getFileName().toString().replace(".intent.json","");
            Path started=config.artifactRoot.resolve(prefix+".process-started.json"),stopped=config.artifactRoot.resolve(prefix+".process-stopped.json");
            if (!Files.isRegularFile(started) || !Files.isRegularFile(stopped)) return false;
            var first=JSON.readTree(readBounded(started));var last=JSON.readTree(readBounded(stopped));
            if (!id.equals(string(first,"invocation_id")) || !id.equals(string(last,"invocation_id"))
                    || !sha(readBounded(intentPath)).equals(string(first,"intent_sha256"))
                    || nonnegative(first,"pid")!=nonnegative(last,"pid") || !string(first,"process_start").equals(string(last,"process_start"))
                    || !bool(last,"exit_observed") || "UNKNOWN".equals(string(first,"process_start"))) return false;
            if(nonnegative(first,"process_tree_version")!=1||nonnegative(last,"process_tree_version")!=1
                    ||!bool(last,"tree_observation_complete"))return false;
            if(!bool(last,"process_tree_stopped")||bool(last,"response_present")&&!bool(last,"bridge_identity_proved")){
                if(!hasStoppedTreeInvestigation(ledger,runId,intentPath))return false;
                continue; // Only an explicit immutable investigation can cover the historical duplicate tombstones.
            }
            if(!endedIdentity(nonnegative(first,"pid"),string(first,"process_start")))return false;
            var children=last.get("observed_children");if(children==null||!children.isArray()||children.size()>64)return false;
            var seen=new HashSet<String>();var pidInstances=new HashMap<Long,Integer>();var bridgeNodes=new ArrayList<BridgeNode>();var observedBridgeIds=new HashSet<Long>();observedBridgeIds.add(nonnegative(first,"pid"));
            for(var child:children){
                long childPid=nonnegative(child,"pid");String childBirth=string(child,"process_start");
                if(!seen.add(childPid+"@"+childBirth)||!bool(child,"exit_observed")||!endedIdentity(childPid,childBirth))return false;
                Path evidence=Path.of(string(child,"evidence_path")).toAbsolutePath().normalize();
                if(!evidence.startsWith(config.artifactRoot)||!sha(readBounded(evidence)).equals(string(child,"evidence_sha256")))return false;
                var proof=JSON.readTree(readBounded(evidence));
                if(nonnegative(proof,"process_tree_version")!=1||!bool(proof,"observed_descendant")||!id.equals(string(proof,"invocation_id"))
                        ||nonnegative(proof,"pid")!=nonnegative(first,"pid")||!string(proof,"process_start").equals(string(first,"process_start"))
                        ||!string(proof,"intent_sha256").equals(string(first,"intent_sha256"))||!string(proof,"request_sha256").equals(string(first,"request_sha256"))
                        ||nonnegative(proof,"child_pid")!=childPid||!string(proof,"child_process_start").equals(childBirth)
                        ||!Objects.equals(proof.get("parent_pid"),child.get("parent_pid")))return false;
                observedBridgeIds.add(childPid);pidInstances.merge(childPid,1,Integer::sum);
                bridgeNodes.add(new BridgeNode(childPid,Instant.parse(childBirth),nullableCounter(child,"parent_pid")));
            }
            Path response=config.artifactRoot.resolve(prefix+".response.json");
            if(!Files.isRegularFile(response)){
                // No ACK is invented: only an explicit readonly reconcile may use the entire stopped producer tree.
                if(bool(last,"response_present")||bool(last,"bridge_identity_proved")||!last.path("actual_bridge_pid").isNull())return false;
                for(var child:bridgeNodes)if(!parentChainProved(child.pid,nonnegative(first,"pid"),Instant.parse(string(first,"process_start")),bridgeNodes))return false;
            }else{
                if(!bool(last,"response_present")||!bool(last,"bridge_identity_proved"))return false;
                var value=JSON.readTree(readBounded(response));long actualBridge=nonnegative(value.get("bridge_process"),"pid");
                if(actualBridge!=nonnegative(last,"actual_bridge_pid")||!observedBridgeIds.contains(actualBridge)
                        ||pidInstances.getOrDefault(actualBridge,0)>1
                        ||!parentChainProved(actualBridge,nonnegative(first,"pid"),Instant.parse(string(first,"process_start")),bridgeNodes))return false;
                if("VERIFIED_READTHROUGH".equals(value.path("status").asText())&&!bool(value,"owner_sender_stopped"))return false;
            }
        }
        return found&&claimed.isEmpty();
    }

    /** Explicit file/native-process investigation only. It never sends, queries a database, kills or edits old markers. */
    public synchronized List<Path> investigateStoppedTree(Path ledgerPath,String runId)throws Exception{
        requireConfigured();Path ledger=ledgerPath.toAbsolutePath().normalize();
        if(runId==null||runId.isBlank()||!Files.isRegularFile(ledger))throw new IOException("Actual existing ledger and run identity required");
        var candidates=new ArrayList<ObjectNode>();var matching=new ArrayList<Path>();
        for(Path intentPath:intents()){
            var intent=JSON.readTree(readBounded(intentPath));
            if(!ledger.toString().equals(intent.path("ledger_path").asText())||!runId.equals(intent.path("run_id").asText()))continue;
            String prefix=intentPath.getFileName().toString().replace(".intent.json","");
            var stop=JSON.readTree(readBounded(config.artifactRoot.resolve(prefix+".process-stopped.json")));
            if(bool(stop,"process_tree_stopped")&&bool(stop,"bridge_identity_proved"))continue;
            candidates.add(reviewStoppedTombstones(ledger,runId,intentPath));matching.add(intentPath);
        }
        if(candidates.isEmpty())throw new IOException("No admitted duplicate-tombstone process tree requires investigation");
        // Validate every candidate before persisting any new proof; no partial process admission is published.
        for(var body:candidates){body.set("native_absence",nativeAbsence(body));body.put("checked_at",Instant.now().toString());}
        var proofs=new ArrayList<Path>();
        for(int i=0;i<candidates.size();i++){
            var body=candidates.get(i);
            String prefix=matching.get(i).getFileName().toString().replace(".intent.json","");
            Path proof=config.artifactRoot.resolve(prefix+".process-reconciled-"+UUID.randomUUID()+".json");newJson(proof,body);proofs.add(proof);
        }
        return List.copyOf(proofs);
    }
    private boolean hasStoppedTreeInvestigation(Path ledger,String runId,Path intentPath)throws Exception{
        String prefix=intentPath.getFileName().toString().replace(".intent.json","")+".process-reconciled-";
        List<Path> proofs;try(var paths=Files.list(config.artifactRoot)){
            proofs=paths.filter(path->path.getFileName().toString().startsWith(prefix)&&path.getFileName().toString().endsWith(".json")).limit(101).toList();
        }
        if(proofs.isEmpty())return false;if(proofs.size()>100)throw new IOException("Finite process investigation inventory exceeded");
        var expected=reviewStoppedTombstones(ledger,runId,intentPath);
        for(Path path:proofs){
            var proof=JSON.readTree(readBounded(path));
            for(var fields=expected.fieldNames();fields.hasNext();){String field=fields.next();
                // JSON integer carriers can differ after parsing. Serialize the exact validated native values.
                if(!JSON.writeValueAsString(expected.get(field)).equals(JSON.writeValueAsString(proof.get(field))))throw new IOException("Process investigation no longer binds original evidence: "+field);
            }
            var absence=proof.get("native_absence");if(absence==null||!bool(absence,"all_absent")||!"Java ProcessHandle.of".equals(string(absence,"method")))throw new IOException("Original native absence proof missing");
            Instant.parse(string(absence,"checked_at"));Instant.parse(string(proof,"checked_at"));
            var current=nativeAbsence(expected);
            if(!JSON.writeValueAsString(current.get("checked_pids")).equals(JSON.writeValueAsString(absence.get("checked_pids"))))throw new IOException("Process investigation native inventory differs");
        }
        return true;
    }
    /** Overridable only for pure process guards. Presence, including PID reuse, rejects historical tombstone admission. */
    protected boolean nativeProcessPresent(long pid){return ProcessHandle.of(pid).isPresent();}
    private ObjectNode nativeAbsence(ObjectNode investigation)throws IOException{
        var pids=new TreeSet<Long>();for(var known:investigation.path("known_instances"))pids.add(positivePid(known,"pid"));
        for(var unknown:investigation.path("duplicate_tombstones"))pids.add(positivePid(unknown,"pid"));
        if(pids.isEmpty()||pids.size()>65||pids.contains(config.expectedPid))throw new IOException("Finite original producer inventory required");
        for(long pid:pids)if(nativeProcessPresent(pid))throw new IOException("Original or reused producer PID is still present: "+pid);
        var proof=JSON.createObjectNode();proof.put("method","Java ProcessHandle.of");proof.put("checked_at",Instant.now().toString());proof.put("all_absent",true);
        var ids=proof.putArray("checked_pids");pids.forEach(ids::add);return proof;
    }
    private ObjectNode reviewStoppedTombstones(Path ledger,String runId,Path intentPath)throws Exception{
        var evidence=JSON.createArrayNode();var intent=originalEvidence(evidence,"intent",intentPath);
        if(!ledger.toString().equals(string(intent,"ledger_path"))||!runId.equals(string(intent,"run_id"))||nonnegative(intent,"revision")!=4)throw new IOException("Actual original run intent differs");
        String id=string(intent,"invocation_id"),slice=string(intent,"slice_id"),prefix=intentPath.getFileName().toString().replace(".intent.json","");
        String ledgerIdentity=ledger.toString();if(File.separatorChar=='\\')ledgerIdentity=ledgerIdentity.toLowerCase(Locale.ROOT);
        Path claimPath=claimRoot(ledger).resolve(sha(JSON.writeValueAsBytes(List.of(ledgerIdentity,runId,slice,4L)))+".claim.json");
        var claim=originalEvidence(evidence,"claim",claimPath);
        for(String field:List.of("ledger_path","run_id","slice_id","invocation_id"))if(!string(intent,field).equals(string(claim,field)))throw new IOException("Atomic original submission claim differs");
        if(nonnegative(claim,"revision")!=4||!intentPath.toAbsolutePath().normalize().toString().equals(string(claim,"intent_path")))throw new IOException("Atomic original claim path differs");
        var request=originalEvidence(evidence,"request",config.artifactRoot.resolve(prefix+".request.json"));
        var start=originalEvidence(evidence,"started",config.artifactRoot.resolve(prefix+".process-started.json"));
        var stop=originalEvidence(evidence,"stopped",config.artifactRoot.resolve(prefix+".process-stopped.json"));
        var response=originalEvidence(evidence,"response",config.artifactRoot.resolve(prefix+".response.json"));
        LocalDate day=LocalDate.parse(string(intent,"trade_date"));requireCommon(response,"publish",day,id);
        if(nonnegative(request,"protocol_version")!=1||!"publish".equals(string(request,"operation"))||!id.equals(string(request,"invocation_id"))
                ||!day.toString().equals(string(request,"trade_date"))||!EtfMarketOverviewCachePublicationEnvelope.DATASET.equals(string(request,"dataset_id"))
                ||!JSON.writeValueAsString(target()).equals(JSON.writeValueAsString(request.get("target")))
                ||!intentPath.toString().equals(string(request,"intent_path"))||!sha(readBounded(intentPath)).equals(string(request,"intent_sha256")))throw new IOException("Original publish request differs");
        for(String field:List.of("invocation_id","pid","process_start","request_sha256","intent_sha256","process_tree_version"))
            if(!JSON.writeValueAsString(start.get(field)).equals(JSON.writeValueAsString(stop.get(field))))throw new IOException("Original process start/stop binding differs");
        if(!id.equals(string(start,"invocation_id"))||nonnegative(start,"process_tree_version")!=1||!bool(stop,"exit_observed")||nonnegative(stop,"exit_code")!=0
                ||!bool(stop,"tree_observation_complete")||!bool(stop,"response_present")||bool(stop,"process_tree_stopped")
                ||!sha(readBounded(intentPath)).equals(string(start,"intent_sha256"))
                ||!sha(readBounded(config.artifactRoot.resolve(prefix+".request.json"))).equals(string(start,"request_sha256")))throw new IOException("Historical process failure is outside duplicate tombstone boundary");
        long rootPid=positivePid(start,"pid");Instant rootBirth=Instant.parse(string(start,"process_start")),stoppedAt=Instant.parse(string(stop,"observed_at"));
        if(stoppedAt.isBefore(rootBirth))throw new IOException("Original stop predates launcher birth");
        if(!"VERIFIED_READTHROUGH".equals(string(response,"status"))||!bool(response,"owner_invoked")||!bool(response,"owner_sender_stopped")
                ||!bool(response.path("bridge_process"),"terminal_response_written")||nonnegative(response.path("bridge_process"),"exit_code")!=0)throw new IOException("Actual stopped original-owner response missing");
        var responseIntent=response.get("java_intent");
        if(responseIntent==null||!intentPath.toString().equals(string(responseIntent,"path"))||!sha(readBounded(intentPath)).equals(string(responseIntent,"sha256"))
                ||!ledger.toString().equals(string(responseIntent,"ledger_path"))||!runId.equals(string(responseIntent,"run_id"))||!slice.equals(string(responseIntent,"slice_id"))||nonnegative(responseIntent,"revision")!=4)throw new IOException("Actual response binds another original intent");
        for(String field:List.of("source_fingerprint","sources_fingerprint","target_id"))if(!string(intent,field).equals(string(response,field)))throw new IOException("Actual owner identity differs from original intent");
        var children=stop.get("observed_children");if(children==null||!children.isArray()||children.size()>64)throw new IOException("Finite original child evidence required");
        var known=new LinkedHashMap<Long,JsonNode>();var unknown=new ArrayList<JsonNode>();var observations=new HashMap<Long,JsonNode>();var unknownObservations=new ArrayList<JsonNode>();
        var nodes=new ArrayList<BridgeNode>();var seenUnknown=new HashSet<Long>();
        for(var child:children){
            long pid=positivePid(child,"pid");if(pid==rootPid)throw new IOException("Child aliases launcher identity");
            Path path=Path.of(string(child,"evidence_path")).toAbsolutePath().normalize();
            if(!path.startsWith(config.artifactRoot)||!sha(readBounded(path)).equals(string(child,"evidence_sha256")))throw new IOException("Original observed-child SHA differs");
            var observed=originalEvidence(evidence,"observed_child",path);
            for(String field:List.of("invocation_id","pid","process_start","request_sha256","intent_sha256","process_tree_version"))
                if(!JSON.writeValueAsString(start.get(field)).equals(JSON.writeValueAsString(observed.get(field))))throw new IOException("Original child binds another launcher");
            if(!bool(observed,"observed_descendant")||positivePid(observed,"child_pid")!=pid||!string(child,"process_start").equals(string(observed,"child_process_start"))
                    ||!Objects.equals(child.get("parent_pid"),observed.get("parent_pid")))throw new IOException("Original child birth or parent differs");
            Instant observedAt=Instant.parse(string(observed,"observed_at"));if(observedAt.isBefore(rootBirth)||observedAt.isAfter(stoppedAt))throw new IOException("Original child observation time differs");
            if("UNKNOWN".equals(string(child,"process_start"))){
                if(!seenUnknown.add(pid)||bool(child,"exit_observed")||!child.path("parent_pid").isNull())throw new IOException("Unknown instance is not an ended duplicate tombstone");
                unknown.add(child);unknownObservations.add(observed);
            }else{
                Instant born=Instant.parse(string(child,"process_start"));
                if(known.putIfAbsent(pid,child)!=null||!bool(child,"exit_observed")||born.isBefore(rootBirth)||born.isAfter(observedAt))throw new IOException("Original known instance is incomplete or ambiguous");
                Long parent=nullableCounter(child,"parent_pid");if(parent==null||parent<=0)throw new IOException("Original known parent missing");
                observations.put(pid,observed);nodes.add(new BridgeNode(pid,born,parent));
            }
        }
        if(unknown.isEmpty())throw new IOException("Only the certified duplicate UNKNOWN boundary is investigable");
        for(var node:nodes)if(!parentChainProved(node.pid,rootPid,rootBirth,nodes))throw new IOException("Original known parent chain is incomplete");
        long actualBridge=positivePid(response.path("bridge_process"),"pid");
        if(actualBridge!=positivePid(stop,"actual_bridge_pid")||actualBridge!=rootPid&&!known.containsKey(actualBridge)
                ||!parentChainProved(actualBridge,rootPid,rootBirth,nodes))throw new IOException("Actual bridge has no unique original birth and parent chain");
        var body=JSON.createObjectNode();body.put("status","VERIFIED_STOPPED_TREE_DUPLICATE_TOMBSTONES");body.put("investigation_version",1);
        body.put("invocation_id",id);body.put("ledger_path",ledger.toString());body.put("run_id",runId);body.put("slice_id",slice);body.put("launcher_pid",rootPid);body.put("actual_bridge_pid",actualBridge);
        var instances=body.putArray("known_instances");var root=instances.addObject();root.put("pid",rootPid);root.put("process_start",rootBirth.toString());root.putNull("parent_pid");root.put("exit_observed",true);
        root.put("evidence_path",config.artifactRoot.resolve(prefix+".process-started.json").toString());root.put("evidence_sha256",sha(readBounded(config.artifactRoot.resolve(prefix+".process-started.json"))));
        for(var child:known.values())instances.add(child.deepCopy());
        var tombstones=body.putArray("duplicate_tombstones");
        for(int i=0;i<unknown.size();i++){
            var child=unknown.get(i);long pid=positivePid(child,"pid");var previous=known.get(pid);
            if(previous==null||!Instant.parse(string(unknownObservations.get(i),"observed_at")).isAfter(Instant.parse(string(observations.get(pid),"observed_at"))))throw new IOException("Unknown PID is new or does not follow its unique complete original observation");
            var row=tombstones.addObject();row.put("pid",pid);row.put("matched_known_birth",string(previous,"process_start"));row.put("observed_at",string(unknownObservations.get(i),"observed_at"));row.put("evidence_path",string(child,"evidence_path"));row.put("evidence_sha256",string(child,"evidence_sha256"));
        }
        body.set("original_evidence",evidence);return body;
    }
    private JsonNode originalEvidence(ArrayNode evidence,String kind,Path path)throws IOException{
        byte[] bytes=readBounded(path);var row=evidence.addObject();row.put("kind",kind);row.put("path",path.toAbsolutePath().normalize().toString());row.put("sha256",sha(bytes));return JSON.readTree(bytes);
    }
    private static long positivePid(JsonNode row,String field)throws IOException{long value=nonnegative(row,field);if(value<=0)throw new IOException("Positive original process identity required");return value;}

    private static boolean endedIdentity(long pid,String birth){
        if("UNKNOWN".equals(birth))return false;Instant original;try{original=Instant.parse(birth);}catch(java.time.DateTimeException bad){return false;}
        var current=ProcessHandle.of(pid);if(current.isEmpty()||!current.get().isAlive())return true;
        var currentBirth=current.get().info().startInstant();return currentBirth.isPresent()&&!currentBirth.get().equals(original);
    }

    /** Independent check in addition to the Python bridge's direct revision/event/cancellation check. */
    public void validateSubmission(EtfMarketOverviewCachePublicationEnvelope envelope,VerifiedBatchExecutor.Submission submission) throws Exception {
        if (!envelope.knownSourceDate() || submission.revision()!=4 || !envelope.sourceFingerprint().equals(submission.sourceFingerprint()))
            throw new IllegalStateException("Exact known-day revision4 submission required");
        Path workspace=Path.of("").toAbsolutePath().normalize();
        Path ledgerPath=submission.ledgerPath().toRealPath();
        if (!ledgerPath.startsWith(workspace.resolve("var").toRealPath())) throw new IllegalStateException("Workspace var ledger required");
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var entry=ledger.get(submission.sliceId());var run=ledger.getRun(submission.runId());
        if (entry==null || run==null || entry.kind()!=SyncRunLedger.Kind.SLICE || entry.state()!=SyncRunState.SUBMITTED
                || entry.revision()!=4 || !entry.runId().equals(submission.runId()) || run.jobVersion()!=1
                || !Set.of("data.etf_market_overview_daily_cache","write.etf_market_overview_daily_cache").contains(run.jobId())
                || !run.targetId().equals(envelope.targetId())) throw new IllegalStateException("Not an admitted actual shared submission");
        var frozen=JSON.readTree(run.frozenJson());var definition=frozen.get("definition");var params=frozen.get("parameters");
        if(definition==null||!run.jobId().equals(string(definition,"jobId"))||nonnegative(definition,"version")!=1
                ||!EtfMarketOverviewCachePublicationEnvelope.CACHE.equals(string(definition,"datasetId"))||nonnegative(definition,"datasetVersion")!=1)
            throw new IllegalStateException("Frozen job definition differs");
        if(run.jobId().equals("data.etf_market_overview_daily_cache")){
            if(!envelope.sourcesFingerprint().equals(string(params,"source_version"))||!envelope.targetId().equals(string(params,"target_id"))
                    ||!Set.of("INCREMENTAL","MATERIALIZE").contains(string(frozen,"mode"))
                    ||LocalDate.parse(string(frozen,"from")).isAfter(envelope.tradeDate())||LocalDate.parse(string(frozen,"to")).isBefore(envelope.tradeDate()))
                throw new IllegalStateException("Frozen canonical source, target or day window differs");
        }else{
            if(!"INGEST".equals(string(frozen,"mode")))throw new IllegalStateException("Prepared write mode differs");
            for(String field:List.of("groupBatch","memberBatch","planFingerprint","payloadFingerprint"))string(params,field);
        }
        var payload=JSON.readTree(entry.payloadJson());var evidence=payload.get("responseEvidence");
        if (evidence!=null && evidence.isTextual()) evidence=JSON.readTree(evidence.textValue());
        if (!submission.sourceFingerprint().equals(string(payload,"sourceFingerprint")) || nonnegative(payload,"returnedRows")!=1
                || evidence==null || !envelope.previewPath().toAbsolutePath().normalize().toString().equals(string(evidence,"preview_path"))
                || !envelope.previewSha256().equals(string(evidence,"preview_sha256"))) throw new IllegalStateException("Durable preview evidence differs");
        if (run.jobId().equals("write.etf_market_overview_daily_cache")) {
            var caller=JSON.readTree(envelope.responseEvidence());
            for(String field:List.of("prepared_input_path","prepared_input_sha256"))
                if(!string(caller,field).equals(string(evidence,field))) throw new IllegalStateException("Durable prepared caller evidence differs");
            Path prepared=Path.of(string(caller,"prepared_input_path")).toRealPath();
            if(!prepared.startsWith(workspace.toRealPath())||!sha(readBounded(prepared)).equals(string(caller,"prepared_input_sha256")))
                throw new IllegalStateException("Immutable prepared input differs");
        }
        ledger.requireUncancelledRevision4SubmissionEvent(submission.runId(),submission.sliceId(),entry.payloadJson());
    }

    protected void requireConfigured() throws IOException {
        if (config.expectedPid<=0) throw new IllegalStateException("D101 explicit private process configuration is absent");
        Path root=Path.of("var/d101-isolated-questdb").toAbsolutePath().normalize();
        Path artifacts=Path.of("artifacts/java-migration/D101/commands").toAbsolutePath().normalize();
        if (!config.privateRoot.equals(root) || !config.artifactRoot.startsWith(artifacts)
                || !Files.isRegularFile(config.pythonExecutable) || !Files.isRegularFile(config.bridgeScript))
            throw new IllegalStateException("Only explicit D101 private root and command artifacts are admitted");
        Files.createDirectories(config.artifactRoot);
        if (!config.artifactRoot.toRealPath().startsWith(artifacts.toRealPath()) || !config.privateRoot.toRealPath().equals(root.toRealPath()))
            throw new IllegalStateException("D101 path identity differs");
    }
    private ObjectNode target(){var value=JSON.createObjectNode();value.put("private_root",config.privateRoot.toString());value.put("expected_pid",config.expectedPid);value.put("http_port",19020);value.put("pg_port",18832);return value;}
    private ObjectNode request(String operation,LocalDate date,String id){var value=JSON.createObjectNode();value.put("protocol_version",1);value.put("operation",operation);value.put("dataset_id",EtfMarketOverviewCachePublicationEnvelope.DATASET);value.put("trade_date",date.toString());value.put("invocation_id",id);value.set("target",target());value.put("max_source_rows",config.maxSourceRows);return value;}
    private Invocation invocation(String operation){String id=UUID.randomUUID().toString();String prefix="java-owner-"+operation+"-"+id;return new Invocation(config.artifactRoot.resolve(prefix+".request.json"),config.artifactRoot.resolve(prefix+".response.json"),config.artifactRoot.resolve(prefix+".intent.json"),config.artifactRoot.resolve(prefix+".process-started.json"),config.artifactRoot.resolve(prefix+".process-stopped.json"),config.artifactRoot.resolve(prefix+".log"),id);}
    private List<Path> intents() throws IOException {if(!Files.isDirectory(config.artifactRoot))return List.of();try(var paths=Files.list(config.artifactRoot)){var files=paths.filter(path->path.getFileName().toString().endsWith(".intent.json")).limit(1001).toList();if(files.size()>1000)throw new IOException("Finite intent inventory exceeded");return files;}}
    private static Path claimRoot(Path ledgerPath){return ledgerPath.toAbsolutePath().normalize().getParent().resolve("d101-owner-intent-claims");}
    private void claimSubmission(VerifiedBatchExecutor.Submission submission,Invocation call)throws IOException{
        Path ledger=submission.ledgerPath().toAbsolutePath().normalize();
        String ledgerIdentity=ledger.toString();if(java.io.File.separatorChar=='\\')ledgerIdentity=ledgerIdentity.toLowerCase(Locale.ROOT);
        String fingerprint=sha(JSON.writeValueAsBytes(List.of(ledgerIdentity,submission.runId(),submission.sliceId(),submission.revision())));
        Path directory=claimRoot(ledger);Files.createDirectories(directory);
        var claim=JSON.createObjectNode();claim.put("ledger_path",ledger.toString());claim.put("run_id",submission.runId());claim.put("slice_id",submission.sliceId());
        claim.put("revision",submission.revision());claim.put("invocation_id",call.id);claim.put("intent_path",call.intent.toString());
        try{newJson(directory.resolve(fingerprint+".claim.json"),claim);}
        catch(FileAlreadyExistsException already){throw new IllegalStateException("Original slice already has an atomic owner claim; reconcile without resend",already);}
    }
    private void requireNoEarlierIntent(VerifiedBatchExecutor.Submission submission) throws IOException {
        for(var file:intents()){var node=JSON.readTree(readBounded(file));if(submission.ledgerPath().toString().equals(node.path("ledger_path").asText())&&submission.sliceId().equals(node.path("slice_id").asText()))throw new IllegalStateException("Original slice already has an immutable owner intent; reconcile without resend");}
    }
    private record ObservedChild(ProcessHandle handle,long pid,Instant birth,Long parentPid,
                                 java.util.concurrent.CompletableFuture<ProcessHandle> exited,Path evidencePath,String evidenceSha){}
    private void observeChildren(Process process,Invocation call,ObjectNode root,Map<String,ObservedChild> observed)throws IOException{
        // Capture the real descendants while the launcher is alive. Python's wall-clock started_at is not an OS birth identity.
        try(var descendants=process.descendants()){
            for(var child:descendants.limit(65).toList()){
                Instant birth=child.info().startInstant().orElse(null);String key=child.pid()+"@"+(birth==null?"UNKNOWN":birth);
                if(observed.containsKey(key))continue;
                if(birth==null&&!child.isAlive()){
                    var previous=observed.values().stream().filter(known->known.pid==child.pid()).toList();
                    // A stale descendants snapshot can outlive info(). Retain the already observed, ended instance.
                    // A new PID, live sample, missing retained identity or readable different birth is never ignored.
                    if(previous.size()==1&&previous.getFirst().birth!=null&&previous.getFirst().parentPid!=null
                            &&!previous.getFirst().evidenceSha.isEmpty()&&childEnded(previous.getFirst()))continue;
                }
                if(observed.size()>=64)throw new IOException("Observed process tree exceeds64children; submission remains unknown");
                Path proof=call.started.resolveSibling(call.started.getFileName().toString().replace(".process-started.json",".process-observed-"+observed.size()+".json"));
                Long parentPid=child.parent().map(ProcessHandle::pid).orElse(null);
                var exitFuture=child.onExit();
                var node=root.deepCopy();node.put("process_tree_version",1);node.put("observed_descendant",true);node.put("child_pid",child.pid());node.put("child_process_start",birth==null?"UNKNOWN":birth.toString());
                if(parentPid==null)node.putNull("parent_pid");else node.put("parent_pid",parentPid);
                node.put("observed_at",Instant.now().toString());
                // Retain the handle even if durable observation storage fails; that process must still be stopped.
                observed.put(key,new ObservedChild(child,child.pid(),birth,parentPid,exitFuture,proof,""));
                newJson(proof,node);observed.put(key,new ObservedChild(child,child.pid(),birth,parentPid,exitFuture,proof,sha(readBounded(proof))));
            }
        }
    }
    private static boolean childEnded(ObservedChild child){
        if(child.birth==null)return false;
        if(!child.handle.isAlive())return true;
        var current=child.handle.info().startInstant();
        // A reused PID denotes a different process. Never terminate that replacement.
        return current.isPresent()&&!current.get().equals(child.birth);
    }
    private static boolean originalChildAlive(ObservedChild child){
        if(!child.handle.isAlive())return false;
        var current=child.handle.info().startInstant();return child.birth!=null&&current.isPresent()&&current.get().equals(child.birth);
    }
    private static boolean waitChildren(Collection<ObservedChild> children,long deadline)throws InterruptedException{
        boolean complete=true;
        for(var child:children){
            if(childEnded(child))continue;
            if(child.birth==null){complete=false;continue;}
            long remaining=deadline-System.nanoTime();if(remaining<=0){complete=false;continue;}
            try{child.exited.get(remaining,TimeUnit.NANOSECONDS);}catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException failed){complete=false;}
            if(!childEnded(child))complete=false;
        }
        return complete;
    }
    private static Long responseBridgePid(Path response)throws IOException{
        if(!Files.isRegularFile(response))return null;var value=JSON.readTree(readBounded(response));var bridge=value.get("bridge_process");
        return bridge==null?null:nonnegative(bridge,"pid");
    }
    private record BridgeNode(long pid,Instant birth,Long parentPid){}
    private static boolean parentChainProved(long bridgePid,long rootPid,Instant rootBirth,Collection<BridgeNode> nodes){
        if(rootBirth==null)return false;if(bridgePid==rootPid)return true;
        var byPid=new HashMap<Long,List<BridgeNode>>();for(var node:nodes)byPid.computeIfAbsent(node.pid,ignored->new ArrayList<>()).add(node);
        var seen=new HashSet<Long>();long cursor=bridgePid;
        while(cursor!=rootPid){
            if(seen.size()>=64||!seen.add(cursor))return false;var matches=byPid.get(cursor);
            if(matches==null||matches.size()!=1)return false;var node=matches.getFirst();if(node.birth==null||node.parentPid==null)return false;
            Instant parentBirth=rootBirth;
            if(node.parentPid!=rootPid){var parents=byPid.get(node.parentPid);if(parents==null||parents.size()!=1||parents.getFirst().birth==null)return false;parentBirth=parents.getFirst().birth;}
            if(parentBirth.isAfter(node.birth))return false;cursor=node.parentPid;
        }
        return true;
    }
    private static boolean observedBridge(Long bridgePid,long rootPid,Instant rootBirth,Collection<ObservedChild> observed){
        if(bridgePid==null)return false;
        return parentChainProved(bridgePid,rootPid,rootBirth,observed.stream().map(child->new BridgeNode(child.pid,child.birth,child.parentPid)).toList());
    }
    private void invoke(Invocation call,BooleanSupplier cancelled)throws Exception{
        Files.write(call.log,new byte[0],StandardOpenOption.CREATE_NEW);
        List<String> argv=List.of(config.pythonExecutable.toString(),"-B",config.bridgeScript.toString(),"--request",call.request.toString(),"--response",call.response.toString());
        Process process=launcher.start(argv,call.log);long pid=process.pid();Instant birth=process.info().startInstant().orElse(null);
        boolean exited=false,forced=false,treeStopped=false,observationComplete=true;Exception failure=null;Long bridgePid=null;
        var observed=new LinkedHashMap<String,ObservedChild>();
        var start=JSON.createObjectNode();start.put("invocation_id",call.id);start.put("pid",pid);start.put("process_tree_version",1);
        start.put("process_start",birth==null?"UNKNOWN":birth.toString());start.put("request_sha256",sha(readBounded(call.request)));
        start.put("intent_sha256",Files.isRegularFile(call.intent)?sha(readBounded(call.intent)):"");
        try{
            newJson(call.started,start);if(birth==null)throw new IOException("Launcher OS birth identity is unavailable");
            long deadline=System.nanoTime()+config.timeout.toNanos();
            do{
                try{observeChildren(process,call,start,observed);}catch(Exception unobserved){observationComplete=false;throw unobserved;}
                exited=process.waitFor(100,TimeUnit.MILLISECONDS);
                if(exited)break;
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline
                        ||Files.size(call.log)>MAX_LOG_BYTES||Files.exists(call.response)&&Files.size(call.response)>MAX_JSON_BYTES)
                    throw new IOException("Owner process cancelled, timed out or exceeded output budget; do not retry");
            }while(true);
            if(process.exitValue()!=0)throw new IOException("Owner bridge launcher exited unsuccessfully; do not retry");
            bridgePid=responseBridgePid(call.response);
            if(!observedBridge(bridgePid,pid,birth,observed.values()))throw new IOException("Response bridge PID is not the actual launcher or an observed descendant with OS birth identity");
            treeStopped=waitChildren(observed.values(),System.nanoTime()+Duration.ofSeconds(5).toNanos());
            if(!treeStopped)throw new IOException("Observed bridge process tree has not exited; submission remains unknown");
        }catch(Exception error){failure=error;}
        finally{
            if(!exited||!treeStopped){
                forced=true;
                // Capture any last descendants before killing the launcher, retaining handles rather than refetching arbitrary PIDs.
                if(!exited)try{observeChildren(process,call,start,observed);}catch(Exception unobserved){observationComplete=false;if(failure==null)failure=unobserved;}
                for(var child:observed.values())if(originalChildAlive(child))child.handle.destroyForcibly();
                if(!exited)process.destroyForcibly();
                boolean interrupted=Thread.interrupted();long cleanupDeadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
                try{
                    if(!exited){long remaining=cleanupDeadline-System.nanoTime();exited=remaining>0&&process.waitFor(remaining,TimeUnit.NANOSECONDS);}
                    treeStopped=exited&&birth!=null&&observationComplete&&waitChildren(observed.values(),cleanupDeadline);
                }finally{if(interrupted)Thread.currentThread().interrupt();}
            }
            if(bridgePid==null)try{bridgePid=responseBridgePid(call.response);}catch(IOException malformed){if(failure==null)failure=malformed;}
            if(exited){
                var stop=start.deepCopy();stop.put("exit_observed",true);stop.put("process_tree_stopped",treeStopped);stop.put("forced",forced);
                stop.put("tree_observation_complete",observationComplete);stop.put("response_present",Files.isRegularFile(call.response));
                stop.put("bridge_identity_proved",observedBridge(bridgePid,pid,birth,observed.values()));
                if(bridgePid==null)stop.putNull("actual_bridge_pid");else stop.put("actual_bridge_pid",bridgePid);
                stop.put("exit_code",process.exitValue());stop.put("observed_at",Instant.now().toString());
                var children=stop.putArray("observed_children");for(var child:observed.values()){
                    var row=children.addObject();row.put("pid",child.pid);row.put("process_start",child.birth==null?"UNKNOWN":child.birth.toString());row.put("exit_observed",childEnded(child));
                    if(child.parentPid==null)row.putNull("parent_pid");else row.put("parent_pid",child.parentPid);
                    row.put("evidence_path",child.evidencePath.toString());row.put("evidence_sha256",child.evidenceSha);
                }
                newJson(call.stopped,stop);
            }
        }
        if(!exited||!treeStopped)throw new IOException("Owner process tree termination is unproved; reconcile required",failure);
        if(failure!=null)throw failure;
        if(Files.size(call.log)>MAX_LOG_BYTES)throw new IOException("Process output budget exceeded");
        var terminal=JSON.readTree(readBounded(call.response));var bridge=terminal.get("bridge_process");
        if(bridge==null||!bool(bridge,"terminal_response_written")||nonnegative(bridge,"exit_code")!=0)
            throw new IOException("Response does not bind the terminal exited bridge process");
    }
    public EtfMarketOverviewCachePublicationEnvelope parsePreview(JsonNode value,LocalDate day,String invocation,Path path,String digest) throws IOException {
        requireCommon(value,"preview",day,invocation);
        if(!"PREVIEW_VERIFIED".equals(string(value,"status")) || !bool(value,"owner_sender_stopped"))throw new IOException("Not a stopped SELECT-only preview");
        String version=hash(value,"source_version"),sourceFp=hash(value,"sources_fingerprint"),targetFp=hash(value,"targets_fingerprint"),unitFp=hash(value,"source_fingerprint");
        String targetId=string(value,"target_id");if(!targetId.matches("questdb-[0-9a-f]{64}"))throw new IOException("D101 private identity required");
        var sources=snapshotMap(value.get("source_snapshots"),EtfMarketOverviewCachePublicationEnvelope.SOURCE_TABLES);
        var targets=snapshotMap(value.get("target_snapshots"),EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES);
        validateTargetCounts(targets,value.get("target_actual_row_counts"));
        JsonNode census=value.get("raw_source_census");exactKeys(census,EtfMarketOverviewCachePublicationEnvelope.SOURCE_TABLES);long total=0;
        for(String table:EtfMarketOverviewCachePublicationEnvelope.SOURCE_TABLES){var row=census.get(table);long count=nonnegative(row,"row_count");if(count!=nonnegative(row,"complete_key_count"))throw new IOException("Incomplete source key census");total=Math.addExact(total,count);hash(row,"full_field_sha256");hash(row,"complete_key_sha256");var fields=row.get("fields");if(fields==null||!fields.isArray()||fields.isEmpty()||fields.size()>256)throw new IOException("Complete bounded source fields required");var names=new HashSet<String>();for(var field:fields){if(!field.isTextual()||!names.add(field.textValue()))throw new IOException("Source field census invalid");}if(!names.containsAll(Set.of("timestamp","ts_code")))throw new IOException("Complete source keys required");exactKeys(row.get("null_counts"),names);for(String field:names)if(nonnegative(row.get("null_counts"),field)>count)throw new IOException("Invalid null census");}
        if(total>config.maxSourceRows)throw new IOException("Total complete source census exceeds budget");
        boolean known=bool(value,"known_source_date");var cache=parseCache(value.get("expected_cache_records"),day,version).orElse(null);var receipt=parseReceipt(value.get("expected_receipt"));
        long hits=nonnegative(value,"expected_owner_hits"),misses=nonnegative(value,"expected_owner_misses");if(hits+misses!=(known?1:0))throw new IOException("Invalid owner date inventory");
        if(!known && (cache!=null||receipt!=null) || known&&receipt==null)throw new IOException("Publication does not match known source anchor");
        if(cache==null&&!bool(value,"cache_key_absent_before"))throw new IOException("Absent publication needs actual current-key absence");
        JsonNode schemas=value.get("validated_schemas");var all=new ArrayList<String>(EtfMarketOverviewCachePublicationEnvelope.SOURCE_TABLES);all.addAll(EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES);exactKeys(schemas,all);
        var files=value.get("frozen_python_files_sha256");if(files==null||!files.isObject()||files.isEmpty())throw new IOException("Original owner file identities required");for(var item:files)if(!item.isTextual()||!item.textValue().matches("[0-9a-f]{64}"))throw new IOException("Owner file SHA invalid");
        String evidence=JSON.writeValueAsString(Map.of("preview_path",path.toAbsolutePath().normalize().toString(),"preview_sha256",digest));
        return new EtfMarketOverviewCachePublicationEnvelope(day,version,cache,receipt,sources,targets,sourceFp,targetFp,targetId,unitFp,total,known,path.toAbsolutePath().normalize(),digest,evidence,value);
    }
    private void requireCommon(JsonNode value,String operation,LocalDate day,String invocation) throws IOException {
        if(nonnegative(value,"protocol_version")!=1 || !operation.equals(string(value,"operation")) || !EtfMarketOverviewCachePublicationEnvelope.DATASET.equals(string(value,"dataset_id"))
                || !day.toString().equals(string(value,"trade_date")) || !invocation.equals(string(value,"invocation_id")))throw new IOException("Bridge response identity differs");
        var actual=value.get("target");if(actual==null || nonnegative(actual,"expected_pid")!=config.expectedPid||nonnegative(actual,"http_port")!=19020||nonnegative(actual,"pg_port")!=18832
                || !Path.of(string(actual,"private_root")).toAbsolutePath().normalize().equals(config.privateRoot))throw new IOException("Bridge response private target differs");
        var attest=value.get("process_attestation");
        if(attest==null||nonnegative(attest,"pid")!=config.expectedPid||!"127.0.0.1".equals(string(attest,"host"))
                ||nonnegative(attest,"http_port")!=19020||nonnegative(attest,"pg_port")!=18832
                ||!Path.of(string(attest,"data_root")).toAbsolutePath().normalize().equals(config.privateRoot))
            throw new IOException("Exact private process attestation required");
    }
    public static Map<String,JsonNode> snapshotMap(JsonNode node,List<String> tables) throws IOException {
        exactKeys(node,tables);var result=new LinkedHashMap<String,JsonNode>();
        for(String table:tables){var row=node.get(table);validateSnapshot(row,EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES.contains(table));result.put(table,row.deepCopy());}return result;
    }
    public static void validateSnapshot(JsonNode node)throws IOException{validateSnapshot(node,false);}
    /** Only an uninitialized target can carry an explicit null physical txn; it never means txn0. */
    public static void validateSnapshot(JsonNode node,boolean allowUninitializedTarget)throws IOException{
        var p=node==null?null:node.get("physical");var w=node==null?null:node.get("wal");
        if(p==null||w==null||!bool(node,"settled")||!bool(p,"walEnabled")||!bool(p,"dedup")||bool(p,"table_suspended")||bool(w,"suspended")
                ||nonnegative(p,"wal_pending_row_count")!=0||nonnegative(w,"bufferedTxnSize")!=0||nonnegative(w,"writerTxn")!=nonnegative(w,"sequencerTxn"))
            throw new IOException("Unsettled physical/WAL snapshot");
        nonnegative(p,"id");string(p,"directoryName");string(p,"partitionBy");string(p,"designatedTimestamp");
        Long txn=nullableCounter(p,"table_txn");
        if(txn==null){
            Long rows=nullableCounter(p,"table_row_count");
            if(!allowUninitializedTarget||nonnegative(w,"sequencerTxn")!=0||rows!=null&&rows!=0)
                throw new IOException("Null physical txn requires a newly empty target with WAL0 and explicit raw rows null/0");
        }
    }
    private static void validateTargetCounts(Map<String,JsonNode> targets,JsonNode counts)throws IOException{
        exactKeys(counts,EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES);
        for(String table:EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES){long actual=nonnegative(counts,table);
            if(nullableCounter(targets.get(table).get("physical"),"table_txn")==null&&actual!=0)
                throw new IOException("Null target txn needs authoritative actual COUNT0");
        }
    }
    private static void validateAfterTargets(JsonNode before,JsonNode after)throws IOException{
        var end=snapshotMap(after.get("target_snapshots_after"),EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES);
        validateTargetCounts(end,after.get("target_actual_row_counts_after"));
        for(var table:EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES){var a=before.path("target_snapshots").path(table);var b=end.get(table);
            Long oldTxn=nullableCounter(a.get("physical"),"table_txn"),newTxn=nullableCounter(b.get("physical"),"table_txn");
            if(nonnegative(a.get("physical"),"id")!=nonnegative(b.get("physical"),"id")||!string(a.get("physical"),"directoryName").equals(string(b.get("physical"),"directoryName"))
                    ||oldTxn!=null&&(newTxn==null||newTxn<oldTxn)||nonnegative(b.get("wal"),"sequencerTxn")<nonnegative(a.get("wal"),"sequencerTxn"))
                throw new IOException("Target physical identity or frontier changed unexpectedly");
        }
    }
    public static Long nullableCounter(JsonNode row,String field)throws IOException{
        var value=row==null?null:row.get(field);if(value==null)throw new IOException("Explicit metadata counter required: "+field);
        return value.isNull()?null:nonnegative(row,field);
    }
    private static boolean samePublication(EtfMarketOverviewCachePublicationEnvelope a,EtfMarketOverviewCachePublicationEnvelope b){return a.key().equals(b.key())&&Objects.equals(a.cache(),b.cache())&&Objects.equals(a.receipt(),b.receipt())&&a.sources().equals(b.sources())&&a.targets().equals(b.targets())&&a.targetId().equals(b.targetId())&&a.sourceFingerprint().equals(b.sourceFingerprint());}
    public static Optional<EtfMarketOverviewDailyCache> parseCache(JsonNode rows,LocalDate day,String version) throws IOException {
        if(rows==null||!rows.isArray()||rows.size()>1)throw new IOException("One exact cache row at most required");if(rows.isEmpty())return Optional.empty();var row=rows.get(0);exactKeys(row,List.of("trade_date","etf_count","total_share","total_size_yi","source_version"));
        var result=new EtfMarketOverviewDailyCache(businessDate(string(row,"trade_date")),nonnegative(row,"etf_count"),nullableDouble(row,"total_share"),nullableDouble(row,"total_size_yi"),hash(row,"source_version"));
        if(!result.tradeDate().equals(day)||!result.sourceVersion().equals(version))throw new IOException("Cache complete key differs");return Optional.of(result);
    }
    public static MarketBarometerCacheCoverage parseReceipt(JsonNode row) throws IOException {if(row==null||row.isNull())return null;exactKeys(row,List.of("trade_date","dataset_id","source_version","row_count","content_digest"));return new MarketBarometerCacheCoverage(businessDate(string(row,"trade_date")),string(row,"dataset_id"),hash(row,"source_version"),nonnegative(row,"row_count"),hash(row,"content_digest"));}
    public static LocalDate businessDate(String text) throws IOException {try{Instant time=Instant.parse(text);LocalDate day=time.atOffset(ZoneOffset.UTC).toLocalDate();if(!time.equals(day.atStartOfDay().toInstant(ZoneOffset.UTC)))throw new IOException("Exact UTC midnight required");return day;}catch(java.time.DateTimeException error){throw new IOException("UTC timestamp required",error);}}
    public static String string(JsonNode row,String key) throws IOException {var node=row==null?null:row.get(key);if(node==null||!node.isTextual()||node.textValue().isBlank())throw new IOException("Required string: "+key);return node.textValue();}
    public static long nonnegative(JsonNode row,String key) throws IOException {var node=row==null?null:row.get(key);if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<0)throw new IOException("Required exact nonnegative LONG: "+key);return node.longValue();}
    public static boolean bool(JsonNode row,String key) throws IOException {var node=row==null?null:row.get(key);if(node==null||!node.isBoolean())throw new IOException("Required boolean: "+key);return node.booleanValue();}
    private static Double nullableDouble(JsonNode row,String key) throws IOException {var node=row.get(key);if(node==null)throw new IOException("Missing nullable field");if(node.isNull())return null;if(!node.isNumber()||!Double.isFinite(node.doubleValue()))throw new IOException("Finite binary64 required");return node.doubleValue();}
    private static String hash(JsonNode row,String key) throws IOException {String hash=string(row,key);if(!hash.matches("[0-9a-f]{64}"))throw new IOException("Lowercase SHA required: "+key);return hash;}
    public static void exactKeys(JsonNode node,Collection<String> expected) throws IOException {if(node==null||!node.isObject())throw new IOException("Exact object required");var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);if(!keys.equals(new HashSet<>(expected)))throw new IOException("Object fields differ");}
    public static byte[] readBounded(Path path) throws IOException {if(!Files.isRegularFile(path)||Files.size(path)>MAX_JSON_BYTES)throw new IOException("Bounded regular JSON artifact required");return FileEvidenceStore.readBounded(path,MAX_JSON_BYTES,()->new IOException("JSON budget exceeded"));}
    private static void newJson(Path path,JsonNode value) throws IOException {byte[] bytes=JSON.writeValueAsBytes(value);if(bytes.length>MAX_JSON_BYTES)throw new IOException("JSON budget exceeded");FileEvidenceStore.writeNewDurable(path,bytes);}
    public static String sha(byte[] bytes){return FileEvidenceStore.sha256(bytes);}
}
