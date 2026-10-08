package com.zoutrankil.batch;

import com.zoutrankil.data.config.MainStrategyBatchProperties;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;

/** Durable, bounded launches; unknown owner outcomes are exposed for reconciliation, never replayed. */
public final class MainStrategyDailyCoordinator implements AutoCloseable {
    public static final String GROUP="main-strategy";
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final String CALENDAR_VERSION="exchange_calendar.SSE.v1";
    private static final Set<BusinessState> RECOVERABLE=Set.of(BusinessState.WAITING_SOURCE,
            BusinessState.WAITING_UPSTREAM,BusinessState.BLOCKED,BusinessState.PARTIAL);
    private final SqliteLedger ledger;
    private final LaunchService launches;
    private final MainStrategyDailyWork work;
    private final MainStrategyBatchProperties properties;
    private final Scheduler scheduler;
    private final Clock clock;
    private final ExecutorService asynchronous=Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("main-strategy-startup-",0).factory());
    private final AtomicBoolean queued=new AtomicBoolean();
    private final AtomicBoolean closing=new AtomicBoolean();
    private final Object ownerMonitor=new Object();
    private final Set<Thread> activeOwners=new HashSet<>();
    private volatile String lastError;

    public MainStrategyDailyCoordinator(SqliteLedger ledger,LaunchService launches,MainStrategyDailyWork work,
            MainStrategyBatchProperties properties,Scheduler scheduler,Clock clock) {
        this.ledger=ledger;this.launches=launches;this.work=work;
        this.properties=properties;this.scheduler=scheduler;this.clock=clock;
    }
    public void install() throws Exception {
        boolean enabled=ledger.installScheduleDefinition(MainStrategyDailyWork.JOB,properties.cron(),ZONE.getId(),CALENDAR_VERSION,properties.enabled());
        var jobKey=new JobKey(MainStrategyDailyWork.JOB,GROUP);
        boolean fresh=!scheduler.checkExists(jobKey);
        if(fresh) scheduler.addJob(JobBuilder.newJob(MainStrategyDailyScheduledLaunch.class)
                .withIdentity(jobKey).storeDurably().build(),false);
        addTrigger(jobKey,"daily",properties.cron());
        addTrigger(jobKey,"recovery",properties.retryCron());
        // A Quartz pause made before shutdown must remain authoritative after restart.
        if(!fresh && enabled && scheduler.getTriggersOfJob(jobKey).stream().anyMatch(trigger -> {
            try { return scheduler.getTriggerState(trigger.getKey())==Trigger.TriggerState.PAUSED; }
            catch(SchedulerException error) { throw new IllegalStateException(error); }
        })) {
            ledger.setScheduleEnabled(MainStrategyDailyWork.JOB,false);
            enabled=false;
        }
        if(!enabled) scheduler.pauseJob(jobKey);
        ledger.audit(null,"main-strategy-schedule-installed",MainStrategyDailyWork.JOB+":v1:"+(enabled?"enabled":"paused"));
        scheduler.start();
    }
    private void addTrigger(JobKey job,String name,String cron) throws SchedulerException {
        var key=new TriggerKey(name,GROUP);
        if(scheduler.checkExists(key)) {
            Trigger trigger=scheduler.getTrigger(key);
            if(!(trigger instanceof CronTrigger existing) || !cron.equals(existing.getCronExpression())
                    || !ZONE.getId().equals(existing.getTimeZone().getID()) || !job.equals(existing.getJobKey()))
                throw new IllegalStateException("Persisted main-strategy trigger definition differs: "+name);
            return;
        }
        scheduler.scheduleJob(TriggerBuilder.newTrigger().withIdentity(key).forJob(job)
                .withSchedule(CronScheduleBuilder.cronSchedule(cron).inTimeZone(TimeZone.getTimeZone(ZONE))
                        .withMisfireHandlingInstructionDoNothing()).build());
    }
    private boolean enabled() {
        return ledger.scheduleEnabled(MainStrategyDailyWork.JOB);
    }
    /** Returns immediately; a single bounded slot services startup and operator catch-up. */
    public Map<String,Object> catchup(String requestId) {
        if(closing.get())return Map.of("disposition","shutting-down");
        if(!enabled()) return Map.of("disposition","paused");
        if(!queued.compareAndSet(false,true)) return Map.of("disposition","already-queued");
        try {
            asynchronous.execute(() -> {
                try { probe(requestId,clock.instant(),clock.instant(),false);lastError=null; }
                catch(Exception error) {
                    lastError=error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),"");
                    ledger.audit(null,"main-strategy-launch-failed",lastError);
                } finally { queued.set(false); }
            });
        } catch(RejectedExecutionException closed) { queued.set(false);if(closing.get())return Map.of("disposition","shutting-down");throw closed; }
        return Map.of("disposition","accepted","requestId",requestId);
    }
    public Map<String,Object> probe(String requestId,Instant scheduled,Instant fired,boolean recovery) throws Exception {
        synchronized(ownerMonitor){if(closing.get())return Map.of("disposition","shutting-down");activeOwners.add(Thread.currentThread());}
        try {
        if(!enabled()) return Map.of("disposition","paused");
        try(var guard=ledger.tryLock("job:"+MainStrategyDailyWork.JOB).orElse(null)) {
            if(guard==null) return Map.of("disposition","already-running");
            guard.requireAlive();
            LocalDate target=work.resolveTarget(Clock.fixed(fired,ZONE),properties.completionCutoff());
            if(target==null) throw new IllegalStateException("Trading calendar did not provide a completed trading date");
            var prior=latest(target);
            if(prior.isPresent() && prior.get().state().ready()) {
                var completed=prior.get().request();
                var certificates=ledger.readyStageEvidence(completed.instanceId());
                try { work.validateCompleted(completed,certificates); }
                catch(Exception changed) {
                    ledger.audit(completed.instanceId(),"main-strategy-completed-live-check",changed.getClass().getSimpleName()+":"+Objects.toString(changed.getMessage(),""));
                    return Map.of("disposition","revision-required","instanceId",completed.instanceId(),"state","BLOCKED","reason","Completed data or definition changed: "+Objects.toString(changed.getMessage(),""));
                }
                return ledger.detail(completed.instanceId());
            }
            if(prior.isPresent() && !RECOVERABLE.contains(prior.get().state())) {
                var candidate=prior.get();
                // The runtime lease and job lease are held here: a RUNNING marker belongs to a lost execution.
                if(candidate.state()==BusinessState.RUNNING || candidate.state()==BusinessState.VERIFYING)
                    ledger.state(candidate.request().instanceId(),BusinessState.IN_DOUBT,null,
                            "Prior main-strategy execution lost its runtime owner; reconcile before replay");
                return Map.of("disposition","reconciliation-required","instanceId",candidate.request().instanceId(),
                        "state",ledger.state(candidate.request().instanceId()).name());
            }
            String input=work.inputFingerprint(target);
            RunRequest request;
            if(prior.isPresent()) {
                var canonical=prior.get().request();
                if(!canonical.inputFingerprint().equals(input))
                    throw new IllegalStateException("Main-strategy frozen input changed; explicit revision required");
                request=new RunRequest(requestId,canonical.job(),target,canonical.rangeStart(),canonical.rangeEnd(),
                        canonical.definitionVersion(),canonical.revision(),canonical.supersedes(),canonical.revisionReason(),
                        canonical.inputFingerprint(),canonical.calendarVersion(),canonical.zone(),scheduled,fired,canonical.scopeIdentity());
            } else request=new RunRequest(requestId,MainStrategyDailyWork.JOB,target,target,target,
                    MainStrategyDailyWork.DEFINITION_VERSION,"0",null,null,input,CALENDAR_VERSION,ZONE.getId(),scheduled,fired);
            ledger.register(request);
            if(prior.isPresent()) {
                LocalTime time=fired.atZone(ZONE).toLocalTime();
                if(recovery && time.isBefore(LocalTime.of(21,0)))
                    return Map.of("disposition","outside-recovery-window","instanceId",request.instanceId());
                var claimed=requestId.startsWith("manual:")
                        ?ledger.claimMainStrategyOperatorRecoveryAttempt(request.instanceId(),requestId,fired,Duration.ofMinutes(15),properties.maximumRecoveryAttempts())
                        :ledger.claimRecoveryAttempt(request.instanceId(),requestId,fired,Duration.ofMinutes(15),properties.maximumRecoveryAttempts());
                if(claimed.isEmpty())
                    return Map.of("disposition","retry-not-due-or-budget-exhausted","instanceId",request.instanceId());
            }
            var result=launches.launch(request);
            ledger.audit(request.instanceId(),"main-strategy-launch-result",Objects.toString(result.get("business_state"),"unknown"));
            return result;
        }
        } finally {synchronized(ownerMonitor){activeOwners.remove(Thread.currentThread());ownerMonitor.notifyAll();}}
    }
    private Optional<SqliteLedger.RecoveryCandidate> latest(LocalDate target) {
        return ledger.latestCandidate(MainStrategyDailyWork.JOB,target);
    }
    public synchronized Map<String,Object> setEnabled(boolean enabled) throws Exception {
        if(closing.get())throw new IllegalStateException("Main-strategy runtime is shutting down");
        var key=new JobKey(MainStrategyDailyWork.JOB,GROUP);
        // Pause first, then commit the durable operator preference before a later resume.
        scheduler.pauseJob(key);
        ledger.setScheduleEnabled(MainStrategyDailyWork.JOB,enabled);
        ledger.audit(null,"main-strategy-schedule-control",enabled?"resumed":"paused");
        if(enabled) scheduler.resumeJob(key);
        return Map.of("enabled",enabled,"runningExecutionUnaffected",true);
    }
    public Map<String,Object> status() throws Exception {
        var result=new LinkedHashMap<String,Object>();
        result.put("job",MainStrategyDailyWork.JOB);result.put("enabled",enabled());
        result.put("startupCatchup",properties.startupCatchup());result.put("queued",queued.get());
        result.put("closing",closing.get());
        result.put("completionCutoff",properties.completionCutoff().toString());result.put("zone",ZONE.getId());
        result.put("maximumRecoveryAttempts",properties.maximumRecoveryAttempts());
        var triggers=new ArrayList<Map<String,Object>>();
        for(var key:scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(GROUP))) {
            Trigger trigger=scheduler.getTrigger(key);
            var entry=new LinkedHashMap<String,Object>();entry.put("name",key.getName());
            entry.put("state",scheduler.getTriggerState(key).name());
            entry.put("nextFireAt",trigger.getNextFireTime()==null?null:trigger.getNextFireTime().toInstant().toString());
            triggers.add(entry);
        }
        result.put("triggers",triggers);result.put("lastError",lastError);
        result.put("instances",ledger.recent().stream().filter(row -> MainStrategyDailyWork.JOB.equals(row.get("job"))).toList());
        result.put("pipeline",work.status());return result;
    }
    public Map<String,Object> detail(String instance) {
        RunRequest request=ledger.request(instance);
        if(!MainStrategyDailyWork.JOB.equals(request.job())) throw new IllegalArgumentException("Not a main-strategy instance");
        return ledger.detail(instance);
    }
    /** Operator-only original-run recovery. The five-stage parent is not marked complete here. */
    public Map<String,Object> reconcilePublication(String instance,String stage,String runId,boolean writerStopped,String requestId)throws Exception {
        if(instance==null||!instance.matches("[a-f0-9]{64}")||runId==null||!runId.matches("[A-Za-z0-9-]{1,128}")||stage==null||!Set.of("MarketSentimentDaily","RegimeFeaturesMonitorDaily","BacktestDaily").contains(stage))throw new IllegalArgumentException("Exact instance, native stage and bounded original run required");
        if(!writerStopped)throw new IllegalArgumentException("Explicit stopped-writer proof required");
        synchronized(ownerMonitor){if(closing.get())throw new IllegalStateException("Runtime is shutting down");if(!queued.compareAndSet(false,true))throw new IllegalStateException("A main-strategy owner is already queued/running");activeOwners.add(Thread.currentThread());}
        try(var job=ledger.tryLock("job:"+MainStrategyDailyWork.JOB).orElse(null);var owner=ledger.tryLock("instance:"+instance).orElse(null)) {
            if(job==null||owner==null)throw new IllegalStateException("Main-strategy owner is active; stopped-writer proof rejected");job.requireAlive();owner.requireAlive();
            var request=ledger.request(instance);if(!MainStrategyDailyWork.JOB.equals(request.job()))throw new IllegalArgumentException("Not a main-strategy instance");
            var current=latest(request.logicalDate()).orElseThrow();if(!current.request().instanceId().equals(instance))throw new IllegalStateException("Superseded instance requires revision, not reconciliation");
            var certificates=new LinkedHashMap<String,CompletionEvidence>(ledger.readyStageEvidence(instance));
            var prior=certificates.get(stage);boolean already=prior!=null;
            if(already&&!runId.equals(prior.batchId()))throw new IllegalStateException("Ready stage belongs to another original owner");
            if(!already&&(ledger.state(instance)!=BusinessState.IN_DOUBT||ledger.stages(instance).get(stage)!=BusinessState.IN_DOUBT))throw new IllegalStateException("Only the uncertain native stage can be reconciled explicitly");
            if(already&&(ledger.state(instance)==BusinessState.RUNNING||ledger.state(instance)==BusinessState.VERIFYING))throw new IllegalStateException("Current execution is active");
            ledger.audit(instance,"main-strategy-publication-reconciliation-start",requestId+":"+stage+":"+runId);
            var recovered=work.reconcilePublication(request,stage,runId,true,certificates);job.requireAlive();owner.requireAlive();
            if(!recovered.state().ready()||!recovered.matches(request,stage)||!runId.equals(recovered.batchId()))throw new IllegalStateException("Recovered physical certificate does not bind the original run");
            if(!already){ledger.stage(request,stage,recovered.state(),recovered,"Explicit original-run publication reconciliation");ledger.state(instance,BusinessState.WAITING_UPSTREAM,null,"Original native publication verified; remaining stages pending");}
            ledger.audit(instance,"main-strategy-publication-reconciled",requestId+":"+stage+":"+runId+":original-run-verified");return ledger.detail(instance);
        } catch(Exception error){ledger.audit(instance,"main-strategy-publication-reconciliation-failed",requestId+":"+error.getClass().getSimpleName()+":"+Objects.toString(error.getMessage(),""));throw error;}
        finally{queued.set(false);synchronized(ownerMonitor){activeOwners.remove(Thread.currentThread());ownerMonitor.notifyAll();}}
    }
    @Override public void close() {
        if(!closing.compareAndSet(false,true))return;
        // Neither SQLite metadata nor its runtime lease may disappear while an owner can still write.
        try {scheduler.standby();}catch(SchedulerException error){lastError="Scheduler standby: "+error.getMessage();}
        asynchronous.shutdown();
        boolean interrupted=Thread.interrupted();
        try {
            boolean done=false;try{done=asynchronous.awaitTermination(30,TimeUnit.SECONDS);}catch(InterruptedException signal){interrupted=true;}
            if(!done){asynchronous.shutdownNow();synchronized(ownerMonitor){activeOwners.forEach(Thread::interrupt);}}
            while(!asynchronous.isTerminated())try{asynchronous.awaitTermination(10,TimeUnit.SECONDS);}catch(InterruptedException signal){interrupted=true;asynchronous.shutdownNow();}
            synchronized(ownerMonitor){
                activeOwners.forEach(Thread::interrupt);
                while(!activeOwners.isEmpty())try{ownerMonitor.wait(10000);}catch(InterruptedException signal){interrupted=true;activeOwners.forEach(Thread::interrupt);}
            }
        } finally {if(interrupted)Thread.currentThread().interrupt();}
    }
}
