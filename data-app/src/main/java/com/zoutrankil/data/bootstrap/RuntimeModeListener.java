package com.zoutrankil.data.bootstrap;

import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;

/** Resolves the application mode after ConfigData and before Spring creates the context. */
final class RuntimeModeListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    private static final String WEB_TYPE = "spring.main.web-application-type";
    private final StartupArguments arguments;

    RuntimeModeListener(StartupArguments arguments) { this.arguments = arguments; }

    @Override public int getOrder() {
        return EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER + 1;
    }

    @Override public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        var environment = event.getEnvironment();
        boolean web = !arguments.hasCommand()
                && (arguments.web() || environment.getProperty("app.web.enabled", Boolean.class, true));
        var selected = web ? WebApplicationType.REACTIVE : WebApplicationType.NONE;
        if (environment.containsProperty(WEB_TYPE)) {
            var configured = environment.getProperty(WEB_TYPE, WebApplicationType.class);
            if (configured != selected)
                throw new IllegalArgumentException(WEB_TYPE + " conflicts with the selected " + selected + " mode");
        }
        // Spring binds spring.main after this event; preserve the already validated decision.
        environment.getPropertySources().addFirst(new MapPropertySource("questDataRuntimeMode",
                Map.of(WEB_TYPE, selected.name())));
        event.getSpringApplication().setWebApplicationType(selected);
    }
}
