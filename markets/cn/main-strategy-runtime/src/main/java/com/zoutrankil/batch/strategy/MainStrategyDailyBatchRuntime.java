package com.zoutrankil.batch.strategy;

import com.zoutrankil.batch.job.MainStrategyDailyWork;
import com.zoutrankil.data.config.MainStrategyBatchProperties;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 独立策略上下文就绪后才启动；有限命令行调用不构造此生命周期组件。 */
public final class MainStrategyDailyBatchRuntime
        implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {
    private final ApplicationContext parent;
    private final MainStrategyBatchProperties properties;
    private final MainStrategyDailyWork work;
    private volatile AnnotationConfigApplicationContext child;
    private volatile String startupError;

    public MainStrategyDailyBatchRuntime(
            ApplicationContext parent,
            MainStrategyBatchProperties properties,
            MainStrategyDailyWork work) {
        this.parent = parent;
        this.properties = properties;
        this.work = work;
    }

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (event.getApplicationContext() != parent || child != null) return;
        var context = new AnnotationConfigApplicationContext();
        try {
            context.setParent(parent);
            context.setId(parent.getId() + ":main-strategy-batch");
            // 直接从父上下文取得唯一的配置对象和 Java 流水线。
            // 若在子上下文额外注册别名，会产生重复的自动注入候选。
            context.register(MainStrategyDailyChildConfiguration.class);
            context.refresh();
            var coordinator = context.getBean(MainStrategyDailyCoordinator.class);
            coordinator.install();
            child = context;
            if (properties.startupCatchup()) coordinator.catchup("startup:" + UUID.randomUUID());
        } catch (Exception error) {
            startupError =
                    error.getClass().getSimpleName()
                            + ": "
                            + Objects.toString(error.getMessage(), "");
            child = null;
            context.close();
            throw new IllegalStateException(
                    "Main-strategy Batch runtime failed to initialize", error);
        }
    }

    private MainStrategyDailyCoordinator coordinator() {
        var current = child;
        if (current == null)
            throw new IllegalStateException(
                    "Main-strategy Batch runtime is unavailable: " + startupError);
        return current.getBean(MainStrategyDailyCoordinator.class);
    }

    public Map<String, Object> status() throws Exception {
        if (child == null)
            return Map.of(
                    "job",
                    MainStrategyDailyWork.JOB,
                    "status",
                    "UNAVAILABLE",
                    "reason",
                    startupError == null ? "Strategy context is not ready" : startupError);
        return coordinator().status();
    }

    public Map<String, Object> detail(String instance) {
        return coordinator().detail(instance);
    }

    public Map<String, Object> catchup(String requestId) {
        return coordinator().catchup(requestId);
    }

    public Map<String, Object> reconcilePublication(
            String instance, String stage, String runId, boolean writerStopped, String requestId)
            throws Exception {
        return coordinator().reconcilePublication(instance, stage, runId, writerStopped, requestId);
    }

    public Map<String, Object> setEnabled(boolean enabled) throws Exception {
        return coordinator().setEnabled(enabled);
    }

    @Override
    public synchronized void destroy() {
        if (child != null) {
            child.getBean(MainStrategyDailyCoordinator.class).close();
            child.close();
            child = null;
        }
    }
}
