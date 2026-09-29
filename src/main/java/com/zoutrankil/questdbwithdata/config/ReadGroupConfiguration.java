package com.zoutrankil.questdbwithdata.config;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.QuestDbBoundedReader;
import com.zoutrankil.questdbwithdata.service.DatasetRegistry;
import com.zoutrankil.questdbwithdata.service.ReadGroupReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/** Only registered read definitions are exposed; projections retain strict Java field types. */
@Configuration
public class ReadGroupConfiguration {
    @Bean
    DatasetImplementation stockBasicLatestDataset() {
        return () -> StockBasicDataset.LATEST;
    }

    @Bean
    ReadGroupReader readGroupReader(DatasetRegistry datasets, QuestDbBoundedReader reader) {
        var bindings = new java.util.ArrayList<ReadGroupReader.Binding<?>>();
        for (var definition : datasets.definitions()) {
            if (definition.capabilities().contains(DatasetDefinition.Capability.READ))
                bindings.add(new ReadGroupReader.Binding<>(definition, DatasetValues.class,
                        java.util.function.Function.identity(), () -> null));
        }
        return new ReadGroupReader(datasets, reader, bindings);
    }

}
