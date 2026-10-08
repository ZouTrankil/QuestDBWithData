package com.zoutrankil.batch;

import org.quartz.*;
import java.util.TimeZone;

/** The initial schedule is durable and paused. Restart never overwrites pause state. */
public final class ScheduleCatalog {
    private ScheduleCatalog() {}
    public static final java.util.Set<String> RECOVERY_SCHEDULES=java.util.Set.of("post_close_recovery","post_close_recovery_final");
    public static final java.util.Map<String,String> SCHEDULES=java.util.Map.of(
            "post_close","0 30 18 ? * MON-FRI",
            "source_cyq_perf","0 10 21 ? * MON-FRI",
            "source_us_tbr","0 35 21 ? * MON-FRI",
            "post_close_recovery","0 0/15 19-23 ? * MON-FRI",
            "post_close_recovery_final","0 55 23 ? * MON-FRI");
    public static void install(Scheduler scheduler,SqliteLedger ledger) throws SchedulerException {
        for(var entry:SCHEDULES.entrySet()) {
            var key=new JobKey(entry.getKey(),"jdb");
            if (scheduler.checkExists(key)) continue;
            var job=JobBuilder.newJob(ScheduledLaunch.class).withIdentity(key).usingJobData("task",entry.getKey()).storeDurably().build();
            var trigger=TriggerBuilder.newTrigger().withIdentity(entry.getKey(),"jdb").forJob(job)
                    .withSchedule(CronScheduleBuilder.cronSchedule(entry.getValue())
                        .inTimeZone(TimeZone.getTimeZone("Asia/Shanghai")).withMisfireHandlingInstructionDoNothing()).build();
            try {
                // Pause the whole group before insertion, so an enabled scheduler cannot race a fresh trigger.
                scheduler.pauseTriggers(org.quartz.impl.matchers.GroupMatcher.triggerGroupEquals("jdb"));
                scheduler.scheduleJob(job,trigger);
                ledger.audit(null,"schedule-installed",entry.getKey()+":v1:paused");
            } catch (ObjectAlreadyExistsException concurrentInstaller) {
                // A concurrent instance owns the same immutable initial definition.
            }
        }
    }
}
