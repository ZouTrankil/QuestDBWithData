package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.ExchangeCalendarReadRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Complete SSE/SZSE calendar coverage for bounded full-market daily source slices. */
public final class MoneyflowTradingDates {
    private static final List<String> EXCHANGES=List.of("SSE","SZSE");private final ExchangeCalendarReadRepository calendar;
    public MoneyflowTradingDates(ExchangeCalendarReadRepository calendar){this.calendar=Objects.requireNonNull(calendar);}
    public List<LocalDate> read(LocalDate from,LocalDate to){Objects.requireNonNull(from);Objects.requireNonNull(to);long days=ChronoUnit.DAYS.between(from,to)+1;if(from.isAfter(to)||days>MoneyflowSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("moneyflow calendar window must be 1..5 days");
        var cols=ExchangeCalendarDataset.DEFINITION.columns().stream().map(DatasetDefinition.Column::logicalName).toList();var query=new DatasetReadQuery(cols,Map.of(),"calendar_date",from,to.plusDays(1),1000,null);var rows=new ArrayList<ExchangeCalendar>();
        while(true){var page=calendar.findPage(query);rows.addAll(page.rows());if(!page.hasMore())break;query=query.after(page.nextCursor());}
        if(rows.size()!=Math.multiplyExact(Math.toIntExact(days),EXCHANGES.size()))throw new IllegalStateException("moneyflow SSE/SZSE calendar does not cover every frozen date");var seen=new HashMap<LocalDate,Set<String>>();var open=new TreeSet<LocalDate>();
        for(var row:rows){if(row.calendarDate().isBefore(from)||row.calendarDate().isAfter(to)||!EXCHANGES.contains(row.exchange())||!seen.computeIfAbsent(row.calendarDate(),k->new HashSet<>()).add(row.exchange()))throw new IllegalStateException("moneyflow calendar row is out-of-range or duplicate");if(row.open())open.add(row.calendarDate());}
        for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1))if(!seen.getOrDefault(date,Set.of()).containsAll(EXCHANGES))throw new IllegalStateException("Missing moneyflow exchange calendar row for "+date);return List.copyOf(open);}
}
