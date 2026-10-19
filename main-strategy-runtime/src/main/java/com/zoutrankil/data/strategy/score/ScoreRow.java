package com.zoutrankil.data.strategy.score;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** One observed row. Missing numbers remain NaN; source adapters must not impute missing dates. */
public record ScoreRow(LocalDate tradeDate, String symbol, Map<String, Object> values) {
    public ScoreRow {
        Objects.requireNonNull(tradeDate, "tradeDate");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("Row symbol required");
        values = FrozenScalarValues.copyOf(Objects.requireNonNull(values));
    }
    public double number(String key) { return ScoreMath.number(values.get(key)); }
    public String text(String key) { return Objects.toString(values.get(key), ""); }
    public boolean flag(String key) { return ScoreMath.flag(values.get(key)); }
}
