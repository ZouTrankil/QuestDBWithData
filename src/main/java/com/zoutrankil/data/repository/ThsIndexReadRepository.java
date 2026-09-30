package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.ThsIndexMapper;
import org.springframework.stereotype.Repository;
import java.util.HashSet;

/** Full typed reference row; shared read groups may independently request a projection. */
@Repository
public class ThsIndexReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final ThsIndexMapper mapper=new ThsIndexMapper();
    public ThsIndexReadRepository(QuestDbBoundedReader reader) { this.reader=reader; }
    @Override public DatasetDefinition definition() { return ThsIndexDataset.DEFINITION; }
    public DatasetReadPage<ThsIndex> findPage(DatasetReadQuery query) {
        var columns=definition().columns().stream().map(DatasetDefinition.Column::logicalName).toList();
        if(query.columns().size()!=columns.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(columns)))
            throw new IllegalArgumentException("Typed THS directory row requires all seven columns");
        return reader.read(definition(),query,null,mapper::fromValues);
    }
}
