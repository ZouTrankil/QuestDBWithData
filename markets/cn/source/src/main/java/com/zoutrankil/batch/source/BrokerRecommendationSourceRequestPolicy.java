package com.zoutrankil.batch.source;

import com.zoutrankil.data.domain.PageContract;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Set;

/** 一次冻结一个月份的所有券商；接口没有明确分页约定，满额就拒绝完整。 */
final class BrokerRecommendationSourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override
    protected void validateLogicalDate(SourceContract c, LocalDate date) {
        if (date.getDayOfMonth() != 1) throw new IllegalArgumentException("荐股月份须用月初日期承载");
    }

    @Override
    public PageContract pageContract(SourceContract c) {
        return unpaged(c, sourceFields(c), sourceKeys(c), Set.of("month"), c.sourceDocumentation());
    }

    @Override
    protected LinkedHashMap<String, Object> baseParameters(
            SourceContract c, SourceCollector.Request r) {
        var result = new LinkedHashMap<String, Object>();
        result.put("month", r.logicalDate().format(SourceContract.BASIC_MONTH));
        return result;
    }
}
