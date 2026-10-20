package com.zoutrankil.data.l2live.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zoutrankil.data.l2live.application.AliyunL2MqttCollector;
import com.zoutrankil.data.l2live.application.L2MqttSettings;
import com.zoutrankil.data.l2live.application.L2Subscriptions;
import com.zoutrankil.data.l2live.domain.AliyunL2WireParser;
import com.zoutrankil.data.l2live.domain.L2MinuteAccumulator;
import com.zoutrankil.data.l2live.storage.LiveMarketSnapshotStore;
import com.zoutrankil.data.l2live.storage.LocalLiveSink;
import com.zoutrankil.data.l2live.storage.RawCaptureBatchWriter;
import com.zoutrankil.data.l2live.storage.RawJournal;
import com.zoutrankil.runtime.ProjectConfiguration;
import com.zoutrankil.runtime.ProjectLogging;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 独立的实时数据进程；不依赖策略、QMT 或订单执行。 */
public final class AliyunL2LiveMain {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AliyunL2LiveMain() {}

    public static void main(String[] args) {
        ProjectLogging.initialize(ProjectConfiguration.load("l2"));
        int result = execute(args, System.getenv());
        if (result != 0) System.exit(result);
    }

    static int execute(String[] args, Map<String, String> env) {
        return execute(args, env, Clock.systemUTC());
    }

