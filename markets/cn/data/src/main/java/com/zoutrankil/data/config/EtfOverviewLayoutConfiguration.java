package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.fund.EtfOverviewScope;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 普通来源可独立切版；MV 逐侧绑定实际配置，不假设两个来源同时迁移。 */
@Configuration(proxyBeanMethods = false)
public class EtfOverviewLayoutConfiguration {
    @Bean
    EtfOverviewScope etfOverviewScope(Environment env) {
        return resolve(env);
    }

    public static EtfOverviewScope resolve(Environment env) {
        int share = env.getProperty("app.sync.etf-share-schema-version", Integer.class, 1);
        int daily = env.getProperty("app.sync.etf-daily-schema-version", Integer.class, 1);
        int declared =
                env.getProperty(
                        "app.materialized-views.etf-overview.layout-version", Integer.class, 1);
        CnTradeDateLayout.fromVersion(declared);
        if (share == 1 && daily == 1 && declared == 1) return EtfOverviewScope.LEGACY;
        return new EtfOverviewScope(
                env.getProperty("app.sync.etf-share-table", "java_d016_etf_share_acceptance"),
                share,
                env.getProperty("app.sync.etf-daily-table", "java_d014_etf_daily_acceptance"),
                daily,
                env.getProperty(
                        "app.materialized-views.etf-overview.v2-output-table",
                        "java_etf_overview_v2_current"));
    }
}
