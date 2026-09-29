package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.StockDetailInfo;
import com.zoutrankil.questdbwithdata.domain.StockDetailInfoDataset;
import com.zoutrankil.questdbwithdata.mapper.StockDetailInfoMapper;
import java.util.HashSet;
import org.springframework.stereotype.Repository;

/** Typed full-row reference read; the registered read group also supports partial projections. */
@Repository
public class StockDetailInfoReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final StockDetailInfoMapper mapper = new StockDetailInfoMapper();

    public StockDetailInfoReadRepository(QuestDbBoundedReader reader) { this.reader = reader; }

    @Override public DatasetDefinition definition() { return StockDetailInfoDataset.DEFINITION; }

    public DatasetReadPage<StockDetailInfo> findPage(DatasetReadQuery query) {
        var declared = definition().columns().stream().map(DatasetDefinition.Column::logicalName).toList();
        if (query.columns().size() != declared.size()
                || !new HashSet<>(query.columns()).equals(new HashSet<>(declared)))
            throw new IllegalArgumentException("Typed stock detail row requires all declared columns");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
