package com.zoutrankil.data.strategy.score;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一条实际观测记录；缺失数值保持 NaN，来源适配器不能补造缺失日期。 */
public record ScoreRow(LocalDate tradeDate, String symbol, Map<String, Object> values) {
    public ScoreRow {
        Objects.requireNonNull(tradeDate, "tradeDate");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("Row symbol required");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values)));
    }
    public double number(String key) { return ScoreMath.number(values.get(key)); }
    public String text(String key) { return Objects.toString(values.get(key), ""); }
    public boolean flag(String key) { return ScoreMath.flag(values.get(key)); }
}
