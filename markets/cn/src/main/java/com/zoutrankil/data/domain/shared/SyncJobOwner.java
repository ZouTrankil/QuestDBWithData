package com.zoutrankil.data.domain.shared;

import com.zoutrankil.data.domain.SyncJobDefinition;

import java.util.List;
import java.util.Set;

/** Implemented by the actual source service, not by a detached metadata catalog. */
public interface SyncJobOwner {
    String datasetId();

    Set<SyncJobDefinition.Mode> supportedSyncModes();

    List<SyncJobDefinition> syncJobDefinitions();
}
