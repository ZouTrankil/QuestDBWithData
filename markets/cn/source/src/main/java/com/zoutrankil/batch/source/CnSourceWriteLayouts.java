package com.zoutrankil.batch.source;

import java.util.Set;

/** Batch 独立来源的新布局；与正式 data.* 的默认版本和目标分开选择。 */
final class CnSourceWriteLayouts {
    private static final Set<String> TRADE_DATE =
            Set.of("etf_adj", "etf_daily", "etf_share", "stk_st_daily", "stk_suspend");

    private CnSourceWriteLayouts() {}

    static SourceContract bind(SourceContract source, String definitionVersion) {
        if (source.version().equals(definitionVersion)) return source;
        if (!TRADE_DATE.contains(source.dataset())
                || !source.version().equals(source.dataset() + "-v1")
                || !definitionVersion.equals(source.dataset() + "-trade-date-v2")
                || !source.timestampColumn().equals("timestamp")
                || !source.sourceDate().equals("trade_date"))
            throw new IllegalArgumentException(
                    "Unsupported CN source write definition: " + definitionVersion);
        // 来源仍按原合同归一化及留证；只改目标列，日期值不转换时区。
        var columns =
                source.columns().stream()
                        .map(
                                c ->
                                        c.target().equals("timestamp")
                                                ? new SourceContract.Column(
                                                        c.source(), "trade_date", c.type(), c.key())
                                                : c)
                        .toList();
        return new SourceContract(
                source.dataset(),
                definitionVersion,
                source.endpoint(),
                source.dateParameter(),
                source.sourceDate(),
                "trade_date",
                source.pageSize(),
                source.paged(),
                source.emptyAllowed(),
                source.maxRows(),
                source.maxBytes(),
                source.sourceDocumentation(),
                columns,
                source.partitionBy());
    }
}
