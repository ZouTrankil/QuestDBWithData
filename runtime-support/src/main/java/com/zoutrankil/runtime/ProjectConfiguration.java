package com.zoutrankil.runtime;

import java.util.Map;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Loads the same ConfigData as Boot for finite tools, without creating application beans. */
public final class ProjectConfiguration {
    private ProjectConfiguration() {}

    public static ConfigurableEnvironment load(String profile) {
        return load(profile, System.getenv());
    }

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
