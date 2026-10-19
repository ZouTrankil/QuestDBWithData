package com.zoutrankil.data.calendar.port;

import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.domain.*;
public interface ExchangeCalendarReadPort {DatasetReadPage<ExchangeCalendar> findPage(DatasetReadQuery query);}
