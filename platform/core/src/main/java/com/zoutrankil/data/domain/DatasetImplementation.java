package com.zoutrankil.data.domain;

/** Implemented by the owning adapter, so definitions are registered with actual code. */
public interface DatasetImplementation {
    DatasetDefinition definition();

    /** 历史版本必须由原实现显式提供，不能拿当前定义替换冻结请求。 */
    default DatasetDefinition definition(int schemaVersion) {
        var current = definition();
        if (current.schemaVersion() != schemaVersion)
            throw new IllegalArgumentException("Dataset version is not supported by this owner");
        return current;
    }
}
