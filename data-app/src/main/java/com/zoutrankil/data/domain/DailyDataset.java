package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Frozen D007 storage and source-to-domain contract for `daily`. */
public final class DailyDataset {
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    private DailyDataset() {}

    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            DailySemantics.V1.datasetId(), DailySemantics.V1.semanticVersion(),
            "tushare.daily", "daily_owner", "daily", ObjectKind.TABLE,
            DailySemantics.V1.fields(), DailySemantics.V1.businessKey(), DailySemantics.V1.businessKey(),
            DailySemantics.V1.businessDateField().storageName(),
            Partition.YEAR, true, Set.of(Capability.READ, Capability.WRITE),
            List.of("exchange_calendar", "stock_detail_info"),
            "Retain the audited YEAR/WAL/dedup table and (ts_code, trade_date) UPSERT KEY. "
                    + "Same-key provider corrections replace prior values; re-fetch a finite five-calendar-day overlap. "
                    + "No source revision timestamp is available, so revisions are not separately versioned.");
}
