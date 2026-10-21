package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.AtomicMarketStatus;
import com.zoutrankil.data.marketdata.FrozenHttpSource;
import com.zoutrankil.data.marketdata.PersistentMarketApiBudget;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.us.marketdata.YahooPriceMapper;

import org.springframework.core.env.Environment;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** 同一挂牌进程内预取来源；只推进原队列的 PLANNED，不接触正式发布和未决任务。 */
final class UsBroadDailyPrefetch implements AutoCloseable {
    record Task(String id, String symbol, String yahoo) {}

    enum Step {
        FETCHED,
        FAILED_SOURCE,
        RETRY_WAIT,
        BUFFER_FULL,
        COMPLETE,
        STOPPED
    }

    @FunctionalInterface
    interface Fetch {
        FrozenHttpSource.Response get(Task task) throws Exception;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Environment env;
    private final Path root;
    private final LocalDate from, through;
    private final int capacity;
    private final PersistentMarketApiBudget.Policy policy;
    private final UsBroadSourceRetry.Policy retryPolicy;
    private final Thread worker;
    private volatile boolean stopping, finished;
    private volatile boolean started;
    private volatile Throwable failure;

    UsBroadDailyPrefetch(Environment env, Path root, LocalDate through) {
        this(env, root, through, true);
    }

    UsBroadDailyPrefetch(Environment env, Path root, LocalDate through, boolean startImmediately) {
        this.env = env;
        this.root = root;
        this.through = through;
        this.from = LocalDate.parse(env.getRequiredProperty("app.market-data.history-start"));
        this.capacity = capacity(env);
        policy =
                new PersistentMarketApiBudget.Policy(
                        env.getProperty(
                                "app.market-data.yahoo-requests-per-hour", Integer.class, 1800),
                        env.getProperty(
                                "app.market-data.yahoo-requests-per-day", Integer.class, 20000),
                        100000,
                        env.getProperty(
                                "app.market-data.yahoo-monthly-bytes",
                                Long.class,
                                20L * 1024 * 1024 * 1024),
                        env.getProperty(
                                "app.market-data.yahoo-request-spacing-ms", Long.class, 2000L));
        retryPolicy = UsBroadSourceRetry.policy(env);
        worker =
                Thread.ofPlatform()
                        .name("us-broad-daily-prefetch")
                        .daemon(true)
                        .unstarted(this::run);
        if (startImmediately) start();
    }

    synchronized void start() {
        if (stopping) throw new IllegalStateException("预取已关闭，不能重新启动");
        // 多个原组可能重复通知就绪，整个持锁进程只启动这一个生产者。
        if (started) return;
        started = true;
        worker.start();
    }

    boolean started() {
        return started;
    }

    static int capacity(Environment env) {
        int value =
                env.getProperty(
                        "app.market-data.broad-prefetch-buffer-symbols", Integer.class, 256);
        if (value < 16 || value > 500) throw new IllegalArgumentException("挂牌预取缓存须为 16 至 500 个来源");
        return value;
    }

    boolean finished() {
        return finished;
    }

    void check() {
        if (failure != null) throw new IllegalStateException("挂牌预取异常；保留原队列，停止启动后续正式发布", failure);
    }

    private boolean stopped() {
        return stopping || Files.exists(root.resolve("us-broad-stop.request"));
    }

