package com.zoutrankil.data.l2live.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Direct MQTT ingestion. Listener completion means the raw envelope has been durably captured. */
public final class AliyunL2MqttCollector implements AutoCloseable {
    // JUL registers loggers weakly; retain the safe parent for the lifetime of every client.
    private static final java.util.logging.Logger PAHO_LOG_NAMESPACE =
            java.util.logging.Logger.getLogger("org.eclipse.paho.client.mqttv3");
    private final L2MqttSettings settings;
    private final Consumer<ReceivedMessage> listener;
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
    private long generation, connectAttempt, sessionCount, disconnectCount, receivedCount, persistedCount;
    private long captureFailures, subscriptionFailures, transportCloseFailures;
    private int authorizedTopics, backoffSeconds = 5, lastMqttReason;
    private Instant lastReceivedAt;

    public AliyunL2MqttCollector(L2MqttSettings settings, Consumer<ReceivedMessage> listener) {
        this(settings, listener, null, Clock.systemUTC(), Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "aliyun-l2-mqtt-reconnect"); thread.setDaemon(true); return thread;
        }));
    }

    /** Offline test seam: injected client/scheduler never create a real network connection. */
    AliyunL2MqttCollector(L2MqttSettings settings, Consumer<ReceivedMessage> listener,
                         IMqttAsyncClient client, Clock clock, ScheduledExecutorService reconnectExecutor) {
        this.settings = Objects.requireNonNull(settings);
        this.listener = Objects.requireNonNull(listener);
        this.client = client;
        this.clock = Objects.requireNonNull(clock);
        this.reconnectExecutor = Objects.requireNonNull(reconnectExecutor);
        expectedTopics = Set.copyOf(settings.topics());
    }

    /** Nonblocking and idempotent; a closed collector cannot be reactivated. Network outages keep retrying. */
    public void begin() {
        synchronized (lifecycle) {
            if (closed) throw safe("collector_closed");
            if (started) return;
            started = true;
            try {
                if (client == null) {
                    disablePahoCredentialLogs();
                    client = new MqttAsyncClient(settings.brokerUri(), settings.clientId(), new MemoryPersistence());
                }
                client.setCallback(new Callbacks());
            } catch (MqttException | RuntimeException invalid) { failLocked("mqtt_client_initialization_failed", reason(invalid)); }
        }
        attemptConnection(Duration.ofSeconds(30));
    }

    /** A successful login alone does not complete this method: every topic must receive QoS0 SUBACK. */
    public void start(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("A bounded positive MQTT start timeout is required");
        begin();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            CompletableFuture<Void> changed;
            String refusal;
            synchronized (lifecycle) {
                if (!closed && !fatal && connected && authorizedTopics == expectedTopics.size()) return;
                refusal = closed || fatal ? (failureCode.isEmpty() ? "collector_closed" : failureCode) : null;
                changed = authorizationChanged;
            }
            if (refusal != null) { close(); throw safe(refusal); }
            try { changed.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); close(); throw safe("start_interrupted");
            } catch (TimeoutException timeoutExpired) {
                synchronized (lifecycle) { failLocked("authorization_timeout", 0); }
                close(); throw safe("authorization_timeout");
            } catch (ExecutionException unsuccessful) {
                String code;
                synchronized (lifecycle) { code = failureCode.isEmpty() ? "authorization_failed" : failureCode; }
                close(); throw safe(code);
            }
        }
    }

    private void attemptConnection(Duration timeout) {
        synchronized (lifecycle) {
            if (closed || fatal || connected || connectInFlight) return;
            connectInFlight = true; state = "connecting";
            long attempt = ++connectAttempt;
            var options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setUserName(settings.username()); options.setPassword(settings.password().toCharArray());
            options.setCleanSession(true); options.setKeepAliveInterval(settings.keepAliveSeconds());
            // The collector owns retries, including cancellation and exact 5..60-second backoff.
            options.setAutomaticReconnect(false);
            options.setConnectionTimeout((int)Math.max(1, Math.min(30, (timeout.toMillis() + 999) / 1000)));
            try {
                client.connect(options, null, new IMqttActionListener() {
                    @Override public void onSuccess(IMqttToken token) { /* connectComplete starts authorization. */ }
                    @Override public void onFailure(IMqttToken token, Throwable error) { connectionFailed(attempt, error); }
                });
            } catch (MqttException | RuntimeException invalid) { connectionFailed(attempt, invalid); }
        }
    }

    private void connectionFailed(long attempt, Throwable error) {
        synchronized (lifecycle) {
            if (closed || fatal || connected || attempt != connectAttempt) return;
            connectInFlight = false; lastMqttReason = reason(error);
            if (lastMqttReason == 4 || lastMqttReason == 5) { failLocked("mqtt_authentication_rejected", lastMqttReason); return; }
            state = "connect_retry"; failureCode = "mqtt_connect_failed"; scheduleReconnectLocked();
        }
    }

    private void scheduleReconnectLocked() {
        if (closed || fatal || pendingReconnect != null && !pendingReconnect.isDone()) return;
        int delay = backoffSeconds; backoffSeconds = Math.min(60, backoffSeconds * 2);
        try {
            pendingReconnect = reconnectExecutor.schedule(() -> {
                synchronized (lifecycle) { pendingReconnect = null; }
                attemptConnection(Duration.ofSeconds(30));
            }, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException stopped) { failLocked("mqtt_reconnect_scheduler_stopped", 0); }
    }

    private final class Callbacks implements MqttCallbackExtended {
        @Override public void connectComplete(boolean reconnect, String serverUri) {
            synchronized (lifecycle) {
                if (closed || fatal) return;
                connected = true; connectInFlight = false; authorizedTopics = 0; state = "subscribing";
                if (pendingReconnect != null) { pendingReconnect.cancel(false); pendingReconnect = null; }
                sessionId = UUID.randomUUID().toString(); sessionCount++;
                if (authorizationChanged.isDone()) authorizationChanged = new CompletableFuture<>();
                long sessionGeneration = ++generation;
                String[] topics = settings.topics().toArray(String[]::new);
                int[] qos = new int[topics.length];
                try {
                    pendingAuthorizationTimeout = reconnectExecutor.schedule(() -> {
                        synchronized (lifecycle) {
                            if (!closed && !fatal && connected && generation == sessionGeneration && authorizedTopics != topics.length)
                                failLocked("mqtt_suback_timeout", 0);
                        }
                    }, 20, TimeUnit.SECONDS);
                    client.subscribe(topics, qos, null, new IMqttActionListener() {
                        @Override public void onSuccess(IMqttToken token) {
                            synchronized (lifecycle) {
                                if (closed || fatal || !connected || generation != sessionGeneration) return;
                                int[] granted = token == null ? null : token.getGrantedQos();
                                if (granted == null || granted.length != topics.length || Arrays.stream(granted).anyMatch(value -> value != 0)) {
                                    subscriptionFailures++; failLocked("mqtt_subscription_rejected", 0); return;
                                }
                                if (pendingAuthorizationTimeout != null) pendingAuthorizationTimeout.cancel(false);
                                authorizedTopics = topics.length; backoffSeconds = 5; failureCode = "";
                                state = "ready"; authorizationChanged.complete(null);
                            }
                        }
                        @Override public void onFailure(IMqttToken token, Throwable error) {
                            synchronized (lifecycle) {
                                if (closed || fatal || !connected || generation != sessionGeneration) return;
                                subscriptionFailures++; failLocked("mqtt_subscription_failed", reason(error));
                            }
                        }
                    });
                } catch (MqttException | RuntimeException invalid) {
                    subscriptionFailures++; failLocked("mqtt_subscription_failed", reason(invalid));
                }
            }
        }

        @Override public void connectionLost(Throwable cause) {
            synchronized (lifecycle) {
                if (closed || fatal) return;
                connected = false; connectInFlight = false; authorizedTopics = 0; generation++;
                CompletableFuture<Void> previous = authorizationChanged;
                authorizationChanged = new CompletableFuture<>(); previous.complete(null);
                if (pendingAuthorizationTimeout != null) pendingAuthorizationTimeout.cancel(false);
                disconnectCount++; state = "disconnected"; failureCode = "mqtt_connection_lost";
                lastMqttReason = reason(cause); scheduleReconnectLocked();
            }
        }

        @Override public void messageArrived(String topic, MqttMessage message) throws Exception {
            ReceivedMessage received;
            synchronized (lifecycle) {
                if (closed || fatal || !connected || sessionId.isEmpty()) return;
                received = new ReceivedMessage(topic, message.getPayload(), clock.instant(), sessionId,
                        message.getQos(), message.isRetained(), message.isDuplicate(),
                        authorizedTopics == expectedTopics.size());
                receivedCount++; lastReceivedAt = received.receivedAt();
            }
            try {
                listener.accept(received); // The runtime must persist raw bytes before attempting cleaning.
                synchronized (lifecycle) {
                    persistedCount++;
                    if (!expectedTopics.contains(topic)) failLocked("mqtt_unexpected_topic", 0);
                    else if (received.qos() != 0) failLocked("mqtt_unexpected_message_qos", 0);
                }
            } catch (RuntimeException captureFailed) {
                synchronized (lifecycle) { captureFailures++; failLocked("raw_capture_failed", 0); }
                throw new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION);
            }
        }
        @Override public void deliveryComplete(IMqttDeliveryToken token) { /* Subscriber never publishes. */ }
    }

    private void failLocked(String code, int mqttReason) {
        if (closed || fatal) return;
        fatal = true; connected = false; connectInFlight = false; authorizedTopics = 0; generation++;
        state = "failed"; failureCode = code; lastMqttReason = mqttReason;
        if (pendingReconnect != null) pendingReconnect.cancel(false);
        if (pendingAuthorizationTimeout != null) pendingAuthorizationTimeout.cancel(false);
        authorizationChanged.completeExceptionally(safe(code));
        // Disconnect outside a Paho callback; its callback thread must never wait on itself.
        try { reconnectExecutor.execute(this::stopTransport); }
        catch (RejectedExecutionException alreadyStopping) { /* close also owns stopTransport. */ }
    }

    public Map<String, Object> status() {
        synchronized (lifecycle) {
            var result = new LinkedHashMap<String, Object>();
            result.put("state", state); result.put("ready", !closed && !fatal && connected && authorizedTopics == expectedTopics.size());
            result.put("connected", connected); result.put("started", started); result.put("closed", closed);
            result.put("fatal", fatal); result.put("failureCode", failureCode); result.put("mqttReasonCode", lastMqttReason);
            result.put("topics", settings.topics()); result.put("authorizedTopicCount", authorizedTopics);
            result.put("sessionId", sessionId); result.put("sessionCount", sessionCount); result.put("disconnectCount", disconnectCount);
            result.put("connectAttemptCount", connectAttempt); result.put("receivedCount", receivedCount); result.put("persistedCount", persistedCount);
            result.put("captureFailureCount", captureFailures); result.put("subscriptionFailureCount", subscriptionFailures);
            result.put("transportCloseFailureCount", transportCloseFailures);
            result.put("lastReceivedAt", lastReceivedAt == null ? "" : lastReceivedAt.toString());
            return Collections.unmodifiableMap(result);
        }
    }

    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true; connected = false; authorizedTopics = 0; generation++; state = "closed";
            if (pendingReconnect != null) pendingReconnect.cancel(false);
            if (pendingAuthorizationTimeout != null) pendingAuthorizationTimeout.cancel(false);
            authorizationChanged.completeExceptionally(safe("closed_before_authorization"));
        }
        reconnectExecutor.shutdown(); stopTransport();
        try { transportClosed.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException unfinished) {
            synchronized (lifecycle) { transportCloseFailures++; }
        }
        finally { reconnectExecutor.shutdownNow(); }
    }

    private void stopTransport() {
        if (!transportStopped.compareAndSet(false, true)) return;
        IMqttAsyncClient transport;
        synchronized (lifecycle) { transport = client; }
        try {
            if (transport != null) {
                try { transport.disconnectForcibly(0, 2_000); }
                catch (MqttException | RuntimeException unsuccessful) { synchronized (lifecycle) { transportCloseFailures++; } }
                try {
                    if (transport instanceof MqttAsyncClient concrete) concrete.close(true);
                    else transport.close();
                } catch (MqttException | RuntimeException unsuccessful) { synchronized (lifecycle) { transportCloseFailures++; } }
            }
        } finally { transportClosed.complete(null); }
    }

    private static int reason(Throwable error) { return error instanceof MqttException mqtt ? mqtt.getReasonCode() : 0; }
    private static IllegalStateException safe(String code) { return new IllegalStateException("aliyun_l2:" + code); }

    /** Paho embeds client ID in JUL resource names. Only our safe status codes are diagnostic output. */
    private static void disablePahoCredentialLogs() {
        String namespace = "org.eclipse.paho.client.mqttv3";
        var logger = PAHO_LOG_NAMESPACE;
        logger.setLevel(java.util.logging.Level.OFF); logger.setUseParentHandlers(false);
        var manager = java.util.logging.LogManager.getLogManager();
        var names = manager.getLoggerNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.equals(namespace) || name.startsWith(namespace + ".")) {
                var existing = manager.getLogger(name);
                if (existing != null) { existing.setLevel(java.util.logging.Level.OFF); existing.setUseParentHandlers(false); }
            }
        }
    }
}
