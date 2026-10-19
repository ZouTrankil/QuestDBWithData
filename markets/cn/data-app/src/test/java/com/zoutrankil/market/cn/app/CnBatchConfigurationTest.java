package com.zoutrankil.market.cn.app;

import com.zoutrankil.batch.api.ManagementServer;
import com.zoutrankil.batch.schedule.ScheduleCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CnBatchConfigurationTest {
    @TempDir Path directory;

    private AnnotationConfigApplicationContext host(boolean persistent, boolean enabled, String token) {
        var host = new AnnotationConfigApplicationContext();
        host.getEnvironment().setConversionService(new org.springframework.boot.convert.ApplicationConversionService());
        var values = new HashMap<String, Object>(Map.of(
                "app.runtime.persistent", persistent, "jdb.enabled", enabled,
                "jdb.metadata-url", "jdbc:sqlite:" + directory.resolve("batch.sqlite"),
                "jdb.archive-root", directory.resolve("archive").toString(),
                "jdb.api-port", 0, "jdb.api-token", token, "jdb.scheduling-enabled", true));
        values.put("jdb.l2-settle-age", "5m");
        values.put("jdb.questdb-visibility-timeout", "10s");
        values.put("jdb.questdb-http-url", "");
        values.put("jdb.tushare-token", "");
        ScheduleCatalog.SCHEDULE_IDS.forEach(id -> values.put("jdb.schedules[" + id + "]", "0 0 0 * * ?"));
        host.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", values));
        host.register(CnBatchConfiguration.class);
        return host;
    }

    @Test void disabledRuntimeAndFiniteCliDoNotCreateDatabaseOrBackgroundBeans() {
        for (var switches : new boolean[][]{{true, false}, {false, true}, {false, false}}) {
            try (var host = host(switches[0], switches[1], "")) {
                host.refresh();
                assertTrue(host.getBeansOfType(CnBatchConfiguration.ManagedBatchRuntime.class).isEmpty());
                assertFalse(Files.exists(directory.resolve("batch.sqlite")));
                assertFalse(Files.exists(directory.resolve("archive")));
            }
        }
    }

    @Test void marketHostOwnsIsolatedRuntimeAndReleasesDatabaseOnShutdown() throws Exception {
        var parentSource = mock(DataSource.class);
        var parentTransaction = mock(PlatformTransactionManager.class);
        CnBatchConfiguration.ManagedBatchRuntime runtime;
        Scheduler scheduler;
        ManagementServer management;
        try (var host = host(true, true, "fixture-token-with-at-least-24-characters")) {
            host.getBeanFactory().registerSingleton("dataSource", parentSource);
            host.getBeanFactory().registerSingleton("transactionManager", parentTransaction);
            host.refresh();
            runtime = host.getBean(CnBatchConfiguration.ManagedBatchRuntime.class);
            assertTrue(runtime.isRunning());
            var child = runtime.context();
            assertNull(child.getParent());
            assertNotSame(parentSource, child.getBean(DataSource.class));
            assertNotSame(parentTransaction, child.getBean(PlatformTransactionManager.class));
            assertSame(parentSource, host.getBean(DataSource.class));
            scheduler = child.getBean(Scheduler.class);
            management = child.getBean(ManagementServer.class);
            assertTrue(management.port() > 0);
            assertTrue(scheduler.isStarted());
            for (String id : ScheduleCatalog.SCHEDULE_IDS) {
                assertTrue(scheduler.checkExists(new JobKey(id, "jdb")));
                assertEquals(Trigger.TriggerState.PAUSED, scheduler.getTriggerState(new TriggerKey(id, "jdb")));
            }
            runtime.start();
            assertSame(child, runtime.context());
            verifyNoInteractions(parentSource, parentTransaction);
        }
        assertFalse(runtime.isRunning());
        assertTrue(scheduler.isShutdown());
        try (var socket = new java.net.ServerSocket(management.port(), 1, java.net.InetAddress.getLoopbackAddress())) {
            assertTrue(socket.isBound());
        }
        // 同一 SQLite 再启动成功，证明宿主退出已释放租约，并保留暂停状态。
        try (var restarted = host(true, true, "fixture-token-with-at-least-24-characters")) {
            restarted.refresh();
            var restored = restarted.getBean(CnBatchConfiguration.ManagedBatchRuntime.class).context().getBean(Scheduler.class);
            assertEquals(Trigger.TriggerState.PAUSED, restored.getTriggerState(new TriggerKey("post_close", "jdb")));
        }
    }

    @Test void startupFailureReleasesLeaseForCorrectedConfiguration() {
        try (var broken = host(true, true, "")) {
            assertThrows(RuntimeException.class, broken::refresh);
        }
        try (var corrected = host(true, true, "fixture-token-with-at-least-24-characters")) {
            corrected.refresh();
            assertTrue(corrected.getBean(CnBatchConfiguration.ManagedBatchRuntime.class).isRunning());
        }
    }
}
