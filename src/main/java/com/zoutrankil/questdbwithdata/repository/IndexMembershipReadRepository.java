package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.IndexMembershipMapper;
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
