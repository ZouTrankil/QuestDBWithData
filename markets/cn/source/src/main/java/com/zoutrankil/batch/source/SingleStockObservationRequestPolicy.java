package com.zoutrankil.batch.source;

import com.zoutrankil.data.domain.PageContract;

import java.util.Set;

/** 日期来自明确发布期次，不用交易日推断期次；单证券避免全市场响应截断。 */
final class SingleStockObservationRequestPolicy extends PerEntitySourceRequestPolicy {
    @Override
    protected void validateCardinality(SourceContract c, Set<String> codes) {
        if (codes.size() != 1) throw new IllegalArgumentException("观测来源需要单个明确证券");
    }

    @Override
    public PageContract pageContract(SourceContract c) {
        return unpaged(
                c,
                sourceFields(c),
                sourceKeys(c),
                Set.of(c.dateParameter(), "ts_code"),
                c.sourceDocumentation());
    }
}
