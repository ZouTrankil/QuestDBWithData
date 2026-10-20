package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.derived.domain.RetailSentimentDailyLayout;

/** Shared configuration; a new mutable session is required for every operation. */
@FunctionalInterface
public interface RetailSentimentDailyTarget {
    interface Versioned extends RetailSentimentDailyTarget {
        RetailSentimentDailyLayout layout();

        RetailSentimentDailyTarget forLayout(RetailSentimentDailyLayout layout);
    }

    static RetailSentimentDailyLayout layoutOf(RetailSentimentDailyTarget target) {
        return target instanceof Versioned selected
                ? selected.layout()
                : RetailSentimentDailyLayout.LEGACY;
    }

    /** 原请求明确取作用域，保留部署准入，不按当前配置换表或升级旧会话。 */
    static RetailSentimentDailyTarget forFrozenLayout(
            RetailSentimentDailyTarget target, RetailSentimentDailyLayout layout) {
        if (!(target instanceof Versioned selected)) {
            if (!RetailSentimentDailyLayout.LEGACY.equals(layout))
                throw new IllegalArgumentException("旧 MV 目标只支持原作用域");
            return target;
        }
        var original = selected.forLayout(layout);
        if (!layout.equals(layoutOf(original))) throw new IllegalStateException("MV 原作用域不匹配");
        return original;
    }

    RetailSentimentDailySession newSession();
}
