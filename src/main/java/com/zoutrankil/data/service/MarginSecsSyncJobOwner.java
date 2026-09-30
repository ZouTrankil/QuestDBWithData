package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Canonical bounded daily owner for D030. */
@org.springframework.stereotype.Service
public final class MarginSecsSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS=31,MAX_SOURCE_SLICES=31,MAX_RUN_ROWS=MAX_SOURCE_SLICES*MarginSecsSource.API_ROW_CAP,REVISION_DAYS=5;
    public static final SyncJobDefinition DEFINITION=new SyncJobDefinition(
            "data.margin_secs",1,"margin_secs",1,"margin_secs_owner",
            Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE),Mode.INCREMENTAL,
            Map.ofEntries(Map.entry("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of())),
                    Map.entry("physicalTargetId",new Parameter(ParameterType.STRING,true,128,1,Set.of())),
                    Map.entry("targetRowsBefore",new Parameter(ParameterType.INTEGER,true,10,1,Set.of())),
                    Map.entry("targetFingerprint",new Parameter(ParameterType.STRING,true,64,1,Set.of())),
                    Map.entry("targetMinBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of())),
                    Map.entry("targetMaxBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of())),
                    Map.entry("checkpointAnchor",new Parameter(ParameterType.DATE,false,10,1,Set.of())),
                    Map.entry("checkpointBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of())),
                    Map.entry("calendarFingerprint",new Parameter(ParameterType.STRING,true,64,1,Set.of())),
                    Map.entry("calendarDays",new Parameter(ParameterType.STRING,true,400,1,Set.of())),
                    Map.entry("tradeDates",new Parameter(ParameterType.STRING,true,310,1,Set.of()))),
            "tushare.shared","margin.trade_date.daily","questdb.year_wal_dedup",
            new RetryPolicy(3,Duration.ofSeconds(2),Duration.ofMinutes(10)),Duration.ofMinutes(45),
            new Budget(MAX_WINDOW_DAYS,MAX_SOURCE_SLICES,MAX_SOURCE_SLICES,MAX_RUN_ROWS,1024*1024),
            REVISION_DAYS,List.of(),Frequency.DAILY,ZoneId.of("Asia/Shanghai"),true,true);
    @Override public String datasetId(){return "margin_secs";}
    @Override public Set<Mode> supportedSyncModes(){return DEFINITION.supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(DEFINITION);}
}
