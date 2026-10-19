package com.zoutrankil.data.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** 市场入口管理常驻服务和有限 CLI 的生命周期；CLI 执行完立即关闭容器。 */
public final class QuestDataStartup {
    private QuestDataStartup() {}

    public static ConfigurableApplicationContext run(SpringApplication application, String... arguments) {
        var parsed = StartupArguments.parse(arguments);
        application.addListeners(new RuntimeModeListener(parsed));
        application.addInitializers(context -> com.zoutrankil.runtime.ProjectConfiguration
                .configureDatabaseNetwork(context.getEnvironment()));
        var context = application.run(arguments);
        if (application.getWebApplicationType() == WebApplicationType.NONE) context.close();
        return context;
    }
}
