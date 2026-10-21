package com.zoutrankil.data.domain.fund;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.List;
import java.util.Objects;

/** 每一侧单独声明物理对象和日期布局，不能把当前配置套到旧凭证上。 */
public record EtfOverviewScope(
        String shareTable,
        int shareVersion,
        String dailyTable,
        int dailyVersion,
        String outputTable) {
    public static final String V2_OUTPUT_PREFIX = "java_etf_overview_v2_";
    public static final EtfOverviewScope LEGACY =
            new EtfOverviewScope("etf_share", 1, "etf_daily", 1, "mv_etf_market_overview_daily");

    public EtfOverviewScope {
        CnTradeDateLayout.fromVersion(shareVersion);
        CnTradeDateLayout.fromVersion(dailyVersion);
        DatasetDefinition.identifier(shareTable);
        DatasetDefinition.identifier(dailyTable);
        DatasetDefinition.identifier(outputTable);
        if (!shareTable.equals("etf_share")) EtfShareDataset.requireIsolatedTable(shareTable);
        EtfDailyDataset.requireExecutionTable(dailyTable);
        boolean legacy =
                shareTable.equals("etf_share")
                        && shareVersion == 1
                        && dailyTable.equals("etf_daily")
                        && dailyVersion == 1
                        && outputTable.equals("mv_etf_market_overview_daily");
        if (!legacy
                && (!outputTable.startsWith(V2_OUTPUT_PREFIX)
                        || outputTable.length() <= V2_OUTPUT_PREFIX.length()))
            throw new IllegalArgumentException("新版 ETF MV 必须使用专属输出命名空间");
        if (outputTable.equals(shareTable) || outputTable.equals(dailyTable))
            throw new IllegalArgumentException("ETF 来源与输出不能同名");
    }

    public boolean legacy() {
        return equals(LEGACY);
    }

    public String shareDate() {
        return CnTradeDateLayout.fromVersion(shareVersion).physicalDateColumn("etf_share");
    }

    public String dailyDate() {
        return CnTradeDateLayout.fromVersion(dailyVersion).physicalDateColumn("etf_daily");
    }

    public DatasetDefinition definition() {
        var old = EtfMarketOverviewDailyViewDataset.DEFINITION;
        if (legacy()) return old;
        var columns =
                old.columns().stream()
                        .map(
                                c ->
                                        new Column(
                                                c.logicalName().equals("trade_date")
                                                        ? shareTable + "." + shareDate()
                                                        : c.sourceName()
                                                                .replace(
                                                                        "etf_share.",
                                                                        shareTable + ".")
                                                                .replace(
                                                                        "etf_daily.",
                                                                        dailyTable + "."),
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
                "java.etf_overview.s" + shareVersion + "_d" + dailyVersion,
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
                "显式份额/日线来源布局；保留同代码同日期 INNER JOIN、原单位与 NULL；关联日线修订必须全量刷新并重新核验。");
    }

    /** 只匹配已声明的四种完整契约，不试探数据库列名，不接收仿造的泛型 MV。 */
    public static EtfOverviewScope fromDefinition(DatasetDefinition definition) {
        Objects.requireNonNull(definition);
        if (definition.equals(EtfMarketOverviewDailyViewDataset.DEFINITION)) return LEGACY;
        if (definition.schemaVersion() == 2 && definition.columns().size() == 4) {
            // dependencies 是逻辑数据集 ID；物理来源只从本协议的完整字段映射解码。
            String dateSource = definition.columns().get(0).sourceName();
            int separator = dateSource.lastIndexOf('.');
            if (separator < 1) throw new IllegalArgumentException("ETF 份额日期来源缺失");
            String shareTable = dateSource.substring(0, separator);
            String aggregate = definition.columns().get(3).sourceName();
            String prefix = "derived:sum(" + shareTable + ".fd_share*", suffix = ".close)/10000.0";
            if (!aggregate.startsWith(prefix) || !aggregate.endsWith(suffix))
                throw new IllegalArgumentException("ETF 日线来源映射不完整");
            String dailyTable =
                    aggregate.substring(prefix.length(), aggregate.length() - suffix.length());
            for (int share : List.of(1, 2))
                for (int daily : List.of(1, 2)) {
                    try {
                        var scope =
                                new EtfOverviewScope(
                                        shareTable,
                                        share,
                                        dailyTable,
                                        daily,
                                        definition.objectName());
                        if (scope.definition().equals(definition)) return scope;
                    } catch (IllegalArgumentException | IllegalStateException invalid) {
                        /* 不匹配的声明继续拒绝。 */
                    }
                }
        }
        throw new IllegalArgumentException("ETF MV 必须使用完整的显式来源契约");
    }
}
