package com.zoutrankil.batch;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

@Configuration(proxyBeanMethods=false)
@Import(SourceRuntimeConfiguration.class)
@EnableBatchProcessing
@EnableJdbcJobRepository(tablePrefix="BATCH_")
public class RuntimeConfiguration {
    @Bean(destroyMethod="close") DataSource dataSource(Environment env) {
        if (env.getProperty("jdb.production-enabled",Boolean.class,false))
            throw new IllegalArgumentException("Production triggers are not supported in this delivery");
        String url=env.getRequiredProperty("jdb.metadata-url");
        return metadataDataSource(url);
    }
    static DataSource metadataDataSource(String url) {
        validateMetadataUrl(url);
        var sqlite=new org.sqlite.SQLiteConfig();
        sqlite.setBusyTimeout(10_000); sqlite.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        sqlite.enforceForeignKeys(true);
        var config=new HikariConfig(); config.setJdbcUrl(url);
        config.setDataSourceProperties(sqlite.toProperties());
        config.setMaximumPoolSize(4); config.setConnectionTimeout(10_000);
        return new HikariDataSource(config);
    }
    @Bean(destroyMethod="close") SqliteOwnershipLock.Handle sqliteRuntimeLease(DataSource dataSource) throws java.sql.SQLException {
        return metadataRuntimeLease(dataSource);
    }
    static SqliteOwnershipLock.Handle metadataRuntimeLease(DataSource dataSource) throws java.sql.SQLException {
        String url;
        try(var connection=dataSource.getConnection()) { url=connection.getMetaData().getURL(); }
        return SqliteOwnershipLock.tryAcquire(url,"runtime")
                .orElseThrow(() -> new IllegalStateException("Another Java runtime already owns this SQLite metadata file"));
    }
    static void validateMetadataUrl(String url) {
        if (url==null || !url.startsWith("jdbc:sqlite:") || url.equals("jdbc:sqlite::memory:")
                || url.contains("mode=memory") || url.contains("?"))
            throw new IllegalArgumentException("Metadata must use a persistent local SQLite file");
        Path file=Path.of(url.substring("jdbc:sqlite:".length())).toAbsolutePath().normalize();
        if (Files.isDirectory(file)) throw new IllegalArgumentException("SQLite metadata path must be a file");
        try { Files.createDirectories(file.getParent()); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot create SQLite metadata directory",error); }
    }
    @Bean(initMethod="migrate") Flyway metadataMigrations(DataSource dataSource,SqliteOwnershipLock.Handle sqliteRuntimeLease) {
        return metadataFlyway(dataSource);
    }
    static Flyway metadataFlyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/batch").cleanDisabled(true).baselineOnMigrate(false).load();
    }
    @Bean L2ArchiveAdmission l2ArchiveAdmission(DataSource dataSource, Environment env) throws java.io.IOException {
        return new L2ArchiveAdmission(new org.springframework.jdbc.core.JdbcTemplate(dataSource),
                Path.of(env.getRequiredProperty("jdb.archive-root")),
                env.getProperty("jdb.l2-settle-age", java.time.Duration.class, java.time.Duration.ofMinutes(5)),
                env.getProperty("jdb.l2-max-archive-bytes", Long.class, 8L*1024*1024*1024),
                env.getProperty("jdb.l2-max-expanded-bytes", Long.class, 32L*1024*1024*1024));
    }
    @Bean L2ArchiveMaterializer l2ArchiveMaterializer(Environment env) throws java.io.IOException {
        return new L2ArchiveMaterializer(Path.of(env.getRequiredProperty("jdb.archive-root")),
                env.getProperty("jdb.l2-max-expanded-bytes",Long.class,32L*1024*1024*1024),
                env.getProperty("jdb.l2-max-materialized-bytes",Long.class,32L*1024*1024*1024));
    }
    @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new JdbcTransactionManager(dataSource); }
    @Bean @DependsOn("metadataMigrations") SqliteLedger ledger(DataSource dataSource) { return new SqliteLedger(dataSource); }
    @Bean @DependsOn("metadataMigrations") ExternalExecutionStore externalExecutionStore(DataSource dataSource) {
        return new ExternalExecutionStore(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }
    @Bean ExternalComputation externalComputation(ExternalExecutionStore store,Environment env) throws java.io.IOException {
        var commands=Json.MAPPER.readValue(env.getProperty("jdb.external.commands-json","{}"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,List<String>>>() {});
        var childEnvironment=Json.MAPPER.readValue(env.getProperty("jdb.external.environment-json","{}"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>() {});
        if(commands.isEmpty())return store::observe;
        return new RestrictedExternalExecutor(store,Path.of(env.getRequiredProperty("jdb.archive-root")),commands,childEnvironment,
                env.getProperty("jdb.external.timeout",java.time.Duration.class,java.time.Duration.ofHours(12)));
    }
    @Bean StageExecutor stageExecutor(ExternalComputation external,SqliteLedger ledger,Environment env) {
        var remaining=StageExecutor.disconnected(external);
        return new DataReadiness(ledger,Path.of(env.getRequiredProperty("jdb.archive-root")),remaining);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="jdb.core-source-fanout-enabled",havingValue="true",matchIfMissing=true)
    CoreSourceFanout coreSourceFanout(SqliteLedger ledger,NativeSourceService sources,Environment env) {
        return new CoreSourceFanout(ledger,sources,Path.of(env.getRequiredProperty("jdb.archive-root")));
    }
    @Bean Job postClose(JobRepository repository, PlatformTransactionManager transactionManager, SqliteLedger ledger, StageExecutor executor) {
        return BatchJobs.postClose(repository,transactionManager,ledger,executor);
    }
    @Bean L2ArchiveQuestDbIngestor l2ArchiveQuestDbIngestor(Environment env,SqliteLedger ledger) throws java.io.IOException {
        String configured=env.getProperty("jdb.questdb-http-url","");
        java.net.URI endpoint=configured.isBlank()?null:java.net.URI.create(configured);
        return new L2ArchiveQuestDbIngestor(Path.of(env.getRequiredProperty("jdb.archive-root")),endpoint,
                env.getProperty("jdb.questdb-http-authorization",""),ledger,
                env.getProperty("jdb.questdb-visibility-timeout",java.time.Duration.class,java.time.Duration.ofSeconds(10)),
                env.getProperty("jdb.questdb-poll-interval",java.time.Duration.class,java.time.Duration.ofMillis(100)));
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="jdb.baidu",name="enabled",havingValue="true")
    BaiduL2Subscription baiduL2Subscription(Environment env,BaiduTransferIntentStore intents) throws java.io.IOException {
        String cookiePath=env.getRequiredProperty("jdb.baidu.cookie-file");
        String shareUrl=env.getRequiredProperty("jdb.baidu.share-url");
        String sourcePath=env.getProperty("jdb.baidu.source-path","");
        if(sourcePath.isBlank())sourcePath="{year}/{month}";
        var cookies=BaiduShareClient.readPrivateCookieFile(Path.of(cookiePath));
        var client=new BaiduShareClient(cookies);
        var settings=new BaiduL2Subscription.Settings(shareUrl,env.getProperty("jdb.baidu.share-password",""),sourcePath,
                env.getProperty("jdb.baidu.unwrap-single-folder",Boolean.class,false),
                env.getProperty("jdb.baidu.remote-root","/"),env.getProperty("jdb.baidu.minimum-size-bytes",Long.class,1_000_000_000L));
        return new BaiduL2Subscription(client,settings,intents);
    }
    @Bean @DependsOn("metadataMigrations") BaiduTransferIntentStore baiduTransferIntentStore(DataSource dataSource) {
        return new BaiduTransferIntentStore(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="jdb.baidu",name="download-enabled",havingValue="true")
    BaiduPcsGoDownloader baiduPcsGoDownloader(Environment env) {
        var config=new BaiduPcsGoDownloader.Config(Path.of(env.getRequiredProperty("jdb.baidu.pcs-executable")),
                Path.of(env.getRequiredProperty("jdb.baidu.pcs-config-directory")),
                Path.of(env.getRequiredProperty("jdb.archive-root")),env.getRequiredProperty("jdb.baidu.expected-account-uid"),
                env.getProperty("jdb.baidu.download-timeout",java.time.Duration.class,java.time.Duration.ofHours(12)),
                env.getProperty("jdb.l2-max-archive-bytes",Long.class,8L*1024*1024*1024));
        return new BaiduPcsGoDownloader(config);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="jdb.baidu",name="download-enabled",havingValue="true")
    BaiduL2ArchiveDownloader baiduL2ArchiveDownloader(BaiduL2Subscription subscription,BaiduPcsGoDownloader downloader) {
        return new BaiduL2ArchiveDownloader(subscription,downloader);
    }
    @Bean Job l2ArchiveIntegrity(JobRepository repository,PlatformTransactionManager transactionManager,L2ArchiveAdmission archives,L2ArchiveMaterializer materializer,L2ArchiveQuestDbIngestor ingestor) {
        return BatchJobs.l2ArchiveIntegrity(repository,transactionManager,archives,materializer,ingestor);
    }
    @Bean LaunchService launchService(SqliteLedger ledger, JobOperator operator, java.util.Map<String,Job> jobs,SourceCollector collector,
                                      org.springframework.beans.factory.ObjectProvider<CoreSourceFanout> coreSourceFanout) {
        return new LaunchService(ledger,operator,jobs.values(),collector,coreSourceFanout.getIfAvailable());
    }
    @Bean PostCloseRecoveryCoordinator postCloseRecoveryCoordinator(SqliteLedger ledger,LaunchService launches) {
        return new PostCloseRecoveryCoordinator(ledger,launches);
    }
    @Bean @DependsOn("metadataMigrations") SchedulerFactoryBean quartzScheduler(DataSource dataSource, LaunchService launches,
                    PostCloseRecoveryCoordinator recovery, SqliteLedger ledger, Environment env) throws Exception {
        var factory=new SchedulerFactoryBean(); factory.setDataSource(dataSource);
        factory.setSchedulerName("jdb-isolated"); factory.setAutoStartup(env.getProperty("jdb.scheduling-enabled",Boolean.class,false));
        factory.setWaitForJobsToCompleteOnShutdown(true); factory.setOverwriteExistingJobs(false);
        var properties=new Properties();
        properties.setProperty("org.quartz.scheduler.instanceId","AUTO");
        properties.setProperty("org.quartz.jobStore.driverDelegateClass",SqliteQuartzDelegate.class.getName());
        properties.setProperty("org.quartz.jobStore.tablePrefix","QRTZ_");
        properties.setProperty("org.quartz.jobStore.isClustered","false");
        properties.setProperty("org.quartz.jobStore.acquireTriggersWithinLock","true");
        properties.setProperty("org.quartz.jobStore.selectWithLockSQL","SELECT * FROM {0}LOCKS WHERE SCHED_NAME = {1} AND LOCK_NAME = ?");
        properties.setProperty("org.quartz.jobStore.useProperties","true");
        properties.setProperty("org.quartz.threadPool.threadCount","2");
        factory.setQuartzProperties(properties);
        var context=new HashMap<String,Object>(); context.put("launches",launches); context.put("ledger",ledger);context.put("postCloseRecovery",recovery);
        Path archive=Path.of(env.getRequiredProperty("jdb.archive-root")).toAbsolutePath().normalize();
        Files.createDirectories(archive); context.put("archive",archive);
        String calendar=env.getProperty("jdb.calendar-file","");
        if (!calendar.isBlank()) context.put("calendar",Json.read(Files.readString(Path.of(calendar)),TradingCalendar.class));
        factory.setSchedulerContextAsMap(context);
        return factory;
    }
    @Bean org.springframework.boot.ApplicationRunner installSchedules(org.quartz.Scheduler scheduler, SqliteLedger ledger) {
        return args -> ScheduleCatalog.install(scheduler,ledger);
    }
    @Bean(initMethod="start",destroyMethod="close") ManagementServer managementServer(SqliteLedger ledger, LaunchService launches,
                    org.quartz.Scheduler scheduler, Environment env,NativeSourceService sources,L2ArchiveAdmission l2Archives,
                    org.springframework.batch.core.launch.JobOperator jobOperator,
                    @Qualifier("l2ArchiveIntegrity") Job l2ArchiveJob,org.springframework.beans.factory.ObjectProvider<BaiduL2Subscription> baidu,
                    org.springframework.beans.factory.ObjectProvider<BaiduL2ArchiveDownloader> baiduDownloader) throws Exception {
        return new ManagementServer(env.getProperty("jdb.api-port",Integer.class,9085),
                env.getRequiredProperty("jdb.api-token"),ledger,launches,scheduler,sources,l2Archives,jobOperator,l2ArchiveJob,
                baidu.getIfAvailable(),baiduDownloader.getIfAvailable());
    }
}
