package com.zoutrankil.market.btc.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.FrozenHttpSource;
import com.zoutrankil.data.marketdata.PersistentMarketApiBudget;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;
import com.zoutrankil.market.btc.archive.BinanceArchiveDefinitions;
import com.zoutrankil.market.btc.archive.mapper.BinanceFundingMapper;

import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

/** 月包未覆盖的尾部使用公开历史 API；保存完整响应，间隔未知就留空，不推算。 */
public final class BinanceFundingTailSync {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BinanceFundingTailSync() {}

    public static Map<String, Object> run(
            Path root,
            FrozenHttpSource source,
            VerifiedMarketDataStore store,
            LocalDate from,
            LocalDate through)
            throws Exception {
        if (through.isBefore(from)
                || !through.isBefore(LocalDate.now(ZoneOffset.UTC))
                || !YearMonth.from(from).equals(YearMonth.from(through)))
            throw new IllegalArgumentException("资金费率 API 每次只补同一个月的已结束 UTC 日期");
        String id =
                VerifiedMarketDataStore.sha(
                        ("binance.funding.tail.v1|" + from + "|" + through)
                                .getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("archive-queue.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("归档采集尚未结束，尾部稍后补");
            try (var db =
                    DriverManager.getConnection(
                            "jdbc:sqlite:" + root.resolve("archive-queue.sqlite3"))) {
                try (var s = db.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=FULL");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS funding_api_tails(id TEXT PRIMARY KEY,from_date TEXT NOT NULL,through_date TEXT NOT NULL,phase TEXT NOT NULL,run_id TEXT,raw TEXT,sha TEXT,observed TEXT,reason TEXT)");
                    try (var r =
                            s.executeQuery(
                                    "SELECT name FROM sqlite_master WHERE name='archive_tasks'")) {
                        if (r.next()) {
                            var month = YearMonth.from(from);
                            var archive =
                                    new BinanceArchiveSync.Window(
                                            BinanceArchiveDataset.FUNDING,
                                            BinanceArchiveDataset.FUNDING.monthly(month),
                                            month.atDay(1),
                                            month.atEndOfMonth());
                            try (var q =
                                    db.prepareStatement(
                                            "SELECT phase FROM archive_tasks WHERE id=?")) {
                                q.setString(1, archive.id());
                                try (var state = q.executeQuery()) {
                                    if (state.next() && state.getString(1).equals("VERIFIED"))
                                        return Map.of(
                                                "phase",
                                                "ARCHIVE_VERIFIED",
                                                "month",
                                                month.toString());
                                }
                            }
                        }
                    }
                }
                try (var q =
                        db.prepareStatement(
                                "INSERT OR IGNORE INTO funding_api_tails(id,from_date,through_date,phase) VALUES(?,?,?,'PLANNED')")) {
                    q.setString(1, id);
                    q.setString(2, from.toString());
                    q.setString(3, through.toString());
                    q.executeUpdate();
                }
                FrozenHttpSource.Response response = null;
                try (var q =
                        db.prepareStatement(
                                "SELECT phase,raw,sha,observed,run_id FROM funding_api_tails WHERE id=?")) {
                    q.setString(1, id);
                    try (var r = q.executeQuery()) {
                        r.next();
                        String phase = r.getString(1);
                        if (phase.equals("VERIFIED")) {
                            markArchiveFallback(db, from, through, r.getString(5), id);
                            return Map.of("task", id, "phase", phase);
                        }
                        if (phase.equals("PUBLISHING"))
                            throw new IllegalStateException("资金费率 API 原发布未决，不自动重发");
                        if (phase.equals("FETCHED"))
                            response =
                                    new FrozenHttpSource.Response(
                                            Path.of(r.getString(2)),
                                            r.getString(3),
                                            Instant.parse(r.getString(4)),
                                            "retained",
                                            0);
                    }
                }
                long start = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                        end =
                                through.plusDays(1)
                                                .atStartOfDay(ZoneOffset.UTC)
                                                .toInstant()
                                                .toEpochMilli()
                                        - 1;
                URI uri =
                        URI.create(
                                "https://fapi.binance.com/fapi/v1/fundingRate?symbol=BTCUSDT&startTime="
                                        + start
                                        + "&endTime="
                                        + end
                                        + "&limit=1000");
                if (response == null) {
                    while (true) {
                        try {
                            response =
                                    source.get(
                                            "binance",
                                            "public",
                                            "BTCUSDT",
                                            uri,
                                            Map.of(),
                                            2 * 1024 * 1024);
                            break;
                        } catch (PersistentMarketApiBudget.Exhausted wait) {
                            long delay =
                                    Duration.between(Instant.now(), wait.nextAllowed).toMillis();
                            if (delay > 60000) throw wait;
                            Thread.sleep(Math.max(1, delay));
                        }
                    }
                    try (var q =
                            db.prepareStatement(
                                    "UPDATE funding_api_tails SET phase='FETCHED',raw=?,sha=?,observed=? WHERE id=?")) {
                        q.setString(1, response.path().toString());
                        q.setString(2, response.sha256());
                        q.setString(3, response.observedAt().toString());
                        q.setString(4, id);
                        q.executeUpdate();
                    }
                }
                byte[] bytes = Files.readAllBytes(response.path());
                if (!VerifiedMarketDataStore.sha(bytes).equals(response.sha256()))
                    throw new IllegalStateException("原 API 响应摘要变化");
                var rows =
                        BinanceFundingMapper.map(
                                bytes, from, through, response.sha256(), response.observedAt());
                var definition =
                        BinanceArchiveDefinitions.definition(BinanceArchiveDataset.FUNDING);
                store.requireNoUnresolved(definition.datasetId());
                String originalTarget =
                        BinanceArchiveOverlap.inspect(store, definition, rows, root);
                try (var q =
                        db.prepareStatement(
                                "UPDATE funding_api_tails SET phase='PUBLISHING',reason='等待原运行完整回读，未决禁止重发' WHERE id=?")) {
                    q.setString(1, id);
                    q.executeUpdate();
                }
                // 原响应与请求边界一起冻结；少于 API 上限才接受完整响应，不假定结算次数。
                var result =
                        originalTarget == null
                                ? store.publish(
                                        definition,
                                        rows,
                                        response.path(),
                                        response.sha256(),
                                        Map.of(
                                                "queueTask",
                                                id,
                                                "sourceUrl",
                                                uri.toString(),
                                                "from",
                                                from.toString(),
                                                "through",
                                                through.toString(),
                                                "responseLimit",
                                                1000,
                                                "actualRows",
                                                rows.size(),
                                                "intervalUnknown",
                                                true))
                                : store.publishToOriginalTarget(
                                        definition,
                                        rows,
                                        response.path(),
                                        response.sha256(),
                                        Map.of(
                                                "queueTask",
                                                id,
                                                "sourceUrl",
                                                uri.toString(),
                                                "from",
                                                from.toString(),
                                                "through",
                                                through.toString(),
                                                "responseLimit",
                                                1000,
                                                "actualRows",
                                                rows.size(),
                                                "intervalUnknown",
                                                true),
                                        originalTarget);
                try (var q =
                        db.prepareStatement(
                                "UPDATE funding_api_tails SET phase='VERIFIED',run_id=?,reason=? WHERE id=?")) {
                    q.setString(1, result.runId());
                    q.setString(2, "原正式运行已核对完整键值、WAL 和原目标");
                    q.setString(3, id);
                    q.executeUpdate();
                }
                markArchiveFallback(db, from, through, result.runId(), id);
                return Map.of(
                        "task",
                        id,
                        "phase",
                        "VERIFIED",
                        "runId",
                        result.runId(),
                        "rows",
                        rows.size(),
                        "fundingIntervalKnown",
                        false);
            }
        }
    }

