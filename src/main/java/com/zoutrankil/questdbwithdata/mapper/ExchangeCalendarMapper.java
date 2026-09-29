package com.zoutrankil.questdbwithdata.mapper;

import com.zoutrankil.questdbwithdata.client.dto.TushareTradeCalendarDto;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.ExchangeCalendarRow;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;

public final class ExchangeCalendarMapper {
    public ExchangeCalendar fromSource(TushareTradeCalendarDto source) {
        return new ExchangeCalendar(source.exchange(), date(source.calDate()), open(source.isOpen()),
                optionalDate(source.pretradeDate()));
    }
    public ExchangeCalendar fromStorage(ExchangeCalendarRow row) {
        return new ExchangeCalendar(row.exchange(), TemporalValues.CalendarTimestamp.fromStorage(row.calDate()).date(),
                open(row.isOpen()), optionalDate(row.pretradeDate()));
    }
    public ExchangeCalendarRow toStorage(ExchangeCalendar row) {
        return new ExchangeCalendarRow(row.exchange(),new TemporalValues.CalendarTimestamp(row.calendarDate()).storageCarrier(),
                row.open()?1:0,row.previousTradeDate()==null?null:TemporalValues.formatDate(
                row.previousTradeDate(),TemporalValues.DateFormat.BASIC));
    }
    public DatasetValues values(ExchangeCalendar row) {
        var values = new LinkedHashMap<String,Object>();
        values.put("exchange",row.exchange()); values.put("calendar_date",row.calendarDate());
        values.put("is_open",row.open()?1:0); values.put("previous_trade_date",row.previousTradeDate());
        return new DatasetValues(values);
    }
    public ExchangeCalendar fromValues(DatasetValues values) {
        return new ExchangeCalendar(values.get("exchange",String.class),values.get("calendar_date",LocalDate.class),
                open(values.get("is_open",Integer.class)),values.get("previous_trade_date",LocalDate.class));
    }
    private static boolean open(Integer value) {
        if (value==null || value!=0 && value!=1) throw new IllegalArgumentException("Explicit 0/1 is_open required");
        return value==1;
    }
    private static LocalDate date(String value) { return TemporalValues.businessDate(value,TemporalValues.DateFormat.BASIC); }
    private static LocalDate optionalDate(String value) { return value==null || value.isEmpty()?null:date(value); }
}
