package com.zoutrankil.data.index.port;

/** Target identity and fresh writer assembly without database client types. */
public interface IndexDailyMarketTarget {
    /** 旧端口默认v1，只有明确实现才声明新的物理布局。 */
    default int datasetVersion() {
        return 1;
    }

    default IndexDailyMarketTarget forVersion(int version) {
        if (version != datasetVersion()) throw new IllegalArgumentException("指数日线目标不能切换原冻结布局");
        return this;
    }

    String tableName();

    String targetId();

    IndexDailyMarketWriteSession newWriter(String frozenTargetId);
}
