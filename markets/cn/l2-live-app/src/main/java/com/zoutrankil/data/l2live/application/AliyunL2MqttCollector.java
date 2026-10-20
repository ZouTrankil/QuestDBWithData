package com.zoutrankil.data.l2live.application;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** 直接接收 MQTT 消息；异步监听的完成凭证成功后才累计已持久化数。 */
public final class AliyunL2MqttCollector implements AutoCloseable {
    // JUL 仅弱引用日志器；需在客户端整个生命周期内持有经过安全配置的父日志器。
    private static final Logger PAHO_LOG_NAMESPACE =
            Logger.getLogger("org.eclipse.paho.client.mqttv3");
    private final L2MqttSettings settings;
    private final Function<ReceivedMessage, CompletionStage<Void>> listener;
    private final Clock clock;
    private final ScheduledExecutorService reconnectExecutor;
    private final Object lifecycle = new Object();
    private CompletableFuture<Void> authorizationChanged = new CompletableFuture<>();
    private final CompletableFuture<Void> transportClosed = new CompletableFuture<>();
    private final AtomicBoolean transportStopped = new AtomicBoolean();
    private final Set<String> expectedTopics;
    private IMqttAsyncClient client;
    private ScheduledFuture<?> pendingReconnect;
    private ScheduledFuture<?> pendingAuthorizationTimeout;
    private String state = "idle", failureCode = "", sessionId = "";
    private boolean started, closed, connected, fatal, connectInFlight;
    private long generation,
            connectAttempt,
            sessionCount,
            disconnectCount,
            receivedCount,
            persistedCount;
    private long captureFailures, subscriptionFailures, transportCloseFailures;
    private int authorizedTopics, backoffSeconds = 5, lastMqttReason;
    private Instant lastReceivedAt;

