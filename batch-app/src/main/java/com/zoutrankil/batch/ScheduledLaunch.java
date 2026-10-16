package com.zoutrankil.batch;

import org.quartz.DisallowConcurrentExecution;

/** Compatibility name for Quartz jobs already persisted before the package split. */
@Deprecated
@DisallowConcurrentExecution
public final class ScheduledLaunch extends com.zoutrankil.batch.schedule.ScheduledLaunch {}
