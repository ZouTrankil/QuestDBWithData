package com.zoutrankil.data.l2.port;

import com.zoutrankil.market.cn.model.CnTradeDateLayout;

/** Physical identity and fresh bounded writers for the configured D086 target. */
public interface L2DailyFeaturesTarget {
    interface Versioned extends L2DailyFeaturesTarget {
        int datasetVersion();

        L2DailyFeaturesTarget forVersion(int version);
    }

    static int versionOf(L2DailyFeaturesTarget target) {
        return target instanceof Versioned selected ? selected.datasetVersion() : 1;
    }

    /** 历史作用域只按原冻结版本选择，不能猜列或改绑另一张表。 */
    static L2DailyFeaturesTarget forFrozenVersion(L2DailyFeaturesTarget target, int version) {
        CnTradeDateLayout.fromVersion(version);
        if (!(target instanceof Versioned selected)) {
            if (version != 1) throw new IllegalArgumentException("旧 L2 日特征目标只支持 v1");
            return target;
        }
        var scoped = selected.forVersion(version);
        if (versionOf(scoped) != version || !target.tableName().equals(scoped.tableName()))
            throw new IllegalStateException("L2 日特征原作用域的布局或表名不同");
        return scoped;
    }

    String tableName();

    String targetId() throws Exception;

    L2DailyFeaturesWriteSession newWriter();

    void createIsolatedTarget(String table);
}