    public AliyunL2MqttCollector(L2MqttSettings settings, Consumer<ReceivedMessage> listener) {
        this(
                settings,
                listener,
                null,
                Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            var thread = new Thread(task, "aliyun-l2-mqtt-reconnect");
                            thread.setDaemon(true);
                            return thread;
                        }));
    }

    /** 离线测试替换点：注入的客户端和调度器不会建立真实网络连接。 */
    AliyunL2MqttCollector(
            L2MqttSettings settings,
            Consumer<ReceivedMessage> listener,
            IMqttAsyncClient client,
            Clock clock,
            ScheduledExecutorService reconnectExecutor) {
        this(
                settings,
                message -> {
                    listener.accept(message);
                    return CompletableFuture.completedFuture(null);
                },
                client,
                clock,
                reconnectExecutor,
                true);
    }

    /** 运行时使用持久化凭证；入队成功不表示磁盘提交完成。 */
    public static AliyunL2MqttCollector durable(
            L2MqttSettings settings, Function<ReceivedMessage, CompletionStage<Void>> listener) {
        return new AliyunL2MqttCollector(
                settings,
                listener,
                null,
                Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            var thread = new Thread(task, "aliyun-l2-mqtt-reconnect");
                            thread.setDaemon(true);
                            return thread;
                        }),
                true);
    }

    private AliyunL2MqttCollector(
            L2MqttSettings settings,
            Function<ReceivedMessage, CompletionStage<Void>> listener,
            IMqttAsyncClient client,
            Clock clock,
            ScheduledExecutorService reconnectExecutor,
            boolean asynchronous) {
        this.settings = Objects.requireNonNull(settings);
        this.listener = Objects.requireNonNull(listener);
        this.client = client;
        this.clock = Objects.requireNonNull(clock);
        this.reconnectExecutor = Objects.requireNonNull(reconnectExecutor);
        expectedTopics = Set.copyOf(settings.topics());
    }

    /** 非阻塞、可重复调用；已关闭的采集器不能重新激活，网络中断时持续重试。 */
    public void begin() {
        synchronized (lifecycle) {
            if (closed) throw safe("collector_closed");
            if (started) return;
            started = true;
            try {
                if (client == null) {
                    disablePahoCredentialLogs();
                    client =
                            new MqttAsyncClient(
                                    settings.brokerUri(),
                                    settings.clientId(),
                                    new MemoryPersistence());
                }
                client.setCallback(new Callbacks());
            } catch (MqttException | RuntimeException invalid) {
                failLocked("mqtt_client_initialization_failed", reason(invalid));
            }
        }
        attemptConnection(Duration.ofSeconds(30));
    }

    /** 登录成功还不够；全部主题都收到 QoS 0 的 SUBACK 后，才算完成订阅。 */
    public void start(Duration timeout) {
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("A bounded positive MQTT start timeout is required");
        begin();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            CompletableFuture<Void> changed;
            String refusal;
            synchronized (lifecycle) {
                if (!closed && !fatal && connected && authorizedTopics == expectedTopics.size())
                    return;
                refusal =
                        closed || fatal
                                ? (failureCode.isEmpty() ? "collector_closed" : failureCode)
                                : null;
                changed = authorizationChanged;
            }
            if (refusal != null) {
                close();
                throw safe(refusal);
            }
            try {
                changed.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                close();
                throw safe("start_interrupted");
            } catch (TimeoutException timeoutExpired) {
                synchronized (lifecycle) {
                    failLocked("authorization_timeout", 0);
                }
                close();
                throw safe("authorization_timeout");
            } catch (ExecutionException unsuccessful) {
                String code;
                synchronized (lifecycle) {
                    code = failureCode.isEmpty() ? "authorization_failed" : failureCode;
                }
                close();
                throw safe(code);
            }
        }
    }

    private void attemptConnection(Duration timeout) {
        synchronized (lifecycle) {
            if (closed || fatal || connected || connectInFlight) return;
            connectInFlight = true;
            state = "connecting";
            long attempt = ++connectAttempt;
            var options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setUserName(settings.username());
            options.setPassword(settings.password().toCharArray());
            options.setCleanSession(true);
            options.setKeepAliveInterval(settings.keepAliveSeconds());
            // 采集器统一管理重试、取消和严格限定在 5～60 秒的退避。
            options.setAutomaticReconnect(false);
            options.setConnectionTimeout(
                    (int) Math.max(1, Math.min(30, (timeout.toMillis() + 999) / 1000)));
            try {
                client.connect(
                        options,
                        null,
                        new IMqttActionListener() {
                            @Override
                            public void onSuccess(IMqttToken token) {
                                /* 由 connectComplete 开始订阅授权流程。 */
                            }

                            @Override
                            public void onFailure(IMqttToken token, Throwable error) {
                                connectionFailed(attempt, error);
                            }
                        });
            } catch (MqttException | RuntimeException invalid) {
                connectionFailed(attempt, invalid);
            }
        }
    }

    private void connectionFailed(long attempt, Throwable error) {
        synchronized (lifecycle) {
            if (closed || fatal || connected || attempt != connectAttempt) return;
            connectInFlight = false;
            lastMqttReason = reason(error);
            if (lastMqttReason == 4 || lastMqttReason == 5) {
                failLocked("mqtt_authentication_rejected", lastMqttReason);
                return;
            }
            state = "connect_retry";
            failureCode = "mqtt_connect_failed";
            scheduleReconnectLocked();
        }
    }

    private void scheduleReconnectLocked() {
        if (closed || fatal || pendingReconnect != null && !pendingReconnect.isDone()) return;
        int delay = backoffSeconds;
        backoffSeconds = Math.min(60, backoffSeconds * 2);
        try {
            pendingReconnect =
                    reconnectExecutor.schedule(
                            () -> {
                                synchronized (lifecycle) {
                                    pendingReconnect = null;
                                }
                                attemptConnection(Duration.ofSeconds(30));
                            },
                            delay,
                            TimeUnit.SECONDS);
        } catch (RejectedExecutionException stopped) {
            failLocked("mqtt_reconnect_scheduler_stopped", 0);
        }
    }

    private final class Callbacks implements MqttCallbackExtended {
        @Override
        public void connectComplete(boolean reconnect, String serverUri) {
            synchronized (lifecycle) {
                if (closed || fatal) return;
                connected = true;
                connectInFlight = false;
                authorizedTopics = 0;
                state = "subscribing";
                if (pendingReconnect != null) {
                    pendingReconnect.cancel(false);
                    pendingReconnect = null;
                }
                sessionId = UUID.randomUUID().toString();
                sessionCount++;
                if (authorizationChanged.isDone()) authorizationChanged = new CompletableFuture<>();
                long sessionGeneration = ++generation;
                String[] topics = settings.topics().toArray(String[]::new);
                int[] qos = new int[topics.length];
                try {
                    pendingAuthorizationTimeout =
                            reconnectExecutor.schedule(
                                    () -> {
                                        synchronized (lifecycle) {
                                            if (!closed
                                                    && !fatal
                                                    && connected
                                                    && generation == sessionGeneration
                                                    && authorizedTopics != topics.length)
                                                failLocked("mqtt_suback_timeout", 0);
                                        }
                                    },
                                    20,
                                    TimeUnit.SECONDS);
                    client.subscribe(
                            topics,
                            qos,
                            null,
                            new IMqttActionListener() {
                                @Override
                                public void onSuccess(IMqttToken token) {
                                    synchronized (lifecycle) {
                                        if (closed
                                                || fatal
                                                || !connected
                                                || generation != sessionGeneration) return;
                                        int[] granted =
                                                token == null ? null : token.getGrantedQos();
                                        if (granted == null
                                                || granted.length != topics.length
                                                || Arrays.stream(granted)
                                                        .anyMatch(value -> value != 0)) {
                                            subscriptionFailures++;
                                            failLocked("mqtt_subscription_rejected", 0);
                                            return;
                                        }
                                        if (pendingAuthorizationTimeout != null)
                                            pendingAuthorizationTimeout.cancel(false);
                                        authorizedTopics = topics.length;
                                        backoffSeconds = 5;
                                        failureCode = "";
                                        state = "ready";
                                        authorizationChanged.complete(null);
                                    }
                                }

                                @Override
                                public void onFailure(IMqttToken token, Throwable error) {
                                    synchronized (lifecycle) {
                                        if (closed
                                                || fatal
                                                || !connected
                                                || generation != sessionGeneration) return;
                                        subscriptionFailures++;
                                        failLocked("mqtt_subscription_failed", reason(error));
                                    }
                                }
                            });
                } catch (MqttException | RuntimeException invalid) {
                    subscriptionFailures++;
                    failLocked("mqtt_subscription_failed", reason(invalid));
                }
            }
        }

        @Override
        public void connectionLost(Throwable cause) {
            synchronized (lifecycle) {
                if (closed || fatal) return;
                connected = false;
                connectInFlight = false;
                authorizedTopics = 0;
                generation++;
                CompletableFuture<Void> previous = authorizationChanged;
                authorizationChanged = new CompletableFuture<>();
                previous.complete(null);
                if (pendingAuthorizationTimeout != null) pendingAuthorizationTimeout.cancel(false);
                disconnectCount++;
                state = "disconnected";
                failureCode = "mqtt_connection_lost";
                lastMqttReason = reason(cause);
                scheduleReconnectLocked();
            }
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) throws Exception {
            ReceivedMessage received;
            synchronized (lifecycle) {
                if (closed || fatal || !connected || sessionId.isEmpty()) return;
                received =
                        new ReceivedMessage(
                                topic,
                                message.getPayload(),
                                clock.instant(),
                                sessionId,
                                message.getQos(),
                                message.isRetained(),
                                message.isDuplicate(),
                                authorizedTopics == expectedTopics.size());
                receivedCount++;
                lastReceivedAt = received.receivedAt();
            }
            try {
                listener.apply(received)
                        .whenComplete(
                                (unused, error) -> {
                                    synchronized (lifecycle) {
                                        if (error != null) {
                                            captureFailures++;
                                            failLocked("raw_capture_failed", 0);
                                            return;
                                        }
                                        persistedCount++;
                                        if (!expectedTopics.contains(topic))
                                            failLocked("mqtt_unexpected_topic", 0);
                                        else if (received.qos() != 0)
                                            failLocked("mqtt_unexpected_message_qos", 0);
                                    }
                                });
            } catch (RuntimeException captureFailed) {
                synchronized (lifecycle) {
                    captureFailures++;
                    failLocked("raw_capture_failed", 0);
                }
                throw new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION);
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            /* 订阅客户端从不发布消息。 */
        }
    }

    private void failLocked(String code, int mqttReason) {
        if (closed || fatal) return;
        fatal = true;
        connected = false;
        connectInFlight = false;
        authorizedTopics = 0;
        generation++;
        state = "failed";
        failureCode = code;
        lastMqttReason = mqttReason;
        if (pendingReconnect != null) pendingReconnect.cancel(false);
        if (pendingAuthorizationTimeout != null) pendingAuthorizationTimeout.cancel(false);
        authorizationChanged.completeExceptionally(safe(code));
        // 在 Paho 回调之外断开连接，防止回调线程等待自身而死锁。
        try {
            reconnectExecutor.execute(this::stopTransport);
        } catch (RejectedExecutionException alreadyStopping) {
            /* close 同时负责调用 stopTransport。 */
        }
    }

    public Map<String, Object> status() {
        synchronized (lifecycle) {
            var result = new LinkedHashMap<String, Object>();
            result.put("state", state);
            result.put(
                    "ready",
                    !closed && !fatal && connected && authorizedTopics == expectedTopics.size());
            result.put("connected", connected);
            result.put("started", started);
            result.put("closed", closed);
            result.put("fatal", fatal);
            result.put("failureCode", failureCode);
            result.put("mqttReasonCode", lastMqttReason);
            result.put("topics", settings.topics());
            result.put("authorizedTopicCount", authorizedTopics);
            result.put("sessionId", sessionId);
            result.put("sessionCount", sessionCount);
            result.put("disconnectCount", disconnectCount);
            result.put("connectAttemptCount", connectAttempt);
            result.put("receivedCount", receivedCount);
            result.put("persistedCount", persistedCount);
            result.put("captureFailureCount", captureFailures);
            result.put("subscriptionFailureCount", subscriptionFailures);
            result.put("transportCloseFailureCount", transportCloseFailures);
            result.put("lastReceivedAt", lastReceivedAt == null ? "" : lastReceivedAt.toString());
            return Collections.unmodifiableMap(result);
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            connected = false;
            authorizedTopics = 0;
            generation++;
            state = "closed";
            if (pendingReconnect != null) pendingReconnect.cancel(false);
            if (pendingAuthorizationTimeout != null) pendingAuthorizationTimeout.cancel(false);
            authorizationChanged.completeExceptionally(safe("closed_before_authorization"));
        }
        reconnectExecutor.shutdown();
        stopTransport();
        try {
            transportClosed.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException unfinished) {
            synchronized (lifecycle) {
                transportCloseFailures++;
            }
        } finally {
            reconnectExecutor.shutdownNow();
        }
    }

    private void stopTransport() {
        if (!transportStopped.compareAndSet(false, true)) return;
        IMqttAsyncClient transport;
        synchronized (lifecycle) {
            transport = client;
        }
        try {
            if (transport != null) {
                try {
                    transport.disconnectForcibly(0, 2_000);
                } catch (MqttException | RuntimeException unsuccessful) {
                    synchronized (lifecycle) {
                        transportCloseFailures++;
                    }
                }
                try {
                    if (transport instanceof MqttAsyncClient concrete) concrete.close(true);
                    else transport.close();
                } catch (MqttException | RuntimeException unsuccessful) {
                    synchronized (lifecycle) {
                        transportCloseFailures++;
                    }
                }
            }
        } finally {
            transportClosed.complete(null);
        }
    }

    private static int reason(Throwable error) {
        return error instanceof MqttException mqtt ? mqtt.getReasonCode() : 0;
    }

    private static IllegalStateException safe(String code) {
        return new IllegalStateException("aliyun_l2:" + code);
    }

    /** Paho 会把客户端标识放入 JUL 资源名；诊断输出只能使用本模块的安全状态码。 */
    private static void disablePahoCredentialLogs() {
        String namespace = "org.eclipse.paho.client.mqttv3";
        var logger = PAHO_LOG_NAMESPACE;
        logger.setLevel(Level.OFF);
        logger.setUseParentHandlers(false);
        var manager = LogManager.getLogManager();
        var names = manager.getLoggerNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.equals(namespace) || name.startsWith(namespace + ".")) {
                var existing = manager.getLogger(name);
                if (existing != null) {
                    existing.setLevel(Level.OFF);
                    existing.setUseParentHandlers(false);
                }
            }
        }
    }
}