    private void run() {
        int fetched = 0, failed = 0;
        try (var budget =
                        new PersistentMarketApiBudget(
                                root.resolve("api-budget.sqlite3"), Map.of("yahoo", policy));
                var source =
                        new FrozenHttpSource(
                                root, budget, env.getProperty("app.market-data.proxy-url", ""));
                var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + root.resolve("broad-queue.sqlite3"))) {
            try (var s = db.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA synchronous=FULL");
            }
            UsBroadSourceRetry.initialize(db);
            while (!stopped()) {
                Step step;
                try {
                    step =
                            step(
                                    db,
                                    capacity,
                                    from,
                                    through,
                                    task -> {
                                        long end =
                                                LocalDate.now(ZoneOffset.UTC)
                                                        .plusDays(1)
                                                        .atStartOfDay(ZoneOffset.UTC)
                                                        .toEpochSecond();
                                        var uri =
                                                URI.create(
                                                        "https://query1.finance.yahoo.com/v8/finance/chart/"
                                                                + task.yahoo()
                                                                + "?period1="
                                                                + from.atStartOfDay(ZoneOffset.UTC)
                                                                        .toEpochSecond()
                                                                + "&period2="
                                                                + end
                                                                + "&interval=1d&events=div,splits");
                                        return source.get(
                                                "yahoo",
                                                "public",
                                                task.yahoo(),
                                                uri,
                                                Map.of(),
                                                4 * 1024 * 1024);
                                    },
                                    this::stopped,
                                    retryPolicy,
                                    Clock.systemUTC());
                } catch (PersistentMarketApiBudget.Exhausted wait) {
                    status("WAITING_QUOTA", fetched, failed, wait.nextAllowed.toString());
                    Thread.sleep(
                            Math.max(
                                    1,
                                    Math.min(
                                            10000,
                                            Duration.between(Instant.now(), wait.nextAllowed)
                                                    .toMillis())));
                    continue;
                } catch (FrozenHttpSource.SourceStatusException http) {
                    // 429 仍由同一持久预算限制，不能换账户或重置计数绕过。
                    if (http.status != 429) throw http;
                    continue;
                }
                if (step == Step.COMPLETE) {
                    status("SOURCE_SCOPE_FETCHED", fetched, failed, null);
                    return;
                }
                if (step == Step.STOPPED) break;
                if (step == Step.BUFFER_FULL) {
                    Thread.sleep(1000);
                    continue;
                }
                if (step == Step.RETRY_WAIT) {
                    status("WAITING_SOURCE_RETRY", fetched, failed, null);
                    Thread.sleep(1000);
                    continue;
                }
                if (step == Step.FETCHED) fetched++;
                else failed++;
                status(step.name(), fetched, failed, null);
            }
            status("STOPPED", fetched, failed, null);
        } catch (Throwable error) {
            if (!stopping) {
                failure = error;
                try {
                    status("FAILED", fetched, failed, error.getClass().getSimpleName());
                } catch (Exception ignored) {
                }
            }
        } finally {
            finished = true;
        }
    }

    static Step step(
            Connection db,
            int capacity,
            LocalDate from,
            LocalDate through,
            Fetch fetch,
            BooleanSupplier stopped)
            throws Exception {
        return step(
                db,
                capacity,
                from,
                through,
                fetch,
                stopped,
                new UsBroadSourceRetry.Policy(3, 5000),
                Clock.systemUTC());
    }

