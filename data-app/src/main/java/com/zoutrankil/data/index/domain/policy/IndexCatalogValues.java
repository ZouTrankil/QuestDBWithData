package com.zoutrankil.data.index.domain.policy;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.*;

/** Pure business projection and physical row decoding shared with the mapper. */
public final class IndexCatalogValues {
    private IndexCatalogValues() {}
    public static DatasetValues values(IndexCatalogEntry r) {
        var v=new java.util.LinkedHashMap<String,Object>();
        v.put("index_code",r.indexCode());v.put("short_name",r.shortName());v.put("full_name",r.fullName());
        v.put("base_date",r.baseDate());v.put("base_point",r.basePoint());v.put("series",r.series());
        v.put("sample_count",r.sampleCount());v.put("latest_close",r.latestClose());v.put("return_1m",r.return1m());
        v.put("asset_class",r.assetClass());v.put("hotspot",r.hotspot());v.put("currency",r.currency());
        v.put("cooperation",r.cooperation());v.put("tracking_product",r.trackingProduct());
        v.put("compliance_status",r.complianceStatus());v.put("category",r.category());
        v.put("publish_date",r.publishDate());v.put("import_time",r.importTime());return new DatasetValues(v);
    }
    public static IndexCatalogEntry fromStorage(IndexRow row) {
        return new IndexCatalogEntry(row.indexCode(),row.indexShortName(),row.indexFullName(),
                date(row.baseDate()),row.basePoint(),row.indexSeries(),row.sampleCount(),
                row.latestClose(),row.return1m(),row.assetClass(),row.indexHotspot(),
                row.currency(),row.isCooperation(),row.hasTrackingProduct(),
                row.complianceStatus(),row.indexCategory(),date(row.publishDate()),row.importTime());
    }
    private static LocalDate date(String value) {
        return TemporalValues.businessDate(value,TemporalValues.DateFormat.ISO);
    }
}
