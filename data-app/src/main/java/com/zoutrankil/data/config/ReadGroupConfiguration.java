package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.StockBasicDataset;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.ReadGroupReader;
import java.util.ArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit read representations preserve their Java types and the active physical definitions. */
@Configuration(proxyBeanMethods = false)
public class ReadGroupConfiguration {
    @Bean
    DatasetImplementation stockBasicLatestDataset() {
        return () -> StockBasicDataset.LATEST;
    }

    @Bean
    ReadBindingCatalog readBindingCatalog(ObjectProvider<ReadBindingCatalog.Registration<?>> extensions) {
        var registrations = new ArrayList<>(DefaultReadBindings.registrations());
        extensions.orderedStream().forEach(registrations::add);
        return new ReadBindingCatalog(registrations);
    }

    @Bean
    ReadGroupReader readGroupReader(DatasetRegistry datasets, QuestDbBoundedReader reader, ReadBindingCatalog catalog) {
        return new ReadGroupReader(datasets, reader, catalog.bind(datasets));
    }

    /** Direct callers can still assemble a partial registry with the standard representations. */
    ReadGroupReader readGroupReader(DatasetRegistry datasets, QuestDbBoundedReader reader) {
        return readGroupReader(datasets, reader, new ReadBindingCatalog(DefaultReadBindings.registrations()));
    }
}
