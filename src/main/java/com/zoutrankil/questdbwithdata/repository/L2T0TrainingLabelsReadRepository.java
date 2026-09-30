package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.DatasetReadCursor;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabels;
import com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelsDataset;
import com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelsKey;
import com.zoutrankil.questdbwithdata.mapper.L2T0TrainingLabelsMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** Bounded typed D089 reads with all 62 explicit columns and the symbol-minute keyset cursor. */
@Repository
public class L2T0TrainingLabelsReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final String table;
    private final L2T0TrainingLabelsMapper mapper = new L2T0TrainingLabelsMapper();

    public L2T0TrainingLabelsReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.l2-t0-training-labels.target-table:l2_t0_training_labels}") String table) {
        this.reader = reader; this.table = table;
    }

    @Override public DatasetDefinition definition() { return L2T0TrainingLabelsDataset.definition(table); }
    public DatasetReadPage<L2T0TrainingLabels> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }
    public DatasetReadPage<L2T0TrainingLabels> findForKey(L2T0TrainingLabelsKey key, int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2T0TrainingLabelsMapper.columns(),
                Map.of("symbol", key.symbol(), "minute", key.minute()),
                null, null, null, pageSize, cursor));
    }
    public DatasetReadPage<L2T0TrainingLabels> findForSymbolAndDate(
            String symbol, LocalDate date, int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2T0TrainingLabelsMapper.columns(),
                Map.of("symbol", symbol, "trade_date", date),
                null, null, null, pageSize, cursor));
    }
    public DatasetReadPage<L2T0TrainingLabels> findRange(Instant fromInclusive, Instant toExclusive,
            int pageSize, DatasetReadCursor cursor) {
        return findPage(new DatasetReadQuery(L2T0TrainingLabelsMapper.columns(), Map.of(),
                "minute", fromInclusive, toExclusive, pageSize, cursor));
    }
}
