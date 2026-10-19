package com.zoutrankil.data.catalog.domain;

import com.zoutrankil.data.domain.DatasetDefinition;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 数据目录的只读结果；元数据存在不代表业务数据已经就绪。 */
public final class DataCatalog {
    private DataCatalog() {}
    public enum Presence { PRESENT, MISSING }
    public enum ClassificationStatus { CLASSIFIED, UNCLASSIFIED }

    public record MaterializedView(String status, String refreshType, String baseTableName,
            Instant lastRefreshStart, Instant lastRefreshFinish, Long refreshBaseTableTxn,
            Long baseTableTxn, Long timerInterval, String timerIntervalUnit, String invalidationReason) {}
    public record PhysicalObject(String objectName, DatasetDefinition.ObjectKind objectKind,
            String designatedTimestamp, Long rowCount, Instant minTimestamp, Instant maxTimestamp,
            String viewStatus, MaterializedView materializedView) {}
    public record Snapshot(String databaseId, Instant observedAt, List<PhysicalObject> objects) {
        public Snapshot { objects = List.copyOf(objects); }
    }
    public enum DocumentationStatus { VERIFIED_SOURCE, LOCAL_RULE, UNVERIFIED }
    /** 来源说明单独保存，不能把来源类型、单位或日期格式当成本地存储规则。 */
    public record SourceField(String provider, String sourceField, String sourceType, String description,
            String unit, String sourceFormat, String documentationUrl, LocalDate reviewedOn,
            DocumentationStatus status, String notes) {}
    public record Column(String name, String storageType, Boolean nullable, String meaning,
            DatasetDefinition.TemporalContract temporal, SourceField sourceDocumentation) {
        public Column(String name, String storageType, Boolean nullable, String meaning,
                DatasetDefinition.TemporalContract temporal) {
            this(name, storageType, nullable, meaning, temporal, null);
        }
    }
    public record Entry(String objectName, String datasetId, DataObjectDescription description,
            DatasetDefinition.ObjectKind objectKind, Presence presence, ClassificationStatus classificationStatus,
            Long rowCount, Instant minTimestamp, Instant maxTimestamp, String designatedTimestamp,
            String viewStatus, MaterializedView materializedView, List<String> upstreamObjects,
            boolean readSupported) {
        public Entry { upstreamObjects = List.copyOf(upstreamObjects); }
    }
    public record Result(String databaseId, Instant observedAt, List<Entry> items) {
        public Result { items = List.copyOf(items); }
    }
    public record Page(String databaseId, Instant observedAt, int total, int page, int pageSize,
            List<Entry> items) { public Page { items = List.copyOf(items); } }
    public record Detail(String databaseId, Instant observedAt, Entry object, List<Column> columns,
            List<String> businessKey) {
        public Detail { columns = List.copyOf(columns); businessKey = List.copyOf(businessKey); }
    }
    public record TagCount(CatalogTag id, String label, long objectCount) {}
    public record Tags(String databaseId, Instant observedAt, List<TagCount> items) {
        public Tags { items = List.copyOf(items); }
    }
    public record Filter(DataObjectDescription.Market market, DataObjectDescription.SourceType sourceType,
            DatasetDefinition.ObjectKind objectKind, Set<CatalogTag> tags, String keyword,
            boolean includeMissing) {
        public Filter {
            tags = Set.copyOf(tags);
            keyword = keyword == null ? "" : keyword.strip().toLowerCase(java.util.Locale.ROOT);
            if (keyword.length() > 200) throw new IllegalArgumentException("keyword 最多 200 个字符");
        }
        public static Filter all() { return new Filter(null, null, null, Set.of(), "", false); }
    }
}
