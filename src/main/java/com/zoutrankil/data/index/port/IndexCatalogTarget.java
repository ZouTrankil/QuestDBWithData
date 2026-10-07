package com.zoutrankil.data.index.port;

import com.zoutrankil.data.domain.IndexCatalogEntry;
import com.zoutrankil.data.index.domain.IndexCatalogState.*;
import java.util.List;

/** Configured target and factories for per-operation storage collaborators. */
public interface IndexCatalogTarget extends IndexCatalogTables {
    String tableName();
    IndexCatalogTables publicationTables();
    Prepared prepare(Snapshot before,List<IndexCatalogEntry> source) throws Exception;
    IndexCatalogStagingPort newStaging();
}
