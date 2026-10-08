package com.zoutrankil.data.margin.port;
/** Configured target; creates fresh per-attempt writer and protocol clients. */
public interface MarginZrzTarget extends MarginZrzTables {
    String tableName();
    MarginZrzWriteSession newWriter(String frozenPhysicalTargetId);
    MarginZrzStagingPort newStaging();
    MarginZrzTables newPublicationTables();
}
