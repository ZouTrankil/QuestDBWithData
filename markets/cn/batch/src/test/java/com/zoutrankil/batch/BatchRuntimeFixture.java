package com.zoutrankil.batch;

import com.zoutrankil.batch.config.RuntimeConfiguration;
import com.zoutrankil.runtime.ProjectConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

/** 隔离测试用的 Batch 容器，不连接数据 API 的 QuestDB 数据源。 */
@SpringBootConfiguration
@Import(RuntimeConfiguration.class)
public class BatchRuntimeFixture {
    public static SpringApplication application() {
        var application = new SpringApplication(BatchRuntimeFixture.class);
        application.setAdditionalProfiles("batch");
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "fixture-logging",
                                Map.of("logging.config", "file:./config/logback-spring.xml")));
        application.setEnvironment(environment);
        application.setDefaultProperties(
                Map.of("spring.config.location", "file:./config/application.yml"));
        application.addInitializers(
                context -> ProjectConfiguration.configureDatabaseNetwork(context.getEnvironment()));
        application.setWebApplicationType(WebApplicationType.NONE);
        return application;
    }
}
