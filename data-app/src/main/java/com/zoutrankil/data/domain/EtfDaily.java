package com.zoutrankil.data.domain;
import java.time.LocalDate;
/** D014 source prices are yuan, volume lots, turnover thousand yuan; nulls remain null. */
public record EtfDaily(EtfDailyKey key, Double preClose, Double open, Double high, Double low, Double close, Double change, Double pctChg, Double vol, Double amount) {
    public EtfDaily {
        if(key==null) throw new IllegalArgumentException("Complete ETF daily key required");
        if((preClose != null && !Double.isFinite(preClose)) || (open != null && !Double.isFinite(open)) || (high != null && !Double.isFinite(high)) || (low != null && !Double.isFinite(low)) || (close != null && !Double.isFinite(close)) || (change != null && !Double.isFinite(change)) || (pctChg != null && !Double.isFinite(pctChg)) || (vol != null && !Double.isFinite(vol)) || (amount != null && !Double.isFinite(amount))) throw new IllegalArgumentException("ETF daily metrics must be finite or null");
    }
    public EtfDaily(String tsCode,LocalDate tradeDate,Double preClose, Double open, Double high, Double low, Double close, Double change, Double pctChg, Double vol, Double amount) { this(new EtfDailyKey(tsCode,tradeDate),preClose, open, high, low, close, change, pctChg, vol, amount); }
    public String tsCode() { return key.tsCode(); }
    public LocalDate tradeDate() { return key.tradeDate(); }
}
