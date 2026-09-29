package com.zoutrankil.questdbwithdata.domain;

import java.util.*;

/** Versioned business-to-storage contract. A schema projection is not a dataset definition. */
public record DatasetDefinition(
        String datasetId, int schemaVersion, String provider, String owner, String objectName,
        ObjectKind objectKind, List<Column> columns, List<String> businessKey,
        List<String> dedupKey, String designatedTimestamp, Partition partition, boolean wal,
        Set<Capability> capabilities, List<String> dependencies, String storageRationale) {

    public enum ObjectKind { TABLE, VIEW, MATERIALIZED_VIEW }
    public enum Partition { NONE, HOUR, DAY, WEEK, MONTH, YEAR }
    public enum Capability { READ, WRITE }
    public enum StorageType { BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, CHAR, STRING,
        VARCHAR, SYMBOL, UUID, LONG256, BINARY, IPV4, DATE, TIMESTAMP, TIMESTAMP_NS }
    public enum TemporalKind { BUSINESS_DATE, INSTANT, TECHNICAL }

    /** Format includes epoch unit where applicable; zone and precision are never inferred. */
    public record TemporalContract(TemporalKind kind, String sourceFormat, String zone,
                                   String precision, String meaning) {
        public TemporalContract {
            Objects.requireNonNull(kind);
            requireText(sourceFormat, "sourceFormat");
            requireText(zone, "zone (use calendar for business dates)");
            requireText(precision, "precision");
            requireText(meaning, "temporal meaning");
            if (kind == TemporalKind.BUSINESS_DATE && !zone.equals("calendar")) {
                throw new IllegalArgumentException("Business dates require calendar semantics");
            }
        }
    }

    public record Column(String sourceName, String logicalName, String storageName,
                         StorageType storageType, boolean nullable, String meaning,
                         TemporalContract temporal) {
        public Column {
            requireText(sourceName, "source mapping (use derived:... for computed values)");
            identifier(logicalName);
            identifier(storageName);
            Objects.requireNonNull(storageType);
            requireText(meaning, "column meaning");
            if (Set.of(StorageType.DATE, StorageType.TIMESTAMP, StorageType.TIMESTAMP_NS)
                    .contains(storageType) && temporal == null) {
                throw new IllegalArgumentException("Temporal storage column requires semantic contract: " + storageName);
            }
        }
    }

    public DatasetDefinition {
        identifier(datasetId);
        identifier(objectName);
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        requireText(provider, "provider");
        requireText(owner, "owner");
        requireText(storageRationale, "partition/key rationale");
        Objects.requireNonNull(objectKind);
        Objects.requireNonNull(partition);
        columns = List.copyOf(columns);
        businessKey = List.copyOf(businessKey);
        dedupKey = List.copyOf(dedupKey);
        capabilities = Set.copyOf(capabilities);
        dependencies = List.copyOf(dependencies);
        if (columns.isEmpty() || capabilities.isEmpty()) throw new IllegalArgumentException("Columns and capabilities required");
        var logical = new HashSet<String>();
        var physical = new HashMap<String, Column>();
        for (var c : columns) {
            if (!logical.add(c.logicalName()) || physical.putIfAbsent(c.storageName(), c) != null) {
                throw new IllegalArgumentException("Duplicate column mapping");
            }
        }
        if (businessKey.isEmpty()) throw new IllegalArgumentException("Complete business key required");
        validateKey(businessKey, logical, "businessKey");
        validateKey(dedupKey, physical.keySet(), "dedupKey");
        for (var c : columns) {
            if ((businessKey.contains(c.logicalName()) || dedupKey.contains(c.storageName())) && c.nullable()) {
                throw new IllegalArgumentException("Key column cannot be nullable: " + c.logicalName());
            }
        }
        if (designatedTimestamp != null) {
            var c = physical.get(designatedTimestamp);
            if (c == null || c.temporal() == null ||
                    !(c.storageType() == StorageType.TIMESTAMP || c.storageType() == StorageType.TIMESTAMP_NS)) {
                throw new IllegalArgumentException("Designated timestamp requires a declared timestamp contract");
            }
            if (c.nullable()) throw new IllegalArgumentException("Designated timestamp cannot be nullable");
        }
        if (partition != Partition.NONE && designatedTimestamp == null) {
            throw new IllegalArgumentException("Partition requires designated timestamp");
        }
        if (!dedupKey.isEmpty() && (!wal || designatedTimestamp == null || !dedupKey.contains(designatedTimestamp))) {
            throw new IllegalArgumentException("Dedup requires WAL and designated timestamp in key");
        }
        if (objectKind != ObjectKind.TABLE && (capabilities.contains(Capability.WRITE) || !dedupKey.isEmpty())) {
            throw new IllegalArgumentException("Views and materialized views cannot be directly written or upserted");
        }
        if (objectKind == ObjectKind.VIEW && (partition != Partition.NONE || wal)) {
            throw new IllegalArgumentException("Ordinary view has no partition or WAL");
        }
        if (!dedupKey.isEmpty()) {
            for (var c : columns) {
                if (businessKey.contains(c.logicalName()) && !dedupKey.contains(c.storageName())) {
                    throw new IllegalArgumentException("Dedup key would collapse business identity: " + c.logicalName());
                }
            }
        }
        var seenDependencies = new HashSet<String>();
        for (var dependency : dependencies) {
            identifier(dependency);
            if (dependency.equals(datasetId) || !seenDependencies.add(dependency)) {
                throw new IllegalArgumentException("Duplicate or self dependency");
            }
        }
    }

    public List<String> storageColumns() { return columns.stream().map(Column::storageName).toList(); }
    public void requireCapability(Capability capability) {
        if (!capabilities.contains(capability)) throw new IllegalArgumentException("Unsupported " + capability + ": " + datasetId);
    }
    private static void validateKey(List<String> key, Set<String> known, String name) {
        if (new HashSet<>(key).size() != key.size() || !known.containsAll(key)) {
            throw new IllegalArgumentException("Duplicate or unknown " + name + " field");
        }
    }
    public static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid identifier");
    }
    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " required");
    }
}
