package com.zoutrankil.data.calendar.port;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
public interface ExchangeCalendarTarget {
    String tableName();
    String targetId() throws Exception;
    VerifiedWriteSession<ExchangeCalendar, ExchangeCalendar.Key> newWriter();
}
