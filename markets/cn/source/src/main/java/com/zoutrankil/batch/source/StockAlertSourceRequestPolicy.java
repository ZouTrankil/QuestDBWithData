package com.zoutrankil.batch.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.PageContract;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 供应商按单证券返回历史提示，先留全原文，再显式选择生效起始日。 */
final class StockAlertSourceRequestPolicy extends PerEntitySourceRequestPolicy {
    @Override
    protected void validateCardinality(SourceContract c, Set<String> codes) {
        if (codes.size() != 1) throw new IllegalArgumentException("提示来源需要一个明确证券");
    }

    @Override
    public PageContract pageContract(SourceContract c) {
        return unpaged(
                c, sourceFields(c), sourceKeys(c), Set.of("ts_code"), c.sourceDocumentation());
    }

    @Override
    protected LinkedHashMap<String, Object> baseParameters(
            SourceContract c, SourceCollector.Request r) {
        return new LinkedHashMap<>();
    }

    @Override
    public SourceCollectionSession openSession(
            SourceContract c, SourceCollector.Request r, SourceRowPolicy rows) {
        return new StandardSourceCollectionSession(c, r, rows) {
            @Override
            public List<Map<String, JsonNode>> prepareRows(
                    String code, List<Map<String, JsonNode>> raw) {
                // 原始响应达到预算时不能靠筛选变短来证明收齐；原文已由 collector 留存。
                if (raw.size() >= c.pageSize())
                    throw new IllegalArgumentException("提示原始响应达到容量预算，不能认证完整");
                for (var row : raw) {
                    if (row.get("ts_code") == null
                            || !row.get("ts_code").isTextual()
                            || !code.equals(row.get("ts_code").asText()))
                        throw new IllegalArgumentException("提示历史响应越出证券范围");
                    var start = row.get("start_date");
                    if (start == null || !start.isTextual() || !start.asText().matches("[0-9]{8}"))
                        throw new IllegalArgumentException("提示生效起始日格式错误");
                    LocalDate.parse(start.asText(), DateTimeFormatter.BASIC_ISO_DATE);
                }
                return raw.stream()
                        .filter(
                                row ->
                                        row.get("start_date")
                                                .asText()
                                                .equals(
                                                        r.logicalDate()
                                                                .format(
                                                                        DateTimeFormatter
                                                                                .BASIC_ISO_DATE)))
                        .toList();
            }
        };
    }
}
