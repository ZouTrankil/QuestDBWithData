package com.zoutrankil.questdbwithdata.domain;

import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** Audited current-row storage contract. Writer admission requires the static-table publication protocol. */
public final class StockDetailInfoDataset {
    private StockDetailInfoDataset() {}
    public static final DatasetDefinition DEFINITION=new DatasetDefinition(
            "stock_detail_info",1,"tushare.stock_basic","stock_detail_info_owner","stock_detail_info",ObjectKind.TABLE,
            List.of(new Column("ts_code","ts_code","ts_code",StorageType.SYMBOL,false,"Natural stock identity",null),
                    new Column("derived:observation_instant","observed_at","update_time",StorageType.TIMESTAMP,false,
                            "Local observation instant; never an upstream change or business-date cursor",
                            new TemporalContract(TemporalKind.INSTANT,"ISO_INSTANT","UTC","MICROS","Observation time")),
                    text("symbol","Stock symbol"),text("name","Stock short name"),text("market","Market category"),
                    text("exchange","Exchange identifier"),text("list_status","L listed, D delisted, P suspended"),
                    date("list_date","listing_date","Listing business date"),text("fullname","Company full name"),
                    text("enname","English name"),text("cnspell","Name pinyin abbreviation"),text("area","Area"),
                    text("industry","Provider industry classification"),text("curr_type","Currency code; no amount conversion"),
                    new Column("delist_date","delisting_date","delist_date",StorageType.STRING,true,
                            "Delisting business date; legacy literal None means absent",
                            new TemporalContract(TemporalKind.BUSINESS_DATE,"BASIC","calendar","DAY",
                                    "Delisting business date"),Set.of("None")),text("is_hs","Connect eligibility"),
                    text("act_name","Actual controller name"),text("act_ent_type","Actual controller entity type")),
            List.of("ts_code"),List.of(),null,Partition.NONE,false,
            Set.of(Capability.READ,Capability.STATIC_REPLACE),List.of(),
            "Retain static non-WAL unpartitioned current-row table; ts_code uniqueness is application-enforced. "
                    +"Writes require bounded full-row preparation and verified staging/publication; append writes are forbidden.");
    private static Column text(String name,String meaning) {
        return new Column(name,name,name,StorageType.STRING,true,meaning,null);
    }
    private static Column date(String source,String logical,String meaning) {
        return new Column(source,logical,source,StorageType.STRING,true,meaning,
                new TemporalContract(TemporalKind.BUSINESS_DATE,"BASIC","calendar","DAY",meaning));
    }
}
