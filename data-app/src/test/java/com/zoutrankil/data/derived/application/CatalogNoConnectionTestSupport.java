package com.zoutrankil.data.derived.application;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import static org.mockito.Mockito.*;
class CatalogNoConnectionTestSupport {
    @Configuration(proxyBeanMethods = false)
    @Profile("d101-catalog-test")
    static class NoConnectionConfiguration {
        @Bean DataSource d101NoConnectionDataSource() throws Exception {
            var source = mock(DataSource.class);
            doThrow(new AssertionError("Catalog startup must not request a database connection")).when(source).getConnection();
            doThrow(new AssertionError("Catalog startup must not request a database connection")).when(source).getConnection(anyString(), anyString());
            return source;
        }
    }
}
