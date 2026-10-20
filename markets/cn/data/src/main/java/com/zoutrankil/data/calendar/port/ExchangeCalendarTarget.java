package com.zoutrankil.data.calendar.port;

import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

public interface ExchangeCalendarTarget {
    String tableName();

    String targetId() throws Exception;

    VerifiedWriteSession<ExchangeCalendar, ExchangeCalendar.Key> newWriter();
}
