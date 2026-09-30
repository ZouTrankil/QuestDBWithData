package com.zoutrankil.data.domain;

import java.time.Instant;
import java.util.List;

public record DatasetReadPage<T>(String datasetId, int definitionVersion, String sourceVersion, Instant observedAt,
                                  List<T> rows, DatasetReadCursor nextCursor) {
    public DatasetReadPage { rows = List.copyOf(rows); }
    public boolean hasMore() { return nextCursor != null; }
}
