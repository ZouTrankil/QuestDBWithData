package com.zoutrankil.data.domain;

import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

public final class IndexMembershipDataset {
    public static final int MAX_ROWS=100_000,MAX_BYTES=32*1024*1024;

    private IndexMembershipDataset() {}
    public static final DatasetDefinition DEFINITION=new DatasetDefinition(
            "index_member",1,"tushare.index_member_all","index_membership_owner","index_member",ObjectKind.TABLE,
            List.of(new Column("l2_code","index_code","index_code",StorageType.SYMBOL,false,"SW2021 industry identity",null),
                    new Column("ts_code","ts_code","ts_code",StorageType.SYMBOL,false,"Constituent stock identity",null),
                    new Column("derived:observation_instant","observed_at","update_time",StorageType.TIMESTAMP,false,
                            "Observation time, not effective date or source revision",
                            new TemporalContract(TemporalKind.INSTANT,"ISO_INSTANT","UTC","MICROS","Observation time")),
                    text("index_classify.industry_name","index_name","index_name"),
                    text("legacy:con_code","constituent_code","con_code"),text("name","constituent_name","con_name"),
                    new Column("in_date","membership_start_date","in_date",StorageType.STRING,false,"Membership period identity",
                            new TemporalContract(TemporalKind.BUSINESS_DATE,"BASIC","calendar","DAY","Membership start date")),
                    new Column("out_date","membership_end_date","out_date",StorageType.STRING,true,"Mutable exit date; endpoint inclusion unspecified",
                            new TemporalContract(TemporalKind.BUSINESS_DATE,"BASIC","calendar","DAY","Membership end date"),Set.of("None")),
                    new Column("is_new","latest_flag","is_new",StorageType.SYMBOL,false,"Explicit Y or N, not historical completeness",null),
                    new Column("legacy:weight","weight","weight",StorageType.DOUBLE,true,"Preserve existing weight; this source provides no weight or unit",null),
                    text("derived:L2","level","level"),text("l1_name","l1_name","l1_name"),
                    text("l2_name","l2_name","l2_name"),text("l3_name","l3_name","l3_name")),
            List.of("index_code","ts_code","membership_start_date"),List.of(),"update_time",Partition.YEAR,true,
            Set.of(Capability.READ,Capability.WAL_REPLACE),List.of(),
            "Audited YEAR/WAL without physical dedup. Natural membership periods include in_date, not mutable out_date. "
                    +"WAL replacement is admitted only through the isolated membership owner and its explicit receipt.");
    private static Column text(String source,String logical,String storage) {
        return new Column(source,logical,storage,StorageType.STRING,true,"Preserved membership attribute",null);
    }
}
