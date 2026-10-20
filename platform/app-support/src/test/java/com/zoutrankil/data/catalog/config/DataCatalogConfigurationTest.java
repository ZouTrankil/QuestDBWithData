package com.zoutrankil.data.catalog.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.catalog.application.DataCatalogService;
import com.zoutrankil.data.catalog.port.DataCatalogMetadataPort;
import com.zoutrankil.data.catalog.web.DataCatalogController;
import com.zoutrankil.data.config.DataApiConfiguration;
import com.zoutrankil.data.service.DatasetRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import javax.sql.DataSource;

class DataCatalogConfigurationTest {
    @Test
    void commonConfigurationDoesNotRequireMarketsOrOpenTheDatabase() {
        var source = mock(DataSource.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> source);
            context.register(DataApiConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(DataCatalogService.class));
            assertNotNull(context.getBean(DataCatalogMetadataPort.class));
            assertNotNull(context.getBean(DataCatalogController.class));
            verifyNoInteractions(source);
        }
    }

    @Test
    void marketRegistryIsUsedWhenTheApplicationProvidesOne() {
        var source = mock(DataSource.class);
        var datasets = mock(DatasetRegistry.class);
        when(datasets.definitions()).thenReturn(List.of());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> source);
            context.registerBean(DatasetRegistry.class, () -> datasets);
            context.register(DataCatalogConfiguration.class);
            context.refresh();
            verify(datasets).definitions();
            verifyNoInteractions(source);
        }
    }
}
