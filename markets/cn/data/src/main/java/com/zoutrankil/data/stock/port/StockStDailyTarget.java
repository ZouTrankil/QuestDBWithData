package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.stock.basic.StockStDaily;
import com.zoutrankil.data.domain.stock.basic.StockStDailyKey;
import com.zoutrankil.data.stock.domain.StockStDailyState.Content;
import com.zoutrankil.data.stock.domain.StockTargetRange;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.time.LocalDate;
import java.util.List;

public interface StockStDailyTarget {
    /** 每个作用域固定布局；恢复通过原冻结版本取得独立作用域。 */
    interface Versioned extends StockStDailyTarget {
        int datasetVersion();

        StockStDailyTarget forVersion(int version);
    }

    static int versionOf(StockStDailyTarget target) {
        return target instanceof Versioned selected ? selected.datasetVersion() : 1;
    }

    static StockStDailyTarget forFrozenVersion(StockStDailyTarget target, int version) {
        CnTradeDateLayout.fromVersion(version);
        if (!(target instanceof Versioned selected)) {
            if (version != 1) throw new IllegalArgumentException("旧 ST 目标只支持 v1");
            return target;
        }
        var scoped = selected.forVersion(version);
        if (versionOf(scoped) != version || !target.tableName().equals(scoped.tableName()))
            throw new IllegalStateException("ST 冻结作用域的布局或目标不同");
        return scoped;
    }

    String tableName();

    String targetId();

    String physicalTargetId();

    StockTargetRange range();

    StockDateWriteSession<StockStDaily, StockStDailyKey> newWriter(String physicalTargetId);

    StockDateWriteSession<StockStDaily, StockStDailyKey> stageWriter(
            String stageTable, String physicalTargetId);

    StockStDailyTables newPublicationTables();

    StockStDailyStagingPort newStaging();

    StockStDailyTables.Table openTable();

    Content fingerprintWindow(List<StockStDaily> rows, LocalDate from, LocalDate to)
            throws Exception;
}
