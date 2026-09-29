package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** D003 physical read contract; source ingestion remains gated on an identified CSV snapshot. */
public final class IndexCatalogDataset {
    private IndexCatalogDataset() {}
    private static Column text(String source, String logical, String storage, String meaning) {
        return new Column(source,logical,storage,StorageType.STRING,true,meaning,null);
    }
    private static Column number(String source, String logical, String storage, String meaning) {
        return new Column(source,logical,storage,StorageType.DOUBLE,true,meaning,null);
    }
    private static Column date(String source, String logical, String storage, String meaning) {
        return new Column(source,logical,storage,StorageType.STRING,false,meaning,
                new TemporalContract(TemporalKind.BUSINESS_DATE,"ISO","calendar","DAY",meaning));
    }
    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "index",1,"historical.index_catalog_csv","index_catalog_owner","index",ObjectKind.TABLE,
            List.of(
                    new Column("指数代码","index_code","index_code",StorageType.SYMBOL,false,
                            "Six-character catalog code, including leading zeroes and H-prefixed identities",null),
                    text("指数简称","short_name","index_short_name","Index short name"),
                    text("指数全称","full_name","index_full_name","Index full name"),
                    date("基日","base_date","base_date","Index base business date"),
                    number("基点","base_point","base_point","Index base points"),
                    text("指数系列","series","index_series","Index family"),
                    number("样本数量","sample_count","sample_count","Sample count; physical DOUBLE"),
                    number("最新收盘","latest_close","latest_close","Catalog close; market as-of date is not declared"),
                    number("近一个月收益率","return_1m","return_1m","One-month percentage points as supplied, not a fractional return; as-of undeclared"),
                    text("资产类别","asset_class","asset_class","Asset class"),
                    text("指数热点","hotspot","index_hotspot","Index theme"),
                    text("指数币种","currency","currency","Index currency"),
                    text("合作指数","cooperation","is_cooperation","Cooperation flag as source text"),
                    text("跟踪产品","tracking_product","has_tracking_product","Tracking product flag as source text"),
                    text("指数合规","compliance_status","compliance_status","Compliance status"),
                    text("指数类别","category","index_category","Index category"),
                    date("发布时间","publish_date","publish_date","Index publication business date"),
                    new Column("derived:import_instant","import_time","import_time",StorageType.TIMESTAMP,false,
                            "One batch import observation, not an upstream revision cursor",
                            new TemporalContract(TemporalKind.INSTANT,"ISO_INSTANT","UTC","MICROS","Import observation"))),
            List.of("index_code"),List.of(),"import_time",Partition.MONTH,true,
            Set.of(Capability.READ),List.of(),
            "Current table is monthly WAL without physical dedup. Historical import inserted only new codes. "
                    + "Read rejects duplicate business identities; write admission awaits a verified source and revision policy.");
}
