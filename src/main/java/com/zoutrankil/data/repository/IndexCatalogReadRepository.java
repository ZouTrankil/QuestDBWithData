package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.IndexCatalogMapper;
import java.util.HashSet;
import org.springframework.stereotype.Repository;

/** Bounded typed catalog read; registered with the shared read group. */
@Repository
public class IndexCatalogReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final IndexCatalogMapper mapper = new IndexCatalogMapper();
    public IndexCatalogReadRepository(QuestDbBoundedReader reader) { this.reader = reader; }
    @Override public DatasetDefinition definition() { return IndexCatalogDataset.DEFINITION; }
    public DatasetReadPage<IndexCatalogEntry> findPage(DatasetReadQuery query) {
        var names=definition().columns().stream().map(DatasetDefinition.Column::logicalName).toList();
        if(query.columns().size()!=names.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(names)))
            throw new IllegalArgumentException("Typed catalog row requires all physical columns");
        return reader.read(definition(),query,null,mapper::fromValues);
    }
}
