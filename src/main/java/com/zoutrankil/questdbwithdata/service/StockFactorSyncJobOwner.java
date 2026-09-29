package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Registers only the stk_factor owner; all actual requests still require a frozen bounded window. */
@org.springframework.stereotype.Service
public final class StockFactorSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS=5;
    /** Re-read the checkpoint date and two calendar days before it for provider revisions. */
    public static final int REVISION_DAYS=2;
    public static final SyncJobDefinition DEFINITION=new SyncJobDefinition(
            // Separate run/checkpoint history after switching the source contract from stk_factor_pro.
            "data.stk_factor",2,"stk_factor",1,"stk_factor_owner",
            Set.of(Mode.INCREMENTAL,Mode.BACKFILL),Mode.INCREMENTAL,
            Map.of("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "tsCode",new Parameter(ParameterType.STRING,false,12,1,Set.of()),
                    "checkpointAnchor",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "checkpointBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "targetMinBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "targetMaxBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of())),
            "tushare.shared","stk_factor.daily","questdb.full_key_values",
            new RetryPolicy(3,Duration.ofSeconds(1),Duration.ofMinutes(2)),Duration.ofMinutes(10),
            new Budget(MAX_WINDOW_DAYS,MAX_WINDOW_DAYS,MAX_WINDOW_DAYS,50_000,16*1024*1024),REVISION_DAYS,List.of(),Frequency.DAILY,
            ZoneId.of("Asia/Shanghai"),true,false);
    @Override public String datasetId() { return "stk_factor"; }
    @Override public Set<Mode> supportedSyncModes() { return DEFINITION.supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(DEFINITION); }
}
