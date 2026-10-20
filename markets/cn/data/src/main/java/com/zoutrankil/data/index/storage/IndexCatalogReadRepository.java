package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.index.IndexCatalogDataset;
import com.zoutrankil.data.domain.index.IndexCatalogEntry;
import com.zoutrankil.data.index.mapper.IndexCatalogMapper;
import com.zoutrankil.data.repository.QuestDbBoundedReader;

import org.springframework.stereotype.Repository;

import java.util.HashSet;

/** Bounded typed catalog read; registered with the shared read group. */
@Repository
public class IndexCatalogReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final IndexCatalogMapper mapper = new IndexCatalogMapper();

    public IndexCatalogReadRepository(QuestDbBoundedReader reader) {
        this.reader = reader;
    }

    @Override
    public DatasetDefinition definition() {
        return IndexCatalogDataset.DEFINITION;
    }

    public DatasetReadPage<IndexCatalogEntry> findPage(DatasetReadQuery query) {
        var names =
                definition().columns().stream().map(DatasetDefinition.Column::logicalName).toList();
        if (query.columns().size() != names.size()
                || !new HashSet<>(query.columns()).equals(new HashSet<>(names)))
            throw new IllegalArgumentException("Typed catalog row requires all physical columns");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
