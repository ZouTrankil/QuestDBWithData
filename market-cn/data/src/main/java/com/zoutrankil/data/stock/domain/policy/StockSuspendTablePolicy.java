package com.zoutrankil.data.stock.domain.policy;

import com.zoutrankil.data.domain.DatasetDefinition;

/** Public execution targets and privately owned replacement stages have separate admission rules. */
public final class StockSuspendTablePolicy {
    private StockSuspendTablePolicy() {}

    public static void requireExecutionTarget(String table) {
        DatasetDefinition.identifier(table);
        if (!"stk_suspend".equals(table) && !table.matches("(?:java_d011_stk_suspend_|stk_suspend_d011_)[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("D011 target must be exact formal stk_suspend or an explicitly isolated suspension target");
    }

    public static void requireStagingTable(String table) {
        DatasetDefinition.identifier(table);
        if (!table.matches("java_stk_suspend_stage_[0-9a-f]{32}"))
            throw new IllegalArgumentException("Owned D011 replacement stage required");
    }
}