    static int execute(String[] args, Map<String, String> env, Clock clock) {
        L2LiveConfiguration configuration = null;
        boolean ownsStatus = false;
        try {
            configuration = L2LiveConfiguration.parse(args, env);
            if (configuration.command.equals("plan")) {
                print(configuration.plan());
                return 0;
            }
            if (configuration.command.equals("status")) {
                Path status = configuration.root.resolve("status.json");
                if (Files.isRegularFile(status) && Files.size(status) <= 1_048_576) {
                    System.out.println(Files.readString(status, StandardCharsets.UTF_8));
                } else print(Map.of("producer", "java.aliyun_l2_live", "phase", "not_started"));
                return 0;
            }
            Files.createDirectories(configuration.root);
            try (FileChannel channel =
                    FileChannel.open(
                            configuration.root.resolve("collector.lock"),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE)) {
                FileLock lock = channel.tryLock();
                if (lock == null) throw new IllegalStateException("COLLECTOR_ALREADY_RUNNING");
                ownsStatus = true;
                try (lock) {
                    L2TradingSession session = null;
                    if (configuration.live())
                        Files.deleteIfExists(configuration.root.resolve("stop.request"));
                    if (configuration.command.equals("session")) {
                        phase(configuration.root, "checking_calendar");
                        session = L2TradingSession.resolve(configuration, clock);
                        if (!session.open() || session.expired(clock)) {
                            phase(
                                    configuration.root,
                                    session.open() ? "session_closed" : "non_trading_day");
                            return 0;
                        }
                        while (session.waiting(clock)) {
                            if (Files.exists(configuration.root.resolve("stop.request"))) {
                                phase(configuration.root, "stopped");
                                return 0;
                            }
                            phase(configuration.root, "waiting_session");
                            Thread.sleep(1000);
                        }
                        if (session.expired(clock)) {
                            phase(configuration.root, "session_closed");
                            return 0;
                        }
                    }
                    if (configuration.live()) {
                        configuration = awaitCredentials(configuration, args, env, session, clock);
                        if (configuration == null) return 0;
                    }
                    try (RawJournal journal =
                            new RawJournal(configuration.root, configuration.storageBytes)) {
                        if (configuration.command.equals("benchmark")) {
                            print(
                                    L2LoadBenchmark.run(
                                            journal,
                                            configuration.benchmarkMessages,
                                            configuration.benchmarkRate,
                                            configuration.batchSize,
                                            configuration.seconds));
                            return 0;
                        }
                        if (configuration.command.equals("archive")) {
                            print(
                                    journal.archiveDay(
                                            configuration.archiveDate,
                                            LocalDate.now(
                                                    clock.withZone(ZoneId.of("Asia/Shanghai")))));
                            return 0;
                        }
                        if (configuration.command.equals("verify-storage")) {
                            print(journal.verifyStorage());
                            return 0;
                        }
                        if (configuration.command.equals("files")) {
                            print(journal.parquetFiles());
                            return 0;
                        }
                        if (configuration.command.equals("migrate-parquet")) {
                            print(Map.of("phase", "migrated", "batches", journal.migrateParquet()));
                            return 0;
                        }
                        if (configuration.command.equals("export")) {
                            Path output = configuration.root.resolve("raw-export.parquet");
                            var evidence = journal.exportParquet(output);
                            print(Map.of("phase", "exported", "evidence", evidence));
                            return 0;
                        }
                        var accumulator =
                                new L2MinuteAccumulator(
                                        configuration.lateness,
                                        configuration.retainedMinutes,
                                        Clock.systemUTC());
                        var sink = new LocalLiveSink(journal);
                        var topics =
                                configuration.live()
                                        ? Set.copyOf(L2Subscriptions.topics(configuration.symbols))
                                        : Set.<String>of();
                        var processor =
                                new L2LiveProcessor(
                                        journal,
                                        sink,
                                        new AliyunL2WireParser(),
                                        accumulator,
                                        configuration.batchSize,
                                        topics);
                        if (configuration.command.equals("replay")) {
                            long written = 0;
                            for (int n; (n = processor.processOnce()) > 0; ) written += n;
                            print(
                                    Map.of(
                                            "phase",
                                            "replayed",
                                            "rowsVerified",
                                            written,
                                            "journal",
                                            journal.status()));
                            return 0;
                        }
                        run(configuration, journal, processor, session, clock);
                    }
                }
            }
            return 0;
        } catch (Exception error) {
            var failure = new LinkedHashMap<String, Object>();
            if (ownsStatus && configuration != null) {
                try {
                    Path previous = configuration.root.resolve("status.json");
                    if (Files.isRegularFile(previous) && Files.size(previous) <= 1_048_576) {
                        var saved = JSON.readValue(Files.readString(previous), Map.class);
                        if (saved.get("processId") instanceof Number pid
                                && pid.longValue() == ProcessHandle.current().pid()
                                && "failed".equals(saved.get("phase")))
                            saved.forEach((key, value) -> failure.put(String.valueOf(key), value));
                    }
                } catch (Exception ignored) {
                }
            }
            failure.put("producer", "java.aliyun_l2_live");
            failure.put("phase", "failed");
            failure.put("processId", ProcessHandle.current().pid());
            failure.put("failureCode", safeCode(error));
            failure.put("at", Instant.now().toString());
            if (ownsStatus && configuration != null && Files.isDirectory(configuration.root)) {
                try {
                    status(configuration.root, failure);
                } catch (Exception ignored) {
                }
            }
            try {
                print(failure);
            } catch (Exception ignored) {
                System.err.println("L2_LIVE_FAILED");
            }
            return 2;
        }
    }

    private static L2LiveConfiguration awaitCredentials(
            L2LiveConfiguration initial,
            String[] args,
            Map<String, String> env,
            L2TradingSession session,
            Clock clock)
            throws Exception {
        L2LiveConfiguration config = initial;
        if (!config.waitForCredentials) {
            config.requireLiveCredentials();
            return config;
        }
        while (true) {
            if (session != null && session.expired(clock)) {
                phase(initial.root, "session_closed");
                return null;
            }
            if (Files.exists(initial.root.resolve("stop.request"))) {
                status(
                        initial.root,
                        Map.of(
                                "producer",
                                "java.aliyun_l2_live",
                                "phase",
                                "stopped",
                                "processId",
                                ProcessHandle.current().pid(),
                                "at",
                                Instant.now().toString()));
                return null;
            }
            String failure = null;
            try {
                config = L2LiveConfiguration.parse(args, env);
                config.requireLiveCredentials();
                return config;
            } catch (Exception error) {
                failure = safeCode(error);
            }
            status(
                    initial.root,
                    Map.of(
                            "producer",
                            "java.aliyun_l2_live",
                            "phase",
                            "awaiting_credentials",
                            "processId",
                            ProcessHandle.current().pid(),
                            "at",
                            Instant.now().toString(),
                            "failureCode",
                            failure,
                            "symbols",
                            initial.symbols,
                            "connected",
                            false));
            Thread.sleep(1000);
        }
    }

