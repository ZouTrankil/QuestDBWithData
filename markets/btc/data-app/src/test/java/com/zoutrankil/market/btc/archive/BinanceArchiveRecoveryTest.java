package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveRecovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

class BinanceArchiveRecoveryTest {
    @TempDir Path base;

    @Test
    void activeOriginalWriterCannotBeReconciledOrResent() throws Exception {
        String run = "market-00000000-0000-0000-0000-000000000001";
        Files.createDirectories(base.resolve("prepared"));
        Files.write(
                base.resolve("prepared").resolve(run + ".json"),
                new ObjectMapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "writerPid",
                                        ProcessHandle.current().pid(),
                                        "writerStartedAt",
                                        ProcessHandle.current()
                                                .info()
                                                .startInstant()
                                                .orElseThrow()
                                                .toString())));
        var store = mock(VerifiedMarketDataStore.class);
        assertThrows(
                IllegalStateException.class,
                () ->
                        BinanceArchiveRecovery.reconcile(
                                base.resolve("binance-vision"), store, run, true));
        verifyNoInteractions(store);
    }
}
