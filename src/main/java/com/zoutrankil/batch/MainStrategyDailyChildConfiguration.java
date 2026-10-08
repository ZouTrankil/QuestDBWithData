package com.zoutrankil.batch;

import com.zoutrankil.data.config.MainStrategyBatchProperties;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/** Local names shadow only the child context; the parent QuestDB DataSource remains separate. */
@Configuration(proxyBeanMethods=false)
@EnableBatchProcessing(taskExecutorRef="mainStrategyBatchTaskExecutor",transactionManagerRef="transactionManager")
@EnableJdbcJobRepository(tablePrefix="BATCH_",dataSourceRef="dataSource",transactionManagerRef="transactionManager")
public class MainStrategyDailyChildConfiguration {
    @Bean(destroyMethod="close") DataSource dataSource(MainStrategyBatchProperties properties) {
        return RuntimeConfiguration.metadataDataSource("jdbc:sqlite:"+
                java.nio.file.Path.of(properties.ledgerPath()).toAbsolutePath().normalize());
    }
    @Bean(destroyMethod="close") SqliteOwnershipLock.Handle sqliteRuntimeLease(DataSource dataSource) throws Exception {
        return RuntimeConfiguration.metadataRuntimeLease(dataSource);
    }
    @Bean(initMethod="migrate") Flyway metadataMigrations(DataSource dataSource,SqliteOwnershipLock.Handle sqliteRuntimeLease) {
        return RuntimeConfiguration.metadataFlyway(dataSource);
    }
    @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
    @Bean TaskExecutor mainStrategyBatchTaskExecutor() {
        // LaunchService owns its file lease until the synchronous Batch operator returns.
        return new SyncTaskExecutor();
    }
    @Bean @DependsOn("metadataMigrations") SqliteLedger ledger(DataSource dataSource) {
        return new SqliteLedger(dataSource);
    }
    @Bean Job mainStrategyDaily(JobRepository repository,PlatformTransactionManager manager,
            SqliteLedger ledger,MainStrategyDailyWork work) {
        return MainStrategyDailyBatchJob.build(repository,manager,ledger,work);
    }
    @Bean LaunchService launchService(SqliteLedger ledger,JobOperator operator,Job mainStrategyDaily) {
        return new LaunchService(ledger,operator,List.of(mainStrategyDaily),null,null);
    }
    @Bean @DependsOn("metadataMigrations") SchedulerFactoryBean quartzScheduler(DataSource dataSource) {
        var factory=new SchedulerFactoryBean();factory.setDataSource(dataSource);
        factory.setSchedulerName("main-strategy-daily");factory.setAutoStartup(false);
        factory.setWaitForJobsToCompleteOnShutdown(true);factory.setOverwriteExistingJobs(false);
        var properties=new Properties();
        properties.setProperty("org.quartz.scheduler.instanceId","AUTO");
        properties.setProperty("org.quartz.jobStore.driverDelegateClass",SqliteQuartzDelegate.class.getName());
        properties.setProperty("org.quartz.jobStore.tablePrefix","QRTZ_");
        properties.setProperty("org.quartz.jobStore.isClustered","false");
        properties.setProperty("org.quartz.jobStore.acquireTriggersWithinLock","true");
        properties.setProperty("org.quartz.jobStore.selectWithLockSQL","SELECT * FROM {0}LOCKS WHERE SCHED_NAME = {1} AND LOCK_NAME = ?");
        properties.setProperty("org.quartz.jobStore.useProperties","true");
        properties.setProperty("org.quartz.threadPool.threadCount","1");
        factory.setQuartzProperties(properties);return factory;
    }
    @Bean(destroyMethod="close") MainStrategyDailyCoordinator coordinator(SqliteLedger ledger,LaunchService launches,
            MainStrategyDailyWork work,MainStrategyBatchProperties properties,org.quartz.Scheduler scheduler) throws Exception {
        var coordinator=new MainStrategyDailyCoordinator(ledger,launches,work,properties,scheduler,Clock.systemUTC());
        scheduler.getContext().put("mainStrategyCoordinator",coordinator);
        return coordinator;
    }
}
