package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveRecovery;
import com.zoutrankil.market.btc.archive.mapper.BinanceFundingMapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    @Test
    void frozenFundingApiIsReprojectedAndOriginalQueueIsCompleted() throws Exception {
        String run = "market-00000000-0000-0000-0000-000000000001";
        var json = new ObjectMapper();
        var root = base.resolve("binance-vision");
        Files.createDirectories(root);
        Files.createDirectories(base.resolve("prepared"));
        var from = LocalDate.of(2026, 10, 1);
        var through = from;
        byte[] response =
                "[{\"symbol\":\"BTCUSDT\",\"fundingTime\":1790812800000,\"fundingRate\":\"0.0001\"}]"
                        .getBytes(StandardCharsets.UTF_8);
        Path raw = root.resolve("funding.json");
        Files.write(raw, response);
        String sha = VerifiedMarketDataStore.sha(response);
        String task =
                VerifiedMarketDataStore.sha(
                        ("binance.funding.tail.v1|" + from + "|" + through)
                                .getBytes(StandardCharsets.UTF_8));
        var definition = BinanceArchiveDefinitions.definition(BinanceArchiveDataset.FUNDING);
        var rows =
                BinanceFundingMapper.map(
                        response, from, through, sha, Instant.parse("2026-10-21T00:00:00Z"));
        String url =
                "https://fapi.binance.com/fapi/v1/fundingRate?symbol=BTCUSDT&startTime="
                        + from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                        + "&endTime="
                        + (through.plusDays(1)
                                        .atStartOfDay(ZoneOffset.UTC)
                                        .toInstant()
                                        .toEpochMilli()
                                - 1)
                        + "&limit=1000";
        Files.write(
                base.resolve("prepared").resolve(run + ".json"),
                json.writeValueAsBytes(
                        Map.of(
                                "writerPid",
                                99999999L,
                                "writerStartedAt",
                                "2026-10-01T00:00:00Z",
                                "definition",
                                definition,
                                "raw",
                                raw.toString(),
                                "sourceSha256",
                                sha,
                                "rows",
                                rows,
                                "evidence",
                                Map.of(
                                        "from",
                                        from.toString(),
                                        "through",
                                        through.toString(),
                                        "sourceUrl",
                                        url,
                                        "queueTask",
                                        task,
                                        "responseLimit",
                                        1000))));
        try (var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"));
                var q = db.createStatement()) {
            q.execute(
                    "CREATE TABLE funding_api_tails(id TEXT PRIMARY KEY,from_date TEXT,through_date TEXT,phase TEXT,run_id TEXT,raw TEXT,sha TEXT,reason TEXT)");
            try (var insert =
                    db.prepareStatement(
                            "INSERT INTO funding_api_tails VALUES(?,?,?,'PUBLISHING',NULL,?,?,NULL)")) {
                insert.setString(1, task);
                insert.setString(2, from.toString());
                insert.setString(3, through.toString());
                insert.setString(4, raw.toString());
                insert.setString(5, sha);
                insert.executeUpdate();
            }
        }
        var store = mock(VerifiedMarketDataStore.class);
        Mockito.when(
                        store.verifyExisting(
                                ArgumentMatchers.eq(run),
                                ArgumentMatchers.any(),
                                ArgumentMatchers.anyList(),
                                ArgumentMatchers.eq(raw),
                                ArgumentMatchers.eq(sha),
                                ArgumentMatchers.eq(task)))
                .thenReturn(Map.of("state", "VERIFIED"));
        BinanceArchiveRecovery.reconcile(root, store, run, false);
        try (var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"));
                var q = db.createStatement();
                var r = q.executeQuery("SELECT phase,run_id FROM funding_api_tails")) {
            Assertions.assertTrue(r.next());
            Assertions.assertEquals("VERIFIED", r.getString(1));
            Assertions.assertEquals(run, r.getString(2));
        }
    }
}
