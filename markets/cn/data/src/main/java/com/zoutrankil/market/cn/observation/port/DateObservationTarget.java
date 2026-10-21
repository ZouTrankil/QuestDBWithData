package com.zoutrankil.market.cn.observation.port;

import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.util.Map;

/** 只有一个日期键的数值观测表；范围和空源规则由各来源 owner 负责。 */
public interface DateObservationTarget
        extends VerifiedBatchExecutor.Port<Map<String, Object>, String> {
    String targetId() throws Exception;
}
