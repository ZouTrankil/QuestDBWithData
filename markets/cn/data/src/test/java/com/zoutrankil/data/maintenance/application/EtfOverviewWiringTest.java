package com.zoutrankil.data.maintenance.application;

import com.zoutrankil.data.config.EtfOverviewRefreshConfiguration;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtfOverviewWiringTest {
    @TempDir Path root;
    @Test void finiteProcessBindsDeploymentDurationsWithoutStartingARefreshLoopOrOpeningTheDatabase() throws Exception {
        var source=mock(DataSource.class);
        var application=new SpringApplication(EtfOverviewMaintenance.class,EtfOverviewRefreshConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);application.setAdditionalProfiles("data");
        application.addInitializers(context -> ((GenericApplicationContext)context).registerBean(
                "fixtureMaintenanceJdbc",JdbcTemplate.class,() -> new JdbcTemplate(source)));
        try(var context=application.run("--spring.main.banner-mode=off","--app.sync.ledger-path="+root.resolve("uncreated.sqlite"),
                "--logging.file.name="+root.resolve("application.log"),"--app.materialized-views.etf-overview.refresh-timeout=3s")) {
            assertNotNull(context.getBean(EtfOverviewMaintenance.class));
            assertTrue(context.getBeansOfType(ThreadPoolTaskScheduler.class).isEmpty());
            verify(source,never()).getConnection();assertFalse(java.nio.file.Files.exists(root.resolve("uncreated.sqlite")));
        } finally {
            // 日志上下文由测试 JVM 共用；只关闭本用例的文件句柄，避免 Windows 无法清理临时目录。
            var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            var appender=logger.getAppender("FILE");
            if(appender instanceof ch.qos.logback.core.FileAppender<?> file && file.getFile()!=null
                    && Path.of(file.getFile()).toAbsolutePath().normalize().equals(root.resolve("application.log").toAbsolutePath().normalize())) {
                logger.detachAppender(appender);file.stop();
            }
        }
    }
}
