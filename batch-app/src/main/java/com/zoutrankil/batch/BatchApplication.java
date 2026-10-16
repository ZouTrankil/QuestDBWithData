package com.zoutrankil.batch;

import com.zoutrankil.batch.config.RuntimeConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

/** Separate entry point in the existing project; does not scan legacy QuestDB CLI beans. */
@SpringBootConfiguration
@Import(RuntimeConfiguration.class)
public class BatchApplication {
    public static void main(String[] args) {
        application().run(args);
    }

    public static SpringApplication application() {
        var application=new SpringApplication(BatchApplication.class);
        application.setAdditionalProfiles("batch");
        application.setWebApplicationType(WebApplicationType.NONE);
        return application;
    }
}
