package com.zoutrankil.data.index.port;

public interface IndexMonthlyTarget {
    String tableName();

    String targetId();

    String physicalTargetId();

    IndexMonthlyWriteSession newWriter(String physicalId);

    IndexMonthlyTables newPublicationTables();

    IndexMonthlyStagingPort newStaging();
}
