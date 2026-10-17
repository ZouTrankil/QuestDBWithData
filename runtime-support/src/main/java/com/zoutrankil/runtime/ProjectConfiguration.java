package com.zoutrankil.runtime;

import java.util.Map;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** 有限工具加载与 Boot 相同的 ConfigData，不创建应用组件。 */
public final class ProjectConfiguration {
    private ProjectConfiguration() {}

    public static ConfigurableEnvironment load(String profile) {
        return load(profile, System.getenv());
    }

    /** 复用 Boot 配置解析和类型转换，只加载配置；命令行工具因此不会顺带启动调度或数据库组件。 */
    public static ConfigurableEnvironment load(String profile, Map<String, String> variables) {
        var environment = new StandardEnvironment();
        environment.setConversionService(new ApplicationConversionService());
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new java.util.LinkedHashMap<>(variables)));
        ConfigurationPropertySources.attach(environment);
        ConfigDataEnvironmentPostProcessor.applyTo(environment, null, null, profile);
        return environment;
    }
}
