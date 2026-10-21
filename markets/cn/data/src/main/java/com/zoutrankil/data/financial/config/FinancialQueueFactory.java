package com.zoutrankil.data.financial.config;

import com.zoutrankil.data.financial.application.FinancialDailyQueue;
import com.zoutrankil.data.financial.storage.FinancialQueueStore;

import java.nio.file.Path;

/** CLI 和常驻入口装配同一队列位置。 */
public final class FinancialQueueFactory {
    private FinancialQueueFactory() {}

    public static FinancialDailyQueue at(Path root) {
        return new FinancialDailyQueue(new FinancialQueueStore(root));
    }

    public static FinancialDailyQueue forLedger(Path ledger) {
        Path path = ledger.toAbsolutePath().normalize();
        return at(path.resolveSibling(path.getFileName() + ".financial-daily-queue"));
    }
}
