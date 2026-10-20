package com.zoutrankil.data.catalog.web;

import com.zoutrankil.data.catalog.domain.DataCatalog;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 每个对象一行，保留原清单列顺序；UTF-8 BOM 便于常见表格软件识别中文。 */
public final class DataCatalogCsv {
    private DataCatalogCsv() {}

    public static byte[] encode(DataCatalog.Result result) {
        var output =
                new StringBuilder(
                        "\uFEFF市场分组,业务分组,来源分类,对象名,数据库类型,内容/用途,来源或上游,维护方式,当前行数,对象状态,备注,判断依据,标签\r\n");
        for (var entry : result.items()) {
            var description = entry.description();
            String source = description.provider();
            if (!entry.upstreamObjects().isEmpty())
                source += "；上游：" + String.join(";", entry.upstreamObjects());
            String status =
                    entry.presence() == DataCatalog.Presence.MISSING
                            ? "已不存在"
                            : entry.viewStatus() == null ? "存在" : entry.viewStatus();
            var fields =
                    Arrays.asList(
                            join(description.markets(), market -> market.label()),
                            description.businessGroup(),
                            description.sourceType().label(),
                            entry.objectName(),
                            kind(entry),
                            description.description(),
                            source,
                            description.maintenance(),
                            entry.rowCount() == null ? "" : entry.rowCount().toString(),
                            status,
                            description.notes(),
                            description.descriptionBasis(),
                            join(description.tags(), Enum::name));
            output.append(
                            fields.stream()
                                    .map(DataCatalogCsv::quote)
                                    .collect(Collectors.joining(",")))
                    .append("\r\n");
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 逐字段导出复用目录详情；未登记的逻辑映射留空，不拿物理列名冒充。 */
    public static byte[] encodeColumns(DataCatalog.Detail detail) {
        var output =
                new StringBuilder(
                        "\uFEFF对象名,物理字段,逻辑字段,来源字段,存储类型,时间角色,来源编码,日期或时区,精度,业务键,字段说明,来源单位,数值原单位,基准单位,原值到基准倍率,币种,分母口径,价格口径,数值缺失规则,数值依据\r\n");
        for (var column : detail.columns()) {
            var time = column.temporal();
            var numeric = column.numeric();
            var fields =
                    Arrays.asList(
                            detail.object().objectName(),
                            column.name(),
                            column.logicalName(),
                            column.sourceName(),
                            column.storageType(),
                            time == null ? null : time.kind(),
                            time == null ? null : time.sourceFormat(),
                            time == null ? null : time.zone(),
                            time == null ? null : time.precision(),
                            column.logicalName() == null
                                    ? "待核"
                                    : detail.businessKey().contains(column.logicalName())
                                            ? "是"
                                            : "否",
                            column.meaning(),
                            column.sourceDocumentation() == null
                                    ? null
                                    : column.sourceDocumentation().unit(),
                            numeric == null ? null : numeric.sourceUnit(),
                            numeric == null ? null : numeric.baseUnit(),
                            numeric == null ? null : numeric.multiplierToBaseUnit(),
                            numeric == null ? null : numeric.currency(),
                            numeric == null ? null : numeric.denominator(),
                            numeric == null ? null : numeric.priceBasis(),
                            numeric == null ? null : numeric.missingPolicy(),
                            numeric == null ? null : numeric.evidence());
            output.append(
                            fields.stream()
                                    .map(value -> quote(value == null ? "" : value.toString()))
                                    .collect(Collectors.joining(",")))
                    .append("\r\n");
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String kind(DataCatalog.Entry entry) {
        if (entry.objectKind() == null) return "待补充";
        return switch (entry.objectKind()) {
            case TABLE -> "表";
            case VIEW -> "视图";
            case MATERIALIZED_VIEW -> "物化视图";
        };
    }

    private static <T> String join(Collection<T> values, Function<T, String> text) {
        return values.stream().map(text).sorted().collect(Collectors.joining(";"));
    }

    private static String quote(String value) {
        return value.indexOf(',') >= 0
                        || value.indexOf('"') >= 0
                        || value.indexOf('\n') >= 0
                        || value.indexOf('\r') >= 0
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }
}
