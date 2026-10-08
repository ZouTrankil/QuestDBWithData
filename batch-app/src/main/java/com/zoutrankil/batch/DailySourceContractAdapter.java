package com.zoutrankil.batch;

import com.zoutrankil.data.domain.DailySemantics;
import java.util.List;
import java.util.Objects;

/** Binds the daily semantic version to the existing batch contract representation. */
public final class DailySourceContractAdapter {
    private DailySourceContractAdapter() {}

    private static final List<SourceContract.Column> COLUMNS = DailySemantics.V1.fields().stream()
            .map(field -> new SourceContract.Column(field.sourceName(), field.storageName(),
                    field.storageType().name(), DailySemantics.V1.businessKey().contains(field.logicalName())))
            .toList();

    public static SourceContract bind(SourceContract loaded) {
        Objects.requireNonNull(loaded);
        var semantics = DailySemantics.V1;
        if (!semantics.datasetId().equals(loaded.dataset()))
            throw new IllegalArgumentException("Daily source contract differs from shared dataset semantics");
        String version = switch (semantics.semanticVersion()) {
            case 1 -> "daily-v1";
            default -> throw new IllegalArgumentException("Unsupported daily semantic version");
        };
        if (!version.equals(loaded.version()))
            throw new IllegalArgumentException("Daily source contract differs from shared version semantics");
        var date = semantics.businessDateField();
        if (!date.sourceName().equals(loaded.sourceDate()) || !date.storageName().equals(loaded.timestampColumn()))
            throw new IllegalArgumentException("Daily source contract differs from shared business-date semantics");
        if (!COLUMNS.equals(loaded.columns()))
            throw new IllegalArgumentException("Daily source contract differs from shared field semantics");
        return new SourceContract(loaded.dataset(), loaded.version(), loaded.endpoint(), loaded.dateParameter(),
                loaded.sourceDate(), loaded.timestampColumn(), loaded.pageSize(), loaded.paged(), loaded.emptyAllowed(),
                loaded.maxRows(), loaded.maxBytes(), loaded.sourceDocumentation(), COLUMNS, loaded.partitionBy());
    }
}
