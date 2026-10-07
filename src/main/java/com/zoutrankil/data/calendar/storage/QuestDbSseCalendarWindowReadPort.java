package com.zoutrankil.data.calendar.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.temporal.TemporalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Bounded streaming SSE calendar read used to validate formal northbound source coverage. */
public final class QuestDbSseCalendarWindowReadPort implements com.zoutrankil.data.calendar.port.SseCalendarWindowReadPort {
    private final JdbcTemplate jdbc;

    public QuestDbSseCalendarWindowReadPort(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }

    public void readSseDates(LocalDate from,LocalDate to,BiConsumer<LocalDate,Integer> consumer) {
        long lower=new TemporalValues.CalendarTimestamp(from).storageEpoch(TemporalValues.EpochUnit.MICROS);
        long upper=new TemporalValues.CalendarTimestamp(to.plusDays(1)).storageEpoch(TemporalValues.EpochUnit.MICROS);
        jdbc.query("SELECT cast(cal_date AS long) AS cal_micros,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date LIMIT 33",
                (RowCallbackHandler)rs->{
                    Object raw=rs.getObject("cal_micros"),flag=rs.getObject("is_open");
                    if(!(raw instanceof Number n)||!(flag instanceof Number open)||open.intValue()!=0&&open.intValue()!=1)
                        throw new IllegalStateException("Formal northbound coverage requires typed SSE calendar rows");
                    LocalDate date=TemporalValues.CalendarTimestamp.fromStorageEpoch(n.longValue(),TemporalValues.EpochUnit.MICROS).date();
                    consumer.accept(date,open.intValue());
                },lower,upper);
    }
}
