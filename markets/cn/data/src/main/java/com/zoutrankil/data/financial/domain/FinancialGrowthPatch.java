package com.zoutrankil.data.financial.domain;

import java.time.LocalDate;
import java.util.*;

/** Full physical before/after rows; only missing quarterly growth values may differ. */
public record FinancialGrowthPatch(Key key,Map<String,Object> before,Map<String,Object> after) {
    public record Key(String symbol,LocalDate announcement,String period) {}
    public FinancialGrowthPatch {
        Objects.requireNonNull(key);
        before=Collections.unmodifiableMap(new LinkedHashMap<>(before));
        after=Collections.unmodifiableMap(new LinkedHashMap<>(after));
    }
}
