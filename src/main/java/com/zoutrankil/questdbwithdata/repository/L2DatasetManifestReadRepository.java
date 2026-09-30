package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.DatasetReadCursor;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.L2DatasetManifest;
import com.zoutrankil.questdbwithdata.domain.L2DatasetManifestDataset;
import com.zoutrankil.questdbwithdata.mapper.L2DatasetManifestMapper;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** D085 typed reads use explicit columns, the complete business key and stable keyset paging. */
@Repository
public class L2DatasetManifestReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final String table;
    private final L2DatasetManifestMapper mapper = new L2DatasetManifestMapper();

    public L2DatasetManifestReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.l2-manifest.target-table:l2_dataset_manifest}") String table) {
        this.reader = reader;
        this.table = table;
    }

    @Override public DatasetDefinition definition() { return L2DatasetManifestDataset.definition(table); }

    public DatasetReadPage<L2DatasetManifest> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<L2DatasetManifest> findForDate(LocalDate date, int pageSize,
                                                            DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2DatasetManifestMapper.columns(),
                Map.of("trade_date", date), null, null, null, pageSize, cursor));
    }

    /** Inclusive start, exclusive end; the string date contract sorts chronologically as YYYYMMDD. */
    public DatasetReadPage<L2DatasetManifest> findRange(LocalDate fromInclusive, LocalDate toExclusive,
                                                          int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2DatasetManifestMapper.columns(), Map.of(),
                "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }

    public DatasetReadPage<L2DatasetManifest> findForSymbolAndDate(String symbol, LocalDate date,
                                                                     int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2DatasetManifestMapper.columns(),
                Map.of("trade_date", date, "symbol", symbol), null, null, null, pageSize, cursor));
    }
}
