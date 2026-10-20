package com.zoutrankil.data.financial.application;

import com.zoutrankil.data.config.TushareProperties;
import java.time.Duration;
import java.util.Collection;

/** 发出第一个请求前核算整轮预算，避免先写完部分来源才发现全市场范围无法在当天完成。 */
public final class FinancialDailyBudget {
    private FinancialDailyBudget() {}

    public record Estimate(long sourceRequests, long maximumAttempts, Duration minimumDuration) {}

    public static Estimate requireFits(int codes, int periods, Collection<String> datasets,
                                      TushareProperties quota, long maximumAttempts, Duration window) {
        if(codes<1 || periods<1 || datasets.isEmpty() || maximumAttempts<1 || window.isNegative()
                || window.isZero() || window.compareTo(Duration.ofHours(12))>0
                || quota.getGlobalPerMinute()<1 || quota.getEndpointPerMinute()<1 || quota.getMaxAttempts()<1)
            throw new IllegalArgumentException("财务日批范围、请求预算和执行窗口必须有效，窗口最多 12 小时");
        long perSource=Math.multiplyExact((long)codes,periods);
        long requests=Math.multiplyExact(perSource,datasets.size());
        long attempts=Math.multiplyExact(requests,quota.getMaxAttempts());
        long millis=0;
        var limits=quota.effectiveEndpointLimits();
        for(String dataset:datasets) {
            int rate=Math.min(quota.getGlobalPerMinute(),limits.getOrDefault(dataset,quota.getEndpointPerMinute()));
            if(rate<1)throw new IllegalArgumentException("财务来源配额必须大于零："+dataset);
            millis=Math.addExact(millis,Math.multiplyExact(perSource,Math.ceilDiv(60_000L,rate)));
        }
        var estimate=new Estimate(requests,attempts,Duration.ofMillis(millis));
        // 配额只能算耗时下界；留出一半窗口给响应、重试、写入与回读，并不承诺实际完成时间。
        if(attempts>maximumAttempts || estimate.minimumDuration().compareTo(window.dividedBy(2))>0)
            throw new IllegalStateException("财务日批范围超过预算，尚未发起采集：sourceRequests="+requests
                    +", maximumAttempts="+attempts+", minimumDuration="+estimate.minimumDuration()
                    +"；请缩小批准范围或先安排有界历史回补，不能把未执行部分视为完成");
        return estimate;
    }
}
