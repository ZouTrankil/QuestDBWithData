package com.zoutrankil.data.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** The QuestData entry point owns finite CLI contexts; the Batch entry point has its own lifecycle. */
public final class QuestDataStartup {
    private QuestDataStartup() {}

    public static ConfigurableApplicationContext run(SpringApplication application, String... arguments) {
        var parsed = StartupArguments.parse(arguments);
        application.addListeners(new RuntimeModeListener(parsed));
        var context = application.run(arguments);
        if (application.getWebApplicationType() == WebApplicationType.NONE) context.close();
        return context;
    }
}
