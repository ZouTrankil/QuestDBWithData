package com.zoutrankil.data.domain;

import com.zoutrankil.data.domain.table.RegimeFeaturesMonitorDailyRow;
import java.util.List;

/** Immutable physical target evidence returned to application callers. */
public record RegimeFeaturesMonitorDailyTargetSnapshot(String targetId, long tableId, String directory,
        boolean wal, List<RegimeFeaturesMonitorDailyRow> rows, String fingerprint) {
    public RegimeFeaturesMonitorDailyTargetSnapshot { rows = List.copyOf(rows); }
}
