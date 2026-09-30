package com.zoutrankil.batch;

import java.nio.file.*;
import java.time.*;
import org.quartz.*;

@DisallowConcurrentExecution
public class ScheduledLaunch implements Job {
    @Override public void execute(JobExecutionContext execution) throws JobExecutionException {
        try {
            var context=execution.getScheduler().getContext();
            var ledger=(SqliteLedger)context.get("ledger");
            var calendar=(TradingCalendar)context.get("calendar");
            if (calendar==null) { ledger.audit(null,"trigger-blocked","calendar-not-configured"); return; }
            String task=execution.getJobDetail().getKey().getName();
            if(!ScheduleCatalog.SCHEDULES.containsKey(task)) throw new IllegalArgumentException("Unregistered scheduled task");
            Instant scheduled=execution.getScheduledFireTime().toInstant();
            LocalDate date=scheduled.atZone(RecoveryPolicy.ZONE).toLocalDate();
            if (!calendar.isOpen(date)) { ledger.audit(null,"trigger-skipped","calendar-closed:"+date); return; }
            if (ScheduleCatalog.RECOVERY_SCHEDULES.contains(task)) {
                ((PostCloseRecoveryCoordinator)context.get("postCloseRecovery")).probe(task,scheduled,
                        execution.getFireTime().toInstant(),execution.getFireInstanceId(),calendar);
                return;
            }
            Path archive=(Path)context.get("archive");
            // An explicit dated input manifest is required. Never invent an input identity from current time.
            Path requestFile=archive.resolve("requests").resolve(task).resolve(date+".json");
            if (!Files.isRegularFile(requestFile)) { ledger.audit(null,"trigger-waiting-source","missing-manifest:"+date); return; }
            RunRequest source=Json.read(Files.readString(requestFile),RunRequest.class);
            if (!source.logicalDate().equals(date) || !source.job().equals(task)
                    || !source.calendarVersion().equals(calendar.version()) || !source.zone().equals(RecoveryPolicy.ZONE.getId()))
                throw new IllegalArgumentException("Scheduled input manifest identity mismatch");
            RunRequest request=new RunRequest("quartz:"+execution.getFireInstanceId(),source.job(),date,
                    source.rangeStart(),source.rangeEnd(),source.definitionVersion(),source.revision(),source.supersedes(),
                    source.revisionReason(),source.inputFingerprint(),calendar.version(),RecoveryPolicy.ZONE.getId(),
                    scheduled,execution.getFireTime().toInstant(),source.scopeIdentity());
            ((LaunchService)context.get("launches")).launch(request);
        } catch (Exception error) { throw new JobExecutionException(error,false); }
    }
}
