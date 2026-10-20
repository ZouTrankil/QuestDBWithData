package com.zoutrankil.data.derived.application;

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

class CatalogNoConnectionTestSupport {
    @Configuration(proxyBeanMethods = false)
    @Profile("d101-catalog-test")
    static class NoConnectionConfiguration {
        @Bean
        DataSource d101NoConnectionDataSource() throws Exception {
            var source = mock(DataSource.class);
            doThrow(new AssertionError("Catalog startup must not request a database connection"))
                    .when(source)
                    .getConnection();
            doThrow(new AssertionError("Catalog startup must not request a database connection"))
                    .when(source)
                    .getConnection(anyString(), anyString());
            return source;
        }
    }
}
