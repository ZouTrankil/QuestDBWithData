package com.zoutrankil.data.config;

import com.zoutrankil.batch.ChinabondYieldSource;
import com.zoutrankil.batch.SourceRuntimeConfiguration;
import com.zoutrankil.data.client.SharedRequestBudget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** Imports only the existing native ChinaBond WebFlux source, without isolated batch jobs or writers. */
@Configuration(proxyBeanMethods=false)
public class CnBondYieldSourceConfiguration {
    /** A holder avoids adding another unqualified SharedRequestBudget candidate to TushareClient. */
    public static final class PublicBudget implements AutoCloseable {
        private final SharedRequestBudget budget;
        PublicBudget(TushareProperties properties){budget=SourceRuntimeConfiguration.createChinabondRequestBudget(properties);}
        public void close()throws Exception{budget.close();}
    }
    @Bean(destroyMethod="close")
    PublicBudget formalChinabondPublicBudget(TushareProperties properties){return new PublicBudget(properties);}

    @Bean @Lazy
    ChinabondYieldSource formalChinabondYieldSource(PublicBudget budget,
                                                  TushareProperties properties) {
        return SourceRuntimeConfiguration.createChinabondYieldSource(budget.budget,properties);
    }
}
