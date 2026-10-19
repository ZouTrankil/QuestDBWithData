package com.zoutrankil.data.catalog.config;

import com.zoutrankil.data.catalog.application.DataCatalogService;
import com.zoutrankil.data.catalog.port.DataCatalogMetadataPort;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataCatalogConfigurationTest {
    @Test void commonConfigurationDoesNotRequireMarketsOrOpenTheDatabase() {
        var source = mock(DataSource.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> source);
            context.register(com.zoutrankil.data.config.DataApiConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(DataCatalogService.class));
            assertNotNull(context.getBean(DataCatalogMetadataPort.class));
            assertNotNull(context.getBean(com.zoutrankil.data.catalog.web.DataCatalogController.class));
            verifyNoInteractions(source);
        }
    }
    @Test void marketRegistryIsUsedWhenTheApplicationProvidesOne() {
        var source = mock(DataSource.class);
        var datasets = mock(com.zoutrankil.data.service.DatasetRegistry.class);
        when(datasets.definitions()).thenReturn(java.util.List.of());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> source);
            context.registerBean(com.zoutrankil.data.service.DatasetRegistry.class, () -> datasets);
            context.register(DataCatalogConfiguration.class);
            context.refresh();
            verify(datasets).definitions();
            verifyNoInteractions(source);
        }
    }
}
