package com.zoutrankil.batch;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Durable parent/child correlation. A reservation is never treated as permission to relaunch. */
public final class ExternalExecutionStore {
    public enum State { STARTING, RUNNING, EXITED, IN_DOUBT, BLOCKED }
    public record Execution(String instanceId, String stage, String childId, String inputIdentity, State state,
                            Long pid, Instant processStartedAt, Instant heartbeatAt, Instant startedAt,
                            Instant finishedAt, CompletionEvidence result, String logPath, String reason) {}
    public record Reservation(Execution execution,boolean created) {}

    private final JdbcTemplate jdbc;
    public ExternalExecutionStore(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    /** Persist this identity before calling ProcessBuilder.start(). Existing identity is returned unchanged. */
    public Execution reserve(RunRequest request, String stage, Path logPath) {
        return reserveWithDisposition(request,stage,logPath).execution();
    }
    public Reservation reserveWithDisposition(RunRequest request,String stage,Path logPath) {
        requireExternalStage(stage);
        String childId = UUID.randomUUID().toString();
        String normalizedLog = logPath == null ? null : logPath.toAbsolutePath().normalize().toString();
        int inserted = jdbc.update("""
            INSERT INTO external_execution(instance_id,stage,child_id,input_identity,state,log_path)
            VALUES(?,?,?,?,'STARTING',?) ON CONFLICT(instance_id,stage) DO NOTHING
            """, request.instanceId(), stage, childId, request.inputIdentity(), normalizedLog);
        Execution execution = get(request.instanceId(), stage).orElseThrow();
        if (!execution.inputIdentity().equals(request.inputIdentity()))
            throw new IllegalStateException("External computation input changed; explicit revision required");
        if (inserted == 1) event(execution.childId(), "RESERVED", "stage=" + stage + "; input=" + request.inputIdentity());
        return new Reservation(execution,inserted==1);
    }

    /** Record a PID and kernel start instant so PID reuse cannot attach to an unrelated process. */
    public void started(String childId, ProcessHandle process, Instant heartbeat) {
        if (!process.isAlive()) throw new IllegalStateException("Cannot attach an exited child process");
        var start = process.info().startInstant().orElseThrow(() -> new IllegalStateException("Child process start time unavailable"));
        int changed = jdbc.update("""
            UPDATE external_execution SET state='RUNNING',pid=?,process_started_at=?,started_at=?,heartbeat_at=?,
              updated_at=current_timestamp WHERE child_id=? AND state='STARTING'
            """, process.pid(), Timestamp.from(start), Timestamp.from(Instant.now()), Timestamp.from(heartbeat), childId);
        if (changed != 1) throw new IllegalStateException("Child reservation is not startable");
        event(childId, "STARTED", "pid=" + process.pid() + "; processStartedAt=" + start);
    }

    public void heartbeat(String childId, Instant at) {
        if (at.isAfter(Instant.now().plusSeconds(5))) throw new IllegalArgumentException("Heartbeat is in the future");
        int changed = jdbc.update("UPDATE external_execution SET heartbeat_at=CASE WHEN heartbeat_at IS NULL OR heartbeat_at < ? THEN ? ELSE heartbeat_at END,updated_at=current_timestamp WHERE child_id=? AND state='RUNNING'",
                Timestamp.from(at), Timestamp.from(at), childId);
        if (changed != 1 && jdbc.queryForObject("SELECT count(*) FROM external_execution WHERE child_id=? AND state='RUNNING'", Integer.class, childId) == 0)
            throw new IllegalStateException("Heartbeat for non-running child");
    }
    public void progress(String childId,Instant at,String message) {
        String detail=Objects.toString(message,"").replaceAll("[\\r\\n\\t]"," ");if(detail.length()>512)detail=detail.substring(0,512);
        heartbeat(childId,at);event(childId,"PROGRESS",detail);
    }
    public void setLogPath(String instanceId,String stage,Path logPath) {
        int changed=jdbc.update("UPDATE external_execution SET log_path=?,updated_at=current_timestamp WHERE instance_id=? AND stage=? AND state='STARTING'",
                logPath.toAbsolutePath().normalize().toString(),instanceId,stage);
        if(changed!=1)throw new IllegalStateException("External reservation is not startable");
    }
    public void event(String childId,String name,String detail) {
        if(name==null||!name.matches("[A-Z_]{1,40}"))throw new IllegalArgumentException("Invalid external execution event");
        String safe=Objects.toString(detail,"").replaceAll("[\\r\\n\\t]"," ");if(safe.length()>512)safe=safe.substring(0,512);
        jdbc.update("INSERT INTO external_execution_event(child_id,event,detail) VALUES(?,?,?)",childId,name,safe);
    }
    public void blocked(String childId,String reason) {
        String safe=Objects.toString(reason,"external-protocol-failure").replaceAll("[\\r\\n\\t]"," ");if(safe.length()>512)safe=safe.substring(0,512);
        int changed=jdbc.update("UPDATE external_execution SET state='BLOCKED',finished_at=?,reason=?,updated_at=current_timestamp WHERE child_id=? AND state IN ('STARTING','RUNNING')",
                Timestamp.from(Instant.now()),safe,childId);
        if(changed==1)event(childId,"BLOCKED",safe);
    }
    public void note(String childId,String reason) {
        String safe=Objects.toString(reason,"").replaceAll("[\\r\\n\\t]"," ");if(safe.length()>512)safe=safe.substring(0,512);
        jdbc.update("UPDATE external_execution SET reason=?,updated_at=current_timestamp WHERE child_id=? AND state='RUNNING'",safe,childId);
        event(childId,"DIAGNOSTIC",safe);
    }

    /** Exit status is diagnostic only. It never constructs or upgrades completion evidence. */
    public void exited(String childId, int exitCode, CompletionEvidence result, String reason) {
        String diagnostic = "exitCode=" + exitCode + "; " + Objects.toString(reason, "no diagnostic");
        int changed = jdbc.update("""
            UPDATE external_execution SET state='EXITED',finished_at=?,result_json=?,reason=?,updated_at=current_timestamp
            WHERE child_id=? AND state='RUNNING'
            """, Timestamp.from(Instant.now()), result == null ? null : Json.write(result), diagnostic, childId);
        // Query by ID separately because the update predicate intentionally uses child_id, not instance identity.
        if (changed != 1) throw new IllegalStateException("Child is not running");
        event(childId, "EXITED", diagnostic);
    }

    /** A parent restart classifies a lost/nonmatching PID as uncertain; it must not launch a replacement. */
    public ExternalComputation.Observation observe(RunRequest request, String stage) {
        Optional<Execution> found = get(request.instanceId(), stage);
        if (found.isEmpty()) return new ExternalComputation.Observation(null, false, null, null, "not-connected");
        Execution e = found.get();
        if (!e.inputIdentity().equals(request.inputIdentity()))
            return new ExternalComputation.Observation(e.childId(), false, e.heartbeatAt(), null, "input-identity-mismatch");
        if (e.state() == State.RUNNING && isSameLiveProcess(e))
            return new ExternalComputation.Observation(e.childId(), true, e.heartbeatAt(), null, "child-running");
        if (e.state() == State.STARTING || e.state() == State.RUNNING) {
            jdbc.update("UPDATE external_execution SET state='IN_DOUBT',reason=?,updated_at=current_timestamp WHERE child_id=? AND state IN ('STARTING','RUNNING')",
                    "Child process cannot be proven alive after observation; manual reconciliation required", e.childId());
            event(e.childId(), "IN_DOUBT", "process identity not confirmed");
            return new ExternalComputation.Observation(e.childId(), false, e.heartbeatAt(), null, "child-state-uncertain; manual reconciliation required");
        }
        if (e.state() == State.IN_DOUBT)
            return new ExternalComputation.Observation(e.childId(), false, e.heartbeatAt(), null, "child-state-uncertain; manual reconciliation required");
        if (e.state() == State.BLOCKED)
            return new ExternalComputation.Observation(e.childId(), false, e.heartbeatAt(), null, "blocked:"+Objects.toString(e.reason(),"external execution blocked"));
        return new ExternalComputation.Observation(e.childId(), false, e.heartbeatAt(), e.result(), e.reason());
    }

    public Optional<Execution> get(String instanceId, String stage) {
        var rows = jdbc.query("SELECT * FROM external_execution WHERE instance_id=? AND stage=?", (rs, n) -> {
            String json = rs.getString("result_json");
            long pid = rs.getLong("pid"); Long pidValue = rs.wasNull() ? null : pid;
            return new Execution(rs.getString("instance_id"), rs.getString("stage"), rs.getString("child_id"),
                    rs.getString("input_identity"), State.valueOf(rs.getString("state")), pidValue,
                    instant(rs.getTimestamp("process_started_at")), instant(rs.getTimestamp("heartbeat_at")),
                    instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                    json == null ? null : Json.read(json, CompletionEvidence.class), rs.getString("log_path"), rs.getString("reason"));
        }, instanceId, stage);
        return rows.stream().findFirst();
    }

    private boolean isSameLiveProcess(Execution e) {
        if (e.pid() == null || e.processStartedAt() == null) return false;
        return ProcessHandle.of(e.pid()).filter(ProcessHandle::isAlive).flatMap(p -> p.info().startInstant())
                .map(e.processStartedAt()::equals).orElse(false);
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static void requireExternalStage(String stage) {
        if (PostCloseGraph.STAGES.stream().noneMatch(s -> s.id().equals(stage) && s.external()))
            throw new IllegalArgumentException("Stage is not a registered external computation: " + stage);
    }
}
