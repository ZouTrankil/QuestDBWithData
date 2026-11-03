package com.zoutrankil.market.cn.observation.port;

import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 回读原请求全范围，发现消失旧键时阻断，不能用 UPSERT 冒充范围完整。 */
public interface ContractTableTarget
        extends VerifiedBatchExecutor.Port<Map<String, Object>, String> {
    String targetId() throws Exception;

    List<Map<String, Object>> readScope(String code, LocalDate date) throws Exception;
}
