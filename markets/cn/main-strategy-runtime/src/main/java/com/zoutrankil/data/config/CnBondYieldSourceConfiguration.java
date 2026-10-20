package com.zoutrankil.data.config;

import com.zoutrankil.batch.config.SourceRuntimeConfiguration;
import com.zoutrankil.batch.source.chinabond.ChinabondYieldSource;
import com.zoutrankil.data.client.SharedRequestBudget;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** 仅导入现有原生中债 WebFlux 来源，不加载隔离的 Batch 作业或写入器。 */
@Configuration(proxyBeanMethods = false)
public class CnBondYieldSourceConfiguration {
    /** 通过包装对象注入，避免 TushareClient 出现多个未限定的 SharedRequestBudget 候选。 */
    public static final class PublicBudget implements AutoCloseable {
        private final SharedRequestBudget budget;

        PublicBudget(TushareProperties properties) {
            budget = SourceRuntimeConfiguration.createChinabondRequestBudget(properties);
        }

        public void close() throws Exception {
            budget.close();
        }
    }

    @Bean(destroyMethod = "close")
    PublicBudget formalChinabondPublicBudget(TushareProperties properties) {
        return new PublicBudget(properties);
    }

    @Bean
    @Lazy
    ChinabondYieldSource formalChinabondYieldSource(
            PublicBudget budget, TushareProperties properties) {
        return SourceRuntimeConfiguration.createChinabondYieldSource(budget.budget, properties);
    }
}
