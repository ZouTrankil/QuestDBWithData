package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.stock.domain.StockSuspendState.Identity;
import com.zoutrankil.data.stock.domain.StockSuspendState.Prepared;
import com.zoutrankil.data.stock.domain.StockSuspendState.Range;
import com.zoutrankil.data.stock.domain.StockSuspendState.Snapshot;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.time.LocalDate;
import java.util.List;

/** The admitted logical suspension target and factories for isolated per-run state. */
public interface StockSuspendTarget {
    interface Versioned extends StockSuspendTarget {
        int datasetVersion();

        StockSuspendTarget forVersion(int version);
    }

    static int versionOf(StockSuspendTarget target) {
        return target instanceof Versioned selected ? selected.datasetVersion() : 1;
    }

    /** 只由原请求取独立作用域，不能按当前物理列猜测或重绑目标。 */
    static StockSuspendTarget forFrozenVersion(StockSuspendTarget target, int version) {
        CnTradeDateLayout.fromVersion(version);
        if (!(target instanceof Versioned selected)) {
            if (version != 1) throw new IllegalArgumentException("旧停牌目标只支持 v1");
            return target;
        }
        var scoped = selected.forVersion(version);
        if (versionOf(scoped) != version || !target.tableName().equals(scoped.tableName()))
            throw new IllegalStateException("停牌原冻结作用域的布局或表名不同");
        return scoped;
    }

    String tableName();

    String targetId();

    String physicalTargetId();

    Range range();

    StockSuspendWriteSession newWriter(String frozenPhysicalTargetId);

    StockSuspendTables.Table openTable(String table);

    String physicalTargetId(String table, Identity identity);

    Prepared prepare(
            Snapshot before,
            Snapshot current,
            List<StockSuspend> source,
            LocalDate from,
            LocalDate to)
            throws Exception;

    StockSuspendStagingPort newStaging();

    StockSuspendTables newPublicationTables();
}
