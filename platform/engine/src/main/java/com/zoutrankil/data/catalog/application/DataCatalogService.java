package com.zoutrankil.data.catalog.application;

import com.zoutrankil.data.catalog.domain.CatalogTag;
import com.zoutrankil.data.catalog.domain.DataCatalog;
import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.port.DataCatalogMetadataPort;
import com.zoutrankil.data.domain.DatasetDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 合并实库对象与业务说明；列表和导出使用同一套筛选。 */
public final class DataCatalogService {
    public static final class ObjectNotFoundException extends RuntimeException {
        public ObjectNotFoundException(String name) {
            super("未找到数据对象：" + name);
        }
    }

    private final DataCatalogMetadataPort metadata;
    private final Map<String, DataObjectDescription> descriptions;
    private final Map<String, DatasetDefinition> definitions;
    private final Map<String, String> dependencyObjects;
    private final Map<String, Map<String, DataCatalog.SourceField>> columnDocumentation;

    public DataCatalogService(
            DataCatalogMetadataPort metadata,
            Collection<DataObjectDescription> descriptions,
            Collection<DatasetDefinition> definitions) {
        this(metadata, descriptions, definitions, Map.of());
    }

    public DataCatalogService(
            DataCatalogMetadataPort metadata,
            Collection<DataObjectDescription> descriptions,
            Collection<DatasetDefinition> definitions,
            Map<String, Map<String, DataCatalog.SourceField>> columnDocumentation) {
        this.metadata = Objects.requireNonNull(metadata);
        var immutableDocumentation = new HashMap<String, Map<String, DataCatalog.SourceField>>();
        columnDocumentation.forEach(
                (name, columns) -> immutableDocumentation.put(name, Map.copyOf(columns)));
        this.columnDocumentation = Map.copyOf(immutableDocumentation);
        var byName = new TreeMap<String, DataObjectDescription>();
        for (var description : descriptions) {
            if (byName.putIfAbsent(description.objectName(), description) != null)
                throw new IllegalArgumentException("重复的数据说明：" + description.objectName());
        }
        this.descriptions = Map.copyOf(byName);
        var byObject = new HashMap<String, DatasetDefinition>();
        var byId = new HashMap<String, String>();
        for (var definition : definitions) {
            if (byObject.putIfAbsent(definition.objectName(), definition) != null
                    || byId.putIfAbsent(definition.datasetId(), definition.objectName()) != null)
                throw new IllegalArgumentException("重复的数据集登记：" + definition.objectName());
        }
        this.definitions = Map.copyOf(byObject);
        this.dependencyObjects = Map.copyOf(byId);
    }

    public DataCatalog.Result list(DataCatalog.Filter filter) {
        var snapshot = metadata.snapshot();
        var objects = new TreeMap<String, DataCatalog.PhysicalObject>();
        // 同名 MV 可能同时出现在表元数据里，原生 MV 的类型和刷新信息优先。
        for (var object : snapshot.objects())
            objects.merge(
                    object.objectName(),
                    object,
                    (left, right) ->
                            right.objectKind() == DatasetDefinition.ObjectKind.MATERIALIZED_VIEW
                                    ? right
                                    : left);
        var names = new TreeSet<>(objects.keySet());
        if (filter.includeMissing()) {
            names.addAll(descriptions.keySet());
            names.addAll(definitions.keySet());
        }
        var entries = new ArrayList<DataCatalog.Entry>();
        for (var name : names) {
            var physical = objects.get(name);
            var definition = definitions.get(name);
            var description =
                    descriptions.getOrDefault(name, DataObjectDescription.unclassified(name));
            var upstream = new LinkedHashSet<>(description.upstreamObjects());
            if (definition != null)
                definition
                        .dependencies()
                        .forEach(id -> upstream.add(dependencyObjects.getOrDefault(id, id)));
            var entry =
                    new DataCatalog.Entry(
                            name,
                            definition == null ? null : definition.datasetId(),
                            description,
                            physical != null
                                    ? physical.objectKind()
                                    : definition == null ? null : definition.objectKind(),
                            physical == null
                                    ? DataCatalog.Presence.MISSING
                                    : DataCatalog.Presence.PRESENT,
                            descriptions.containsKey(name)
                                    ? DataCatalog.ClassificationStatus.CLASSIFIED
                                    : DataCatalog.ClassificationStatus.UNCLASSIFIED,
                            physical == null ? null : physical.rowCount(),
                            physical == null ? null : physical.minTimestamp(),
                            physical == null ? null : physical.maxTimestamp(),
                            physical == null ? null : physical.designatedTimestamp(),
                            physical == null ? null : physical.viewStatus(),
                            physical == null ? null : physical.materializedView(),
                            upstream.stream().sorted().toList(),
                            physical != null
                                    && definition != null
                                    && definition
                                            .capabilities()
                                            .contains(DatasetDefinition.Capability.READ)
                                    && definition.objectKind() == physical.objectKind());
            if (matches(entry, filter)) entries.add(entry);
        }
        return new DataCatalog.Result(snapshot.databaseId(), snapshot.observedAt(), entries);
    }

