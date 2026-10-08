package com.zoutrankil.data.stock.mapper;


import com.zoutrankil.data.client.dto.TushareDailyBasicDto;
import com.zoutrankil.data.domain.DailyBasic;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;

/** Explicit field-by-field Tushare, domain, and QuestDB value mapping for D008. */
public final class DailyBasicMapper {
    public DailyBasic fromSource(TushareDailyBasicDto source) throws IOException {
        try {
            return new DailyBasic(source.tsCode(),
                    TemporalValues.businessDate(source.tradeDate(), TemporalValues.DateFormat.BASIC),
                    number(source.close(), "close"), number(source.turnoverRate(), "turnover_rate"),
                    number(source.turnoverRateF(), "turnover_rate_f"), number(source.volumeRatio(), "volume_ratio"),
                    number(source.pe(), "pe"), number(source.peTtm(), "pe_ttm"), number(source.pb(), "pb"),
                    number(source.ps(), "ps"), number(source.psTtm(), "ps_ttm"),
                    number(source.dvRatio(), "dv_ratio"), number(source.dvTtm(), "dv_ttm"),
                    number(source.totalShare(), "total_share"), number(source.floatShare(), "float_share"),
                    number(source.freeShare(), "free_share"), number(source.totalMv(), "total_mv"),
                    number(source.circMv(), "circ_mv"));
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            throw new IOException("Invalid Tushare daily_basic row", invalid);
        }
    }

    public DatasetValues values(DailyBasic row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("ts_code", row.tsCode());
        values.put("trade_date", row.tradeDate());
        values.put("close", row.close());
        values.put("turnover_rate", row.turnoverRate());
        values.put("turnover_rate_f", row.turnoverRateF());
        values.put("volume_ratio", row.volumeRatio());
        values.put("pe", row.pe());
        values.put("pe_ttm", row.peTtm());
        values.put("pb", row.pb());
        values.put("ps", row.ps());
        values.put("ps_ttm", row.psTtm());
        values.put("dv_ratio", row.dvRatio());
        values.put("dv_ttm", row.dvTtm());
        values.put("total_share", row.totalShare());
        values.put("float_share", row.floatShare());
        values.put("free_share", row.freeShare());
        values.put("total_mv", row.totalMv());
        values.put("circ_mv", row.circMv());
        return new DatasetValues(values);
    }

    public DailyBasic fromValues(DatasetValues values) {
        return new DailyBasic(values.get("ts_code", String.class), values.get("trade_date", java.time.LocalDate.class),
                values.get("close", Double.class), values.get("turnover_rate", Double.class),
                values.get("turnover_rate_f", Double.class), values.get("volume_ratio", Double.class),
                values.get("pe", Double.class), values.get("pe_ttm", Double.class), values.get("pb", Double.class),
                values.get("ps", Double.class), values.get("ps_ttm", Double.class),
                values.get("dv_ratio", Double.class), values.get("dv_ttm", Double.class),
                values.get("total_share", Double.class), values.get("float_share", Double.class),
                values.get("free_share", Double.class), values.get("total_mv", Double.class),
                values.get("circ_mv", Double.class));
    }

    private static Double number(String value, String field) throws IOException {
        if (value == null) return null;
        if (value.isBlank()) return null;
        try {
            double parsed = new BigDecimal(value).doubleValue();
            if (!Double.isFinite(parsed)) throw new NumberFormatException("outside finite DOUBLE range");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid daily_basic numeric field " + field, invalid);
        }
    }
}
