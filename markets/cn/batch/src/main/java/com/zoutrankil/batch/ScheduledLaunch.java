package com.zoutrankil.batch;

import org.quartz.DisallowConcurrentExecution;

/** 保留 SQLite 中已有的 Quartz 类名，迁移装配时不重建任务。 */
@Deprecated
@DisallowConcurrentExecution
public final class ScheduledLaunch extends com.zoutrankil.batch.schedule.ScheduledLaunch {}
