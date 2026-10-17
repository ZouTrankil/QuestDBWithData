package com.zoutrankil.strategy;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.bootstrap.QuestDataStartup;
import java.util.TimeZone;

/** 独立策略进程，包含受鉴权保护的控制 API。 */
public final class MainStrategyApplication {
    private MainStrategyApplication() {}

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        QuestDataStartup.run(application(!com.zoutrankil.data.bootstrap.StartupArguments.parse(args).hasCommand()), args);
    }

    public static org.springframework.boot.SpringApplication application() {
        return application(true);
    }

    private static org.springframework.boot.SpringApplication application(boolean persistent) {
        return QuestDataApplication.application(persistent, "data", "main-strategy");
    }
}
