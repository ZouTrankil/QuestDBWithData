package com.zoutrankil.data.domain.derived;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.shared.policy.IsolatedTablePolicy;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.List;
import java.util.Objects;

/** 停牌和 ST 可分别换布局；行情和涨跌停仍绑定原正式来源。 */
public record BacktestDailyScope(
        String suspendTable,
        int suspendVersion,
        String stTable,
        int stVersion,
        String outputTable) {
    public static final String V2_OUTPUT_PREFIX = "java_backtest_v2_";
    public static final BacktestDailyScope LEGACY =
            new BacktestDailyScope("stk_suspend", 1, "stk_st_daily", 1, "mv_backtest_daily");

    public BacktestDailyScope {
        CnTradeDateLayout.fromVersion(suspendVersion);
        CnTradeDateLayout.fromVersion(stVersion);
        DatasetDefinition.identifier(suspendTable);
        DatasetDefinition.identifier(stTable);
        DatasetDefinition.identifier(outputTable);
        if (!suspendTable.equals("stk_suspend")
                && !suspendTable.matches(
                        "(?:java_d011_stk_suspend_|stk_suspend_d011_)[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("回测 MV 停牌来源须为原正式表或显式隔离目标");
        if (!stTable.equals("stk_st_daily")) IsolatedTablePolicy.STOCK_ST_DAILY.require(stTable);
        boolean original =
                suspendTable.equals("stk_suspend")
                        && suspendVersion == 1
                        && stTable.equals("stk_st_daily")
                        && stVersion == 1
                        && outputTable.equals("mv_backtest_daily");
        if (!original
                && (!outputTable.startsWith(V2_OUTPUT_PREFIX)
                        || outputTable.length() <= V2_OUTPUT_PREFIX.length()))
            throw new IllegalArgumentException("新版回测 MV 必须使用独立输出命名空间");
    }

    public boolean legacy() {
        return equals(LEGACY);
    }

    public String suspendDate() {
        return CnTradeDateLayout.fromVersion(suspendVersion).physicalDateColumn("stk_suspend");
    }

    public String stDate() {
        return CnTradeDateLayout.fromVersion(stVersion).physicalDateColumn("stk_st_daily");
    }

    public List<String> sources() {
        return List.of("stk_factor", "stk_limit", suspendTable, stTable);
    }

    public DatasetDefinition definition() {
        var old = BacktestDailyViewDataset.DEFINITION;
        if (legacy()) return old;
        var columns =
                old.columns().stream()
                        .map(
                                c ->
                                        new Column(
                                                switch (c.logicalName()) {
                                                    case "trade_date" -> "stk_limit.trade_date";
                                                    case "ts_code" -> "stk_factor.ts_code";
                                                    case "is_suspended" ->
                                                            "derived:"
                                                                    + suspendTable
                                                                    + ".is_suspended";
                                                    case "is_st" -> "derived:" + stTable + ".is_st";
                                                    default ->
                                                            c.sourceName()
                                                                    .replace(
                                                                            "mv_backtest_daily.",
                                                                            outputTable + ".");
                                                },
                                                c.logicalName(),
                                                c.storageName(),
                                                c.storageType(),
                                                c.nullable(),
                                                c.meaning(),
                                                c.temporal(),
                                                c.legacyNullSentinels(),
                                                c.numeric()))
                        .toList();
        return new DatasetDefinition(
                old.datasetId(),
                2,
                "java.backtest.s" + suspendVersion + "_st" + stVersion,
                old.owner(),
                outputTable,
                old.objectKind(),
                columns,
                old.businessKey(),
                old.dedupKey(),
                old.designatedTimestamp(),
                old.partition(),
                old.wal(),
                old.capabilities(),
                old.dependencies(),
                "显式停牌/ST 来源与日期布局；保留原 ASOF、停牌补行、NULL、单位及 stk_limit 最后提交规则。只读核验，不创建或刷新 MV。");
    }

    /** 只接受完整 canonical 定义，不能仅凭输出名或 schema 号绕过来源核验。 */
    public static BacktestDailyScope fromDefinition(DatasetDefinition definition) {
        Objects.requireNonNull(definition);
        if (definition.equals(BacktestDailyViewDataset.DEFINITION)) return LEGACY;
        if (definition.schemaVersion() == 2 && definition.columns().size() == 13) {
            String suspend = source(definition.columns().get(11), ".is_suspended");
            String st = source(definition.columns().get(12), ".is_st");
            for (int s : List.of(1, 2))
                for (int t : List.of(1, 2)) {
                    var candidate =
                            new BacktestDailyScope(suspend, s, st, t, definition.objectName());
                    if (candidate.definition().equals(definition)) return candidate;
                }
        }
        throw new IllegalArgumentException("回测 MV 必须绑定完整的原版本来源契约");
    }

    private static String source(Column column, String suffix) {
        String value = column.sourceName();
        if (!value.startsWith("derived:") || !value.endsWith(suffix))
            throw new IllegalArgumentException("回测 MV 来源字段不完整");
        return value.substring("derived:".length(), value.length() - suffix.length());
    }
}
