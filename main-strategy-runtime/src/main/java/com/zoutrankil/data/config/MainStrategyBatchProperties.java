package com.zoutrankil.data.config;

import java.nio.file.Path;
import java.time.LocalTime;
import org.quartz.CronExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Main Web runtime only; credentials and the isolated delivery runtime have separate configuration. */
@ConfigurationProperties(prefix="app.sync.batch")
public record MainStrategyBatchProperties(Boolean enabled, Boolean startupCatchup, String ledgerPath,
        String cron, String retryCron, LocalTime completionCutoff, Integer maximumRecoveryAttempts) {
    public MainStrategyBatchProperties {
        enabled=enabled==null?true:enabled;
        startupCatchup=startupCatchup==null?true:startupCatchup;
        ledgerPath=ledgerPath==null||ledgerPath.isBlank()?"var/main-strategy-batch.sqlite":ledgerPath;
        cron=cron==null||cron.isBlank()?"0 35 20 * * ?":cron;
        retryCron=retryCron==null||retryCron.isBlank()?"0 0/15 21-23 * * ?":retryCron;
        completionCutoff=completionCutoff==null?LocalTime.of(20,30):completionCutoff;
        maximumRecoveryAttempts=maximumRecoveryAttempts==null?12:maximumRecoveryAttempts;
        if(!CronExpression.isValidExpression(cron)||!CronExpression.isValidExpression(retryCron))
            throw new IllegalArgumentException("Main-strategy cron expression is invalid");
        if(completionCutoff.isBefore(LocalTime.of(20,30)))
            throw new IllegalArgumentException("Main-strategy completion cutoff must not precede DailySyncEndDate 20:30");
        if(maximumRecoveryAttempts<1||maximumRecoveryAttempts>32)
            throw new IllegalArgumentException("Main-strategy recovery attempts must be bounded to 1..32");
        if(Path.of(ledgerPath).toString().isBlank()) throw new IllegalArgumentException("Persistent metadata path is required");
    }
}
