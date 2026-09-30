package com.zoutrankil.batch;

import java.util.*;

public final class PostCloseGraph {
    private PostCloseGraph() {}
    public record Stage(String id, List<String> dependencies, boolean critical, boolean external) {}
    public static final List<Stage> STAGES = List.of(
        new Stage("DataReady", List.of(), true, false),
        new Stage("FuturesMarket", List.of("DataReady"), false, false),
        new Stage("FactorReady", List.of("DataReady"), true, true),
        new Stage("FactorMetrics", List.of("FactorReady"), false, true),
        new Stage("StrategyPublished", List.of("FactorReady"), true, true),
        new Stage("EtfDailyReview", List.of("StrategyPublished"), false, true),
        new Stage("ExecutionComplianceReview", List.of("StrategyPublished"), false, true),
        new Stage("PendingTargetReview", List.of("StrategyPublished"), false, true));
    public static final Set<String> CORE_TABLES = Set.of("daily", "daily_basic", "stk_factor", "stk_limit",
        "stk_suspend", "stk_st_daily", "moneyflow", "moneyflow_hsgt", "margin_detail", "cn_bond_yield_curve",
        "etf_daily", "etf_adj", "etf_factor", "etf_portfolio");
}
