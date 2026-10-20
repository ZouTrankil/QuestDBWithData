package com.zoutrankil.batch.source;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface SourceStoragePolicy {
    List<SourceContract.Column> businessColumns(SourceContract contract);

    String logicalDateColumn(SourceContract contract);

    int writeBatchSize(SourceContract contract);

    Map<String, Object> physicalRow(
            SourceContract contract, Map<String, Object> businessRow, Instant createdAt);

    String key(SourceContract contract, Map<String, Object> row);

    String createTableSql(SourceContract contract, String table);
}
