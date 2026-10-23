package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.application.BinanceQualityGapRepair;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class BinanceQualityGapRepairTest {
    @TempDir Path root;

    private Path plan(String dataset) throws Exception {
        var path = root.resolve("plan.json");
        new ObjectMapper()
                .writeValue(
                        path.toFile(),
                        Map.of(
                                "schemaVersion",
                                1,
                                "batchId",
                                "a".repeat(64),
                                "requests",
                                List.of(
                                        Map.of(
                                                "dataset",
                                                dataset,
                                                "day",
                                                "2022-04-27",
                                                "dailySha256",
                                                "a".repeat(64)))));
        return path;
    }

    @Test
    void changedArchiveNeverStartsFormalPublication() throws Exception {
        var source = mock(BinanceArchiveSource.class);
        var store = mock(VerifiedMarketDataStore.class);
        var uri = BinanceArchiveDataset.INDEX_1H.daily(LocalDate.of(2022, 4, 27));
        when(source.fetch(uri, false))
                .thenReturn(
                        new BinanceArchiveSource.Archive(
                                uri.toString(),
                                root.resolve("raw"),
                                "b".repeat(64),
                                Instant.parse("2026-10-22T00:00:00Z"),
                                root.resolve("checksum"),
                                "c".repeat(64)));
        assertThrows(
                IllegalStateException.class,
                () -> BinanceQualityGapRepair.run(root, source, store, plan("index_1h")));
        verifyNoInteractions(store);
    }

    @Test
    void existingSpotValuesAreOutsideThisRepairScope() throws Exception {
        var source = mock(BinanceArchiveSource.class);
        var store = mock(VerifiedMarketDataStore.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> BinanceQualityGapRepair.run(root, source, store, plan("spot_1h")));
        verifyNoInteractions(source, store);
    }
}
