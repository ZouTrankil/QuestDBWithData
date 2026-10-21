package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 读取明确声明的 MV，不因同步作业改配置就自动切换原正式消费者。 */
@Configuration(proxyBeanMethods = false)
public class BacktestDailyLayoutConfiguration {
    private static final String PREFIX = "app.materialized-views.backtest.";

    @Bean
    BacktestDailyScope backtestDailyScope(Environment env) {
        return resolve(env);
    }

    public static BacktestDailyScope resolve(Environment env) {
        int version = env.getProperty(PREFIX + "layout-version", Integer.class, 1);
        CnTradeDateLayout.fromVersion(version);
        String suspend = env.getProperty(PREFIX + "suspend-table", "stk_suspend");
        String st = env.getProperty(PREFIX + "st-table", "stk_st_daily");
        int s = env.getProperty(PREFIX + "suspend-layout-version", Integer.class, 1);
        int t = env.getProperty(PREFIX + "st-layout-version", Integer.class, 1);
        if (version == 1) {
            var declared = new BacktestDailyScope(suspend, s, st, t, "mv_backtest_daily");
            if (!declared.legacy()) throw new IllegalArgumentException("原回测 MV 不能重绑来源");
            return BacktestDailyScope.LEGACY;
        }
        return new BacktestDailyScope(
                suspend,
                s,
                st,
                t,
                env.getProperty(PREFIX + "v2-output-table", "java_backtest_v2_current"));
    }
}