    static Step step(
            Connection db,
            int capacity,
            LocalDate from,
            LocalDate through,
            Fetch fetch,
            BooleanSupplier stopped,
            UsBroadSourceRetry.Policy retryPolicy,
            Clock clock)
            throws Exception {
        if (!db.getAutoCommit()) throw new IllegalStateException("来源网络请求不能占有队列写事务");
        if (stopped.getAsBoolean()) return Step.STOPPED;
        try (var s = db.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT count(*) FROM broad_tasks WHERE kind='DAILY' AND phase='FETCHED'")) {
            if (r.next() && r.getInt(1) >= capacity) return Step.BUFFER_FULL;
        }
        var candidate = UsBroadSourceRetry.next(db, retryPolicy, clock.instant());
        if (candidate == null)
            return UsBroadSourceRetry.pending(db) ? Step.RETRY_WAIT : Step.COMPLETE;
        Task task = candidate.task();
        String expected =
                VerifiedMarketDataStore.sha(
                        ("v1|DAILY|" + task.symbol() + "|" + through + "|")
                                .getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(task.id()) || !task.yahoo().matches("[A-Z0-9-]{1,32}"))
            throw new IllegalStateException("预取必须保持原代码映射和请求截止日期");
        if (candidate.failures() >= retryPolicy.maxAttempts()) {
            update(db, task, "FAILED_SOURCE", null, candidate.lastError());
            return Step.FAILED_SOURCE;
        }
        FrozenHttpSource.Response response;
        try {
            response = fetch.get(task);
        } catch (PersistentMarketApiBudget.Exhausted wait) {
            throw wait;
        } catch (Exception error) {
            if (stopped.getAsBoolean()) return Step.STOPPED;
            if (error instanceof InterruptedException) throw error;
            if (error instanceof FrozenHttpSource.SourceStatusException http && http.status == 429)
                throw http;
            // 从失败时刻退避，不能把长请求耗时算作已经等待。
            if (UsBroadSourceRetry.retryable(error))
                return UsBroadSourceRetry.record(db, task, error, retryPolicy, clock.instant())
                        ? Step.RETRY_WAIT
                        : Step.FAILED_SOURCE;
            update(db, task, "FAILED_SOURCE", null, UsBroadSourceRetry.display(error));
            return Step.FAILED_SOURCE;
        }
        // 请求成功后即使收到停止信号也保留已冻结的响应；正式写入只由消费线程推进。
        byte[] bytes = Files.readAllBytes(response.path());
        if (!VerifiedMarketDataStore.sha(bytes).equals(response.sha256()))
            throw new IllegalStateException("预取冻结响应摘要变化");
        try {
            YahooPriceMapper.daily(
                    bytes,
                    task.symbol(),
                    task.yahoo(),
                    response.sha256(),
                    response.observedAt(),
                    from,
                    through);
        } catch (IllegalArgumentException malformed) {
            update(db, task, "FAILED_SOURCE", response, malformed.getMessage());
            return Step.FAILED_SOURCE;
        }
        update(db, task, "FETCHED", response, response.sourceUrl());
        return Step.FETCHED;
    }

    private static void update(
            Connection db,
            Task task,
            String phase,
            FrozenHttpSource.Response response,
            String proof)
            throws Exception {
        try (var s =
                db.prepareStatement(
                        "UPDATE broad_tasks SET phase=?,raw=?,sha=?,observed=?,proof=?,updated_at=? WHERE id=? AND kind='DAILY' AND phase='PLANNED' AND raw IS NULL AND sha IS NULL AND observed IS NULL AND bundle_id IS NULL")) {
            s.setString(1, phase);
            s.setString(2, response == null ? null : response.path().toString());
            s.setString(3, response == null ? null : response.sha256());
            s.setString(4, response == null ? null : response.observedAt().toString());
            s.setString(5, proof);
            s.setString(6, Instant.now().toString());
            s.setString(7, task.id());
            if (s.executeUpdate() != 1)
                throw new IllegalStateException("预取原队列状态变化；禁止覆盖正式发布或范围复核结果");
        }
    }

    static boolean groupReady(Connection db, int groupSize, boolean producerFinished)
            throws SQLException {
        try (var s = db.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT count(*) FROM broad_tasks WHERE kind='DAILY' AND phase='FETCHED'")) {
            return r.next() && (r.getInt(1) >= groupSize || producerFinished && r.getInt(1) > 0);
        }
    }

    private void status(String phase, int fetched, int failed, String detail) throws Exception {
        var card = new LinkedHashMap<String, Object>();
        card.put("pid", ProcessHandle.current().pid());
        card.put("heartbeatAt", Instant.now().toString());
        card.put("phase", phase);
        card.put("fetched", fetched);
        card.put("failed", failed);
        card.put("detail", detail);
        if (!AtomicMarketStatus.write(
                root.resolve("us-broad-prefetch-status.json"), JSON.writeValueAsBytes(card)))
            System.err.println("挂牌预取状态页暂不可写；原队列仍保留");
    }

    @Override
    public synchronized void close() throws InterruptedException {
        stopping = true;
        if (!started) {
            finished = true;
            return;
        }
        worker.interrupt();
        worker.join();
    }
}
