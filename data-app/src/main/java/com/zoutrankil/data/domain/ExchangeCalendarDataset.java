package com.zoutrankil.data.domain;

import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D001 contract; catalog admission occurs with the completed read/write/sync owner. */
public final class ExchangeCalendarDataset {
    private ExchangeCalendarDataset() {}
    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "exchange_calendar",1,"tushare.trade_cal","exchange_calendar_owner","exchange_calendar",ObjectKind.TABLE,
            List.of(new Column("exchange","exchange","exchange",StorageType.SYMBOL,false,"Exchange calendar identity",null),
                    new Column("cal_date","calendar_date","cal_date",StorageType.TIMESTAMP,false,
                            "Calendar day, stored as UTC midnight carrier; not an event instant",date("calendar day")),
                    new Column("is_open","is_open","is_open",StorageType.INT,false,"Explicit 0 closed / 1 open flag",null),
                    new Column("pretrade_date","previous_trade_date","pretrade_date",StorageType.STRING,true,
                            "Previous exchange session date, exact YYYYMMDD or null",date("previous exchange trading day"))),
            List.of("exchange","calendar_date"),List.of("exchange","cal_date"),"cal_date",Partition.YEAR,true,
            Set.of(Capability.READ,Capability.WRITE),List.of(),
            "Retain audited YEAR/WAL table and exchange+cal_date upsert identity; date names map explicitly without DDL renaming");
    private static TemporalContract date(String meaning) {
        return new TemporalContract(TemporalKind.BUSINESS_DATE,"BASIC","calendar","DAY",meaning);
    }
}
