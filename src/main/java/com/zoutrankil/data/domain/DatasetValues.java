package com.zoutrankil.data.domain;

import java.util.*;

/** Strict typed access for dataset-specific record mappers; no positional or coercing reads. */
public final class DatasetValues {
    private final Map<String, Object> values;
    public DatasetValues(Map<String, Object> values) {
        var copy = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> copy.put(key, value instanceof byte[] bytes ? bytes.clone() : value));
        this.values = Collections.unmodifiableMap(copy);
    }
    public <T> T get(String column, Class<T> type) {
        if (!values.containsKey(column)) throw new IllegalArgumentException("Column not projected: " + column);
        var value = values.get(column);
        if (value == null) return null;
        if (!type.isInstance(value)) throw new IllegalArgumentException("Wrong Java type for " + column + ": expected " + type.getSimpleName());
        return type.cast(value instanceof byte[] bytes ? bytes.clone() : value);
    }
    public Set<String> columns() { return values.keySet(); }
    @com.fasterxml.jackson.annotation.JsonValue
    public Map<String,Object> asMap() {
        var copy = new LinkedHashMap<String,Object>();
        values.forEach((key, value) -> copy.put(key, value instanceof byte[] bytes ? bytes.clone() : value));
        return Collections.unmodifiableMap(copy);
    }
}
