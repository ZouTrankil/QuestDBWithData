package com.zoutrankil.data.config;

import com.zoutrankil.batch.MainStrategyDailyBatchRuntime;
import com.zoutrankil.batch.MainStrategyDailyWork;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix="app.sync.batch",name="enabled",havingValue="true",matchIfMissing=true)
@EnableConfigurationProperties(MainStrategyBatchProperties.class)
public class MainStrategyDailyBatchConfiguration {
    @Bean MainStrategyDailyBatchRuntime mainStrategyDailyBatchRuntime(ApplicationContext parent,
            MainStrategyBatchProperties properties,MainStrategyDailyWork work) {
        return new MainStrategyDailyBatchRuntime(parent,properties,work);
    }
}
