package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveRevisionApply;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveSync;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;

import org.junit.jupiter.api.Assertions;
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
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class BinanceArchiveRevisionTest {
    private Map<String, Object> row(String time, double price) {
        return Map.of(
                "open_time",
                time,
                "exchange",
                "BINANCE",
                "symbol",
                "BTCUSDT",
                "instrument",
                "SPOT",
                "interval_minutes",
                1440,
                "close",
                price);
    }

    @Test
    void sameKeyPriceCorrectionIsAllowedButAddedOrDeletedKeysRequireSeparateMigration() {
        var definition = BinanceArchiveDefinitions.definition(BinanceArchiveDataset.SPOT_1D);
        var old = row("2020-01-01T00:00:00Z", 100);
        var revised = row("2020-01-01T00:00:00Z", 101);
        assertDoesNotThrow(
                () ->
                        BinanceArchiveRevisionApply.requireSameKeys(
                                definition, List.of(old), List.of(revised)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        BinanceArchiveRevisionApply.requireSameKeys(
                                definition,
                                List.of(old),
                                List.of(revised, row("2020-01-02T00:00:00Z", 102))));
        assertThrows(
                IllegalStateException.class,
                () ->
                        BinanceArchiveRevisionApply.requireSameKeys(
                                definition, List.of(old), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceArchiveRevisionApply.requireSameKeys(
                                definition, List.of(old), List.of(revised, revised)));
    }

    @TempDir Path base;

    private byte[] zip(String close) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("x.csv"));
            out.write(
                    ("1577836800000,100,120,90," + close + ",2,1577923199999,220,3,1,110,0")
                            .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void correctedWindowUsesOriginalTargetAndReplayDoesNotSendAgain() throws Exception {
        var json = new ObjectMapper();
        var root = base.resolve("binance-vision");
        Files.createDirectories(root);
        Files.createDirectories(base.resolve("prepared"));
        Files.createDirectories(base.resolve("receipts"));
        String oldRun = "market-00000000-0000-0000-0000-000000000001",
                newRun = "market-00000000-0000-0000-0000-000000000002",
                target = "market-" + "a".repeat(64);
        var day = LocalDate.of(2020, 1, 1);
        var dataset = BinanceArchiveDataset.SPOT_1D;
        var definition = BinanceArchiveDefinitions.definition(dataset);
        var window = BinanceArchiveSync.plan(List.of(dataset), day, day).getFirst();
        byte[] oldZip = zip("110"), newZip = zip("111");
        String oldSha = VerifiedMarketDataStore.sha(oldZip),
                newSha = VerifiedMarketDataStore.sha(newZip);
        var oldRaw = Files.write(base.resolve("old.zip"), oldZip);
        var newRaw = Files.write(base.resolve("new.zip"), newZip);
        var observed = Instant.parse("2026-10-21T00:00:00Z");
        var oldRows = BinanceArchiveMapper.parse(dataset, oldZip, day, day, oldSha, observed);
        Files.write(
                base.resolve("prepared").resolve(oldRun + ".json"),
                json.writeValueAsBytes(
                        Map.of(
                                "definition",
                                definition,
                                "rows",
                                oldRows,
                                "raw",
                                oldRaw.toString(),
                                "sourceSha256",
                                oldSha,
                                "evidence",
                                Map.of("queueTask", window.id()))));
        Files.write(
                base.resolve("receipts").resolve(oldRun + ".json"),
                json.writeValueAsBytes(Map.of("targetId", target)));
        try (var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"));
                var q = db.createStatement()) {
            q.execute(
                    "CREATE TABLE archive_tasks(id TEXT PRIMARY KEY,dataset TEXT,url TEXT,phase TEXT,run_id TEXT,reason TEXT,updated_at TEXT)");
            try (var p =
                    db.prepareStatement(
                            "INSERT INTO archive_tasks VALUES(?,?,?,'VERIFIED',?,NULL,NULL)")) {
                p.setString(1, window.id());
                p.setString(2, dataset.id());
                p.setString(3, window.uri().toString());
                p.setString(4, oldRun);
                p.executeUpdate();
            }
        }
        var candidate =
                Map.of(
                        "status",
                        "REVISION_REQUIRES_REVIEW",
                        "task",
                        window.id(),
                        "originalRun",
                        oldRun,
                        "dataset",
                        dataset.id(),
                        "from",
                        day.toString(),
                        "through",
                        day.toString(),
                        "url",
                        window.uri().toString(),
                        "oldSha256",
                        oldSha,
                        "newSha256",
                        newSha);
        var report =
                Files.write(
                        base.resolve("check.json"),
                        json.writeValueAsBytes(Map.of("results", List.of(candidate))));
        var source = mock(BinanceArchiveSource.class);
        when(source.fetch(window.uri(), true))
                .thenReturn(
                        new BinanceArchiveSource.Archive(
                                window.uri().toString(),
                                newRaw,
                                newSha,
                                observed,
                                base.resolve("checksum"),
                                "b".repeat(64)));
        var store = mock(VerifiedMarketDataStore.class);
        when(store.publishToOriginalTarget(
                        any(), anyList(), any(), anyString(), anyMap(), eq(target)))
                .thenReturn(new SyncJobRunner.Result(newRun, SyncRunState.VERIFIED, 1, 1, null));
        var result = BinanceArchiveRevisionApply.run(root, source, store, report);
        Assertions.assertEquals("VERIFIED", result.getFirst().get("status"));
        Assertions.assertEquals(
                "SKIPPED_REQUIRES_REVIEW",
                BinanceArchiveRevisionApply.run(root, source, store, report)
                        .getFirst()
                        .get("status"));
        verify(store, times(1))
                .publishToOriginalTarget(
                        eq(definition),
                        argThat(
                                rows ->
                                        rows.size() == 1
                                                && rows.getFirst().get("close").equals(111d)),
                        any(),
                        anyString(),
                        anyMap(),
                        eq(target));
    }
}