    private static void markArchiveFallback(
            Connection db, LocalDate from, LocalDate through, String run, String apiTask)
            throws Exception {
        try (var s = db.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT name FROM sqlite_master WHERE name='archive_tasks'")) {
            if (!r.next()) return;
        }
        var month = YearMonth.from(from);
        var window =
                new BinanceArchiveSync.Window(
                        BinanceArchiveDataset.FUNDING,
                        BinanceArchiveDataset.FUNDING.monthly(month),
                        from,
                        through);
        // 文件缺失与数据已回补分开保留；有正式 API 运行后不再把它当成未发送源失败自动重试。
        try (var q =
                db.prepareStatement(
                        "UPDATE archive_tasks SET run_id=?,reason=? WHERE id=? AND phase='SKIPPED_SOURCE' AND (run_id IS NULL OR run_id=?)")) {
            q.setString(1, run);
            q.setString(
                    2,
                    JSON.writeValueAsString(
                            Map.of(
                                    "archiveIssue",
                                    "截止月归档尚未覆盖此范围",
                                    "apiTask",
                                    apiTask,
                                    "apiRun",
                                    run,
                                    "apiCoverageVerified",
                                    true)));
            q.setString(3, window.id());
            q.setString(4, run);
            q.executeUpdate();
        }
    }
}
