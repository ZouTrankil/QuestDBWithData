package com.zoutrankil.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

/** Separate entry point in the existing project; does not scan legacy QuestDB CLI beans. */
@SpringBootConfiguration
@Import(RuntimeConfiguration.class)
public class BatchApplication {
    public static void main(String[] args) {
        System.setProperty("spring.config.name", "batch-runtime");
        var application=new SpringApplication(BatchApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
