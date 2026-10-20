package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.AtomicMarketStatus;
import com.zoutrankil.data.marketdata.FrozenHttpSource;
import com.zoutrankil.data.marketdata.PersistentMarketApiBudget;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.us.marketdata.TiingoPriceMapper;
import com.zoutrankil.market.us.marketdata.UsMarketDatasets;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 有限历史队列：请求配额、原始响应和发布状态均持久化；未决发布必须先核对原运行。 */
final class UsCoreCollector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Environment env;
    private final Path root;
    private final VerifiedMarketDataStore store;

    private record Task(
            String id,
            String kind,
            String symbol,
            LocalDate from,
            LocalDate through,
            String phase,
            String raw,
            String sha,
            String observed,
            String evidence) {}

    UsCoreCollector(Environment env, Path root, VerifiedMarketDataStore store) {
        this.env = env;
        this.root = root.toAbsolutePath().normalize();
        this.store = store;
    }

    void reconcileTask(String taskId, String run) throws Exception {
        if (!taskId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("原队列任务标识非法");
        try (var channel =
                        FileChannel.open(
                                root.resolve("us-collector.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock();
                var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + root.resolve("collection-queue.sqlite3"))) {
            if (lock == null) throw new IllegalStateException("核心采集器仍在运行");
            var selected = tasks(db, "id=" + VerifiedMarketDataStore.literal(taskId));
            if (selected.size() != 1 || !selected.getFirst().phase().equals("PUBLISHING"))
                throw new IllegalArgumentException("需要唯一的原发布任务");
            var task = selected.getFirst();
            Path raw = Path.of(task.raw());
            byte[] bytes = Files.readAllBytes(raw);
            Instant observed = Instant.parse(task.observed());
            var proofs = new ArrayList<Object>();
            if (task.kind().equals("DAILY"))
                proofs.add(
                        store.verifyExisting(
                                run,
                                UsMarketDatasets.DAILY,
                                TiingoPriceMapper.daily(
                                        bytes, task.symbol(), task.sha(), observed, task.through()),
                                raw,
                                task.sha(),
                                taskId));
            else {
                var one =
                        TiingoPriceMapper.minute(
                                bytes,
                                task.symbol(),
                                task.sha(),
                                observed,
                                task.from(),
                                task.through());
                proofs.add(
                        store.verifyExisting(
                                run, UsMarketDatasets.MINUTE, one, raw, task.sha(), taskId));
                proofs.add(
                        store.verifyExisting(
                                run,
                                UsMarketDatasets.MINUTE,
                                TiingoPriceMapper.fiveMinute(one),
                                raw,
                                task.sha(),
                                taskId));
            }
            var response =
                    new FrozenHttpSource.Response(
                            raw, task.sha(), observed, task.evidence(), bytes.length);
            update(
                    db,
                    task,
                    "VERIFIED",
                    response,
                    JSON.writeValueAsString(
                            Map.of(
                                    "reconciledRun",
                                    run,
                                    "sourceUrl",
                                    task.evidence(),
                                    "fullReadbackProofs",
                                    proofs)));
            System.out.println(
                    JSON.writeValueAsString(
                            Map.of("task", taskId, "phase", "VERIFIED", "proofs", proofs)));
        }
    }

    void run(LocalDate through, int maximumJobs) throws Exception {
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("us-collector.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("已有美股采集器持有队列，不能并发启动");
            String token = env.getProperty("app.market-data.tiingo-token", "");
            if (token.isBlank()) throw new IllegalStateException("本机 Tiingo 凭据缺失");
            String account = VerifiedMarketDataStore.sha(token.getBytes(StandardCharsets.UTF_8));
            var policy =
                    new PersistentMarketApiBudget.Policy(
                            env.getProperty(
                                    "app.market-data.tiingo-hourly-requests", Integer.class, 50),
                            env.getProperty(
                                    "app.market-data.tiingo-daily-requests", Integer.class, 1000),
                            env.getProperty(
                                    "app.market-data.tiingo-monthly-symbols", Integer.class, 500),
                            env.getProperty(
                                    "app.market-data.tiingo-monthly-bytes",
                                    Long.class,
                                    1073741824L),
                            0);
            try (var budget =
                            new PersistentMarketApiBudget(
                                    root.resolve("api-budget.sqlite3"), Map.of("tiingo", policy));
                    var source =
                            new FrozenHttpSource(
                                    root,
                                    budget,
                                    env.getProperty("app.market-data.proxy-url", ""));
                    var db =
                            DriverManager.getConnection(
                                    "jdbc:sqlite:" + root.resolve("collection-queue.sqlite3"))) {
                try (var s = db.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=FULL");
                    s.execute("PRAGMA busy_timeout=5000");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS us_collection_tasks(id TEXT PRIMARY KEY,kind TEXT NOT NULL,symbol TEXT NOT NULL,from_date TEXT NOT NULL,through_date TEXT NOT NULL,phase TEXT NOT NULL,raw_path TEXT,source_sha TEXT,observed_at TEXT,evidence TEXT,updated_at TEXT NOT NULL)");
                }
                seedProbes(budget, account);
                plan(db, through);
                var unresolved = tasks(db, "phase='PUBLISHING'");
                if (!unresolved.isEmpty())
                    throw new IllegalStateException("有原发布尚未核对：" + unresolved.getFirst().id());
                Files.deleteIfExists(root.resolve("us-stop.request"));
                int completed = 0;
                while (true) {
                    if (Files.exists(root.resolve("us-stop.request"))) {
                        status(db, budget, "STOPPED", null);
                        return;
                    }
                    var pending = tasks(db, "phase IN ('PLANNED','FETCHED')");
                    if (pending.isEmpty()) {
                        status(
                                db,
                                budget,
                                tasks(db, "phase!='VERIFIED'").isEmpty()
                                        ? "COMPLETE"
                                        : "COMPLETE_WITH_GAPS",
                                null);
                        return;
                    }
                    if (completed >= maximumJobs) {
                        status(db, budget, "JOB_LIMIT_REACHED", null);
                        return;
                    }
                    var task = pending.getFirst();
                    status(db, budget, "WORKING", task.id());
                    FrozenHttpSource.Response response;
                    if (task.phase().equals("FETCHED"))
                        response =
                                new FrozenHttpSource.Response(
                                        Path.of(task.raw()),
                                        task.sha(),
                                        Instant.parse(task.observed()),
                                        task.evidence(),
                                        (int) Files.size(Path.of(task.raw())));
                    else {
                        var cached = probe(task);
                        try {
                            response =
                                    cached != null
                                            ? cached
                                            : source.get(
                                                    "tiingo",
                                                    account,
                                                    task.symbol(),
                                                    uri(task),
                                                    Map.of(
                                                            "Authorization",
                                                            "Token " + token,
                                                            "Content-Type",
                                                            "application/json"),
                                                    16 * 1024 * 1024);
                        } catch (PersistentMarketApiBudget.Exhausted wait) {
                            status(db, budget, "WAITING_QUOTA", wait.nextAllowed.toString());
                            while (Instant.now().isBefore(wait.nextAllowed)) {
                                if (Files.exists(root.resolve("us-stop.request"))) {
                                    status(db, budget, "STOPPED", null);
                                    return;
                                }
                                Thread.sleep(
                                        Math.max(
                                                1,
                                                Math.min(
                                                        10000,
                                                        Duration.between(
                                                                        Instant.now(),
                                                                        wait.nextAllowed)
                                                                .toMillis())));
                                status(db, budget, "WAITING_QUOTA", wait.nextAllowed.toString());
                            }
                            continue;
                        } catch (Exception failed) {
                            if (failed instanceof FrozenHttpSource.SourceStatusException http
                                    && http.status == 429) continue;
                            update(
                                    db,
                                    task,
                                    "FAILED_SOURCE",
                                    null,
                                    failed.getClass().getSimpleName() + ": " + failed.getMessage());
                            status(db, budget, "SOURCE_GAP", task.id());
                            continue;
                        }
                        update(db, task, "FETCHED", response, response.sourceUrl());
                    }
                    byte[] bytes = Files.readAllBytes(response.path());
                    if (!VerifiedMarketDataStore.sha(bytes).equals(response.sha256()))
                        throw new IllegalStateException("排队的冻结来源摘要变化");
                    var proof =
                            Map.<String, Object>of(
                                    "queueTask",
                                    task.id(),
                                    "sourceUrl",
                                    response.sourceUrl(),
                                    "requestedFrom",
                                    task.from().toString(),
                                    "requestedThrough",
                                    task.through().toString(),
                                    "observedAt",
                                    response.observedAt().toString(),
                                    "scope",
                                    "核心证券的真实来源响应，未声称全市场完成");
                    try {
                        if (task.kind().equals("DAILY")) {
                            var rows =
                                    TiingoPriceMapper.daily(
                                            bytes,
                                            task.symbol(),
                                            response.sha256(),
                                            response.observedAt(),
                                            task.through());
                            if (rows.isEmpty()) throw new IllegalArgumentException("核心证券日线来源为空");
                            update(db, task, "PUBLISHING", response, response.sourceUrl());
                            var result =
                                    store.publish(
                                            UsMarketDatasets.DAILY,
                                            rows,
                                            response.path(),
                                            response.sha256(),
                                            proof);
                            update(
                                    db,
                                    task,
                                    "VERIFIED",
                                    response,
                                    JSON.writeValueAsString(Map.of("daily", result)));
                        } else {
                            var rows =
                                    TiingoPriceMapper.minute(
                                            bytes,
                                            task.symbol(),
                                            response.sha256(),
                                            response.observedAt(),
                                            task.from(),
                                            task.through());
                            if (rows.isEmpty()) throw new IllegalArgumentException("核心证券分钟来源为空");
                            update(db, task, "PUBLISHING", response, response.sourceUrl());
                            var one =
                                    store.publish(
                                            UsMarketDatasets.MINUTE,
                                            rows,
                                            response.path(),
                                            response.sha256(),
                                            proof);
                            var five =
                                    store.publish(
                                            UsMarketDatasets.MINUTE,
                                            TiingoPriceMapper.fiveMinute(rows),
                                            response.path(),
                                            response.sha256(),
                                            proof);
                            update(
                                    db,
                                    task,
                                    "VERIFIED",
                                    response,
                                    JSON.writeValueAsString(
                                            Map.of("minute", one, "fiveMinute", five)));
                        }
                    } catch (Exception failed) {
                        // 已进入 PUBLISHING 时保留原状态和锁，不能另起发布重发。
                        status(db, budget, "PUBLICATION_REQUIRES_RECONCILIATION", task.id());
                        throw failed;
                    }
                    completed++;
                    status(db, budget, "VERIFIED_TASK", task.id());
                }
            }
        }
    }

    private void plan(Connection db, LocalDate through) throws Exception {
        var symbols = configuredSymbols(env);
        LocalDate start =
                LocalDate.parse(env.getProperty("app.market-data.history-start", "2006-01-01"));
        for (String symbol : symbols) insert(db, "DAILY", symbol, start, through);
        LocalDate minuteStart =
                LocalDate.parse(
                        env.getProperty(
                                "app.market-data.minute-start", through.getYear() + "-01-01"));
        for (LocalDate month = through.withDayOfMonth(1);
                !month.isBefore(minuteStart.withDayOfMonth(1));
                month = month.minusMonths(1)) {
            var from = month.isBefore(minuteStart) ? minuteStart : month;
            var end = month.plusMonths(1).minusDays(1);
            if (end.isAfter(through)) end = through;
            for (String symbol : symbols) insert(db, "MINUTE", symbol, from, end);
        }
    }

    static LinkedHashSet<String> configuredSymbols(Environment environment) {
        var symbols = new LinkedHashSet<String>();
        var binder = Binder.get(environment);
        symbols.addAll(
                binder.bind("app.market-data.benchmark-symbols", Bindable.listOf(String.class))
                        .orElseThrow(() -> new IllegalStateException("核心 ETF 列表缺失")));
        symbols.addAll(
                binder.bind("app.market-data.core-stock-symbols", Bindable.listOf(String.class))
                        .orElseThrow(() -> new IllegalStateException("核心股票列表缺失")));
        if (symbols.isEmpty() || symbols.stream().anyMatch(s -> !s.matches("[A-Z0-9.-]{1,20}")))
            throw new IllegalArgumentException("核心证券代码非法");
        return symbols;
    }

    private static void insert(
            Connection db, String kind, String symbol, LocalDate from, LocalDate through)
            throws Exception {
        String id =
                VerifiedMarketDataStore.sha(
                        ("v1|" + kind + "|" + symbol + "|" + from + "|" + through)
                                .getBytes(StandardCharsets.UTF_8));
        try (var q =
                db.prepareStatement(
                        "INSERT OR IGNORE INTO us_collection_tasks(id,kind,symbol,from_date,through_date,phase,updated_at) VALUES(?,?,?,?,?,'PLANNED',?)")) {
            q.setString(1, id);
            q.setString(2, kind);
            q.setString(3, symbol);
            q.setString(4, from.toString());
            q.setString(5, through.toString());
            q.setString(6, Instant.now().toString());
            q.executeUpdate();
        }
    }

    private static List<Task> tasks(Connection db, String predicate) throws Exception {
        var result = new ArrayList<Task>();
        try (var s = db.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT id,kind,symbol,from_date,through_date,phase,raw_path,source_sha,observed_at,evidence FROM us_collection_tasks WHERE "
                                        + predicate
                                        + " ORDER BY rowid")) {
            while (r.next())
                result.add(
                        new Task(
                                r.getString(1),
                                r.getString(2),
                                r.getString(3),
                                LocalDate.parse(r.getString(4)),
                                LocalDate.parse(r.getString(5)),
                                r.getString(6),
                                r.getString(7),
                                r.getString(8),
                                r.getString(9),
                                r.getString(10)));
        }
        return result;
    }

    private static void update(
            Connection db,
            Task task,
            String phase,
            FrozenHttpSource.Response response,
            String evidence)
            throws Exception {
        try (var q =
                db.prepareStatement(
                        "UPDATE us_collection_tasks SET phase=?,raw_path=coalesce(?,raw_path),source_sha=coalesce(?,source_sha),observed_at=coalesce(?,observed_at),evidence=?,updated_at=? WHERE id=?")) {
            q.setString(1, phase);
            q.setString(2, response == null ? null : response.path().toString());
            q.setString(3, response == null ? null : response.sha256());
            q.setString(4, response == null ? null : response.observedAt().toString());
            q.setString(5, evidence);
            q.setString(6, Instant.now().toString());
            q.setString(7, task.id());
            if (q.executeUpdate() != 1) throw new IllegalStateException("来源队列任务缺失");
        }
    }

    private void status(
            Connection db, PersistentMarketApiBudget budget, String phase, String detail)
            throws Exception {
        var counts = new TreeMap<String, Long>();
        try (var s = db.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT phase,count() FROM us_collection_tasks GROUP BY phase")) {
            while (r.next()) counts.put(r.getString(1), r.getLong(2));
        }
        var card = new LinkedHashMap<String, Object>();
        card.put("phase", phase);
        card.put("detail", detail);
        card.put("pid", ProcessHandle.current().pid());
        card.put("heartbeatAt", Instant.now().toString());
        card.put("tasks", counts);
        card.put("apiBudget", budget.usage());
        if (!AtomicMarketStatus.write(root.resolve("us-status.json"), JSON.writeValueAsBytes(card)))
            System.err.println("状态页暂被读取占用；正式结果以 SQLite 为准");
        System.out.println(JSON.writeValueAsString(card));
    }

    private static URI uri(Task task) {
        String path = task.kind().equals("DAILY") ? "tiingo/daily/" : "iex/";
        return URI.create(
                "https://api.tiingo.com/"
                        + path
                        + task.symbol()
                        + "/prices?startDate="
                        + task.from()
                        + "&endDate="
                        + task.through()
                        + (task.kind().equals("MINUTE")
                                ? "&resampleFreq=1min&columns=open,high,low,close,volume"
                                : ""));
    }

    private FrozenHttpSource.Response probe(Task task) throws Exception {
        if (!task.kind().equals("DAILY")
                || !task.from().equals(LocalDate.of(2006, 1, 1))
                || task.through().isAfter(LocalDate.of(2026, 10, 16))
                || !Set.of("SPY", "AAPL").contains(task.symbol())) return null;
        var raw =
                root.resolve(
                        "probes/tiingo-"
                                + task.symbol().toLowerCase(Locale.ROOT)
                                + "-daily.response");
        if (!Files.isRegularFile(raw)) return null;
        byte[] bytes = Files.readAllBytes(raw);
        return new FrozenHttpSource.Response(
                raw,
                VerifiedMarketDataStore.sha(bytes),
                Files.getLastModifiedTime(raw).toInstant().truncatedTo(ChronoUnit.MICROS),
                "https://api.tiingo.com/tiingo/daily/" + task.symbol() + "/prices (retained probe)",
                bytes.length);
    }

    private void seedProbes(PersistentMarketApiBudget budget, String account) throws Exception {
        for (String name :
                List.of(
                        "tiingo-auth",
                        "tiingo-spy-daily",
                        "tiingo-spy-minute",
                        "tiingo-spy-ytd-minute",
                        "tiingo-aapl-daily")) {
            Path card = root.resolve("probes/" + name + ".json");
            if (!Files.isRegularFile(card)) continue;
            byte[] bytes = Files.readAllBytes(card);
            var document = JSON.readTree(bytes);
            Instant at =
                    document.has("at")
                            ? Instant.parse(document.get("at").asText())
                            : Files.getLastModifiedTime(card).toInstant();
            budget.seed(
                    "probe-" + VerifiedMarketDataStore.sha(bytes),
                    "tiingo",
                    account,
                    name.contains("aapl") ? "AAPL" : name.contains("spy") ? "SPY" : null,
                    at,
                    document.path("bytes").asLong());
        }
    }
}
