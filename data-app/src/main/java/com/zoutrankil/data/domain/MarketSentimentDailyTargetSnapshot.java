package com.zoutrankil.data.domain;

import com.zoutrankil.data.domain.table.MarketSentimentDailyRow;
import java.util.List;

/** Immutable physical target evidence returned to application callers. */
public record MarketSentimentDailyTargetSnapshot(String targetId, long tableId, String directory,
        boolean wal, List<MarketSentimentDailyRow> rows, String fingerprint) {
    public MarketSentimentDailyTargetSnapshot { rows = List.copyOf(rows); }
}
