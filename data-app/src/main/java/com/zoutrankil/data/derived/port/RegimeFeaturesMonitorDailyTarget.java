package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.table.RegimeFeaturesMonitorDailyRow;
/** Creates a fresh native session while leaving target admission at the owner boundary. */
public interface RegimeFeaturesMonitorDailyTarget {
    NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> writer(String table);
}
