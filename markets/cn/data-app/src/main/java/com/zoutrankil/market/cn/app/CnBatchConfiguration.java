package com.zoutrankil.market.cn.app;

import com.zoutrankil.batch.config.RuntimeConfiguration;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

/** 只有常驻 A 股入口可以托管 Batch，主策略和有限 CLI 不导入这套装配。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"app.runtime.persistent", "jdb.enabled"},
        havingValue = "true")
public class CnBatchConfiguration {
    @Bean
    ManagedBatchRuntime managedBatchRuntime(ConfigurableEnvironment environment) {
        return new ManagedBatchRuntime(environment);
    }

    /** 同一进程内隔离 SQLite 与 QuestDB 的 Bean，并随市场应用一起关闭。 */
    static final class ManagedBatchRuntime implements SmartLifecycle, DisposableBean {
        private final ConfigurableEnvironment environment;
        private AnnotationConfigApplicationContext context;

        ManagedBatchRuntime(ConfigurableEnvironment environment) {
            this.environment = environment;
        }

        @Override
        public synchronized void start() {
            if (context != null) return;
            var next = new AnnotationConfigApplicationContext();
            // 复用已解析的 YAML、环境变量和 CLI 覆盖，不另启 SpringApplication 改写全局日志。
            // 不设置 parent，避免 Batch 的来源客户端、事务管理器误用 QuestDB 容器里的 Bean。
            var isolated = new StandardEnvironment();
            isolated.getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            isolated.getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            isolated.merge(environment);
            isolated.setConversionService(environment.getConversionService());
            next.setEnvironment(isolated);
            next.register(RuntimeConfiguration.class);
            try {
                next.refresh();
                for (var runner : next.getBeansOfType(ApplicationRunner.class).values())
                    runner.run(new DefaultApplicationArguments(new String[0]));
                context = next;
            } catch (Exception failure) {
                // 初始化或登记失败必须释放本次 SQLite 租约，不能留下半启动的后台服务。
                next.close();
                throw new IllegalStateException("A 股 Batch 启动失败", failure);
            }
        }

        @Override
        public synchronized void stop() {
            if (context == null) return;
            var previous = context;
            context = null;
            previous.close();
        }

        @Override
        public synchronized boolean isRunning() {
            return context != null && context.isActive();
        }

        @Override
        public void destroy() {
            stop();
        }

        synchronized AnnotationConfigApplicationContext context() {
            return context;
        }
    }
}
