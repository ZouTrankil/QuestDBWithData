package com.zoutrankil.data.domain;

import java.math.BigDecimal;

/** 描述原值的量纲，不自动换算。未知尺度必须保留缺口，不能默认按百分数解释。 */
public record NumericContract(
        String sourceUnit,
        String baseUnit,
        BigDecimal multiplierToBaseUnit,
        String currency,
        String denominator,
        String priceBasis,
        String missingPolicy,
        String evidence) {
    public NumericContract {
        for (var text :
                new String[] {
                    sourceUnit, baseUnit, currency, denominator, priceBasis, missingPolicy, evidence
                })
            if (text == null || text.isBlank())
                throw new IllegalArgumentException("数值契约必须写明单位、币种、口径、缺失规则和依据");
        if (multiplierToBaseUnit != null && multiplierToBaseUnit.signum() <= 0)
            throw new IllegalArgumentException("数值单位倍率必须大于零");
        if ((multiplierToBaseUnit == null) != sourceUnit.equals("UNVERIFIED"))
            throw new IllegalArgumentException("未知倍率必须明确标记来源尺度待核");
    }
}
