package com.zoutrankil.batch.source;

import com.zoutrankil.data.domain.temporal.MarketId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 市场通过此接口提供来源规则，通用引擎不反向依赖市场实现。 */
public interface SourcePolicyProvider {
    MarketId market();

    /** 发布日来源逐日请求，不套股票交易所的开市日。 */
    default boolean usesSourceDates(String dataset) {
        return false;
    }

    Map<String, SourceStrategy> strategies();

    default Set<String> monthlyDatasets() {
        return Set.of();
    }

    default Set<String> quarterlyDatasets() {
        return Set.of();
    }

    default Set<String> applicationJobs() {
        return Set.of();
    }

    default SourceContract bind(SourceContract contract) {
        return contract;
    }

    /** 写入布局由请求定义版本选择，不能随当前应用配置改变旧请求。 */
    default SourceContract writeContract(SourceContract source, String definitionVersion) {
        if (!source.version().equals(definitionVersion))
            throw new IllegalArgumentException(
                    "Unsupported source write definition: " + definitionVersion);
        return source;
    }

    List<String> qualityFailures(String dataset, List<Map<String, Object>> rows);
}
