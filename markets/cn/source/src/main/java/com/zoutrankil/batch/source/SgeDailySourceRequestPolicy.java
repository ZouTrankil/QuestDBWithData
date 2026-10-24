package com.zoutrankil.batch.source;

import com.zoutrankil.data.domain.PageContract;

import java.util.Set;

/** 一次收齐一个来源交易日，不把前一夜盘改成独立自然日，也不套股票代码。 */
final class SgeDailySourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override
    public PageContract pageContract(SourceContract c) {
        return unpaged(
                c, sourceFields(c), sourceKeys(c), Set.of("trade_date"), c.sourceDocumentation());
    }
}
