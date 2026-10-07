package com.zoutrankil.data.calendar.storage;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.*;
import java.util.Objects;
public final class QuestDbExchangeCalendarReadPort implements ExchangeCalendarReadPort {private final ExchangeCalendarReadRepository repository;public QuestDbExchangeCalendarReadPort(ExchangeCalendarReadRepository repository){this.repository=Objects.requireNonNull(repository);}public DatasetReadPage<ExchangeCalendar> findPage(DatasetReadQuery query){return repository.findPage(query);}}
