package com.zoutrankil.batch;

import com.zoutrankil.batch.config.RuntimeConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

/** 独立的批处理入口；不扫描旧 QuestDB 命令行组件，避免启动无关任务。 */
@SpringBootConfiguration
@Import(RuntimeConfiguration.class)
public class BatchApplication {
    public static void main(String[] args) {
        application().run(args);
    }

    public static SpringApplication application() {
        var application=new SpringApplication(BatchApplication.class);
        application.setAdditionalProfiles("batch");
        application.addInitializers(context -> com.zoutrankil.runtime.ProjectConfiguration
                .configureDatabaseNetwork(context.getEnvironment()));
        application.setWebApplicationType(WebApplicationType.NONE);
        return application;
    }
}
