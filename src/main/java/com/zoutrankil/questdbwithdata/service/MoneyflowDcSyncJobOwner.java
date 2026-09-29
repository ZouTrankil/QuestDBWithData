package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Canonical D026 owner. One complete, bounded source slice corresponds to one open trade date. */
@org.springframework.stereotype.Service
public final class MoneyflowDcSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS=5, REVISION_DAYS=2;
    public static final SyncJobDefinition DEFINITION=new SyncJobDefinition("data.moneyflow_dc",2,"moneyflow_dc",1,"moneyflow_dc_owner",
            Set.of(Mode.INCREMENTAL,Mode.BACKFILL),Mode.INCREMENTAL,
            Map.of("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "trade_dates",new Parameter(ParameterType.STRING,true,64,1,Set.of()),
                    "checkpointAnchor",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "checkpointBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "targetMinBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "targetMaxBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "targetRowsBefore",new Parameter(ParameterType.INTEGER,true,10,1,Set.of())),
            "tushare.shared","moneyflow_dc.trade_date","questdb.full_key_values",new RetryPolicy(3,Duration.ofSeconds(2),Duration.ofMinutes(2)),Duration.ofHours(12),
            new Budget(MAX_WINDOW_DAYS,MAX_WINDOW_DAYS,MAX_WINDOW_DAYS*MoneyflowDcSource.MAX_PAGES,MAX_WINDOW_DAYS*MoneyflowDcSource.MAX_DAILY_ROWS,1024*1024),REVISION_DAYS,
            List.of(new JobRef("data.exchange_calendar",1)),Frequency.DAILY,ZoneId.of("Asia/Shanghai"),true,true);
    @Override public String datasetId(){return "moneyflow_dc";}
    @Override public Set<Mode> supportedSyncModes(){return DEFINITION.supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(DEFINITION);}
}
