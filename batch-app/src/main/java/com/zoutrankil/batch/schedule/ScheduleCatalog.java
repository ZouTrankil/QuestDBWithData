package com.zoutrankil.batch.schedule;

import com.zoutrankil.batch.storage.SqliteLedger;

import org.quartz.*;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** The initial schedule is durable and paused. Restart never overwrites pause state. */
public final class ScheduleCatalog {
    private ScheduleCatalog() {}
    public static final java.util.Set<String> RECOVERY_SCHEDULES=java.util.Set.of("post_close_recovery","post_close_recovery_final");
    public static final Set<String> SCHEDULE_IDS=Set.of("post_close","source_cyq_perf","source_us_tbr",
            "post_close_recovery","post_close_recovery_final");

    public static Map<String,String> configured(Environment environment) {
        var schedules=Binder.get(environment).bind("jdb.schedules",Bindable.mapOf(String.class,String.class))
                .orElseThrow(() -> new IllegalArgumentException("jdb.schedules is required"));
        validate(schedules);
        return Map.copyOf(schedules);
    }

    private static void validate(Map<String,String> schedules) {
        if(!schedules.keySet().equals(SCHEDULE_IDS))
            throw new IllegalArgumentException("jdb.schedules must define exactly "+SCHEDULE_IDS);
        schedules.forEach((id,cron) -> {
            if(cron==null || !CronExpression.isValidExpression(cron))
                throw new IllegalArgumentException("Invalid cron for jdb.schedules["+id+"]");
        });
    }

    public static void install(Scheduler scheduler,SqliteLedger ledger,Map<String,String> schedules) throws SchedulerException {
        validate(schedules);
        for(var entry:new java.util.TreeMap<>(schedules).entrySet()) {
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
