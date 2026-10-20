package com.zoutrankil.data.maintenance.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.core.FileAppender;

import com.zoutrankil.data.config.EtfOverviewRefreshConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

class EtfOverviewWiringTest {
    @TempDir Path root;

    @Test
    void finiteProcessBindsDeploymentDurationsWithoutStartingARefreshLoopOrOpeningTheDatabase()
            throws Exception {
        var source = mock(DataSource.class);
        var application =
                new SpringApplication(
                        EtfOverviewMaintenance.class, EtfOverviewRefreshConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("data");
        application.addInitializers(
                context ->
                        ((GenericApplicationContext) context)
                                .registerBean(
                                        "fixtureMaintenanceJdbc",
                                        JdbcTemplate.class,
                                        () -> new JdbcTemplate(source)));
        try (var context =
                application.run(
                        "--spring.main.banner-mode=off",
                        "--app.sync.ledger-path=" + root.resolve("uncreated.sqlite"),
                        "--logging.file.name=" + root.resolve("application.log"),
                        "--app.materialized-views.etf-overview.refresh-timeout=3s")) {
            assertNotNull(context.getBean(EtfOverviewMaintenance.class));
            assertTrue(context.getBeansOfType(ThreadPoolTaskScheduler.class).isEmpty());
            verify(source, never()).getConnection();
            assertFalse(Files.exists(root.resolve("uncreated.sqlite")));
        } finally {
            // 日志上下文由测试 JVM 共用；只关闭本用例的文件句柄，避免 Windows 无法清理临时目录。
            var logger =
                    (ch.qos.logback.classic.Logger)
                            LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            var appender = logger.getAppender("FILE");
            if (appender instanceof FileAppender<?> file
                    && file.getFile() != null
                    && Path.of(file.getFile())
                            .toAbsolutePath()
                            .normalize()
                            .equals(root.resolve("application.log").toAbsolutePath().normalize())) {
                logger.detachAppender(appender);
                file.stop();
            }
        }
    }
}
