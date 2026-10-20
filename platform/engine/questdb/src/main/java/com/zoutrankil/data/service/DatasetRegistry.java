package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, single registration of a definition together with its actual implementation. */
public final class DatasetRegistry {
    private final Map<String, DatasetImplementation> entries;

    public DatasetRegistry(Collection<? extends DatasetImplementation> implementations) {
        var map = new LinkedHashMap<String, DatasetImplementation>();
        var objects = new HashSet<String>();
        for (var implementation : implementations) {
            var definition = Objects.requireNonNull(implementation.definition());
            if (map.putIfAbsent(definition.datasetId(), implementation) != null) {
                throw new IllegalArgumentException(
                        "Duplicate dataset ID: " + definition.datasetId());
            }
            if (!objects.add(definition.objectName()))
                throw new IllegalArgumentException("Duplicate physical object registration");
        }
        for (var item : map.values()) {
            if (!map.keySet().containsAll(item.definition().dependencies())) {
                throw new IllegalArgumentException(
                        "Unknown dependency: " + item.definition().datasetId());
            }
        }
        var visited = new HashSet<String>();
        for (var id : map.keySet()) visit(id, map, new HashSet<>(), visited);
        entries = Collections.unmodifiableMap(map);
    }

    private static void visit(
            String id,
            Map<String, DatasetImplementation> map,
            Set<String> path,
            Set<String> visited) {
        if (visited.contains(id)) return;
        if (!path.add(id)) throw new IllegalArgumentException("Dataset dependency cycle: " + id);
        for (var dependency : map.get(id).definition().dependencies())
            visit(dependency, map, path, visited);
        path.remove(id);
        visited.add(id);
    }

    public DatasetImplementation require(String id) {
        var entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown dataset: " + id);
        return entry;
    }

    /** 分版仍归同一 owner 和逻辑对象；原物理身份由运行账本另行核对。 */
    public DatasetDefinition requireDefinition(String id, int schemaVersion) {
        var entry = require(id);
        var current = entry.definition();
        var selected = Objects.requireNonNull(entry.definition(schemaVersion));
        if (!id.equals(selected.datasetId())
                || schemaVersion != selected.schemaVersion()
                || !current.provider().equals(selected.provider())
                || !current.owner().equals(selected.owner())
                || !current.objectName().equals(selected.objectName())
                || current.objectKind() != selected.objectKind())
            throw new IllegalArgumentException(
                    "Dataset version changed its registered owner or object");
        return selected;
    }

    public List<DatasetDefinition> definitions() {
        return entries.values().stream().map(DatasetImplementation::definition).toList();
    }
}
