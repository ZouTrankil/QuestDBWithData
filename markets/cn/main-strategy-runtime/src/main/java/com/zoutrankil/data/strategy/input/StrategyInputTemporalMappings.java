package com.zoutrankil.data.strategy.input;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.domain.derived.BacktestDailyViewDataset;
import com.zoutrankil.data.domain.derived.MarketSentimentDailyDataset;
import com.zoutrankil.data.domain.derived.RegimeFeaturesMonitorDailyDataset;
import com.zoutrankil.data.domain.fund.EtfBasicDataset;
import com.zoutrankil.data.domain.fund.EtfDailyDataset;
import com.zoutrankil.data.domain.fund.EtfFactorDataset;
import com.zoutrankil.data.domain.fund.EtfPortfolioDataset;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesDataset;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendarDataset;
import com.zoutrankil.data.domain.stock.basic.StockDetailInfoDataset;
import com.zoutrankil.data.domain.stock.market.DailyDataset;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 来源角色绑定现有 DatasetDefinition；列名相同不代表日期角色相同。 */
final class StrategyInputTemporalMappings {
    private StrategyInputTemporalMappings() {}

    record Binding(DatasetDefinition definition, Map<String, Column> columns) {
        Binding(DatasetDefinition definition) {
            this(
                    definition,
                    definition.columns().stream()
                            .collect(
                                    Collectors.toUnmodifiableMap(
                                            Column::storageName, Function.identity())));
        }
    }

    private static final Map<String, Binding> BINDINGS =
            Map.ofEntries(
                    Map.entry("calendar", new Binding(ExchangeCalendarDataset.DEFINITION)),
                    Map.entry(
                            "market_context",
                            new Binding(RegimeFeaturesMonitorDailyDataset.DEFINITION)),
                    Map.entry("stock_daily", new Binding(BacktestDailyViewDataset.DEFINITION)),
                    Map.entry("l2_raw", new Binding(L2DailyFeaturesDataset.DEFINITION)),
                    Map.entry("stock_metadata", new Binding(StockDetailInfoDataset.DEFINITION)),
                    Map.entry("l2_observed_market_keys", new Binding(DailyDataset.DEFINITION)),
                    Map.entry("etf_reference", new Binding(EtfBasicDataset.DEFINITION)),
                    Map.entry("etf_factor", new Binding(EtfFactorDataset.DEFINITION)),
                    Map.entry("etf_daily", new Binding(EtfDailyDataset.DEFINITION)),
                    Map.entry("etf_portfolio", new Binding(EtfPortfolioDataset.DEFINITION)),
                    Map.entry("sentiment", new Binding(MarketSentimentDailyDataset.DEFINITION)));

    static Binding binding(String role) {
        var binding = BINDINGS.get(role);
        if (binding == null) throw new IllegalArgumentException("主策略输入未绑定时间契约：" + role);
        return binding;
    }

    static Object decodeLegacyV1(Binding binding, String column, Object value) {
        if (!(value instanceof Timestamp)
                && !(value instanceof Date)
                && !(value instanceof Instant)
                && !(value instanceof LocalDate)) return value;
        var definition = binding.columns().get(column);
        if (definition == null || definition.temporal() == null) {
            throw new IllegalStateException(
                    "来源时间字段缺少契约：" + binding.definition().objectName() + "." + column);
        }
        var kind = definition.temporal().kind();
        if (value instanceof Timestamp timestamp)
            return instant(binding, column, kind, timestamp.toInstant());
        if (value instanceof Instant timestamp) return instant(binding, column, kind, timestamp);
        LocalDate day = value instanceof Date date ? date.toLocalDate() : (LocalDate) value;
        if (kind == TemporalKind.INSTANT)
            throw new IllegalStateException("真实时刻不能由无时区日期补造：" + column);
        if (kind == TemporalKind.TECHNICAL)
            requireEpochMarker(binding, column, day.atStartOfDay().toInstant(ZoneOffset.UTC));
        return day;
    }

    private static Object instant(
            Binding binding, String column, TemporalKind kind, Instant value) {
        if (kind == TemporalKind.INSTANT) return value.toString();
        if (kind == TemporalKind.TECHNICAL) requireEpochMarker(binding, column, value);
        var utc = value.atZone(ZoneOffset.UTC);
        if (!utc.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            throw new IllegalStateException(
                    "日期载体必须为 UTC 午夜：" + binding.definition().objectName() + "." + column);
        }
        // v1 冻结内容保留原 LocalDate 编码；技术占位仍按 TECHNICAL 解释，不参与交易日或可用时刻。
        return utc.toLocalDate();
    }

    private static void requireEpochMarker(Binding binding, String column, Instant value) {
        if (!binding.definition().datasetId().equals("etf_basic")
                || !column.equals("timestamp")
                || !value.equals(Instant.EPOCH)) {
            throw new IllegalStateException(
                    "技术占位不是已登记的固定 epoch：" + binding.definition().objectName() + "." + column);
        }
    }
}
