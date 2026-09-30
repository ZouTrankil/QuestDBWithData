package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncJobOwner;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** D031 is manually callable for bounded historical recovery; retired source is excluded from daily scheduling. */
@org.springframework.stereotype.Service
public class MarginZrzSyncJobOwner implements SyncJobOwner {
    public static final int MAX_WINDOW_DAYS=366,REVISION_DAYS=5,MAX_SOURCE_SLICES=1;
    public static final SyncJobDefinition DEFINITION=new SyncJobDefinition(
            "data.margin_zrz",1,"margin_zrz",1,"margin_zrz_owner",
            Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE),Mode.INCREMENTAL,
            Map.of("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "physicalTargetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "targetRowsBefore",new Parameter(ParameterType.INTEGER,true,10,1,Set.of()),
                    "targetFingerprint",new Parameter(ParameterType.STRING,true,64,1,Set.of()),
                    "targetMinBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "targetMaxBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "checkpointAnchor",new Parameter(ParameterType.DATE,false,10,1,Set.of()),
                    "checkpointBefore",new Parameter(ParameterType.DATE,false,10,1,Set.of())),
            "tushare.shared","slb_len.start_end.bounded_range","questdb.year_wal_full_row_stage_replace",
            new RetryPolicy(3,Duration.ofSeconds(2),Duration.ofMinutes(10)),Duration.ofMinutes(60),
            new Budget(MAX_WINDOW_DAYS,MAX_SOURCE_SLICES,1,MarginZrzSource.API_ROW_CAP,1024*1024),
            REVISION_DAYS,List.of(),Frequency.MANUAL,ZoneId.of("Asia/Shanghai"),false,false);
    @Override public String datasetId(){return "margin_zrz";}
    @Override public Set<Mode> supportedSyncModes(){return DEFINITION.supportedModes();}
    @Override public List<SyncJobDefinition> syncJobDefinitions(){return List.of(DEFINITION);}
}
