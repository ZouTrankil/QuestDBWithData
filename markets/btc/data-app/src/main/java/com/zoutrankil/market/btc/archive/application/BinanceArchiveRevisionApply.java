package com.zoutrankil.market.btc.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;
import com.zoutrankil.market.btc.archive.BinanceArchiveDefinitions;
import com.zoutrankil.market.btc.archive.BinanceArchiveMapper;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只应用同键修订；新增、删除事件或缺口修复先列待处理，不自动删改业务键。 */
public final class BinanceArchiveRevisionApply {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BinanceArchiveRevisionApply() {}

    public static List<Map<String, Object>> run(
            Path root, BinanceArchiveSource source, VerifiedMarketDataStore store, Path report)
            throws Exception {
        var candidates = JSON.readTree(Files.readAllBytes(report)).path("results");
        if (!candidates.isArray()) throw new IllegalArgumentException("修订报告格式不符");
        var results = new ArrayList<Map<String, Object>>();
        try (var channel =
                        FileChannel.open(
                                root.resolve("archive-queue.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("采集仍在运行，稍后再应用修订");
            try (var db =
                    DriverManager.getConnection(
                            "jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"))) {
                try (var s = db.createStatement()) {
                    s.execute("PRAGMA synchronous=FULL");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS archive_revision_runs(id TEXT PRIMARY KEY,task TEXT NOT NULL,previous_run TEXT NOT NULL,new_run TEXT,source_sha TEXT NOT NULL,bundle TEXT NOT NULL,phase TEXT NOT NULL)");
                }
                for (var candidate : candidates) {
                    if (!candidate.path("status").asText().equals("REVISION_REQUIRES_REVIEW"))
                        continue;
                    String task = candidate.path("task").asText(),
                            oldRun = candidate.path("originalRun").asText();
                    var item = new LinkedHashMap<String, Object>();
                    item.put("task", task);
                    try {
                        if (!oldRun.matches("market-[a-f0-9-]{36}"))
                            throw new IllegalArgumentException("原运行号不符");
                        var dataset =
                                BinanceArchiveDataset.parse(candidate.path("dataset").asText());
                        var from = LocalDate.parse(candidate.path("from").asText());
                        var through = LocalDate.parse(candidate.path("through").asText());
                        var windows = BinanceArchiveSync.plan(List.of(dataset), from, through);
                        if (windows.size() != 1
                                || !windows.getFirst().id().equals(task)
                                || !windows.getFirst()
                                        .uri()
                                        .toString()
                                        .equals(candidate.path("url").asText()))
                            throw new IllegalArgumentException("候选不属于唯一原归档窗口");
                        try (var q =
                                db.prepareStatement(
                                        "SELECT phase,run_id FROM archive_tasks WHERE id=?")) {
                            q.setString(1, task);
                            try (var r = q.executeQuery()) {
                                if (!r.next()
                                        || !List.of("VERIFIED", "PARTIAL_VERIFIED")
                                                .contains(r.getString(1))
                                        || !oldRun.equals(r.getString(2)))
                                    throw new IllegalStateException("原队列运行已变化或尚未验收");
                            }
                        }
                        var base = root.getParent();
                        var prepared =
                                JSON.readTree(
                                        Files.readAllBytes(
                                                base.resolve("prepared")
                                                        .resolve(oldRun + ".json")));
                        var originalDefinition =
                                JSON.treeToValue(
                                        prepared.path("definition"), DatasetDefinition.class);
                        var current = BinanceArchiveDefinitions.definition(dataset);
                        if (!originalDefinition.datasetId().equals(current.datasetId()))
                            throw new IllegalStateException("原数据集不符");
                        Instant lower = from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                                upper =
                                        through.plusDays(1)
                                                .atStartOfDay(ZoneOffset.UTC)
                                                .toInstant();
                        var originalRows = new ArrayList<Map<String, Object>>();
                        for (var row : prepared.path("rows")) {
                            Instant time =
                                    Instant.parse(row.path(current.designatedTimestamp()).asText());
                            if (!time.isBefore(lower) && time.isBefore(upper))
                                originalRows.add(JSON.convertValue(row, Map.class));
                        }
                        if (originalRows.isEmpty()
                                || originalRows.stream()
                                        .anyMatch(
                                                r ->
                                                        !candidate
                                                                .path("oldSha256")
                                                                .asText()
                                                                .equals(r.get("source_sha256"))))
                            throw new IllegalStateException("原窗口与候选 ZIP 摘要不符");
                        var archive = source.fetch(windows.getFirst().uri(), true);
                        if (!archive.sha256().equals(candidate.path("newSha256").asText())
                                || archive.sha256().equals(candidate.path("oldSha256").asText()))
                            throw new IllegalStateException("官方版本再次变化或候选并非修订，先重新探测");
                        var coverage =
                                BinanceArchiveMapper.inspect(
                                        dataset,
                                        Files.readAllBytes(archive.raw()),
                                        from,
                                        through,
                                        archive.sha256(),
                                        archive.observedAt());
                        requireSameKeys(current, originalRows, coverage.rows());
                        store.requireNoUnresolved(current.datasetId());
                        store.verifyExisting(
                                oldRun,
                                originalDefinition,
                                originalRows,
                                Path.of(prepared.path("raw").asText()),
                                prepared.path("sourceSha256").asText(),
                                prepared.path("evidence").path("queueTask").asText());
                        var receipt =
                                JSON.readTree(
                                        Files.readAllBytes(
                                                base.resolve("receipts")
                                                        .resolve(oldRun + ".json")));
                        var member = new LinkedHashMap<String, Object>();
                        member.put("task", task);
                        member.put("url", archive.url());
                        member.put("raw", archive.raw().toString());
                        member.put("sha256", archive.sha256());
                        member.put("checksum", archive.checksum().toString());
                        member.put("checksumSha256", archive.checksumSha256());
                        member.put("from", from.toString());
                        member.put("through", through.toString());
                        member.put(
                                "coverage",
                                Map.of(
                                        "complete",
                                        coverage.complete(),
                                        "missingOpenTimes",
                                        coverage.missingOpenTimes(),
                                        "shortenedBars",
                                        coverage.shortenedBars(),
                                        "excludedRows",
                                        coverage.excludedRows()));
                        byte[] bytes =
                                JSON.writeValueAsBytes(
                                        Map.of(
                                                "format",
                                                "binance.archive.bundle.v2",
                                                "dataset",
                                                current.datasetId(),
                                                "members",
                                                List.of(member)));
                        String sha = VerifiedMarketDataStore.sha(bytes);
                        Path bundle = root.resolve("bundles").resolve(sha + ".json");
                        Files.createDirectories(bundle.getParent());
                        Files.write(bundle, bytes, StandardOpenOption.CREATE_NEW);
                        String revision =
                                VerifiedMarketDataStore.sha(
                                        (oldRun + "|" + archive.sha256())
                                                .getBytes(StandardCharsets.UTF_8));
                        // 新旧运行一起留痕，发送意图与原队列在同一事务里提交。
                        db.setAutoCommit(false);
                        try {
                            try (var q =
                                    db.prepareStatement(
                                            "INSERT INTO archive_revision_runs VALUES(?,?,?,NULL,?,?,'PUBLISHING')")) {
                                q.setString(1, revision);
                                q.setString(2, task);
                                q.setString(3, oldRun);
                                q.setString(4, archive.sha256());
                                q.setString(5, bundle.toString());
                                q.executeUpdate();
                            }
                            try (var q =
                                    db.prepareStatement(
                                            "UPDATE archive_tasks SET phase='PUBLISHING',reason=? WHERE id=?")) {
                                q.setString(1, "bundle=" + bundle + "; revision=" + revision);
                                q.setString(2, task);
                                q.executeUpdate();
                            }
                            db.commit();
                        } catch (Exception e) {
                            db.rollback();
                            throw e;
                        } finally {
                            db.setAutoCommit(true);
                        }
                        var result =
                                store.publishToOriginalTarget(
                                        current,
                                        coverage.rows(),
                                        bundle,
                                        sha,
                                        Map.of(
                                                "queueTask",
                                                sha,
                                                "bundle",
                                                bundle.toString(),
                                                "previousRun",
                                                oldRun,
                                                "revision",
                                                revision),
                                        receipt.path("targetId").asText());
                        db.setAutoCommit(false);
                        try {
                            try (var q =
                                    db.prepareStatement(
                                            "UPDATE archive_tasks SET phase=?,run_id=?,reason=?,updated_at=? WHERE id=?")) {
                                q.setString(
                                        1, coverage.complete() ? "VERIFIED" : "PARTIAL_VERIFIED");
                                q.setString(2, result.runId());
                                q.setString(
                                        3,
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "bundle",
                                                        bundle.toString(),
                                                        "previousRun",
                                                        oldRun,
                                                        "revision",
                                                        revision,
                                                        "coverage",
                                                        member.get("coverage"))));
                                q.setString(4, Instant.now().toString());
                                q.setString(5, task);
                                q.executeUpdate();
                            }
                            try (var q =
                                    db.prepareStatement(
                                            "UPDATE archive_revision_runs SET phase='VERIFIED',new_run=? WHERE id=?")) {
                                q.setString(1, result.runId());
                                q.setString(2, revision);
                                q.executeUpdate();
                            }
                            db.commit();
                        } catch (Exception e) {
                            db.rollback();
                            throw e;
                        } finally {
                            db.setAutoCommit(true);
                        }
                        item.put("status", "VERIFIED");
                        item.put("runId", result.runId());
                    } catch (Exception e) {
                        item.put("status", "SKIPPED_REQUIRES_REVIEW");
                        item.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    results.add(item);
                }
            }
        }
        Path output =
                root.resolve("revision-checks").resolve("applied-" + UUID.randomUUID() + ".json");
        Files.createDirectories(output.getParent());
        Files.write(
                output,
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(
                                Map.of("observedAt", Instant.now().toString(), "results", results)),
                StandardOpenOption.CREATE_NEW);
        return results;
    }

    public static void requireSameKeys(
            DatasetDefinition definition,
            List<Map<String, Object>> original,
            List<Map<String, Object>> revised) {
        var codec = VerifiedMarketDataStore.codec(definition);
        var before = new HashSet<String>();
        var after = new HashSet<String>();
        for (var row : original)
            if (!before.add(codec.key(row))) throw new IllegalArgumentException("原窗口有重复业务键");
        for (var row : revised)
            if (!after.add(codec.key(row))) throw new IllegalArgumentException("修订窗口有重复业务键");
        if (before.isEmpty() || !before.equals(after))
            throw new IllegalStateException("修订改变了业务键集合，先跳过；不能用 UPSERT 代替补键或删除迁移");
    }
}
