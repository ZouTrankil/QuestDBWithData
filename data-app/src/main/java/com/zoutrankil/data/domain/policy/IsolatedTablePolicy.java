package com.zoutrankil.data.domain.policy;

import com.zoutrankil.data.domain.DatasetDefinition;
import java.util.function.Function;

/** Execution admission for explicitly owned tables; preserves each dataset's failure contract. */
public enum IsolatedTablePolicy {
    DAILY("java_d007_daily_",
            "D007 execution requires a dedicated java_d007_daily_<suffix> isolated target", IllegalStateException::new),
    STOCK_LIMIT("java_d010_stk_limit_",
            "D010 execution requires a dedicated java_d010_stk_limit_<suffix> isolated target", IllegalStateException::new),
    STOCK_ST_DAILY("java_d012_stk_st_daily_",
            "D012 execution requires a dedicated java_d012_stk_st_daily_<suffix> isolated target", IllegalStateException::new),
    ETF_DAILY("java_d014_etf_daily_",
            "D014 execution requires a dedicated java_d014_etf_daily_<suffix> isolated target", IllegalStateException::new),
    ETF_ADJ("java_d015_etf_adj_",
            "D015 execution requires a dedicated java_d015_etf_adj_<suffix> isolated target", IllegalStateException::new),
    INDEX_DAILY_MARKET("java_d019_index_daily_market_",
            "D019 execution requires a dedicated java_d019_index_daily_market_<suffix> target", IllegalStateException::new),
    INDEX_DAILY_BASIC("java_d020_index_daily_basic_",
            "D020 execution requires java_d020_index_daily_basic_<suffix> isolated target", IllegalStateException::new),
    INDEX_WEIGHT("java_d021_index_weight_",
            "D021 isolated table name required", IllegalArgumentException::new);

    private final String prefix;
    private final String failureMessage;
    private final Function<String, ? extends RuntimeException> failure;

    IsolatedTablePolicy(String prefix, String failureMessage,
                        Function<String, ? extends RuntimeException> failure) {
        this.prefix = prefix;
        this.failureMessage = failureMessage;
        this.failure = failure;
    }

    public String prefix() { return prefix; }

    public void require(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(prefix) || table.length() <= prefix.length()) {
            throw failure.apply(failureMessage);
        }
    }
}
