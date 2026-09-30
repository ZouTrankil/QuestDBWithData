package com.zoutrankil.data;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.config.TushareProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties({TushareProperties.class, QuestDbProperties.class})
public class QuestDataApplication {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        int exitCode = 0;
        try (var context = application.run(args)) {
            // A finite CLI command owns its context; release HTTP pools and database clients on exit.
        } catch (RuntimeException failure) {
            exitCode = com.zoutrankil.data.cli.CliExitStatus.failureCode(failure);
            System.err.println("{\"status\":\"FAILED\",\"exitCode\":" + exitCode + "}");
        }
        if (exitCode != 0) System.exit(exitCode);
    }
}
