package com.zoutrankil.data.marketdata;

import java.time.Instant;

/** 来源请求在联网前预留，结果和退避要求随后回写；接口不依赖具体数据库。 */
public interface MarketRequestBudget {
    String reserve(String provider, String account, String symbol, int maximumBytes)
            throws Exception;

    void complete(String requestId, int status, int bytes, Instant retryAfter) throws Exception;
}
