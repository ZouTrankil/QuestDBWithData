package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.repository.BacktestDailyMaterializationReadGuard;
import org.springframework.stereotype.Service;

/** Application access to bounded raw dataset rows. */
@Service
public final class DatasetReadService {
    public static final class NotReadyException extends IllegalStateException {
        public NotReadyException(String message,Throwable cause){super(message,cause);}
    }
    private final QuestDbBoundedReader reader;

    public DatasetReadService(QuestDbBoundedReader reader) {
        this.reader = reader;
    }

    public DatasetReadPage<DatasetValues> read(DatasetDefinition definition, DatasetReadQuery query) {
        try { return reader.read(definition, query, null, values -> values); }
        catch(BacktestDailyMaterializationReadGuard.NotReadyException notReady) {
            throw new NotReadyException(notReady.getMessage(),notReady);
        }
    }
}
