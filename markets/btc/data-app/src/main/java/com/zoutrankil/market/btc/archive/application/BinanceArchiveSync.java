package com.zoutrankil.market.btc.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;
import com.zoutrankil.market.btc.archive.BinanceArchiveDefinitions;
import com.zoutrankil.market.btc.archive.BinanceArchiveMapper;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;

import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每个归档独立推进；来源失败跳过，正式未决只挡住原数据集，绝不自动重发。 */
public final class BinanceArchiveSync {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;
    private final BinanceArchiveSource source;
    private final VerifiedMarketDataStore store;

    public record Window(
            BinanceArchiveDataset dataset, URI uri, LocalDate from, LocalDate through) {
        public String id() {
            return VerifiedMarketDataStore.sha(
                    ("binance.archive.v1|" + dataset.id() + "|" + uri + "|" + from + "|" + through)
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    public BinanceArchiveSync(
            Path root, BinanceArchiveSource source, VerifiedMarketDataStore store) {
        this.root = root;
        this.source = source;
        this.store = store;
    }

    /** 整月选月包；不足整月才选日包。资金费率缺日包能力时明确列出未覆盖尾部。 */
    public static List<Window> plan(
            List<BinanceArchiveDataset> datasets, LocalDate from, LocalDate through) {
        if (through.isBefore(from)
                || !through.isBefore(LocalDate.now(ZoneOffset.UTC))
                || from.isBefore(LocalDate.of(2020, 1, 1)))
            throw new IllegalArgumentException("只接收 2020 年起、已完成的 UTC 日期范围");
        var result = new ArrayList<Window>();
        for (var dataset : datasets) {
            for (var month = YearMonth.from(from);
                    !month.isAfter(YearMonth.from(through));
                    month = month.plusMonths(1)) {
                var first = from.isAfter(month.atDay(1)) ? from : month.atDay(1);
                var last = through.isBefore(month.atEndOfMonth()) ? through : month.atEndOfMonth();
                if (first.equals(month.atDay(1)) && last.equals(month.atEndOfMonth()))
                    result.add(new Window(dataset, dataset.monthly(month), first, last));
                else if (dataset == BinanceArchiveDataset.FUNDING) {
                    // 只列出待补范围；不取未结束的整月，也不假设日包和固定结算频率。
                    result.add(new Window(dataset, dataset.monthly(month), first, last));
                } else
                    for (var day = first; !day.isAfter(last); day = day.plusDays(1))
                        result.add(new Window(dataset, dataset.daily(day), day, day));
            }
        }
        return List.copyOf(result);
    }

    public void run(List<Window> windows, boolean downloadOnly) throws Exception {
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("archive-queue.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("已有 Binance 原生归档采集持有队列");
            try (var db =
                    DriverManager.getConnection(
                            "jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"))) {
                try (var s = db.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=FULL");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS archive_tasks(id TEXT PRIMARY KEY,dataset TEXT NOT NULL,url TEXT NOT NULL,phase TEXT NOT NULL,run_id TEXT,reason TEXT,updated_at TEXT NOT NULL)");
                }
                // 一次登记完整计划，状态页不会把尚未走到的归档误当成不存在。
                db.setAutoCommit(false);
                try {
                    for (var window : windows) {
                        try (var q =
                                db.prepareStatement(
                                        "INSERT OR IGNORE INTO archive_tasks VALUES(?,?,?,'PLANNED',NULL,NULL,?)")) {
                            q.setString(1, window.id());
                            q.setString(2, window.dataset().id());
                            q.setString(3, window.uri().toString());
                            q.setString(4, Instant.now().toString());
                            q.executeUpdate();
                        }
                    }
                    db.commit();
                } catch (Exception e) {
                    db.rollback();
                    throw e;
                } finally {
                    db.setAutoCommit(true);
                }
                // 按年合并正式发布，减少远端往返；每个文件仍独立校验、记录缺项。
                var groups = new LinkedHashMap<String, List<Window>>();
                for (var window : windows)
                    groups.computeIfAbsent(
                                    window.dataset().id() + "|" + window.from().getYear(),
                                    key -> new ArrayList<>())
                            .add(window);
                for (var group : groups.values()) {
                    if (Files.exists(root.resolve("stop.request"))) break;
                    processGroup(db, group, downloadOnly);
                }
                var status = new ArrayList<Object>();
                try (var s = db.createStatement();
                        var rows =
                                s.executeQuery(
                                        "SELECT dataset,phase,count(*) FROM archive_tasks GROUP BY dataset,phase ORDER BY dataset,phase")) {
                    while (rows.next())
                        status.add(
                                Map.of(
                                        "dataset",
                                        rows.getString(1),
                                        "phase",
                                        rows.getString(2),
                                        "tasks",
                                        rows.getLong(3)));
                }
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValue(
                                root.resolve("sync-status.json").toFile(),
                                Map.of(
                                        "observedAt",
                                        Instant.now().toString(),
                                        "tasks",
                                        status,
                                        "dataVerified",
                                        false));
            }
        }
    }

    public void retrySource(List<Window> windows) throws Exception {
        if (!Files.isRegularFile(root.resolve("archive-queue.sqlite3"))) return;
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("archive-queue.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("采集仍在运行，稍后再重试来源");
            try (var db =
                    DriverManager.getConnection(
                            "jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"))) {
                try (var s = db.createStatement()) {
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS archive_source_retries(task TEXT NOT NULL,previous_reason TEXT,requested_at TEXT NOT NULL)");
                }
                for (var window : windows) {
                    db.setAutoCommit(false);
                    try {
                        try (var q =
                                db.prepareStatement(
                                        "INSERT INTO archive_source_retries SELECT id,reason,? FROM archive_tasks WHERE id=? AND phase='SKIPPED_SOURCE' AND run_id IS NULL")) {
                            q.setString(1, Instant.now().toString());
                            q.setString(2, window.id());
                            if (q.executeUpdate() == 1) {
                                try (var u =
                                        db.prepareStatement(
                                                "UPDATE archive_tasks SET phase='RETRY_SOURCE',reason='显式重试来源，原原因在 archive_source_retries',updated_at=? WHERE id=? AND phase='SKIPPED_SOURCE' AND run_id IS NULL")) {
                                    u.setString(1, Instant.now().toString());
                                    u.setString(2, window.id());
                                    u.executeUpdate();
                                }
                            }
                        }
                        db.commit();
                    } catch (Exception e) {
                        db.rollback();
                        throw e;
                    } finally {
                        db.setAutoCommit(true);
                    }
                }
            }
        }
    }

