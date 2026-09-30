package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.ExchangeCalendarReadRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Requires exact SSE/SZSE calendar coverage, then returns each open date once. */
public final class DcIndexTradingDates {
    private static final List<String> EXCHANGES=List.of("SSE","SZSE");
    private final ExchangeCalendarReadRepository calendar;
    public DcIndexTradingDates(ExchangeCalendarReadRepository calendar){this.calendar=Objects.requireNonNull(calendar);}
    public List<LocalDate> read(LocalDate from,LocalDate to){
        Objects.requireNonNull(from);Objects.requireNonNull(to);long days=ChronoUnit.DAYS.between(from,to)+1;
        if(from.isAfter(to)||days>366)throw new IllegalArgumentException("dc_index calendar window must be 1..366 days");
        var columns=ExchangeCalendarDataset.DEFINITION.columns().stream().map(DatasetDefinition.Column::logicalName).toList();
        var query=new DatasetReadQuery(columns,Map.of(),"calendar_date",from,to.plusDays(1),1000,null);var rows=new ArrayList<ExchangeCalendar>();
        while(true){var page=calendar.findPage(query);rows.addAll(page.rows());if(!page.hasMore())break;query=query.after(page.nextCursor());}
        if(rows.size()!=Math.multiplyExact(days,EXCHANGES.size()))throw new IllegalStateException("D001 calendar does not fully cover dc_index date window");
        var seen=new HashMap<LocalDate,Set<String>>();var open=new TreeSet<LocalDate>();
        for(var row:rows){if(row.calendarDate().isBefore(from)||row.calendarDate().isAfter(to)||!EXCHANGES.contains(row.exchange())
                    ||!seen.computeIfAbsent(row.calendarDate(),ignored->new HashSet<>()).add(row.exchange()))throw new IllegalStateException("Invalid/duplicate D001 calendar row in dc_index window");
            if(row.open())open.add(row.calendarDate());}
        for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1))if(!seen.getOrDefault(date,Set.of()).containsAll(EXCHANGES))throw new IllegalStateException("D001 calendar lacks exchange/date for "+date);
        return List.copyOf(open);
    }
}
