package com.zoutrankil.market.btc.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 修订探测只刷新官方文件，不重发正式行情；新旧证据都保留，差异单独列出。 */
public final class BinanceArchiveRevisionCheck {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BinanceArchiveRevisionCheck() {}

    public static Path run(
            Path root, BinanceArchiveSource source, List<BinanceArchiveSync.Window> windows)
            throws Exception {
        var results = new ArrayList<Object>();
        Path queue = root.resolve("archive-queue.sqlite3");
        if (!Files.isRegularFile(queue)) throw new IllegalStateException("尚无原生归档队列");
        try (var db =
                DriverManager.getConnection(
                        "jdbc:sqlite:file:" + queue.toAbsolutePath() + "?mode=ro")) {
            for (var window : windows) {
                var item = new LinkedHashMap<String, Object>();
                item.put("task", window.id());
                item.put("dataset", window.dataset().id());
                item.put("from", window.from().toString());
                item.put("through", window.through().toString());
                item.put("url", window.uri().toString());
                try {
                    String phase, run;
                    try (var q =
                            db.prepareStatement(
                                    "SELECT phase,run_id FROM archive_tasks WHERE id=?")) {
                        q.setString(1, window.id());
                        try (var rows = q.executeQuery()) {
                            if (!rows.next()) {
                                item.put("status", "NOT_PLANNED");
                                results.add(item);
                                continue;
                            }
                            phase = rows.getString(1);
                            run = rows.getString(2);
                        }
                    }
                    if (!List.of("VERIFIED", "PARTIAL_VERIFIED").contains(phase)) {
                        item.put("status", "SKIPPED_" + phase);
                        results.add(item);
                        continue;
                    }
                    if (run == null || !run.matches("market-[a-f0-9-]{36}"))
                        throw new IllegalStateException("原正式运行号不符");
                    Path prepared = root.getParent().resolve("prepared").resolve(run + ".json");
                    var doc = JSON.readTree(Files.readAllBytes(prepared));
                    var receipt =
                            JSON.readTree(
                                    Files.readAllBytes(
                                            root.getParent()
                                                    .resolve("receipts")
                                                    .resolve(run + ".json")));
                    if (!VerifiedMarketDataStore.sha(Files.readAllBytes(prepared))
                            .equals(receipt.path("preparedSha256").asText()))
                        throw new IllegalStateException("原 prepared 与回执不一致");
                    Instant from = window.from().atStartOfDay(ZoneOffset.UTC).toInstant();
                    Instant until =
                            window.through().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                    var hashes = new HashSet<String>();
                    String key =
                            window.dataset() == BinanceArchiveDataset.FUNDING
                                    ? "funding_time"
                                    : "open_time";
                    for (var row : doc.path("rows")) {
                        var time = Instant.parse(row.path(key).asText());
                        if (!time.isBefore(from) && time.isBefore(until))
                            hashes.add(row.path("source_sha256").asText());
                    }
                    if (hashes.size() != 1 || !hashes.iterator().next().matches("[a-f0-9]{64}"))
                        throw new IllegalStateException("原窗口未绑定唯一 ZIP 摘要");
                    String old = hashes.iterator().next();
                    var archive = source.fetch(window.uri(), true);
                    item.put("originalRun", run);
                    item.put("oldSha256", old);
                    item.put("newSha256", archive.sha256());
                    item.put("newRaw", archive.raw().toString());
                    item.put(
                            "status",
                            old.equals(archive.sha256())
                                    ? "UNCHANGED"
                                    : "REVISION_REQUIRES_REVIEW");
                } catch (Exception e) {
                    item.put("status", "SKIPPED_SOURCE");
                    item.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                results.add(item);
                System.out.println(JSON.writeValueAsString(item));
            }
        }
        Path folder = root.resolve("revision-checks");
        Files.createDirectories(folder);
        Path report = folder.resolve(UUID.randomUUID() + ".json");
        Files.write(
                report,
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(
                                Map.of(
                                        "observedAt",
                                        Instant.now().toString(),
                                        "results",
                                        results,
                                        "dataVerified",
                                        false)),
                StandardOpenOption.CREATE_NEW);
        return report;
    }
}
