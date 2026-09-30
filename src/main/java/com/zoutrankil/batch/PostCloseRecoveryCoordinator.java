package com.zoutrankil.batch;

import java.time.*;
import java.util.*;

/** Bounded same-instance recovery probes. Unknown executions and writes are never auto-replayed. */
public final class PostCloseRecoveryCoordinator {
    public static final int MAX_ATTEMPTS=21;
    private static final Set<BusinessState> RECOVERABLE=Set.of(BusinessState.BLOCKED,BusinessState.PARTIAL,
            BusinessState.WAITING_SOURCE,BusinessState.WAITING_UPSTREAM);
    private final SqliteLedger ledger;
    private final LaunchService launches;

    public PostCloseRecoveryCoordinator(SqliteLedger ledger,LaunchService launches) {
        this.ledger=Objects.requireNonNull(ledger);this.launches=Objects.requireNonNull(launches);
    }

    public void probe(String task,Instant scheduledAt,Instant firedAt,String fireInstanceId,TradingCalendar calendar) throws Exception {
        if(!ScheduleCatalog.RECOVERY_SCHEDULES.contains(task)) throw new IllegalArgumentException("Unknown recovery trigger");
        LocalDate scheduledDate=RecoveryPolicy.nightLogicalDate(scheduledAt,calendar);
        LocalDate firedDate=RecoveryPolicy.nightLogicalDate(firedAt,calendar);
        if(!scheduledDate.equals(firedDate)) { ledger.audit(null,"post-close-recovery-skipped","stale-misfire:"+task);return; }
        var candidate=ledger.latestPostCloseCandidate(scheduledDate);
        if(candidate.isEmpty()) { ledger.audit(null,"post-close-recovery-skipped","no-post-close-instance:"+scheduledDate);return; }
        var prior=candidate.get();var request=prior.request();
        if(!request.calendarVersion().equals(calendar.version())||!request.zone().equals(RecoveryPolicy.ZONE.getId())) {
            ledger.audit(request.instanceId(),"post-close-recovery-skipped","calendar-or-zone-version-mismatch");return;
        }
        if(!RECOVERABLE.contains(prior.state())) {
            ledger.audit(request.instanceId(),"post-close-recovery-skipped","state-not-recoverable:"+prior.state());return;
        }
        int attempts=ledger.recoveryAttempts(request.instanceId());
        Instant nextAttempt=ledger.nextRecoveryAttempt(request.instanceId());
        if(!RecoveryPolicy.mayProbe(request,firedAt,attempts,nextAttempt,calendar)) {
            ledger.audit(request.instanceId(),"post-close-recovery-skipped","window-backoff-or-attempt-limit");return;
        }
        LocalTime triggerTime=scheduledAt.atZone(RecoveryPolicy.ZONE).toLocalTime();
        Duration interval=task.equals("post_close_recovery_final")||triggerTime.equals(LocalTime.of(23,45))
                ?Duration.ofMinutes(10):Duration.ofMinutes(15);
        String triggerId="post-close-recovery:"+RunRequest.hash(task,Objects.toString(fireInstanceId,""));
        var claimed=ledger.claimRecoveryAttempt(request.instanceId(),triggerId,firedAt,interval,MAX_ATTEMPTS);
        if(claimed.isEmpty()) { ledger.audit(request.instanceId(),"post-close-recovery-skipped","claim-not-due-or-duplicate");return; }
        String retryId="recovery:"+RunRequest.hash(triggerId);
        var retry=new RunRequest(retryId,request.job(),request.logicalDate(),request.rangeStart(),request.rangeEnd(),
                request.definitionVersion(),request.revision(),request.supersedes(),request.revisionReason(),request.inputFingerprint(),
                request.calendarVersion(),request.zone(),scheduledAt,firedAt,request.scopeIdentity());
        var result=launches.launch(retry);
        ledger.audit(request.instanceId(),"post-close-recovery-result","attempt="+claimed.getAsInt()+":state="+
                Objects.toString(result.get("business_state"),Objects.toString(result.get("disposition"),"unknown")));
    }
}