    private static void run(
            L2LiveConfiguration config,
            RawJournal journal,
            L2LiveProcessor processor,
            L2TradingSession session,
            Clock clock)
            throws Exception {
        if (session != null && session.expired(clock)) {
            phase(config.root, "session_closed");
            return;
        }
        if (hasLocalPythonCollector())
            throw new IllegalStateException("PYTHON_COLLECTOR_STILL_RUNNING");
        if (Files.exists(config.root.resolve("stop.request"))) return;
        AtomicBoolean stopping = new AtomicBoolean();
        AtomicLong received = new AtomicLong();
        AtomicLong written = new AtomicLong();
        var settings =
                new L2MqttSettings(
                        config.brokerUri, config.username, config.password, config.symbols, 48, 20);
        try (var marketSnapshots =
                        new LiveMarketSnapshotStore(
                                config.root, Set.copyOf(settings.topics()), clock);
                var rawWriter = new RawCaptureBatchWriter(journal);
                var collector =
                        AliyunL2MqttCollector.durable(
                                settings,
                                message ->
                                        rawWriter
                                                .submit(
                                                        message.topic(),
                                                        message.payload(),
                                                        message.receivedAt(),
                                                        message.sessionId(),
                                                        message.qos(),
                                                        message.retained(),
                                                        message.duplicate(),
                                                        message.subscriptionAuthorized())
                                                .thenRun(received::incrementAndGet))) {
            var finished = new CountDownLatch(1);
            Thread hook =
                    new Thread(
                            () -> {
                                stopping.set(true);
                                try {
                                    finished.await(30, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            },
                            "l2-live-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            Instant started = Instant.now();
            long nextStatus = 0;
            try {
                status(
                        config.root,
                        snapshot(
                                config,
                                "connecting",
                                received.get(),
                                written.get(),
                                collector.status(),
                                journal.status()));
                marketSnapshots.start();
                collector.begin();
                while (!stopping.get()
                        && !Files.exists(config.root.resolve("stop.request"))
                        && (session == null || !session.expired(clock))
                        && (config.seconds == 0
                                || Duration.between(started, Instant.now()).getSeconds()
                                        < config.seconds)) {
                    int n = 0;
                    String writeFailure = null;
                    Map<String, Object> transport = collector.status();
                    if (Boolean.TRUE.equals(transport.get("fatal"))) {
                        String code = String.valueOf(transport.get("failureCode"));
                        throw new IllegalStateException(
                                code.matches("[a-z][a-z_]{2,80}")
                                        ? "MQTT_" + code.toUpperCase(Locale.ROOT)
                                        : "MQTT_COLLECTOR_FAILED");
                    }
                    if (Boolean.TRUE.equals(transport.get("ready"))) {
                        try {
                            n = processor.processOnce();
                            written.addAndGet(n);
                        } catch (Exception error) {
                            writeFailure = safeCode(error);
                        }
                    }
                    long now = System.nanoTime();
                    if (now >= nextStatus) {
                        String phase =
                                writeFailure != null
                                        ? "write_retry"
                                        : !Boolean.TRUE.equals(transport.get("ready"))
                                                ? "reconnecting"
                                                : received.get() == 0
                                                        ? "waiting_messages"
                                                        : "running";
                        var state =
                                new LinkedHashMap<String, Object>(
                                        snapshot(
                                                config,
                                                phase,
                                                received.get(),
                                                written.get(),
                                                transport,
                                                journal.status()));
                        state.put("rawWriter", rawWriter.status());
                        state.put("marketSnapshots", marketSnapshots.status());
                        if (writeFailure != null) state.put("writeFailureCode", writeFailure);
                        status(config.root, state);
                        nextStatus = now + Duration.ofSeconds(1).toNanos();
                    }
                    if (n == 0)
                        Thread.sleep(writeFailure == null ? config.pollInterval.toMillis() : 1000);
                }
                Map<String, Object> finalTransport = collector.status();
                boolean drainAuthorized =
                        Boolean.TRUE.equals(finalTransport.get("ready"))
                                && !Boolean.TRUE.equals(finalTransport.get("fatal"));
                collector.close();
                rawWriter.close();
                Instant drainDeadline = Instant.now().plusSeconds(10);
                while (drainAuthorized && Instant.now().isBefore(drainDeadline)) {
                    int n = processor.processOnce();
                    written.addAndGet(n);
                    if (n == 0) break;
                }
                status(
                        config.root,
                        snapshot(
                                config,
                                "stopped",
                                received.get(),
                                written.get(),
                                collector.status(),
                                journal.status()));
            } catch (Exception failure) {
                // 先停止接收并排空所有已接受的报文，再记录失败时的实际持久化计数。
                try {
                    collector.close();
                } catch (Exception closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                try {
                    rawWriter.close();
                } catch (Exception drainFailure) {
                    failure.addSuppressed(drainFailure);
                }
                try {
                    var state =
                            new LinkedHashMap<String, Object>(
                                    snapshot(
                                            config,
                                            "failed",
                                            received.get(),
                                            written.get(),
                                            collector.status(),
                                            journal.status()));
                    state.put("rawWriter", rawWriter.status());
                    state.put("marketSnapshots", marketSnapshots.status());
                    state.put("failureCode", safeCode(failure));
                    status(config.root, state);
                } catch (Exception statusFailure) {
                    failure.addSuppressed(statusFailure);
                }
                throw failure;
            } finally {
                finished.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    private static boolean hasLocalPythonCollector() {
        return ProcessHandle.allProcesses()
                .anyMatch(
                        process -> {
                            String executable =
                                    process.info().command().orElse("").toLowerCase(Locale.ROOT);
                            String line = process.info().commandLine().orElse("");
                            return executable.contains("python")
                                    && line.contains("aliyun_l2_gateway");
                        });
    }

    private static Map<String, Object> snapshot(
            L2LiveConfiguration config,
            String phase,
            long received,
            long written,
            Map<String, Object> collector,
            Object journal) {
        var state = new LinkedHashMap<String, Object>();
        state.put("producer", "java.aliyun_l2_live");
        state.put("phase", phase);
        state.put("processId", ProcessHandle.current().pid());
        state.put("at", Instant.now().toString());
        state.put("symbols", config.symbols);
        state.put("receivedMessages", received);
        state.put("verifiedEventRows", written);
        state.put("collector", collector);
        state.put("journal", journal);
        return state;
    }

    private static void phase(Path root, String phase) throws Exception {
        status(
                root,
                Map.of(
                        "producer",
                        "java.aliyun_l2_live",
                        "phase",
                        phase,
                        "processId",
                        ProcessHandle.current().pid(),
                        "at",
                        Instant.now().toString(),
                        "connected",
                        false));
    }

    private static String safeCode(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]{2,100}")
                ? message
                : "L2_LIVE_" + error.getClass().getSimpleName();
    }

    private static void print(Object value) throws Exception {
        System.out.println(JSON.writeValueAsString(value));
    }

    private static void status(Path root, Object value) throws Exception {
        Path pending = root.resolve("status.json.tmp");
        String serialized = JSON.writeValueAsString(value);
        for (int attempt = 0; ; attempt++) {
            try {
                Files.writeString(pending, serialized, StandardCharsets.UTF_8);
                try {
                    Files.move(
                            pending,
                            root.resolve("status.json"),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(
                            pending,
                            root.resolve("status.json"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException busyReader) {
                // Windows readers need not grant FILE_SHARE_DELETE. A short
                // status or pending-file read can also raise FileSystemException
                // for a sharing violation; protect writing and renaming alike.
                // Permanent permissions failures remain bounded and visible.
                if (attempt >= 40) throw busyReader;
                Thread.sleep(25);
            }
        }
    }
}
