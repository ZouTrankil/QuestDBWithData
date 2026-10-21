package com.zoutrankil.market.cn.bootstrap;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.config.TushareProperties;
import com.zoutrankil.market.cn.chips.config.CyqPerfConfiguration;
import com.zoutrankil.market.cn.futures.config.FuturesDayConfiguration;
import com.zoutrankil.market.cn.macro.config.MacroObservationConfiguration;
import com.zoutrankil.market.cn.rates.config.InterestObservationConfiguration;

import org.springframework.boot.Banner.Mode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import java.util.Map;

/** A 股数据组件装配；入口可复用它，但库本身不依赖任何启动应用。 */
@SpringBootApplication(scanBasePackages = "com.zoutrankil.data")
@Import({
    MacroObservationConfiguration.class,
    InterestObservationConfiguration.class,
    CyqPerfConfiguration.class,
    FuturesDayConfiguration.class
})
@EnableConfigurationProperties({TushareProperties.class, QuestDbProperties.class})
public class CnDataConfiguration {
    /** 共享数据执行组件；各常驻进程入口自行选择对应的配置环境。 */
    public static SpringApplication application(
            Class<?> primarySource, boolean persistent, String... profiles) {
        var application = new SpringApplication(primarySource);
        application.setAdditionalProfiles(profiles);
        application.setBannerMode(Mode.OFF);
        if (persistent) {
            application.setDefaultProperties(
                    Map.ofEntries(
                            Map.entry("app.sync.stk-limit-table", "stk_limit"),
                            Map.entry("app.sync.stk-suspend-table", "stk_suspend"),
                            Map.entry("app.sync.stk-st-daily-table", "stk_st_daily"),
                            Map.entry("app.sync.moneyflow-table", "moneyflow"),
                            Map.entry("app.sync.moneyflow-hsgt-table", "moneyflow_hsgt"),
                            Map.entry("app.sync.margin-detail-table", "margin_detail"),
                            Map.entry("app.sync.etf-daily-table", "etf_daily"),
                            Map.entry("app.sync.etf-adj-table", "etf_adj"),
                            Map.entry("app.sync.etf-factor-table", "etf_factor"),
                            Map.entry("app.sync.etf-portfolio-table", "etf_portfolio")));
        }
        return application;
    }
}
