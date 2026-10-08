package com.zoutrankil.data.margin.application;

import com.zoutrankil.data.margin.domain.MarginDetailLimits;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Canonical bounded D029 daily job; one full-market source slice is one open SSE date. */
@org.springframework.stereotype.Service
public final class MarginDetailSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS=MarginDetailLimits.MAX_WINDOW_DAYS,REVISION_DAYS=MarginDetailLimits.REVISION_DAYS,MAX_SOURCE_SLICES=MarginDetailLimits.MAX_SOURCE_SLICES;
    public static final SyncJobDefinition DEFINITION=new SyncJobDefinition(
            "data.margin_detail",1,"margin_detail",1,"margin_detail_owner",
            Set.of(Mode.INCREMENTAL,Mode.BACKFILL),Mode.INCREMENTAL,
            Map.of("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "trade_dates",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "targetRowsBefore",new Parameter(ParameterType.STRING,true,20,1,Set.of()),
                    "targetMinBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "targetMaxBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "checkpointAnchor",new Parameter(ParameterType.DATE,true,10,1,Set.of()),
                    "checkpointBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of())),
            "tushare.shared","margin_detail.trade_date","questdb.full_key_values_and_receipt_backed_checkpoint",
            new RetryPolicy(3,Duration.ofSeconds(2),Duration.ofMinutes(8)),Duration.ofHours(2),
            new Budget(MAX_WINDOW_DAYS,MAX_SOURCE_SLICES,MAX_SOURCE_SLICES,MAX_WINDOW_DAYS*MarginDetailSource.API_ROW_CAP,1024*1024),
            REVISION_DAYS,List.of(new JobRef("data.exchange_calendar",1)),Frequency.DAILY,ZoneId.of("Asia/Shanghai"),true,true);
    @Override public String datasetId(){return "margin_detail";}
    @Override public Set<Mode> supportedSyncModes(){return DEFINITION.supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(DEFINITION);}
}
