package com.zoutrankil.data.strategy.score;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** 证券观测和市场汇总分开表示；缺失数值保留 NaN，不补造日期或证券。 */
public record ScoreRow(
        LocalDate tradeDate,
        String instrumentCode,
        AggregationScope aggregationScope,
        Map<String, Object> values) {
    public enum AggregationScope {
        MARKET,
        ETF_UNIVERSE
    }

    public ScoreRow(LocalDate tradeDate, String instrumentCode, Map<String, Object> values) {
        this(tradeDate, instrumentCode, null, values);
    }

    public ScoreRow {
        Objects.requireNonNull(tradeDate, "tradeDate");
        if ((instrumentCode == null) == (aggregationScope == null)) {
            throw new IllegalArgumentException("必须且只能提供证券代码或汇总范围");
        }
        if (instrumentCode != null
                && (instrumentCode.isBlank()
                        || instrumentCode.equals("MARKET")
                        || instrumentCode.equals("ETF_UNIVERSE"))) {
            throw new IllegalArgumentException("汇总标签不能作为证券代码");
        }
        values = FrozenScalarValues.copyOf(Objects.requireNonNull(values));
    }

    public static ScoreRow aggregate(
            LocalDate day, AggregationScope scope, Map<String, Object> values) {
        return new ScoreRow(day, null, Objects.requireNonNull(scope), values);
    }

    public ScoreRow withValues(Map<String, Object> replacement) {
        return new ScoreRow(tradeDate, instrumentCode, aggregationScope, replacement);
    }

    public String requireInstrumentCode() {
        if (instrumentCode == null) throw new IllegalStateException("汇总行不能作为证券行发布");
        return instrumentCode;
    }

    // 旧评分算法内部仍按同一标签分组；此值不能作为新版对外证券身份。
    String algorithmGroupingKey() {
        return instrumentCode != null ? instrumentCode : aggregationScope.name();
    }

    public double number(String key) {
        return ScoreMath.number(values.get(key));
    }

    public String text(String key) {
        return Objects.toString(values.get(key), "");
    }

    public boolean flag(String key) {
        return ScoreMath.flag(values.get(key));
    }
}
