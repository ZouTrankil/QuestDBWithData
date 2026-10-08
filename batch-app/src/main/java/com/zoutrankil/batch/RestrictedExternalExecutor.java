package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Fixed-command, no-shell child protocol. Empty command catalog is deliberately disconnected. */
public final class RestrictedExternalExecutor implements ExternalComputation {
    public record Invocation(int protocolVersion,String childId,String stage,RunRequest request,String outputDirectory) {}
    private record Command(List<String> argv) {}
    private static final int MAX_LINE_BYTES=1024*1024;
    private static final long MAX_OUTPUT_BYTES=16L*1024*1024;
    private static final long MAX_RESULT_BYTES=16L*1024*1024;
    private static final ScheduledExecutorService TIMEOUTS=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"jdb-external-timeout-watch");t.setDaemon(true);return t;});
    private static final Set<String> SHELLS=Set.of("sh","bash","zsh","dash","ksh","csh","tcsh","cmd","powershell","pwsh");
    private final ExternalExecutionStore store;
    private final Path archiveRoot;
    private final Map<String,Command> commands;
    private final Map<String,String> childEnvironment;
    private final Duration timeout;
    private final Set<String> launching=ConcurrentHashMap.newKeySet();

    public RestrictedExternalExecutor(ExternalExecutionStore store,Path archiveRoot,Map<String,List<String>> commands,
                                      Map<String,String> childEnvironment,Duration timeout) throws IOException {
        this.store=Objects.requireNonNull(store);Files.createDirectories(archiveRoot);
        this.archiveRoot=archiveRoot.toRealPath();this.timeout=Objects.requireNonNull(timeout);
        if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofHours(24))>0)
            throw new IllegalArgumentException("External child timeout must be in (0,24h]");
        var validated=new TreeMap<String,Command>();
        for(var entry:commands.entrySet()) {
            String stage=entry.getKey();
            if(PostCloseGraph.STAGES.stream().noneMatch(s->s.external()&&s.id().equals(stage)))
                throw new IllegalArgumentException("External command is not bound to a registered stage");
            var argv=List.copyOf(entry.getValue());
            if(argv.isEmpty()||argv.size()>64||argv.stream().anyMatch(a->a==null||a.isBlank()||a.length()>4096||a.indexOf('\n')>=0||a.indexOf('\r')>=0))
                throw new IllegalArgumentException("Fixed bounded argv is required for each external stage");
            Path executable=Path.of(argv.getFirst());
            if(!executable.isAbsolute()||Files.isSymbolicLink(executable)||!Files.isRegularFile(executable,LinkOption.NOFOLLOW_LINKS)||!Files.isExecutable(executable))
                throw new IllegalArgumentException("External executable must be an absolute regular executable file");
            Path real=executable.toRealPath();
            if(SHELLS.contains(real.getFileName().toString().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Shell interpreters are not accepted as external executors");
            var normalized=new ArrayList<>(argv);normalized.set(0,real.toString());validated.put(stage,new Command(List.copyOf(normalized)));
        }
        var environment=new TreeMap<String,String>();
        for(var entry:childEnvironment.entrySet()) {
            if(entry.getKey()==null||!entry.getKey().matches("[A-Z][A-Z0-9_]{0,63}")||entry.getValue()==null||entry.getValue().length()>8192
                    ||entry.getValue().indexOf('\n')>=0||entry.getValue().indexOf('\r')>=0)
                throw new IllegalArgumentException("Invalid explicit child environment");
            environment.put(entry.getKey(),entry.getValue());
        }
        this.commands=Collections.unmodifiableMap(validated);this.childEnvironment=Collections.unmodifiableMap(environment);
    }

    @Override public Observation observe(RunRequest request,String stage) {
        Command command=commands.get(stage);
        var existing=store.get(request.instanceId(),stage);
        if(existing.isEmpty()&&command==null)return new Observation(null,false,null,null,"not-connected");
        if(existing.isPresent()&&launching.contains(existing.get().childId()))
            return new Observation(existing.get().childId(),true,existing.get().heartbeatAt(),null,"child-starting");
        if(existing.isEmpty()) {
            ExternalExecutionStore.Reservation reservation;
            try { reservation=store.reserveWithDisposition(request,stage,archiveRoot.resolve("external").resolve("pending-"+request.instanceId()).resolve("process.ndjson")); }
            catch(RuntimeException error) { return new Observation(null,false,null,null,"blocked:external reservation failed"); }
            if(!reservation.created())return store.observe(request,stage);
            String childId=reservation.execution().childId();launching.add(childId);
            try { launch(request,stage,command,childId); }
            catch(Exception error) {
                store.blocked(childId,"external launch/protocol setup failed: "+error.getClass().getSimpleName());
                return store.observe(request,stage);
            } finally { launching.remove(childId); }
        }
        return store.observe(request,stage);
    }

    private void launch(RunRequest request,String stage,Command command,String childId) throws Exception {
        Path externalRoot=archiveRoot.resolve("external");Files.createDirectories(externalRoot);
        if(Files.isSymbolicLink(externalRoot)||!externalRoot.toRealPath().startsWith(archiveRoot))
            throw new IllegalStateException("External archive directory escapes its root");
        Path directory=externalRoot.toRealPath().resolve(childId).normalize();
        if(!directory.startsWith(archiveRoot)||Files.exists(directory,LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Child output directory identity conflict");
        Files.createDirectories(directory.getParent());Files.createDirectory(directory);
        Path log=directory.resolve("process.ndjson");
        // Update the reserved diagnostic path before any child can begin work.
        store.setLogPath(request.instanceId(),stage,log);
        var invocation=new Invocation(1,childId,stage,request,directory.toString());
        var processBuilder=new ProcessBuilder(command.argv()).directory(directory.toFile()).redirectErrorStream(true);
        processBuilder.environment().clear();processBuilder.environment().putAll(childEnvironment);
        Process process=processBuilder.start();
        try {
            store.started(childId,process.toHandle(),Instant.now());
            try(var input=process.getOutputStream()) { input.write((Json.write(invocation)+"\n").getBytes(StandardCharsets.UTF_8));input.flush(); }
        } catch(Exception uncertain) {
            if(process.isAlive())process.destroy();
            store.blocked(childId,"child start outcome requires reconciliation");throw uncertain;
        }
        Thread.ofVirtual().name("jdb-external-"+childId).start(()->consume(request,stage,childId,directory,process));
        TIMEOUTS.schedule(()->{if(process.isAlive())store.note(childId,
                "child exceeded configured timeout; still running and retained, no replacement launched");},timeout.toMillis(),TimeUnit.MILLISECONDS);
    }

    private void consume(RunRequest request,String stage,String childId,Path directory,Process process) {
        CompletionEvidence result=null;String invalid=null;long total=0;Path log=directory.resolve("process.ndjson");
        try(var out=Files.newOutputStream(log,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            var stream=new BufferedInputStream(process.getInputStream())) {
            while(true) {
                byte[] line=readLine(stream,MAX_LINE_BYTES);if(line==null)break;
                total=Math.addExact(total,line.length+1L);
                if(total>MAX_OUTPUT_BYTES) { invalid="external protocol output exceeded byte limit";break; }
                out.write(line);out.write('\n');out.flush();
                JsonNode event=Json.MAPPER.readTree(line);
                String type=event.path("type").asText("");
                if(type.equals("progress")) {
                    Instant heartbeat=event.hasNonNull("at")?Instant.parse(event.get("at").asText()):Instant.now();
                    String message=event.path("message").asText("");
                    store.progress(childId,heartbeat,message);
                } else if(type.equals("result")) {
                    if(result!=null)throw new IllegalArgumentException("multiple-result-events");
                    result=Json.MAPPER.treeToValue(event.path("evidence"),CompletionEvidence.class);
                    validateResult(request,stage,childId,directory,result);
                } else throw new IllegalArgumentException("unknown-protocol-event");
            }
            if(invalid!=null&&process.isAlive())process.destroy();
            int exitCode=process.waitFor();
            if(invalid!=null)store.blocked(childId,invalid);
            else if(exitCode!=0)store.blocked(childId,"external child exited nonzero: "+exitCode);
            else if(result==null)store.blocked(childId,"external child exited without a result certificate");
            else store.exited(childId,exitCode,result,null);
        } catch(Exception failure) {
            if(process.isAlive())process.destroy();
            store.blocked(childId,"external result rejected: "+failure.getClass().getSimpleName());
        }
    }

    private void validateResult(RunRequest request,String stage,String childId,Path directory,CompletionEvidence evidence) throws Exception {
        if(!evidence.matches(request,stage))throw new IllegalArgumentException("result-identity-mismatch");
        if(!evidence.producer().equals("external:"+stage))throw new IllegalArgumentException("result-producer-mismatch");
        Path artifact=Path.of(evidence.artifact()).toAbsolutePath().normalize();
        if(!artifact.startsWith(directory)||Files.isSymbolicLink(artifact)||!Files.isRegularFile(artifact,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(artifact)>MAX_RESULT_BYTES)throw new IllegalArgumentException("result-artifact-outside-child-scope");
        var archived=Json.MAPPER.readValue(Files.readAllBytes(artifact),CompletionEvidence.class);
        if(!archived.equals(evidence))throw new IllegalArgumentException("result-artifact-content-mismatch");
    }

    private static byte[] readLine(InputStream input,int maxBytes) throws IOException {
        var out=new ByteArrayOutputStream(Math.min(maxBytes,4096));int value;
        while((value=input.read())!=-1) {
            if(value=='\n')return out.toByteArray();
            if(value!='\r') { if(out.size()>=maxBytes)throw new IOException("external protocol line exceeds bound");out.write(value); }
        }
        return out.size()==0?null:out.toByteArray();
    }

}
