package com.zoutrankil.data.derived.storage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.derived.domain.EtfMarketOverviewOwnerInvocation;
import com.zoutrankil.data.derived.domain.EtfMarketOverviewProcessTree.BridgeNode;
import com.zoutrankil.data.derived.port.EtfMarketOverviewProcess;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.derived.domain.EtfMarketOverviewOwnerJson.*;
import static com.zoutrankil.data.derived.domain.EtfMarketOverviewProcessTree.parentChainProved;

/** Physical original-owner process lifecycle; every handle is local to one invocation. */
public final class EtfMarketOverviewOwnerProcess implements EtfMarketOverviewProcess {
    private static final int MAX_JSON_BYTES=1024*1024;
    private static final int MAX_LOG_BYTES=2*1024*1024;
    private static final ObjectMapper JSON=JobDefinitionJson.mapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    @FunctionalInterface public interface ProcessLauncher { Process start(List<String> argv,Path log) throws IOException; }
    private final Path pythonExecutable;
    private final Path bridgeScript;
    private final Duration timeout;
    private final ProcessLauncher launcher;
    public EtfMarketOverviewOwnerProcess(Path pythonExecutable, Path bridgeScript, Duration timeout) {
        this(pythonExecutable, bridgeScript, timeout, (argv,log)->new ProcessBuilder(argv).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start());
    }
    public EtfMarketOverviewOwnerProcess(Path pythonExecutable, Path bridgeScript, Duration timeout, ProcessLauncher launcher) {
        this.pythonExecutable=Objects.requireNonNull(pythonExecutable);
        this.bridgeScript=Objects.requireNonNull(bridgeScript);
        this.timeout=Objects.requireNonNull(timeout);
        this.launcher=Objects.requireNonNull(launcher);
    }
    @Override public boolean nativeProcessPresent(long pid) { return ProcessHandle.of(pid).isPresent(); }
    @Override public boolean endedIdentity(long pid,String birth){
        if("UNKNOWN".equals(birth))return false;Instant original;try{original=Instant.parse(birth);}catch(java.time.DateTimeException bad){return false;}
        var current=ProcessHandle.of(pid);if(current.isEmpty()||!current.get().isAlive())return true;
        var currentBirth=current.get().info().startInstant();return currentBirth.isPresent()&&!currentBirth.get().equals(original);
    }
    private record ObservedChild(ProcessHandle handle,long pid,Instant birth,Long parentPid,
                                 java.util.concurrent.CompletableFuture<ProcessHandle> exited,Path evidencePath,String evidenceSha){}
    private void observeChildren(Process process,EtfMarketOverviewOwnerInvocation call,ObjectNode root,Map<String,ObservedChild> observed)throws IOException{
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
                Path proof=call.started().resolveSibling(call.started().getFileName().toString().replace(".process-started.json",".process-observed-"+observed.size()+".json"));
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
    private static boolean observedBridge(Long bridgePid,long rootPid,Instant rootBirth,Collection<ObservedChild> observed){
        if(bridgePid==null)return false;
        return parentChainProved(bridgePid,rootPid,rootBirth,observed.stream().map(child->new BridgeNode(child.pid,child.birth,child.parentPid)).toList());
    }
    @Override public void invoke(EtfMarketOverviewOwnerInvocation call,BooleanSupplier cancelled)throws Exception{
        Files.write(call.log(),new byte[0],StandardOpenOption.CREATE_NEW);
        List<String> argv=List.of(pythonExecutable.toString(),"-B",bridgeScript.toString(),"--request",call.request().toString(),"--response",call.response().toString());
        Process process=launcher.start(argv,call.log());long pid=process.pid();Instant birth=process.info().startInstant().orElse(null);
        boolean exited=false,forced=false,treeStopped=false,observationComplete=true;Exception failure=null;Long bridgePid=null;
        var observed=new LinkedHashMap<String,ObservedChild>();
        var start=JSON.createObjectNode();start.put("invocation_id",call.id());start.put("pid",pid);start.put("process_tree_version",1);
        start.put("process_start",birth==null?"UNKNOWN":birth.toString());start.put("request_sha256",sha(readBounded(call.request())));
        start.put("intent_sha256",Files.isRegularFile(call.intent())?sha(readBounded(call.intent())):"");
        try{
            newJson(call.started(),start);if(birth==null)throw new IOException("Launcher OS birth identity is unavailable");
            long deadline=System.nanoTime()+timeout.toNanos();
            do{
                try{observeChildren(process,call,start,observed);}catch(Exception unobserved){observationComplete=false;throw unobserved;}
                exited=process.waitFor(100,TimeUnit.MILLISECONDS);
                if(exited)break;
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline
                        ||Files.size(call.log())>MAX_LOG_BYTES||Files.exists(call.response())&&Files.size(call.response())>MAX_JSON_BYTES)
                    throw new IOException("Owner process cancelled, timed out or exceeded output budget; do not retry");
            }while(true);
            if(process.exitValue()!=0)throw new IOException("Owner bridge launcher exited unsuccessfully; do not retry");
            bridgePid=responseBridgePid(call.response());
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
            if(bridgePid==null)try{bridgePid=responseBridgePid(call.response());}catch(IOException malformed){if(failure==null)failure=malformed;}
            if(exited){
                var stop=start.deepCopy();stop.put("exit_observed",true);stop.put("process_tree_stopped",treeStopped);stop.put("forced",forced);
                stop.put("tree_observation_complete",observationComplete);stop.put("response_present",Files.isRegularFile(call.response()));
                stop.put("bridge_identity_proved",observedBridge(bridgePid,pid,birth,observed.values()));
                if(bridgePid==null)stop.putNull("actual_bridge_pid");else stop.put("actual_bridge_pid",bridgePid);
                stop.put("exit_code",process.exitValue());stop.put("observed_at",Instant.now().toString());
                var children=stop.putArray("observed_children");for(var child:observed.values()){
                    var row=children.addObject();row.put("pid",child.pid);row.put("process_start",child.birth==null?"UNKNOWN":child.birth.toString());row.put("exit_observed",childEnded(child));
                    if(child.parentPid==null)row.putNull("parent_pid");else row.put("parent_pid",child.parentPid);
                    row.put("evidence_path",child.evidencePath.toString());row.put("evidence_sha256",child.evidenceSha);
                }
                newJson(call.stopped(),stop);
            }
        }
        if(!exited||!treeStopped)throw new IOException("Owner process tree termination is unproved; reconcile required",failure);
        if(failure!=null)throw failure;
        if(Files.size(call.log())>MAX_LOG_BYTES)throw new IOException("Process output budget exceeded");
        var terminal=JSON.readTree(readBounded(call.response()));var bridge=terminal.get("bridge_process");
        if(bridge==null||!bool(bridge,"terminal_response_written")||nonnegative(bridge,"exit_code")!=0)
            throw new IOException("Response does not bind the terminal exited bridge process");
    }
    private static byte[] readBounded(Path path) throws IOException {if(!Files.isRegularFile(path)||Files.size(path)>MAX_JSON_BYTES)throw new IOException("Bounded regular JSON artifact required");return FileEvidenceStore.readBounded(path,MAX_JSON_BYTES,()->new IOException("JSON budget exceeded"));}
    private static void newJson(Path path,JsonNode value) throws IOException {byte[] bytes=JSON.writeValueAsBytes(value);if(bytes.length>MAX_JSON_BYTES)throw new IOException("JSON budget exceeded");FileEvidenceStore.writeNewDurable(path,bytes);}
    private static String sha(byte[] bytes){return FileEvidenceStore.sha256(bytes);}
}
