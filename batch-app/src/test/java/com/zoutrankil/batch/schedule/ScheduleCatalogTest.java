package com.zoutrankil.batch.schedule;

import com.zoutrankil.batch.storage.SqliteLedger;
import java.util.HashMap;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduleCatalogTest {
    @Test void configuredCronsInstallPausedAndNeverOverwritePersistedDefinitions() throws Exception {
        var properties=new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName","schedule-test-"+UUID.randomUUID());
        properties.setProperty("org.quartz.threadPool.threadCount","1");
        properties.setProperty("org.quartz.jobStore.class","org.quartz.simpl.RAMJobStore");
        var scheduler=new StdSchedulerFactory(properties).getScheduler();
        var ledger=mock(SqliteLedger.class);
        var crons=ScheduleCatalog.SCHEDULE_IDS.stream().collect(Collectors.toMap(id -> id,id -> "0 0 19 ? * MON-FRI"));
        try {
            ScheduleCatalog.install(scheduler,ledger,crons);
            var trigger=new TriggerKey("post_close","jdb");
            assertEquals("0 0 19 ? * MON-FRI",((CronTrigger)scheduler.getTrigger(trigger)).getCronExpression());
            assertEquals(Trigger.TriggerState.PAUSED,scheduler.getTriggerState(trigger));
            // Simulate an upgraded database whose job still names the original root-package class.
            var job=new JobKey("post_close","jdb");
            scheduler.addJob(JobBuilder.newJob(com.zoutrankil.batch.ScheduledLaunch.class)
                    .withIdentity(job).storeDurably().build(),true);
            scheduler.resumeTriggers(org.quartz.impl.matchers.GroupMatcher.triggerGroupEquals("jdb"));
            var priorState=scheduler.getTriggerState(trigger);
            crons.replaceAll((id,cron) -> "0 0 20 ? * MON-FRI");
            ScheduleCatalog.install(scheduler,ledger,crons);
            assertEquals("0 0 19 ? * MON-FRI",((CronTrigger)scheduler.getTrigger(trigger)).getCronExpression());
            assertEquals(priorState,scheduler.getTriggerState(trigger));
            assertEquals(com.zoutrankil.batch.ScheduledLaunch.class,scheduler.getJobDetail(job).getJobClass());
            assertTrue(scheduler.getJobDetail(job).isConcurrentExecutionDisallowed());
            verify(ledger,times(ScheduleCatalog.SCHEDULE_IDS.size())).audit(isNull(),eq("schedule-installed"),anyString());
        } finally {
            scheduler.shutdown(true);
        }
    }

    @Test void incompleteUnknownAndInvalidSchedulesFailBeforeAnyMutation() {
        var scheduler=mock(org.quartz.Scheduler.class);
        var ledger=mock(SqliteLedger.class);
        var schedules=ScheduleCatalog.SCHEDULE_IDS.stream().collect(Collectors.toMap(id -> id,id -> "0 0 19 ? * MON-FRI"));
        var incomplete=new HashMap<>(schedules); incomplete.remove("post_close");
        var unknown=new HashMap<>(schedules); unknown.put("typo","0 0 19 ? * MON-FRI");
        schedules.put("post_close","invalid");
        for(var invalid:java.util.List.of(incomplete,unknown,schedules))
            assertThrows(IllegalArgumentException.class,() -> ScheduleCatalog.install(scheduler,ledger,invalid));
        verifyNoInteractions(scheduler,ledger);
    }
}
