package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.mapper.IndexMembershipMapper;
import org.springframework.stereotype.Repository;
import java.util.HashSet;

@Repository
public class IndexMembershipReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final IndexMembershipMapper mapper=new IndexMembershipMapper();
    public IndexMembershipReadRepository(QuestDbBoundedReader reader) { this.reader=reader; }
    @Override public DatasetDefinition definition() { return IndexMembershipDataset.DEFINITION; }
    public DatasetReadPage<IndexMembership> findPage(DatasetReadQuery query) {
        var columns=definition().columns().stream().map(DatasetDefinition.Column::logicalName).toList();
        if(query.columns().size()!=columns.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(columns)))
            throw new IllegalArgumentException("Typed membership requires all fourteen columns");
        return reader.read(definition(),query,null,mapper::fromValues);
    }
}
