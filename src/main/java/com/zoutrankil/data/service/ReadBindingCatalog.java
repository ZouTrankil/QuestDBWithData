package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetValues;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Explicit read representations, bound to the definitions enabled in this application. */
public final class ReadBindingCatalog {
    private static final Supplier<String> UNVERSIONED = () -> null;

    public record Registration<T>(String datasetId, int schemaVersion, Class<T> rowType,
                                  Function<DatasetValues, T> mapper, Supplier<String> sourceVersion) {
        public Registration {
            DatasetDefinition.identifier(datasetId);
            if (schemaVersion < 1) throw new IllegalArgumentException("Read registration version must be positive");
            Objects.requireNonNull(rowType);
            if (rowType.isPrimitive() || rowType == Void.class)
                throw new IllegalArgumentException("Read registration requires a reference row type");
            Objects.requireNonNull(mapper);
            Objects.requireNonNull(sourceVersion);
        }

        private ReadGroupReader.Binding<T> bind(DatasetDefinition definition) {
            return new ReadGroupReader.Binding<>(definition, rowType, mapper, sourceVersion);
        }
    }

    private final Map<String, Registration<?>> byDataset;
    private final List<Registration<?>> registrations;

    public ReadBindingCatalog(Collection<? extends Registration<?>> registrations) {
        var found = new LinkedHashMap<String, Registration<?>>();
        for (var registration : Objects.requireNonNull(registrations)) {
            Objects.requireNonNull(registration);
            if (found.putIfAbsent(registration.datasetId(), registration) != null)
                throw new IllegalArgumentException("Duplicate read registration: " + registration.datasetId());
        }
        byDataset = Collections.unmodifiableMap(found);
        this.registrations = List.copyOf(found.values());
    }

    public static <T> Registration<T> typed(String datasetId, int schemaVersion, Class<T> rowType,
                                          Function<DatasetValues, T> mapper) {
        return typed(datasetId, schemaVersion, rowType, mapper, UNVERSIONED);
    }

    public static <T> Registration<T> typed(String datasetId, int schemaVersion, Class<T> rowType,
                                          Function<DatasetValues, T> mapper, Supplier<String> sourceVersion) {
        if (rowType == DatasetValues.class)
            throw new IllegalArgumentException("DatasetValues requires an explicit generic read registration");
        return new Registration<>(datasetId, schemaVersion, rowType, mapper, sourceVersion);
    }

    public static Registration<DatasetValues> generic(String datasetId, int schemaVersion) {
        return generic(datasetId, schemaVersion, UNVERSIONED);
    }

    public static Registration<DatasetValues> generic(String datasetId, int schemaVersion,
                                                      Supplier<String> sourceVersion) {
        return new Registration<>(datasetId, schemaVersion, DatasetValues.class, Function.identity(), sourceVersion);
    }

    public List<Registration<?>> registrations() { return registrations; }

    /** Partial registries are supported; every enabled READ definition still needs an explicit representation. */
    public List<ReadGroupReader.Binding<?>> bind(DatasetRegistry datasets) {
        var bindings = new ArrayList<ReadGroupReader.Binding<?>>();
        for (var definition : Objects.requireNonNull(datasets).definitions()) {
            var registration = byDataset.get(definition.datasetId());
            if (registration == null) {
                if (definition.capabilities().contains(DatasetDefinition.Capability.READ))
                    throw new IllegalArgumentException("Missing read registration: " + definition.datasetId());
                continue;
            }
            definition.requireCapability(DatasetDefinition.Capability.READ);
            if (registration.schemaVersion() != definition.schemaVersion())
                throw new IllegalArgumentException("Read registration version differs from registered definition: "
                        + definition.datasetId());
            bindings.add(registration.bind(definition));
        }
        return List.copyOf(bindings);
    }
}
