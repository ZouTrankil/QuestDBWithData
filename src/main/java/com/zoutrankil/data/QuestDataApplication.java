package com.zoutrankil.data;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.config.TushareProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.Properties;
import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties({TushareProperties.class, QuestDbProperties.class})
public class QuestDataApplication {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication application = new SpringApplication(QuestDataApplication.class);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        boolean webMode = Arrays.asList(args).contains("--web") || args.length == 0 && webEnabledByConfiguration();
        if (webMode) {
            application.setWebApplicationType(WebApplicationType.REACTIVE);
            application.run(args);
            return;
        }
        application.setWebApplicationType(WebApplicationType.NONE);
        int exitCode = 0;
        try (var context = application.run(args)) {
            // A finite CLI command owns its context; release HTTP pools and database clients on exit.
        } catch (RuntimeException failure) {
            exitCode = com.zoutrankil.data.cli.CliExitStatus.failureCode(failure);
            System.err.println("{\"status\":\"FAILED\",\"exitCode\":" + exitCode + "}");
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private static boolean webEnabledByConfiguration() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties configured = yaml.getObject();
        String value = System.getProperty("app.web.enabled",
                System.getenv().getOrDefault("APP_WEB_ENABLED",
                        configured == null ? "false" : configured.getProperty("app.web.enabled", "false")));
        return Boolean.parseBoolean(value);
    }
}
