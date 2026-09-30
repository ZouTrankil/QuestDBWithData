package com.zoutrankil.data.domain;

/** Nullable numeric fields physically present in the audited stk_factor table. */
public record StockFactorFields(
        Double close, Double open, Double high, Double low, Double preClose, Double change, Double pctChange,
        Double vol, Double amount, Double adjFactor,
        Double openHfq, Double openQfq, Double closeHfq, Double closeQfq,
        Double highHfq, Double highQfq, Double lowHfq, Double lowQfq,
        Double preCloseHfq, Double preCloseQfq,
        Double macdDif, Double macdDea, Double macd,
        Double kdjK, Double kdjD, Double kdjJ,
        Double rsi6, Double rsi12, Double rsi24,
        Double bollUpper, Double bollMid, Double bollLower, Double cci) {
    public StockFactorFields {
        Double[] values = {close, open, high, low, preClose, change, pctChange, vol, amount, adjFactor,
                openHfq, openQfq, closeHfq, closeQfq, highHfq, highQfq, lowHfq, lowQfq,
                preCloseHfq, preCloseQfq, macdDif, macdDea, macd, kdjK, kdjD, kdjJ,
                rsi6, rsi12, rsi24, bollUpper, bollMid, bollLower, cci};
        for (Double value : values) {
            if (value != null && !Double.isFinite(value))
                throw new IllegalArgumentException("Finite factor values required; use null for missing source values");
        }
    }
}
