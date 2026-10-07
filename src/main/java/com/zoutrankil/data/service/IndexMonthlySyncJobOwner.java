package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.policy.IndexMonthlyUniverse;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Canonical D022 one-provider-code x bounded monthly interval job declaration. */
@org.springframework.stereotype.Service
public final class IndexMonthlySyncJobOwner implements SyncJobOwner {
    public static final int REVISION_DAYS=92;
    public static final SyncJobDefinition DEFINITION=new SyncJobDefinition(
            "data.index_monthly",1,"index_monthly",1,"index_monthly_owner",
            Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE),Mode.INCREMENTAL,
            Map.of("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "physicalTargetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "tsCode",new Parameter(ParameterType.STRING,true,10,1,Set.copyOf(IndexMonthlyUniverse.providerCodes())),
                    "observedAt",new Parameter(ParameterType.STRING,true,32,1,Set.of()),
                    "checkpointAnchor",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "checkpointBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "targetMinBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "targetMaxBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of())),
            "tushare.shared","index_monthly.month_window","questdb.full_key_values",
            new RetryPolicy(3,Duration.ofSeconds(1),Duration.ofMinutes(2)),Duration.ofHours(12),
            new Budget(IndexMonthlySource.MAX_WINDOW_DAYS,1,1,IndexMonthlySource.CLIENT_ROW_CAP,1024*1024),REVISION_DAYS,
            List.of(new JobRef("data.index",1)),Frequency.MONTHLY,ZoneId.of("Asia/Shanghai"),true,false);
    @Override public String datasetId(){return "index_monthly";}
    @Override public Set<Mode> supportedSyncModes(){return DEFINITION.supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(DEFINITION);}
}
