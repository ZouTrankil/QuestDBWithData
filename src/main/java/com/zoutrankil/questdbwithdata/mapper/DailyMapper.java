package com.zoutrankil.questdbwithdata.mapper;

import com.zoutrankil.questdbwithdata.client.dto.TushareDailyDto;
import com.zoutrankil.questdbwithdata.domain.DailyMarketBar;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.table.DailyRow;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.time.LocalDate;
import java.util.LinkedHashMap;

/** Explicit field mapping across Tushare DTO, business model and QuestDB projection. */
public final class DailyMapper {
    public DailyMarketBar fromSource(TushareDailyDto row) {
        return new DailyMarketBar(row.tsCode(), date(row.tradeDate()), row.open(), row.high(), row.low(),
                row.close(), row.preClose(), row.change(), row.pctChg(), row.vol(), row.amount(),
                row.ahVol(), row.ahAmount());
    }

    public DailyMarketBar fromStorage(DailyRow row) {
        return new DailyMarketBar(row.tsCode(), TemporalValues.CalendarTimestamp.fromStorage(row.tradeDate()).date(),
                row.open(), row.high(), row.low(), row.close(), row.preClose(), row.change(), row.pctChg(),
                row.vol(), row.amount(), row.ahVol(), row.ahAmount());
    }

    public DailyRow toStorage(DailyMarketBar row) {
        return new DailyRow(row.tsCode(), new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier(),
                row.open(), row.high(), row.low(), row.close(), row.preClose(), row.change(), row.pctChg(),
                row.vol(), row.amount(), row.ahVol(), row.ahAmount());
    }

    public DatasetValues values(DailyMarketBar row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("ts_code", row.tsCode());
        values.put("trade_date", row.tradeDate());
        values.put("open", row.open());
        values.put("high", row.high());
        values.put("low", row.low());
        values.put("close", row.close());
        values.put("pre_close", row.preClose());
        values.put("change", row.change());
        values.put("pct_chg", row.pctChg());
        values.put("vol", row.vol());
        values.put("amount", row.amount());
        values.put("ah_vol", row.ahVol());
        values.put("ah_amount", row.ahAmount());
        return new DatasetValues(values);
    }

    public DailyMarketBar fromValues(DatasetValues values) {
        return new DailyMarketBar(values.get("ts_code", String.class), values.get("trade_date", LocalDate.class),
                values.get("open", Double.class), values.get("high", Double.class), values.get("low", Double.class),
                values.get("close", Double.class), values.get("pre_close", Double.class),
                values.get("change", Double.class), values.get("pct_chg", Double.class),
                values.get("vol", Double.class), values.get("amount", Double.class),
                values.get("ah_vol", Double.class), values.get("ah_amount", Double.class));
    }

    private static LocalDate date(String value) {
        return TemporalValues.businessDate(value, TemporalValues.DateFormat.BASIC);
    }
}
