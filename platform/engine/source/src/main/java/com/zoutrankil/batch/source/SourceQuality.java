package com.zoutrankil.batch.source;

import java.util.List;
import java.util.Map;

/** 质量规则由数据所属市场提供，引擎不再枚举股票或利率字段。 */
public final class SourceQuality {
    private SourceQuality() {}
    public static List<String> failures(String dataset, List<Map<String,Object>> rows) {
        return SourceStrategyRegistry.provider(dataset).qualityFailures(dataset, rows);
    }
}
