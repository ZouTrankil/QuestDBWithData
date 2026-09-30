package com.zoutrankil.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** Validates an already bounded source page before any writer can borrow a sender. */
public final class DatasetWritePreparation {
    private DatasetWritePreparation() {}
    public record Limits(int maxRows, int maxNormalizedBytes) {
        public Limits {
            if (maxRows < 1 || maxRows > 10000 || maxNormalizedBytes < 1 || maxNormalizedBytes > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("Finite row and normalized-byte bounds required");
            }
        }
    }
    public record Batch(String datasetId, int definitionVersion, List<DatasetValues> rows,
                        int normalizedBytes, String fingerprint) {
        public Batch { rows = List.copyOf(rows); }
        public boolean empty() { return rows.isEmpty(); }
    }

    public static <T> Batch prepare(DatasetDefinition definition, List<T> input,
                                    Function<T, DatasetValues> mapper, Limits limits) {
        definition.requireCapability(DatasetDefinition.Capability.WRITE);
        if (definition.objectKind() != DatasetDefinition.ObjectKind.TABLE || !definition.wal()
                || definition.dedupKey().isEmpty() || definition.designatedTimestamp() == null) {
            throw new IllegalArgumentException("Idempotent writer requires an explicit WAL table, timestamp and full dedup key");
        }
        return prepareRows(definition,input,mapper,limits);
    }
    /** Full-row validation for owners that publish an application-keyed static replacement. */
    public static <T> Batch prepareStatic(DatasetDefinition definition, List<T> input,
                                          Function<T, DatasetValues> mapper, Limits limits) {
        definition.requireCapability(DatasetDefinition.Capability.STATIC_REPLACE);
        if (definition.objectKind()!=DatasetDefinition.ObjectKind.TABLE || definition.wal()
                || definition.partition()!=DatasetDefinition.Partition.NONE
                || definition.designatedTimestamp()!=null || !definition.dedupKey().isEmpty())
            throw new IllegalArgumentException("Static replacement requires a non-WAL whole-table owner");
        return prepareRows(definition,input,mapper,limits);
    }
    /** Full-row admission for a verified partitioned-WAL stage and whole-table publication. */
    public static <T> Batch prepareWalReplace(DatasetDefinition definition,List<T> input,
                                              Function<T,DatasetValues> mapper,Limits limits) {
        definition.requireCapability(DatasetDefinition.Capability.WAL_REPLACE);
        if(definition.objectKind()!=DatasetDefinition.ObjectKind.TABLE || !definition.wal()
                || definition.partition()==DatasetDefinition.Partition.NONE
                || definition.designatedTimestamp()==null)
            throw new IllegalArgumentException("Partitioned WAL replacement contract required");
        return prepareRows(definition,input,mapper,limits);
    }
    private static <T> Batch prepareRows(DatasetDefinition definition, List<T> input,
                                         Function<T, DatasetValues> mapper, Limits limits) {
        if (input.size() > limits.maxRows()) throw new IllegalArgumentException("Write page exceeds row bound");
        var required = new HashSet<>(definition.columns().stream().map(DatasetDefinition.Column::logicalName).toList());
        var rows = new ArrayList<DatasetValues>(input.size());
        var keys = new HashSet<String>();
        var json = new ObjectMapper();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((definition.datasetId() + ":" + definition.schemaVersion() + ":" + definition.objectName())
                    .getBytes(StandardCharsets.UTF_8));
            int bytes = 0;
            for (var item : input) {
                var values = Objects.requireNonNull(mapper.apply(item), "Mapped row required");
                if (!values.columns().equals(required)) throw new IllegalArgumentException("Explicit complete columns required, including nulls");
                var canonical = new LinkedHashMap<String, Object>();
                var businessKey = new LinkedHashMap<String, Object>();
                for (var column : definition.columns()) {
                    Object value = values.get(column.logicalName(), Object.class);
                    if (value == null && !column.nullable()) throw new IllegalArgumentException("Null required column: " + column.logicalName());
                    Object physical = value == null ? null : QuestDbBoundedReader.storageValue(column, value);
                    Object normalized = canonical(physical);
                    canonical.put(column.storageName(), normalized);
                    if (definition.businessKey().contains(column.logicalName())) businessKey.put(column.logicalName(), normalized);
                }
                if (!keys.add(json.writeValueAsString(businessKey))) throw new IllegalArgumentException("Duplicate business key in write page");
                byte[] encoded = json.writeValueAsBytes(canonical);
                bytes = Math.addExact(bytes, encoded.length);
                if (bytes > limits.maxNormalizedBytes()) throw new IllegalArgumentException("Write page exceeds normalized byte bound");
                digest.update(encoded);
                digest.update((byte) '\n');
                rows.add(values);
            }
            return new Batch(definition.datasetId(), definition.schemaVersion(), rows, bytes,
                    HexFormat.of().formatHex(digest.digest()));
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("Cannot prepare bounded write page", failure); }
    }

    private static Object canonical(Object value) {
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof UUID || value instanceof Character) return value.toString();
        return value;
    }
}
