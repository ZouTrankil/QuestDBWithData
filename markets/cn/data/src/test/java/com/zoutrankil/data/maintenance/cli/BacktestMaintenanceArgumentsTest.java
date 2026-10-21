package com.zoutrankil.data.maintenance.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BacktestMaintenanceArgumentsTest {
    @Test
    void incompleteOrWrongMaintenanceArgumentsFailBeforeOpeningADatabase() {
        for (var args :
                new String[][] {
                    {},
                    {"refresh-backtest"},
                    {"refresh-backtest", "--original-sender-stopped"},
                    {"status", "--sources-stopped"},
                    {"plan-backtest-refresh", "extra"},
                    {"reconcile-backtest", "--run-id", "id"},
                    {"reconcile-backtest", "--run-id", "", "--original-sender-stopped"},
                    {"reconcile-backtest", "--run-id", "id", "--sources-stopped"}
                })
            assertThrows(IllegalArgumentException.class, () -> QuestDbMaintenanceCli.main(args));
    }
}
