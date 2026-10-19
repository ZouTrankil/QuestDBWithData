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
        configureDatabaseNetwork(environment);
        return environment;
    }

    /** 仅将配置的数据库地址加入直连名单，避免本机全局代理截获 PGWire 和 QuestDB HTTP。 */
    public static synchronized void configureDatabaseNetwork(org.springframework.core.env.Environment environment) {
        if (!environment.getProperty("app.questdb.direct-connection", Boolean.class, true)) return;
        String host = environment.getProperty("app.questdb.host", "").trim();
        if (host.isEmpty() || !host.matches("[A-Za-z0-9_.:-]+")) return;
        for (String property : java.util.List.of("http.nonProxyHosts", "socksNonProxyHosts")) {
            var hosts = new java.util.LinkedHashSet<>(java.util.List.of(
                    System.getProperty(property, "localhost|127.*|[::1]").split("\\|")));
            hosts.add(host);
            System.setProperty(property, String.join("|", hosts));
        }
    }

}
