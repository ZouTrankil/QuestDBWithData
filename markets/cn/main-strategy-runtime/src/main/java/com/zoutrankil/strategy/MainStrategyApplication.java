package com.zoutrankil.strategy;

import com.zoutrankil.data.bootstrap.QuestDataStartup;
import com.zoutrankil.data.bootstrap.StartupArguments;
import com.zoutrankil.market.cn.bootstrap.CnDataConfiguration;

import org.springframework.boot.SpringApplication;

import java.util.TimeZone;

/** 独立策略进程，包含受鉴权保护的控制 API。 */
public final class MainStrategyApplication {
    private MainStrategyApplication() {}

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        QuestDataStartup.run(application(!StartupArguments.parse(args).hasCommand()), args);
    }

    public static SpringApplication application() {
        return application(true);
    }

    private static SpringApplication application(boolean persistent) {
        return CnDataConfiguration.application(
                CnDataConfiguration.class, persistent, "data", "main-strategy");
    }
}
