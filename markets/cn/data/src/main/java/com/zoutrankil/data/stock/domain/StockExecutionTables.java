package com.zoutrankil.data.stock.domain;

import com.zoutrankil.data.domain.policy.IsolatedTablePolicy;

/** Physical table admission; bounded execution-mode restrictions remain with each job. */
public final class StockExecutionTables {
    private StockExecutionTables() {}

    public static void requireStockLimit(String table) {
        if (!"stk_limit".equals(table)) IsolatedTablePolicy.STOCK_LIMIT.require(table);
    }

    public static void requireStockStDaily(String table) {
        if (!"stk_st_daily".equals(table)) IsolatedTablePolicy.STOCK_ST_DAILY.require(table);
    }

    public static boolean admitsStockStDaily(String table) {
        try { requireStockStDaily(table); return true; }
        catch (IllegalArgumentException | IllegalStateException invalid) { return false; }
    }
}
