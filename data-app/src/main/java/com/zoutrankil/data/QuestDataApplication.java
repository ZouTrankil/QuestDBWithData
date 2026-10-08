package com.zoutrankil.data;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.config.TushareProperties;
import com.zoutrankil.data.bootstrap.QuestDataStartup;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.util.Map;
import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties({TushareProperties.class, QuestDbProperties.class})
public class QuestDataApplication {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication application = new SpringApplication(QuestDataApplication.class);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        int exitCode = 0;
        try {
            if (!com.zoutrankil.data.bootstrap.StartupArguments.parse(args).hasCommand()) {
            // The persistent application owns formal daily data. Finite CLI commands retain
            // their acceptance-table defaults unless the caller supplies an explicit target.
            application.setDefaultProperties(Map.ofEntries(
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
            QuestDataStartup.run(application, args);
        } catch (RuntimeException failure) {
            exitCode = com.zoutrankil.data.cli.CliExitStatus.failureCode(failure);
            System.err.println("{\"status\":\"FAILED\",\"exitCode\":" + exitCode + "}");
        }
        if (exitCode != 0) System.exit(exitCode);
    }

}
