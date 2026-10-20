package com.zoutrankil.batch.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.batch.ScheduledLaunch;
import com.zoutrankil.batch.storage.SqliteLedger;

import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;

import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

class ScheduleCatalogTest {
    @Test
    void configuredCronsInstallPausedAndNeverOverwritePersistedDefinitions() throws Exception {
        var properties = new Properties();
        properties.setProperty(
                "org.quartz.scheduler.instanceName", "schedule-test-" + UUID.randomUUID());
        properties.setProperty("org.quartz.threadPool.threadCount", "1");
        properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        var scheduler = new StdSchedulerFactory(properties).getScheduler();
        var ledger = mock(SqliteLedger.class);
        var crons =
                ScheduleCatalog.SCHEDULE_IDS.stream()
                        .collect(Collectors.toMap(id -> id, id -> "0 0 19 ? * MON-FRI"));
        try {
            ScheduleCatalog.install(scheduler, ledger, crons);
            var trigger = new TriggerKey("post_close", "jdb");
            assertEquals(
                    "0 0 19 ? * MON-FRI",
                    ((CronTrigger) scheduler.getTrigger(trigger)).getCronExpression());
            assertEquals(Trigger.TriggerState.PAUSED, scheduler.getTriggerState(trigger));
            // Simulate an upgraded database whose job still names the original root-package class.
            var job = new JobKey("post_close", "jdb");
            scheduler.addJob(
                    JobBuilder.newJob(ScheduledLaunch.class)
                            .withIdentity(job)
                            .storeDurably()
                            .build(),
                    true);
            scheduler.resumeTriggers(GroupMatcher.triggerGroupEquals("jdb"));
            var priorState = scheduler.getTriggerState(trigger);
            crons.replaceAll((id, cron) -> "0 0 20 ? * MON-FRI");
            ScheduleCatalog.install(scheduler, ledger, crons);
            assertEquals(
                    "0 0 19 ? * MON-FRI",
                    ((CronTrigger) scheduler.getTrigger(trigger)).getCronExpression());
            assertEquals(priorState, scheduler.getTriggerState(trigger));
            assertEquals(ScheduledLaunch.class, scheduler.getJobDetail(job).getJobClass());
            assertTrue(scheduler.getJobDetail(job).isConcurrentExecutionDisallowed());
            verify(ledger, times(ScheduleCatalog.SCHEDULE_IDS.size()))
                    .audit(isNull(), eq("schedule-installed"), anyString());
        } finally {
            scheduler.shutdown(true);
        }
    }

    @Test
    void incompleteUnknownAndInvalidSchedulesFailBeforeAnyMutation() {
        var scheduler = mock(Scheduler.class);
        var ledger = mock(SqliteLedger.class);
        var schedules =
                ScheduleCatalog.SCHEDULE_IDS.stream()
                        .collect(Collectors.toMap(id -> id, id -> "0 0 19 ? * MON-FRI"));
        var incomplete = new HashMap<>(schedules);
        incomplete.remove("post_close");
        var unknown = new HashMap<>(schedules);
        unknown.put("typo", "0 0 19 ? * MON-FRI");
        schedules.put("post_close", "invalid");
        for (var invalid : List.of(incomplete, unknown, schedules))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ScheduleCatalog.install(scheduler, ledger, invalid));
        verifyNoInteractions(scheduler, ledger);
    }
}
