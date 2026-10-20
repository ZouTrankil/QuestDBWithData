package com.zoutrankil.batch;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/** 兼容现有持久化主策略 Quartz 作业的旧类名。 */
@Deprecated
@DisallowConcurrentExecution
public final class MainStrategyDailyScheduledLaunch implements Job {
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        new com.zoutrankil.batch.strategy.MainStrategyDailyScheduledLaunch().execute(context);
    }
}
