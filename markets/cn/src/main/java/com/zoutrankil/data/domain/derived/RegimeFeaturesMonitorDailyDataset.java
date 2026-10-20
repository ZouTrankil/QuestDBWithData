package com.zoutrankil.data.domain.derived;

import static com.zoutrankil.data.domain.derived.policy.RegimeFeaturesMonitorDailyCalculation.COLUMNS;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.domain.derived.table.RegimeFeaturesMonitorDailyRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 复用现有日频合同；交易日与真实计算时刻分开，不改变旧布局版本。 */
public final class RegimeFeaturesMonitorDailyDataset {
    private RegimeFeaturesMonitorDailyDataset() {}

    public static final List<String> DEPENDENCIES =
            List.of(
                    "stk_factor",
                    "daily",
                    "daily_basic",
                    "stk_limit",
                    "stk_suspend",
                    "stk_st_daily",
                    "margin_detail",
                    "moneyflow_hsgt",
                    "cn_bond_yield_curve",
                    "exchange_calendar");
    public static final DatasetDefinition DEFINITION = definition("regime_features_monitor_daily");

    public static DatasetDefinition definition(String table) {
        var columns = new ArrayList<Column>();
        var components = RegimeFeaturesMonitorDailyRow.class.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            String name = COLUMNS.get(i);
            Class<?> type = components[i].getType();
            var storage =
                    type == Instant.class
                            ? StorageType.TIMESTAMP
                            : type == Double.class ? StorageType.DOUBLE : StorageType.SYMBOL;
            var temporal =
                    type != Instant.class
                            ? null
                            : new TemporalContract(
                                    name.equals("trade_date")
                                            ? TemporalKind.BUSINESS_DATE
                                            : TemporalKind.INSTANT,
                                    "epochMicros",
                                    name.equals("trade_date") ? "calendar" : "UTC",
                                    "MICROS",
                                    name.equals("trade_date")
                                            ? "Trading date encoded at exact UTC midnight"
                                            : "Native calculation observation instant");
            columns.add(
                    new Column(
                            "derived:" + name,
                            name,
                            name,
                            storage,
                            !name.equals("trade_date"),
                            "Legacy proxy-style field " + name,
                            temporal));
        }
        return new DatasetDefinition(
                "regime_features_monitor_daily",
                1,
                "derived.questdb",
                "java.regime_features_monitor_daily",
                table,
                ObjectKind.TABLE,
                columns,
                List.of("trade_date"),
                List.of(),
                "trade_date",
                Partition.MONTH,
                true,
                Set.of(Capability.READ, Capability.WAL_REPLACE),
                DEPENDENCIES,
                "21 existing fields; bounded full-row window replacement retaining outside rows and backup. Warmup=min(month start,from-20 days); qcut size/PB proxy style, monthly compounds, rolling five observations, full warmup-window valuation ranks. Required input-package context fields are finite; optional incomplete-market margin is NULL with separate availability evidence, while the inherited data_quality_flag remains proxy_style. Gov/10Y cn_bond_yield_curve is a required pinned input synchronized by its registered ChinaBond owner before calculation.");
    }
}
