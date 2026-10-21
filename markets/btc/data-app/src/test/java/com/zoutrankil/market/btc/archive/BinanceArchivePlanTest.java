package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.market.btc.archive.application.BinanceArchiveSync;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

class BinanceArchivePlanTest {
    @Test
    void fullMonthUsesMonthlyAndTailUsesDaysWithoutChangingFundingIdentity() {
        var from = LocalDate.of(2026, 9, 1);
        var through = LocalDate.of(2026, 10, 19);
        var tasks =
                BinanceArchiveSync.plan(
                        List.of(BinanceArchiveDataset.SPOT_1D, BinanceArchiveDataset.FUNDING),
                        from,
                        through);
        assertEquals(22, tasks.size());
        assertTrue(tasks.getFirst().uri().toString().contains("/monthly/"));
        assertTrue(tasks.get(1).uri().toString().contains("/daily/"));
        assertNotEquals(tasks.get(20).id(), tasks.getLast().id());
        var full =
                BinanceArchiveSync.plan(
                        List.of(BinanceArchiveDataset.FUNDING),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 30));
        assertEquals(1, full.size());
    }

    @Test
    void invalidRangeStopsBeforeSourceOrTargetAccess() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceArchiveSync.plan(
                                List.of(BinanceArchiveDataset.SPOT_1D),
                                LocalDate.of(2020, 1, 1),
                                LocalDate.of(2019, 12, 31)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceArchiveSync.plan(
                                List.of(BinanceArchiveDataset.SPOT_1D),
                                LocalDate.of(2019, 1, 1),
                                LocalDate.of(2020, 1, 1)));
    }
}
