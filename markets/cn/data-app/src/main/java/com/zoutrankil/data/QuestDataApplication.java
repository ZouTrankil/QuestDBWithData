package com.zoutrankil.data;

import com.zoutrankil.data.bootstrap.QuestDataStartup;
import com.zoutrankil.data.bootstrap.StartupArguments;
import com.zoutrankil.data.cli.CliExitStatus;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.config.TushareProperties;
import com.zoutrankil.market.cn.app.CnBatchConfiguration;
import com.zoutrankil.market.cn.bootstrap.CnDataConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.TimeZone;

@Configuration
@Import({CnDataConfiguration.class, CnBatchConfiguration.class})
@EnableConfigurationProperties({TushareProperties.class, QuestDbProperties.class})
public class QuestDataApplication {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        int exitCode = 0;
        try {
            var application = application(!StartupArguments.parse(args).hasCommand(), "data");
            QuestDataStartup.run(application, args);
        } catch (RuntimeException failure) {
            exitCode = CliExitStatus.failureCode(failure);
            System.err.println("{\"status\":\"FAILED\",\"exitCode\":" + exitCode + "}");
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    /** A 股数据入口保留旧类名，避免破坏日志识别和已有恢复记录。 */
    public static SpringApplication application(boolean persistent, String... profiles) {
        return CnDataConfiguration.application(QuestDataApplication.class, persistent, profiles);
    }
}
