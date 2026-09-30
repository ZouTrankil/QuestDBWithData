package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import java.time.LocalDate;
import java.util.Map;

/** Compile-only stand-in for unrelated incomplete D021 wiring; never used by D088 tests. */
public final class IndexWeightJobService {
    public record Plan(boolean notDue, LocalDate lastRefreshDate, LocalDate nextRefreshDate,
                       String targetId, SyncJobDefinition.FrozenRequest request) {}
    public String targetId() { return "compile-only"; }
    public String tableName() { return "compile-only"; }
    public Plan plan(SyncJobDefinition.Mode mode, LocalDate from, LocalDate to,
                     LocalDate logicalDate, boolean force) { return null; }
    public com.zoutrankil.questdbwithdata.service.SyncJobRunner.Result run(Plan plan) { return null; }
    public com.zoutrankil.questdbwithdata.service.SyncJobRunner.Result resume(String runId) { return null; }
    public static void requireIsolatedTableName(String table) {}
    public static void requireIsolatedTargetParameter(Map<String, Object> parameters) {}
}
