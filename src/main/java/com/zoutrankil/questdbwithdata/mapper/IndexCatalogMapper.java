package com.zoutrankil.questdbwithdata.mapper;

import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.IndexCatalogEntry;
import com.zoutrankil.questdbwithdata.domain.table.IndexRow;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import java.time.LocalDate;

/** Explicit physical-to-business mapping for the original 18-column CSV catalog. */
public final class IndexCatalogMapper {
    public IndexCatalogEntry fromSource(com.zoutrankil.questdbwithdata.client.dto.IndexCatalogSourceRow r,java.time.Instant importedAt) {
        String code=text(r.code());
        if(code!=null && code.matches("[0-9]{1,5}")) code="0".repeat(6-code.length())+code;
        return new IndexCatalogEntry(code,text(r.shortName()),text(r.fullName()),date(r.baseDate()),number(r.basePoint()),
                text(r.series()),number(r.sampleCount()),number(r.referenceClose()),number(r.oneMonthReturnPercent()),
                text(r.assetClass()),text(r.hotspot()),text(r.currency()),text(r.cooperationFlag()),text(r.trackingProductFlag()),
                text(r.complianceStatus()),text(r.category()),date(r.publicationDate()),importedAt);
    }
    public IndexRow toStorage(IndexCatalogEntry r) {
        return new IndexRow(r.indexCode(),r.shortName(),r.fullName(),r.baseDate().toString(),r.basePoint(),r.series(),
                r.sampleCount(),r.latestClose(),r.return1m(),r.assetClass(),r.hotspot(),r.currency(),r.cooperation(),
                r.trackingProduct(),r.complianceStatus(),r.category(),r.publishDate().toString(),r.importTime());
    }
    public DatasetValues values(IndexCatalogEntry r) {
        var v=new java.util.LinkedHashMap<String,Object>();
        v.put("index_code",r.indexCode());v.put("short_name",r.shortName());v.put("full_name",r.fullName());
        v.put("base_date",r.baseDate());v.put("base_point",r.basePoint());v.put("series",r.series());
        v.put("sample_count",r.sampleCount());v.put("latest_close",r.latestClose());v.put("return_1m",r.return1m());
        v.put("asset_class",r.assetClass());v.put("hotspot",r.hotspot());v.put("currency",r.currency());
        v.put("cooperation",r.cooperation());v.put("tracking_product",r.trackingProduct());
        v.put("compliance_status",r.complianceStatus());v.put("category",r.category());
        v.put("publish_date",r.publishDate());v.put("import_time",r.importTime());return new DatasetValues(v);
    }
    private static String text(String value) { return value==null || value.strip().isEmpty()?null:value.strip(); }
    private static Double number(String value) {
        String normalized=text(value);if(normalized==null) return null;
        if(!normalized.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
            throw new IllegalArgumentException("Explicit decimal catalog number required");
        double parsed=Double.parseDouble(normalized);
        if(!Double.isFinite(parsed)) throw new IllegalArgumentException("Finite catalog number required");return parsed;
    }
    public IndexCatalogEntry fromStorage(IndexRow row) {
        return new IndexCatalogEntry(row.indexCode(),row.indexShortName(),row.indexFullName(),
                date(row.baseDate()),row.basePoint(),row.indexSeries(),row.sampleCount(),
                row.latestClose(),row.return1m(),row.assetClass(),row.indexHotspot(),
                row.currency(),row.isCooperation(),row.hasTrackingProduct(),
                row.complianceStatus(),row.indexCategory(),date(row.publishDate()),row.importTime());
    }
    public IndexCatalogEntry fromValues(DatasetValues row) {
        return new IndexCatalogEntry(row.get("index_code",String.class),row.get("short_name",String.class),
                row.get("full_name",String.class),row.get("base_date",LocalDate.class),
                row.get("base_point",Double.class),row.get("series",String.class),
                row.get("sample_count",Double.class),row.get("latest_close",Double.class),
                row.get("return_1m",Double.class),row.get("asset_class",String.class),
                row.get("hotspot",String.class),row.get("currency",String.class),
                row.get("cooperation",String.class),row.get("tracking_product",String.class),
                row.get("compliance_status",String.class),row.get("category",String.class),
                row.get("publish_date",LocalDate.class),row.get("import_time",java.time.Instant.class));
    }
    private static LocalDate date(String value) {
        return TemporalValues.businessDate(value,TemporalValues.DateFormat.ISO);
    }
}
