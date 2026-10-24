package com.zoutrankil.batch.source;

import com.zoutrankil.data.domain.PageContract;

import java.util.Set;

/** 按单证券单日分页取全部价格档位；真实 offset/limit 分页已单独探测。 */
final class CyqChipsSourceRequestPolicy extends PerEntitySourceRequestPolicy {
    @Override
    protected void validateCardinality(SourceContract c, Set<String> codes) {
        if (codes.size() != 1) throw new IllegalArgumentException("筹码分布需要单个明确证券");
    }

    @Override
    public PageContract pageContract(SourceContract c) {
        return shortPage(
                c,
                sourceFields(c),
                sourceKeys(c),
                Set.of("ts_code", "trade_date", "limit", "offset"),
                PageContract.Paging.OFFSET,
                32,
                c.sourceDocumentation());
    }
}
