package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveSync;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class BinanceArchiveQueueTest {
    @TempDir Path root;

    private byte[] archive() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("x.csv"));
            out.write(
                    "1577836800000,100,120,90,110,2,1577923199999,220,3,1,110,0"
                            .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void uncertainPublicationIsRetainedAndNeverAutomaticallySentAgain() throws Exception {
        var source = mock(BinanceArchiveSource.class);
        var store = mock(VerifiedMarketDataStore.class);
        var window =
                BinanceArchiveSync.plan(
                                List.of(BinanceArchiveDataset.SPOT_1D),
                                LocalDate.of(2020, 1, 1),
                                LocalDate.of(2020, 1, 1))
                        .getFirst();
        byte[] bytes = archive();
        Path raw = root.resolve("raw.zip");
        Files.write(raw, bytes);
        var response =
                new BinanceArchiveSource.Archive(
                        window.uri().toString(),
                        raw,
                        VerifiedMarketDataStore.sha(bytes),
                        Instant.parse("2026-10-20T00:00:00Z"),
                        root.resolve("checksum"),
                        "a".repeat(64));
        when(source.fetch(any(), eq(false))).thenReturn(response);
        when(store.publish(any(), anyList(), any(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("测试发送结果未决"));
        var sync = new BinanceArchiveSync(root, source, store);
        sync.run(List.of(window), false);
        sync.retrySource(List.of(window));
        sync.run(List.of(window), false);
        verify(store, times(1)).publish(any(), anyList(), any(), anyString(), anyMap());
        try (var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"));
                var q = db.createStatement();
                var rows = q.executeQuery("SELECT phase FROM archive_tasks")) {
            assertTrue(rows.next());
            assertEquals("PUBLISHING", rows.getString(1));
        }
    }

    @Test
    void sourceFailureDoesNotTouchStoreAndOtherDatasetContinues() throws Exception {
        var source = mock(BinanceArchiveSource.class);
        var store = mock(VerifiedMarketDataStore.class);
        when(source.fetch(any(), eq(false))).thenThrow(new IllegalStateException("测试来源不可用"));
        var windows =
                BinanceArchiveSync.plan(
                        List.of(BinanceArchiveDataset.SPOT_1D, BinanceArchiveDataset.PERP_1H),
                        LocalDate.of(2020, 1, 1),
                        LocalDate.of(2020, 1, 1));
        new BinanceArchiveSync(root, source, store).run(windows, false);
        verify(source, times(2)).fetch(any(), eq(false));
        verifyNoInteractions(store);
    }

    @Test
    void sourceRetryRetainsReasonAndNeverTouchesFormalLedger() throws Exception {
        var source = mock(BinanceArchiveSource.class);
        var store = mock(VerifiedMarketDataStore.class);
        var windows =
                BinanceArchiveSync.plan(
                        List.of(BinanceArchiveDataset.SPOT_1D),
                        LocalDate.of(2020, 1, 1),
                        LocalDate.of(2020, 1, 1));
        when(source.fetch(any(), eq(false))).thenThrow(new IllegalStateException("首次来源不可用"));
        var sync = new BinanceArchiveSync(root, source, store);
        sync.run(windows, true);
        byte[] bytes = archive();
        Path raw = root.resolve("raw.zip");
        Files.write(raw, bytes);
        doReturn(
                        new BinanceArchiveSource.Archive(
                                windows.getFirst().uri().toString(),
                                raw,
                                VerifiedMarketDataStore.sha(bytes),
                                Instant.parse("2026-10-20T00:00:00Z"),
                                root.resolve("checksum"),
                                "a".repeat(64)))
                .when(source)
                .fetch(any(), anyBoolean());
        sync.retrySource(windows);
        sync.run(windows, true);
        verifyNoInteractions(store);
        try (var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"));
                var q = db.createStatement();
                var rows = q.executeQuery("SELECT previous_reason FROM archive_source_retries")) {
            assertTrue(rows.next());
            assertTrue(rows.getString(1).contains("首次来源不可用"));
        }
    }
}
