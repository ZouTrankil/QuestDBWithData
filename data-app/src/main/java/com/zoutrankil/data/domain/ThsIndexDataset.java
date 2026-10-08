package com.zoutrankil.data.domain;

import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** Audited physical schema; no executable owner or writer is admitted by this definition alone. */
public final class ThsIndexDataset {
    private ThsIndexDataset() {}
    public static final DatasetDefinition DEFINITION=new DatasetDefinition(
            "ths_index",1,"tushare.ths_index","ths_index_owner","ths_index",ObjectKind.TABLE,
            List.of(new Column("ts_code","ts_code","ts_code",StorageType.SYMBOL,false,"Natural directory identity; suffix letters retained",null),
                    new Column("name","name","name",StorageType.STRING,true,"Index display name",null),
                    new Column("count","member_count","count",StorageType.INT,true,"Nonnegative number of constituents",null),
                    new Column("exchange","exchange","exchange",StorageType.STRING,true,"Provider market A, HK or US",null),
                    new Column("list_date","listing_date","list_date",StorageType.STRING,true,"Listing business date",
                            new TemporalContract(TemporalKind.BUSINESS_DATE,"BASIC","calendar","DAY","Listing date")),
                    new Column("type","index_type","type",StorageType.STRING,true,"N, I, R, S, ST, TH or BB",null),
                    new Column("derived:observation_instant","observed_at","update_time",StorageType.TIMESTAMP,false,
                            "UTC observation instant; never an upstream change cursor",
                            new TemporalContract(TemporalKind.INSTANT,"ISO_INSTANT","UTC","MICROS","Observation time"))),
            List.of("ts_code"),List.of("ts_code","update_time"),"update_time",Partition.MONTH,true,
            Set.of(Capability.READ,Capability.WAL_REPLACE),List.of(),
            "Retain monthly WAL and audited physical dedup key. Natural identity is ts_code; fresh observation timestamps "
                    +"do not update older rows by themselves. Current-row publication must preserve natural uniqueness.");
}
