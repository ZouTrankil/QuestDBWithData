package com.zoutrankil.data.catalog.domain;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 业务说明与物理统计分开维护，不把某次盘点的行数当成当前结果。 */
public record DataObjectDescription(
        String objectName,
        String displayName,
        String description,
        Set<Market> markets,
        String businessGroup,
        SourceType sourceType,
        String provider,
        List<String> upstreamObjects,
        String maintenance,
        String notes,
        String descriptionBasis,
        Set<CatalogTag> tags) {
    public enum Market {
        CN("A股及国内配套"),
        US("美股"),
        BTC("BTC"),
        SHARED("共用/跨市场");
        private final String label;

        Market(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum SourceType {
        COLLECTED("采集"),
        DERIVED("衍生视图"),
        GENERATED("运行生成"),
        UNKNOWN("待补充");
        private final String label;

        SourceType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public DataObjectDescription {
        if (objectName == null || objectName.isBlank())
            throw new IllegalArgumentException("对象名不能为空");
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(description);
        Objects.requireNonNull(sourceType);
        markets = Collections.unmodifiableSet(new TreeSet<>(markets));
        tags = Collections.unmodifiableSet(new TreeSet<>(tags));
        upstreamObjects = List.copyOf(upstreamObjects);
        Objects.requireNonNull(businessGroup);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(maintenance);
        Objects.requireNonNull(notes);
        Objects.requireNonNull(descriptionBasis);
    }

    public static DataObjectDescription unclassified(String name) {
        return new DataObjectDescription(
                name,
                name,
                "待补充",
                Set.of(),
                "待补充",
                SourceType.UNKNOWN,
                "待补充",
                List.of(),
                "待补充",
                "",
                "实库发现，尚未登记业务说明",
                Set.of());
    }
}
