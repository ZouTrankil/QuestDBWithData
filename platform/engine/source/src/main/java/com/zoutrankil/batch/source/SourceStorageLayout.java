package com.zoutrankil.batch.source;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 冻结来源保留原样；这里只允许为新请求改写指定日期列的物理名称。 */
public record SourceStorageLayout(SourceContract source, SourceContract target) {
    public SourceStorageLayout {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        // 类型、精度、字段顺序、键、分区和来源规则均不能借改名悄悄改变。
        var expectedColumns =
                source.columns().stream()
                        .map(
                                c ->
                                        c.target().equals(source.timestampColumn())
                                                ? new SourceContract.Column(
                                                        c.source(),
                                                        target.timestampColumn(),
                                                        c.type(),
                                                        c.key())
                                                : c)
                        .toList();
        var expected =
                new SourceContract(
                        source.dataset(),
                        target.version(),
                        source.endpoint(),
                        source.dateParameter(),
                        source.sourceDate(),
                        target.timestampColumn(),
                        source.pageSize(),
                        source.paged(),
                        source.emptyAllowed(),
                        source.maxRows(),
                        source.maxBytes(),
                        source.sourceDocumentation(),
                        expectedColumns,
                        source.partitionBy());
        if (!expected.equals(target)
                || source.version().equals(target.version()) && !source.equals(target))
            throw new IllegalArgumentException(
                    "Source storage layout changes more than a versioned date name");
    }

    public Map<String, Object> physicalRow(Map<String, Object> businessRow, Instant createdAt) {
        var physical = source.physicalRow(businessRow, createdAt);
        // 原版走原路径，保留历史 ILP、批次摘要和恢复身份。
        if (source.equals(target)) return physical;
        var result = new LinkedHashMap<String, Object>();
        for (var column : source.columns()) {
            if (!physical.containsKey(column.target()))
                throw new IllegalArgumentException(
                        "Source storage column is missing: " + column.target());
            String name =
                    column.target().equals(source.timestampColumn())
                            ? target.timestampColumn()
                            : column.target();
            result.put(name, physical.get(column.target()));
        }
        if (result.size() != physical.size())
            throw new IllegalArgumentException("Unexpected physical source columns");
        return Collections.unmodifiableMap(result);
    }
}
