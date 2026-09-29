package com.zoutrankil.questdbwithdata.config;

import com.zoutrankil.questdbwithdata.service.DatasetRegistry;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import org.springframework.context.annotation.*;
import java.util.*;
import com.zoutrankil.questdbwithdata.domain.SyncJobOwner;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.service.SyncJobRegistry;

@Configuration
public class DatasetConfiguration {
    @Bean
    DatasetRegistry datasetRegistry(List<DatasetImplementation> implementations) {
        return new DatasetRegistry(implementations);
    }
    @Bean
    SyncJobRegistry syncJobRegistry(
            DatasetRegistry datasets, List<SyncJobOwner> owners) {
        var modes = new HashMap<String, Set<SyncJobDefinition.Mode>>();
        var jobs = new ArrayList<SyncJobDefinition>();
        for (var owner : owners) {
            if (modes.putIfAbsent(owner.datasetId(), owner.supportedSyncModes()) != null)
                throw new IllegalArgumentException("Duplicate sync owner for dataset");
            for (var job : owner.syncJobDefinitions()) {
                if (!job.datasetId().equals(owner.datasetId())) throw new IllegalArgumentException("Job owner dataset mismatch");
                jobs.add(job);
            }
        }
        return new SyncJobRegistry(jobs, datasets, modes,
                new SyncJobRegistry.Policies(
                        Set.of("tushare.shared","file.bounded"), Set.of("stock_basic.snapshot","exchange_calendar.year","stock_detail_info.identity","index_catalog.file"),
                        Set.of("questdb.full_key_values")));
    }
}
