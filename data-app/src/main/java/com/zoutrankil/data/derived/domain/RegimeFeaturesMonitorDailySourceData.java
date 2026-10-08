package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;

public final class RegimeFeaturesMonitorDailySourceData {
    private RegimeFeaturesMonitorDailySourceData() {}
    public record Margins(SortedMap<LocalDate,Double> balances,NavigableMap<LocalDate,Map<String,Long>> exchanges) {}
}
