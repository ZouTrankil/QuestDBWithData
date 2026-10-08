package com.zoutrankil.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Full-key, full-value comparison. Equal row counts alone can never pass this check. */
public final class DatasetWriteComparison {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DatasetWriteComparison() {}
    public record Difference(String keyDigest, String kind, List<String> columns) {
        public Difference { columns = List.copyOf(columns); }
    }
    public record Result(int expectedRows, int actualRows, int matchedRows, List<Difference> differences,
                         String expectedDigest, String actualDigest) {
        public Result { differences = List.copyOf(differences); }
        public boolean matches() { return expectedRows > 0 && matchedRows == expectedRows && differences.isEmpty(); }
    }
    public static Result compare(DatasetDefinition definition, List<DatasetValues> expected, List<DatasetValues> actual) {
        var differences = new ArrayList<Difference>();
        var expectedByKey = index(definition, expected, differences, "duplicate_expected_key");
        var actualByKey = index(definition, actual, differences, "duplicate_actual_key");
        int matched = 0;
        for (var entry : expectedByKey.entrySet()) {
            var found = actualByKey.get(entry.getKey());
            if (found == null) {
                differences.add(new Difference(entry.getKey(), "missing", List.of()));
                continue;
            }
            var changed = new ArrayList<String>();
            for (var column : definition.columns()) {
                String name = column.logicalName();
                if (!Objects.equals(entry.getValue().get(name), found.get(name))) changed.add(name);
            }
            if (changed.isEmpty()) matched++;
            else differences.add(new Difference(entry.getKey(), "value_mismatch", changed));
        }
        for (var key : actualByKey.keySet()) {
            if (!expectedByKey.containsKey(key)) differences.add(new Difference(key, "unexpected", List.of()));
        }
        return new Result(expected.size(), actual.size(), matched, differences, digest(expectedByKey), digest(actualByKey));
    }
    private static SortedMap<String, Map<String, Object>> index(DatasetDefinition definition, List<DatasetValues> rows,
                                                               List<Difference> differences, String duplicateKind) {
        var result = new TreeMap<String, Map<String, Object>>();
        var expectedColumns = new HashSet<>(definition.columns().stream().map(DatasetDefinition.Column::logicalName).toList());
        for (var row : rows) {
            if (!row.columns().equals(expectedColumns)) throw new IllegalArgumentException("Readback must contain every declared field");
            var values = new LinkedHashMap<String, Object>();
            var key = new LinkedHashMap<String, Object>();
            for (var column : definition.columns()) {
                Object value = row.get(column.logicalName(), Object.class);
                if (value == null && !column.nullable()) throw new IllegalArgumentException("Null required readback field");
                Object storage = value == null ? null : QuestDbBoundedReader.storageValue(column, value);
                Object canonical = storage instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes)
                        : storage instanceof UUID || storage instanceof Character ? storage.toString() : storage;
                values.put(column.logicalName(), canonical);
                if (definition.businessKey().contains(column.logicalName())) key.put(column.logicalName(), canonical);
            }
            String fingerprint = digest(key);
            if (result.putIfAbsent(fingerprint, values) != null) differences.add(new Difference(fingerprint, duplicateKind, List.of()));
        }
        return result;
    }
    private static String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalArgumentException("Cannot fingerprint full readback values", failure); }
    }
}
