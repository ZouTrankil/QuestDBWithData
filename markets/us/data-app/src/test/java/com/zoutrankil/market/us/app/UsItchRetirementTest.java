package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UsItchRetirementTest {
    @Test
    void disabledFetchStopsBeforeSourceOrDatabaseWork() {
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> UsMarketDataMain.main(new String[] {"fetch-itch-prefix"}));
        assertTrue(failure.getMessage().contains("ITCH 已退出"));
    }

    @Test
    void disabledImportStopsBeforeOpeningMissingSourceOrCreatingTable() {
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                UsMarketDataMain.main(
                                        new String[] {
                                            "import-itch", "--source", "missing-archive"
                                        }));
        assertTrue(failure.getMessage().contains("ITCH 已退出"));
    }
}
