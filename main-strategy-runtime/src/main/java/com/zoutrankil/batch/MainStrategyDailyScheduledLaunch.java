package com.zoutrankil.batch;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/** Compatibility name for the existing persistent main-strategy Quartz job. */
@Deprecated
@DisallowConcurrentExecution
public final class MainStrategyDailyScheduledLaunch implements Job {
    @Override public void execute(JobExecutionContext context) throws JobExecutionException {
        new com.zoutrankil.batch.strategy.MainStrategyDailyScheduledLaunch().execute(context);
    }
}