    private record Fetched(
            Window window,
            BinanceArchiveSource.Archive archive,
            BinanceArchiveMapper.Coverage coverage) {}

    private void processGroup(Connection db, List<Window> group, boolean downloadOnly)
            throws Exception {
        try (var q =
                db.prepareStatement(
                        "SELECT count(*) FROM archive_tasks WHERE dataset=? AND phase='PUBLISHING'")) {
            q.setString(1, group.getFirst().dataset().id());
            try (var r = q.executeQuery()) {
                r.next();
                if (r.getInt(1) > 0) return;
            }
        }
        var fetched = new ArrayList<Fetched>();
        for (var window : group) {
            if (Files.exists(root.resolve("stop.request"))) return;
            String phase;
            try (var q = db.prepareStatement("SELECT phase FROM archive_tasks WHERE id=?")) {
                q.setString(1, window.id());
                try (var rows = q.executeQuery()) {
                    rows.next();
                    phase = rows.getString(1);
                }
            }
            if (List.of("VERIFIED", "PARTIAL_VERIFIED", "PUBLISHING", "SKIPPED_SOURCE")
                    .contains(phase)) continue;
            if (window.dataset() == BinanceArchiveDataset.FUNDING
                    && (!window.from().equals(window.from().withDayOfMonth(1))
                            || !window.through()
                                    .equals(YearMonth.from(window.from()).atEndOfMonth()))) {
                update(db, window, "SKIPPED_SOURCE", null, "资金费率非完整月份：尚未核实日包或 API 尾部来源");
                continue;
            }
            try {
                var archive = source.fetch(window.uri(), phase.equals("RETRY_SOURCE"));
                var coverage =
                        BinanceArchiveMapper.inspect(
                                window.dataset(),
                                Files.readAllBytes(archive.raw()),
                                window.from(),
                                window.through(),
                                archive.sha256(),
                                archive.observedAt());
                update(
                        db,
                        window,
                        "FETCHED",
                        null,
                        coverage.complete()
                                ? "来源全范围解析通过；未发布"
                                : "来源文件完整，含缺口或提前收盘；只发布实际记录，不推进整月完整状态");
                fetched.add(new Fetched(window, archive, coverage));
            } catch (Exception e) {
                update(
                        db,
                        window,
                        "SKIPPED_SOURCE",
                        null,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        if (downloadOnly || fetched.isEmpty()) return;
        var definition = BinanceArchiveDefinitions.definition(group.getFirst().dataset());
        var allRows = new ArrayList<Map<String, Object>>();
        var members = new ArrayList<Object>();
        for (var item : fetched) {
            var archive = item.archive();
            if (!VerifiedMarketDataStore.sha(Files.readAllBytes(archive.raw()))
                    .equals(archive.sha256())) throw new IllegalStateException("待发布原 ZIP 摘要已变化");
            allRows.addAll(item.coverage().rows());
            members.add(
                    Map.of(
                            "task",
                            item.window().id(),
                            "url",
                            archive.url(),
                            "raw",
                            archive.raw().toString(),
                            "sha256",
                            archive.sha256(),
                            "checksum",
                            archive.checksum().toString(),
                            "checksumSha256",
                            archive.checksumSha256(),
                            "from",
                            item.window().from().toString(),
                            "through",
                            item.window().through().toString(),
                            "coverage",
                            Map.of(
                                    "rows",
                                    item.coverage().rows().size(),
                                    "missingOpenTimes",
                                    item.coverage().missingOpenTimes(),
                                    "shortenedBars",
                                    item.coverage().shortenedBars(),
                                    "complete",
                                    item.coverage().complete(),
                                    "excludedRows",
                                    item.coverage().excludedRows())));
        }
        byte[] bundle =
                JSON.writeValueAsBytes(
                        Map.of(
                                "format",
                                "binance.archive.bundle.v2",
                                "dataset",
                                definition.datasetId(),
                                "members",
                                members));
        String sha = VerifiedMarketDataStore.sha(bundle);
        Path raw = root.resolve("bundles").resolve(sha + ".json");
        Files.createDirectories(raw.getParent());
        if (!Files.exists(raw)) Files.write(raw, bundle, StandardOpenOption.CREATE_NEW);
        if (!VerifiedMarketDataStore.sha(Files.readAllBytes(raw)).equals(sha))
            throw new IllegalStateException("冻结归档组摘要变化");
        String originalTarget;
        try {
            store.requireNoUnresolved(definition.datasetId());
            originalTarget = BinanceArchiveOverlap.inspect(store, definition, allRows, root);
        } catch (Exception e) {
            for (var item : fetched)
                update(
                        db,
                        item.window(),
                        "SKIPPED_SOURCE",
                        null,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        try {
            store.requireNoUnresolved(definition.datasetId());
            // 同组所有意图一起提交；网络期间不占 SQLite 写事务。
            db.setAutoCommit(false);
            try {
                for (var item : fetched)
                    update(db, item.window(), "PUBLISHING", null, "bundle=" + raw);
                db.commit();
            } catch (Exception e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
            var result =
                    originalTarget == null
                            ? store.publish(
                                    definition,
                                    allRows,
                                    raw,
                                    sha,
                                    Map.of(
                                            "queueTask",
                                            sha,
                                            "bundle",
                                            raw.toString(),
                                            "sourceWindows",
                                            fetched.size()))
                            : store.publishToOriginalTarget(
                                    definition,
                                    allRows,
                                    raw,
                                    sha,
                                    Map.of(
                                            "queueTask",
                                            sha,
                                            "bundle",
                                            raw.toString(),
                                            "sourceWindows",
                                            fetched.size()),
                                    originalTarget);
            if (result.state() != SyncRunState.VERIFIED
                    || result.sourceRows() != allRows.size()
                    || result.verifiedRows() != allRows.size())
                throw new IllegalStateException("归档组正式回读未完成");
            // 正式引擎已核对全键值、WAL 和原目标；队列引用原成功回执，不再发第二轮核验请求。
            String receipt =
                    JSON.writeValueAsString(
                            Map.of(
                                    "runId",
                                    result.runId(),
                                    "bundle",
                                    raw.toString(),
                                    "bundleSha256",
                                    sha,
                                    "verifiedRows",
                                    result.verifiedRows()));
            db.setAutoCommit(false);
            try {
                for (var item : fetched)
                    update(
                            db,
                            item.window(),
                            item.coverage().complete() ? "VERIFIED" : "PARTIAL_VERIFIED",
                            result.runId(),
                            JSON.writeValueAsString(
                                    Map.of(
                                            "publication",
                                            receipt,
                                            "missingOpenTimes",
                                            item.coverage().missingOpenTimes(),
                                            "shortenedBars",
                                            item.coverage().shortenedBars(),
                                            "rangeComplete",
                                            item.coverage().complete(),
                                            "excludedRows",
                                            item.coverage().excludedRows())));
                db.commit();
            } catch (Exception e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (Exception e) {
            for (var item : fetched)
                update(
                        db,
                        item.window(),
                        "PUBLISHING",
                        null,
                        "bundle="
                                + raw
                                + "; "
                                + e.getClass().getSimpleName()
                                + ": "
                                + e.getMessage());
        }
    }

    private static void update(
            Connection db, Window window, String phase, String run, String reason)
            throws Exception {
        try (var q =
                db.prepareStatement(
                        "UPDATE archive_tasks SET phase=?,run_id=coalesce(?,run_id),reason=?,updated_at=? WHERE id=?")) {
            q.setString(1, phase);
            q.setString(2, run);
            q.setString(3, reason);
            q.setString(4, Instant.now().toString());
            q.setString(5, window.id());
            if (q.executeUpdate() != 1) throw new IllegalStateException("原生归档任务缺失");
        }
        System.out.println(
                JSON.writeValueAsString(
                        Map.of(
                                "dataset",
                                window.dataset().id(),
                                "from",
                                window.from().toString(),
                                "through",
                                window.through().toString(),
                                "phase",
                                phase,
                                "reason",
                                reason)));
    }
}
