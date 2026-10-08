package com.zoutrankil.batch;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/** Both first launch and recovery use this one durable Quartz job identity. */
@DisallowConcurrentExecution
public final class MainStrategyDailyScheduledLaunch implements Job {
    @Override public void execute(JobExecutionContext execution) throws JobExecutionException {
        try {
            var coordinator=(MainStrategyDailyCoordinator)execution.getScheduler().getContext().get("mainStrategyCoordinator");
            coordinator.probe("quartz:"+execution.getFireInstanceId(),
                    execution.getScheduledFireTime().toInstant(),execution.getFireTime().toInstant(),
                    execution.getTrigger().getKey().getName().equals("recovery"));
        } catch(Exception error) { throw new JobExecutionException(error,false); }
    }
}
