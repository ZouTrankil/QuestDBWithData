package com.zoutrankil.data.margin.port;
/** Configured target; creates fresh per-attempt writer and protocol clients. */
public interface MarginAllTarget extends MarginAllTables {
    String tableName();
    MarginAllWriteSession newWriter(String frozenPhysicalTargetId);
    MarginAllStagingPort newStaging();
    MarginAllTables newPublicationTables();
}
