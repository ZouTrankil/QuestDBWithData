package com.zoutrankil.batch.source;

import com.zoutrankil.data.domain.temporal.MarketId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 注册 A 股及中国宏观来源；只声明规则，不发请求或创建运行记录。 */
public final class CnSourcePolicyProvider implements SourcePolicyProvider {
    @Override
    public MarketId market() {
        return MarketId.CN;
    }

    @Override
    public Map<String, SourceStrategy> strategies() {
        return SourceStrategies.contributions();
    }

    @Override
    public Set<String> monthlyDatasets() {
        return Set.of("cn_cpi", "cn_ppi", "cn_pmi", "cn_m");
    }

    @Override
    public Set<String> quarterlyDatasets() {
        return Set.of("cn_gdp");
    }

    @Override
    public Set<String> applicationJobs() {
        return Set.of(
                "main_strategy_daily", "post_close", "pre_open_acceptance", "l2_archive_integrity");
    }

    @Override
    public SourceContract bind(SourceContract contract) {
        return contract.dataset().equals("daily")
                ? DailySourceContractAdapter.bind(contract)
                : contract;
    }

    @Override
    public List<String> qualityFailures(String dataset, List<Map<String, Object>> rows) {
        return CnSourceQuality.failures(dataset, rows);
    }
}
