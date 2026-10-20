package com.zoutrankil.batch.strategy;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/** 首次启动和恢复均使用这一个持久化 Quartz 作业身份。 */
@DisallowConcurrentExecution
public final class MainStrategyDailyScheduledLaunch implements Job {
    @Override
    public void execute(JobExecutionContext execution) throws JobExecutionException {
        try {
            var coordinator =
                    (MainStrategyDailyCoordinator)
                            execution.getScheduler().getContext().get("mainStrategyCoordinator");
            coordinator.probe(
                    "quartz:" + execution.getFireInstanceId(),
                    execution.getScheduledFireTime().toInstant(),
                    execution.getFireTime().toInstant(),
                    execution.getTrigger().getKey().getName().equals("recovery"));
        } catch (Exception error) {
            throw new JobExecutionException(error, false);
        }
    }
}
