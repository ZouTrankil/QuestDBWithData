package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.marketdata.FrozenHttpSource;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;

class BinanceArchiveSourceTest {
    @TempDir Path root;

    private FrozenHttpSource.Response response(Path path, String text, String url)
            throws Exception {
        Files.writeString(path, text);
        return new FrozenHttpSource.Response(
                path,
                VerifiedMarketDataStore.sha(Files.readAllBytes(path)),
                Instant.parse("2026-10-20T12:00:00Z"),
                url,
                text.length());
    }

    @Test
    void retainedArchiveIsReusedAndCorruptionDoesNotTriggerBlindDownload() throws Exception {
        var http = mock(FrozenHttpSource.class);
        var uri = BinanceArchiveDataset.SPOT_1D.monthly(YearMonth.of(2020, 1));
        var raw = response(root.resolve("raw.zip"), "raw bytes", uri.toString());
        var checksum =
                response(
                        root.resolve("checksum"),
                        raw.sha256() + "  BTCUSDT-1d-2020-01.zip\n",
                        uri + ".CHECKSUM");
        when(http.get(
                        eq("binance"),
                        eq("public"),
                        eq("BTCUSDT"),
                        eq(URI.create(uri + ".CHECKSUM")),
                        anyMap(),
                        anyInt()))
                .thenReturn(checksum);
        when(http.get(eq("binance"), eq("public"), eq("BTCUSDT"), eq(uri), anyMap(), anyInt()))
                .thenReturn(raw);
        var source = new BinanceArchiveSource(http, root);
        assertEquals(raw.sha256(), source.fetch(uri, false).sha256());
        assertEquals(raw.sha256(), source.fetch(uri, false).sha256());
        Files.writeString(raw.path(), "changed");
        assertThrows(IllegalStateException.class, () -> source.fetch(uri, false));
        verify(http, times(2))
                .get(anyString(), anyString(), anyString(), any(), anyMap(), anyInt());
    }

    @Test
    void checksumFilenameMustMatchRequestedArchive() throws Exception {
        var http = mock(FrozenHttpSource.class);
        var uri = BinanceArchiveDataset.SPOT_1D.monthly(YearMonth.of(2020, 1));
        var checksum =
                response(
                        root.resolve("checksum"),
                        "a".repeat(64) + "  WRONG.zip",
                        uri + ".CHECKSUM");
        when(http.get(anyString(), anyString(), anyString(), any(), anyMap(), anyInt()))
                .thenReturn(checksum);
        assertThrows(
                IllegalArgumentException.class,
                () -> new BinanceArchiveSource(http, root).fetch(uri, false));
        verify(http, times(1))
                .get(anyString(), anyString(), anyString(), any(), anyMap(), anyInt());
    }
}
