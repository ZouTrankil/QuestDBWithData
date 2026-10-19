package com.zoutrankil.runtime;

import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.ConfigurableEnvironment;

/** 为独立进程入口应用共享的 Boot 日志配置。 */
public final class ProjectLogging {
    private ProjectLogging() {}

    public static void initialize(ConfigurableEnvironment environment) {
        var system=LoggingSystem.get(ProjectLogging.class.getClassLoader());
        var file=LogFile.get(environment);
        system.beforeInitialize();
        system.getSystemProperties(environment).apply(file);
        system.initialize(new LoggingInitializationContext(environment),environment.getProperty("logging.config"),file);
        Binder.get(environment).bind("logging.level",Bindable.mapOf(String.class,LogLevel.class))
                .orElse(Map.of()).forEach((name,level) -> system.setLogLevel(
                        "root".equalsIgnoreCase(name) ? LoggingSystem.ROOT_LOGGER_NAME : name,level));
        Runnable shutdown=system.getShutdownHandler();
        if(shutdown!=null) Runtime.getRuntime().addShutdownHook(new Thread(shutdown,"logging-shutdown"));
    }
}
