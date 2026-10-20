package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.stock.concept.ThsMember;
import com.zoutrankil.data.domain.stock.concept.ThsMemberDataset;
import com.zoutrankil.data.index.mapper.ThsMemberMapper;
import com.zoutrankil.data.repository.QuestDbBoundedReader;

import org.springframework.stereotype.Repository;

import java.util.HashSet;

/** Bounded typed keyset read, requiring the complete current-membership row. */
@Repository
public class ThsMemberReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final ThsMemberMapper mapper = new ThsMemberMapper();

    public ThsMemberReadRepository(QuestDbBoundedReader reader) {
        this.reader = reader;
    }

    @Override
    public DatasetDefinition definition() {
        return ThsMemberDataset.DEFINITION;
    }

    public DatasetReadPage<ThsMember> findPage(DatasetReadQuery query) {
        var columns =
                definition().columns().stream().map(DatasetDefinition.Column::logicalName).toList();
        if (query.columns().size() != columns.size()
                || !new HashSet<>(query.columns()).equals(new HashSet<>(columns)))
            throw new IllegalArgumentException(
                    "Typed THS membership row requires all eight columns");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
