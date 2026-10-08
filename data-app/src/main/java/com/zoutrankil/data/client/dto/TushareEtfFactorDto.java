package com.zoutrankil.data.client.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact fund_factor_pro response projection: every named numeric field is nullable and unscaled. */
public record TushareEtfFactorDto(String tsCode, String tradeDate, Map<String, Double> factors) {
    public TushareEtfFactorDto {
        if (tsCode == null || tsCode.isBlank() || tradeDate == null || tradeDate.isBlank() || factors == null)
            throw new IllegalArgumentException("fund_factor_pro identity and factor fields required");
        factors = Collections.unmodifiableMap(new LinkedHashMap<>(factors));
    }
}
