package com.zoutrankil.market.btc.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;
import com.zoutrankil.market.btc.archive.BinanceArchiveDefinitions;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/** 显式恢复原冻结运行：确认原进程停止，再由原引擎全字段核对，只在指定时补缺键。 */
public final class BinanceArchiveRecovery {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BinanceArchiveRecovery() {}

    public static Map<String, Object> reconcile(
            Path root, VerifiedMarketDataStore store, String run, boolean repairMissing)
            throws Exception {
        if (!run.matches("market-[a-f0-9-]{36}")) throw new IllegalArgumentException("原运行号格式不符");
        Path base = root.getParent();
        Path prepared = base.resolve("prepared").resolve(run + ".json");
        byte[] preparedBytes = Files.readAllBytes(prepared);
        var doc = JSON.readTree(preparedBytes);
        var process = ProcessHandle.of(doc.path("writerPid").asLong(-1));
        if (process.isPresent()
                && process.get().isAlive()
                && process.get()
                        .info()
                        .startInstant()
                        .map(t -> t.toString().equals(doc.path("writerStartedAt").asText()))
                        .orElse(true)) throw new IllegalStateException("原发送进程仍在运行，先继续其他任务；不重发、不解锁");
        var definition = JSON.treeToValue(doc.path("definition"), DatasetDefinition.class);
        boolean known = false;
        for (var dataset : BinanceArchiveDataset.values())
            if (BinanceArchiveDefinitions.definition(dataset)
                    .datasetId()
                    .equals(definition.datasetId())) known = true;
        if (!known) throw new IllegalArgumentException("该运行不属于 BTC 原生归档");
        Path raw = Path.of(doc.path("raw").asText());
        String sha = doc.path("sourceSha256").asText();
        if (!VerifiedMarketDataStore.sha(Files.readAllBytes(raw)).equals(sha))
            throw new IllegalStateException("原 bundle 摘要已变化");
        var bundle = JSON.readTree(Files.readAllBytes(raw));
        if (!bundle.path("format").asText().matches("binance\\.archive\\.bundle\\.v[12]")
                || !bundle.path("dataset").asText().equals(definition.datasetId()))
            throw new IllegalStateException("只支持原归档组的恢复，不猜测其他来源");
        for (var member : bundle.path("members")) {
            if (!VerifiedMarketDataStore.sha(
                                    Files.readAllBytes(Path.of(member.path("raw").asText())))
                            .equals(member.path("sha256").asText())
                    || !VerifiedMarketDataStore.sha(
                                    Files.readAllBytes(Path.of(member.path("checksum").asText())))
                            .equals(member.path("checksumSha256").asText()))
                throw new IllegalStateException("原 ZIP 或官方 CHECKSUM 摘要变化");
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : doc.path("rows")) rows.add(JSON.convertValue(row, Map.class));
        try (var channel =
                        FileChannel.open(
                                root.resolve("archive-queue.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("其他归档采集仍在运行，稍后再恢复");
            try (var db =
                    DriverManager.getConnection(
                            "jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"))) {
                for (var member : bundle.path("members")) {
                    try (var q =
                            db.prepareStatement(
                                    "SELECT phase,run_id,reason FROM archive_tasks WHERE id=?")) {
                        q.setString(1, member.path("task").asText());
                        try (var r = q.executeQuery()) {
                            if (!r.next()
                                    || !(r.getString(1).equals("PUBLISHING")
                                                    && r.getString(3)
                                                            .contains(
                                                                    doc.path("evidence")
                                                                            .path("bundle")
                                                                            .asText())
                                            || (r.getString(1).equals("VERIFIED")
                                                            || r.getString(1)
                                                                    .equals("PARTIAL_VERIFIED"))
                                                    && run.equals(r.getString(2))))
                                throw new IllegalStateException("原队列成员或归档组已经变化");
                        }
                    }
                }
                Map<String, Object> proof;
                try {
                    // 处理核验已成功、队列回执尚未提交的崩溃窗口；只读原运行，不另开任务。
                    proof =
                            store.verifyExisting(
                                    run,
                                    definition,
                                    rows,
                                    raw,
                                    sha,
                                    doc.path("evidence").path("queueTask").asText());
                } catch (IllegalStateException unfinished) {
                    Path folder = root.resolve("recovery-evidence");
                    Files.createDirectories(folder);
                    Path stop = folder.resolve(UUID.randomUUID() + ".json");
                    Files.write(
                            stop,
                            JSON.writeValueAsBytes(
                                    Map.of(
                                            "runId",
                                            run,
                                            "writerStopped",
                                            true,
                                            "inspectedAt",
                                            Instant.now().toString(),
                                            "evidence",
                                            "本机 ProcessHandle 已确认原 PID 和启动时刻对应的发送进程不再运行")),
                            StandardOpenOption.CREATE_NEW);
                    proof =
                            store.reconcileFrozen(
                                    run,
                                    VerifiedMarketDataStore.sha(preparedBytes),
                                    stop,
                                    repairMissing);
                }
                db.setAutoCommit(false);
                try {
                    for (var member : bundle.path("members")) {
                        boolean complete =
                                !member.has("coverage")
                                        || member.path("coverage")
                                                .path("complete")
                                                .asBoolean(false);
                        try (var q =
                                db.prepareStatement(
                                        "UPDATE archive_tasks SET phase=?,run_id=?,reason=?,updated_at=? WHERE id=?")) {
                            q.setString(1, complete ? "VERIFIED" : "PARTIAL_VERIFIED");
                            q.setString(2, run);
                            q.setString(
                                    3,
                                    JSON.writeValueAsString(
                                            Map.of(
                                                    "reconciliation",
                                                    proof,
                                                    "bundle",
                                                    raw.toString(),
                                                    "coverage",
                                                    member.path("coverage"))));
                            q.setString(4, Instant.now().toString());
                            q.setString(5, member.path("task").asText());
                            if (q.executeUpdate() != 1) throw new IllegalStateException("原队列成员缺失");
                        }
                    }
                    db.commit();
                } catch (Exception e) {
                    db.rollback();
                    throw e;
                } finally {
                    db.setAutoCommit(true);
                }
                return proof;
            }
        }
    }
}
