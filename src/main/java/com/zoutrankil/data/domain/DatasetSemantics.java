package com.zoutrankil.data.domain;

import java.util.HashSet;
import java.util.List;

/** Ordered field and business identity semantics, independent of runtime storage and collection policy. */
public record DatasetSemantics(String datasetId, int semanticVersion,
                               List<DatasetDefinition.Column> fields, List<String> businessKey,
                               String businessDateColumn) {
    public DatasetSemantics {
        DatasetDefinition.identifier(datasetId);
        if (semanticVersion < 1) throw new IllegalArgumentException("Semantic version must be positive");
        fields = List.copyOf(fields);
        businessKey = List.copyOf(businessKey);
        DatasetDefinition.identifier(businessDateColumn);
        if (fields.isEmpty() || businessKey.isEmpty())
            throw new IllegalArgumentException("Fields and business key required");
        var logicalNames = new HashSet<String>();
        var storageNames = new HashSet<String>();
        for (var field : fields) {
            if (!logicalNames.add(field.logicalName()) || !storageNames.add(field.storageName()))
                throw new IllegalArgumentException("Duplicate semantic field mapping");
            if (businessKey.contains(field.logicalName()) && field.nullable())
                throw new IllegalArgumentException("Business key field cannot be nullable");
        }
        if (new HashSet<>(businessKey).size() != businessKey.size() || !logicalNames.containsAll(businessKey))
            throw new IllegalArgumentException("Duplicate or unknown business key field");
        var dateField = fields.stream().filter(field -> field.logicalName().equals(businessDateColumn))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown business date field"));
        if (dateField.temporal() == null
                || dateField.temporal().kind() != DatasetDefinition.TemporalKind.BUSINESS_DATE)
            throw new IllegalArgumentException("Business date field requires calendar semantics");
    }

    public List<String> sourceFields() { return fields.stream().map(DatasetDefinition.Column::sourceName).toList(); }
    public List<String> logicalFields() { return fields.stream().map(DatasetDefinition.Column::logicalName).toList(); }
    public List<String> storageFields() { return fields.stream().map(DatasetDefinition.Column::storageName).toList(); }
    public DatasetDefinition.Column businessDateField() {
        return fields.stream().filter(field -> field.logicalName().equals(businessDateColumn)).findFirst().orElseThrow();
    }
}