    public DataCatalog.Page page(DataCatalog.Filter filter, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 500)
            throw new IllegalArgumentException("page 至少为 1，pageSize 范围为 1～500");
        var result = list(filter);
        int start = (int) Math.min(result.items().size(), (long) (page - 1) * pageSize);
        int end = Math.min(result.items().size(), start + pageSize);
        return new DataCatalog.Page(
                result.databaseId(),
                result.observedAt(),
                result.items().size(),
                page,
                pageSize,
                result.items().subList(start, end));
    }

    public DataCatalog.Detail detail(String name) {
        if (name == null || name.isBlank() || name.length() > 255)
            throw new IllegalArgumentException("对象名无效");
        var all = list(new DataCatalog.Filter(null, null, null, Set.of(), "", true));
        var entry =
                all.items().stream()
                        .filter(item -> item.objectName().equals(name))
                        .findFirst()
                        .orElseThrow(() -> new ObjectNotFoundException(name));
        var definition = definitions.get(name);
        var meanings = new HashMap<String, DatasetDefinition.Column>();
        if (definition != null)
            definition.columns().forEach(column -> meanings.put(column.storageName(), column));
        List<DataCatalog.Column> columns =
                entry.presence() == DataCatalog.Presence.MISSING
                        ? List.of()
                        : metadata.columns(name).stream()
                                .map(
                                        column -> {
                                            var contract = meanings.get(column.name());
                                            var documentation =
                                                    columnDocumentation
                                                            .getOrDefault(name, Map.of())
                                                            .get(column.name());
                                            // 本地契约优先；官方说明只补缺，不改变本地日期、类型或换算语义。
                                            String fallback =
                                                    documentation != null
                                                                    && documentation.status()
                                                                            != DataCatalog
                                                                                    .DocumentationStatus
                                                                                    .UNVERIFIED
                                                            ? documentation.description()
                                                            : "待补充";
                                            return new DataCatalog.Column(
                                                    column.name(),
                                                    column.storageType(),
                                                    column.nullable(),
                                                    contract == null
                                                            ? fallback
                                                            : contract.meaning(),
                                                    contract == null ? null : contract.temporal(),
                                                    documentation,
                                                    contract == null ? null : contract.sourceName(),
                                                    contract == null
                                                            ? null
                                                            : contract.logicalName());
                                        })
                                .toList();
        return new DataCatalog.Detail(
                all.databaseId(),
                all.observedAt(),
                entry,
                columns,
                definition == null ? List.of() : definition.businessKey());
    }

    public DataCatalog.Tags tags() {
        var all = list(DataCatalog.Filter.all());
        var counts =
                Arrays.stream(CatalogTag.values())
                        .sorted(Comparator.comparing(Enum::name))
                        .map(
                                tag ->
                                        new DataCatalog.TagCount(
                                                tag,
                                                tag.label(),
                                                all.items().stream()
                                                        .filter(
                                                                entry ->
                                                                        entry.description()
                                                                                .tags()
                                                                                .contains(tag))
                                                        .count()))
                        .toList();
        return new DataCatalog.Tags(all.databaseId(), all.observedAt(), counts);
    }

    private static boolean matches(DataCatalog.Entry entry, DataCatalog.Filter filter) {
        var description = entry.description();
        if (filter.market() != null && !description.markets().contains(filter.market()))
            return false;
        if (filter.sourceType() != null && description.sourceType() != filter.sourceType())
            return false;
        if (filter.objectKind() != null && entry.objectKind() != filter.objectKind()) return false;
        if (!description.tags().containsAll(filter.tags())) return false;
        String searchable =
                entry.objectName()
                        + " "
                        + description.displayName()
                        + " "
                        + description.description()
                        + " "
                        + description.businessGroup()
                        + " "
                        + description.provider();
        return searchable.toLowerCase(Locale.ROOT).contains(filter.keyword());
    }
}
