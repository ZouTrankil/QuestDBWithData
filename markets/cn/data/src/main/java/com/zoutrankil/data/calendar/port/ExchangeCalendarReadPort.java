package com.zoutrankil.data.calendar.port;

import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;

public interface ExchangeCalendarReadPort {
    DatasetReadPage<ExchangeCalendar> findPage(DatasetReadQuery query);
}
