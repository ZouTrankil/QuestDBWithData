package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Canonical bounded D028 daily job; bootstrap start is supplied explicitly by caller. */
@org.springframework.stereotype.Service
public final class MarginAllSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS=366, REVISION_DAYS=5, MAX_SOURCE_SLICES=MAX_WINDOW_DAYS;
    public static final SyncJobDefinition DEFINITION = new SyncJobDefinition(
            "data.margin_all",1,"margin_all",1,"margin_all_owner",
            Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE),Mode.INCREMENTAL,
            Map.of("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "physicalTargetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "targetRowsBefore",new Parameter(ParameterType.INTEGER,true,10,1,Set.of()),
                    "targetFingerprint",new Parameter(ParameterType.STRING,true,64,1,Set.of()),
                    "targetMinBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "targetMaxBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "checkpointAnchor",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "checkpointBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of())),
            "tushare.shared","margin.trade_date.daily","questdb.year_wal_full_row_stage_replace",
            new RetryPolicy(3,Duration.ofSeconds(2),Duration.ofMinutes(10)),Duration.ofMinutes(60),
            new Budget(MAX_WINDOW_DAYS,MAX_SOURCE_SLICES,MAX_SOURCE_SLICES,MAX_WINDOW_DAYS,1024*1024),
            REVISION_DAYS,List.of(),Frequency.DAILY, ZoneId.of("Asia/Shanghai"),true,true);
    @Override public String datasetId(){return "margin_all";}
    @Override public Set<Mode> supportedSyncModes(){return DEFINITION.supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(DEFINITION);}
}
