package com.zoutrankil.market.cn.futures.port;

import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.util.Map;

/** 单合约日期键的正式读写端口；调用方负责来源范围和恢复归属。 */
public interface FuturesDayTarget extends VerifiedBatchExecutor.Port<Map<String, Object>, String> {
    String targetId() throws Exception;
}
