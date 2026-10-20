package com.zoutrankil.data.calendar.storage;

import com.zoutrankil.data.calendar.mapper.ExchangeCalendarMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendarDataset;
import com.zoutrankil.data.repository.QuestDbBoundedReader;

import org.springframework.stereotype.Repository;

/** D001's bounded typed PGWire read owner and catalog implementation. */
@Repository
public class ExchangeCalendarReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final ExchangeCalendarMapper mapper = new ExchangeCalendarMapper();

    public ExchangeCalendarReadRepository(QuestDbBoundedReader reader) {
        this.reader = reader;
    }

    @Override
    public DatasetDefinition definition() {
        return ExchangeCalendarDataset.DEFINITION;
    }

    public DatasetReadPage<ExchangeCalendar> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
