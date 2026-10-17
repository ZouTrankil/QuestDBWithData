package com.zoutrankil.data.calendar.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.calendar.mapper.ExchangeCalendarMapper;
import org.springframework.stereotype.Repository;

/** D001's bounded typed PGWire read owner and catalog implementation. */
@Repository
public class ExchangeCalendarReadRepository implements DatasetImplementation {
    private final QuestDbBoundedReader reader;
    private final ExchangeCalendarMapper mapper = new ExchangeCalendarMapper();

    public ExchangeCalendarReadRepository(QuestDbBoundedReader reader) {
        this.reader = reader;
    }

    @Override public DatasetDefinition definition() { return ExchangeCalendarDataset.DEFINITION; }

    public DatasetReadPage<ExchangeCalendar> findPage(DatasetReadQuery query) {
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
