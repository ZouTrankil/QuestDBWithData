package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import java.util.*;

/** Immutable, single registration of a definition together with its actual implementation. */
public final class DatasetRegistry {
    private final Map<String, DatasetImplementation> entries;

    public DatasetRegistry(Collection<? extends DatasetImplementation> implementations) {
        var map = new LinkedHashMap<String, DatasetImplementation>();
        var objects = new HashSet<String>();
        for (var implementation : implementations) {
            var definition = Objects.requireNonNull(implementation.definition());
            if (map.putIfAbsent(definition.datasetId(), implementation) != null) {
                throw new IllegalArgumentException("Duplicate dataset ID: " + definition.datasetId());
            }
            if (!objects.add(definition.objectName())) throw new IllegalArgumentException("Duplicate physical object registration");
        }
        for (var item : map.values()) {
            if (!map.keySet().containsAll(item.definition().dependencies())) {
                throw new IllegalArgumentException("Unknown dependency: " + item.definition().datasetId());
            }
        }
        var visited = new HashSet<String>();
        for (var id : map.keySet()) visit(id, map, new HashSet<>(), visited);
        entries = Collections.unmodifiableMap(map);
    }
    private static void visit(String id, Map<String, DatasetImplementation> map, Set<String> path, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!path.add(id)) throw new IllegalArgumentException("Dataset dependency cycle: " + id);
        for (var dependency : map.get(id).definition().dependencies()) visit(dependency, map, path, visited);
        path.remove(id);
        visited.add(id);
    }
    public DatasetImplementation require(String id) {
        var entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown dataset: " + id);
        return entry;
    }
    public List<DatasetDefinition> definitions() {
        return entries.values().stream().map(DatasetImplementation::definition).toList();
    }
}
