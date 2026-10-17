package com.zoutrankil.data.domain;

import java.util.*;

/** All field names are logical names from DatasetDefinition. Range end is exclusive. */
public record DatasetReadQuery(List<String> columns, Map<String, Object> equalities, String rangeColumn,
                               Object fromInclusive, Object toExclusive, int pageSize, DatasetReadCursor cursor) {
    public DatasetReadQuery {
        columns = List.copyOf(columns);
        equalities = Collections.unmodifiableMap(new LinkedHashMap<>(equalities)); // Explicit IS NULL is permitted.
        if (columns.isEmpty() || new HashSet<>(columns).size() != columns.size()) throw new IllegalArgumentException("Unique explicit columns required");
        if (pageSize < 1 || pageSize > 10000) throw new IllegalArgumentException("Page size must be 1..10000");
        if ((rangeColumn == null) != (fromInclusive == null && toExclusive == null)
                || rangeColumn != null && (fromInclusive == null || toExclusive == null)) {
            throw new IllegalArgumentException("Range requires a field and both inclusive/exclusive bounds");
        }
    }
    public DatasetReadQuery after(DatasetReadCursor next) {
        return new DatasetReadQuery(columns, equalities, rangeColumn, fromInclusive, toExclusive, pageSize, next);
    }
}
