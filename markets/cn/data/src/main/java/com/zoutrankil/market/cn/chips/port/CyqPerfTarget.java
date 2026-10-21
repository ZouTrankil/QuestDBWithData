package com.zoutrankil.market.cn.chips.port;

import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.util.Map;

/** 正式表的物理身份及完整键值读写；建表和调度不属于这个端口。 */
public interface CyqPerfTarget extends VerifiedBatchExecutor.Port<Map<String, Object>, String> {
    String targetId() throws Exception;
}
