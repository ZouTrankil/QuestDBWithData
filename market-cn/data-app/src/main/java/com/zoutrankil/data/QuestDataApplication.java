package com.zoutrankil.data;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.config.TushareProperties;
import com.zoutrankil.data.bootstrap.QuestDataStartup;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.util.TimeZone;

@org.springframework.context.annotation.Configuration
@org.springframework.context.annotation.Import(com.zoutrankil.market.cn.bootstrap.CnDataConfiguration.class)
@EnableConfigurationProperties({TushareProperties.class, QuestDbProperties.class})
public class QuestDataApplication {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        int exitCode = 0;
        try {
            var application = application(!com.zoutrankil.data.bootstrap.StartupArguments.parse(args).hasCommand(), "data");
            QuestDataStartup.run(application, args);
        } catch (RuntimeException failure) {
            exitCode = com.zoutrankil.data.cli.CliExitStatus.failureCode(failure);
            System.err.println("{\"status\":\"FAILED\",\"exitCode\":" + exitCode + "}");
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    /** A 股数据入口保留旧类名，避免破坏日志识别和已有恢复记录。 */
    public static SpringApplication application(boolean persistent, String... profiles) {
        return com.zoutrankil.market.cn.bootstrap.CnDataConfiguration.application(QuestDataApplication.class,persistent,profiles);
    }
}
