package com.zoutrankil.data.l2live.application;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AliyunL2MqttCollectorTest {
    private record Timer(Runnable task, long seconds) {}
    private static final class Fixture implements AutoCloseable {
        final IMqttAsyncClient client = mock(IMqttAsyncClient.class);
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final List<ReceivedMessage> received = new ArrayList<>();
        final List<IMqttActionListener> acknowledgments = new ArrayList<>();
        final List<Timer> timers = new ArrayList<>();
        final CountDownLatch subscribed = new CountDownLatch(1);
        final AliyunL2MqttCollector collector;
        MqttCallbackExtended callback;
        MqttConnectOptions options;
        boolean autoConnect = true, autoAck = true;
        int[] grant = {0, 0, 0};
        Throwable connectFailure;

        Fixture() throws Exception { this(null); }
        Fixture(Consumer<ReceivedMessage> listener) throws Exception {
            var settings = new L2MqttSettings("tcp://127.0.0.1:1883", "fixture_account", "fixture_secret", List.of("563360.SH"), 48, 20);
            collector = new AliyunL2MqttCollector(settings, listener == null ? received::add : listener, client,
                    Clock.fixed(Instant.parse("2026-10-14T02:00:00Z"), ZoneOffset.UTC), scheduler);
            doAnswer(call -> { callback = (MqttCallbackExtended)call.getArgument(0); return null; }).when(client).setCallback(any());
            doAnswer(call -> {
                options = call.getArgument(0);
                if (connectFailure != null) ((IMqttActionListener)call.getArgument(2)).onFailure(mock(IMqttToken.class), connectFailure);
                else if (autoConnect) callback.connectComplete(false, "tcp://127.0.0.1:1883");
                return mock(IMqttToken.class);
            }).when(client).connect(any(MqttConnectOptions.class), isNull(), any(IMqttActionListener.class));
            doAnswer(call -> {
                assertArrayEquals(new String[]{"trans/563360", "order/563360", "market/563360"}, call.getArgument(0));
                assertArrayEquals(new int[]{0, 0, 0}, call.getArgument(1));
                IMqttActionListener acknowledgment = call.getArgument(3); acknowledgments.add(acknowledgment);
                subscribed.countDown();
                if (autoAck) acknowledgment.onSuccess(token(grant));
                return mock(IMqttToken.class);
            }).when(client).subscribe(any(String[].class), any(int[].class), isNull(), any(IMqttActionListener.class));
            when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS))).thenAnswer(call -> {
                timers.add(new Timer(call.getArgument(0), call.getArgument(1)));
                return mock(ScheduledFuture.class);
            });
        }
        void start() { collector.start(Duration.ofSeconds(2)); }
        void message(String topic, int qos) throws Exception {
            MqttMessage message = mock(MqttMessage.class);
            when(message.getPayload()).thenReturn(new byte[]{0, -1, 42}); when(message.getQos()).thenReturn(qos);
            when(message.isRetained()).thenReturn(true); when(message.isDuplicate()).thenReturn(true);
            callback.messageArrived(topic, message);
        }
        @Override public void close() { collector.close(); }
    }
    private static IMqttToken token(int[] grant) {
        IMqttToken token = mock(IMqttToken.class); when(token.getGrantedQos()).thenReturn(grant); return token;
    }

    @Test void loginAloneIsNotReadyAndRawMayBeCapturedBeforeFullSuback() throws Exception {
        try (var f = new Fixture(); var worker = Executors.newSingleThreadExecutor()) {
            f.autoAck = false;
            var starting = worker.submit(f::start);
            assertTrue(f.subscribed.await(1, TimeUnit.SECONDS));
            assertFalse(starting.isDone()); assertEquals(false, f.collector.status().get("ready"));
            f.message("market/563360", 0);
            assertEquals(1, f.received.size()); assertArrayEquals(new byte[]{0, -1, 42}, f.received.getFirst().payload());
            assertTrue(f.received.getFirst().duplicate()); assertTrue(f.received.getFirst().retained());
            assertFalse(f.received.getFirst().subscriptionAuthorized());
            assertEquals(Instant.parse("2026-10-14T02:00:00Z"), f.received.getFirst().receivedAt());
            f.acknowledgments.getFirst().onSuccess(token(new int[]{0, 0, 0})); starting.get(1, TimeUnit.SECONDS);
            assertEquals(true, f.collector.status().get("ready")); assertEquals(3, f.collector.status().get("authorizedTopicCount"));
            f.message("market/563360", 0);
            assertFalse(f.received.getFirst().subscriptionAuthorized());
            assertTrue(f.received.getLast().subscriptionAuthorized());
            assertEquals(MqttConnectOptions.MQTT_VERSION_3_1_1, f.options.getMqttVersion());
            assertTrue(f.options.isCleanSession()); assertFalse(f.options.isAutomaticReconnect()); assertEquals(20, f.options.getKeepAliveInterval());
            assertFalse(f.collector.status().toString().contains("fixture_secret")); assertFalse(f.collector.status().toString().contains("fixture_account"));
        }
    }

    @Test void partialRejectedWrongQosMissingOrShortSubackAllFailClosed() throws Exception {
        for (int[] grant : Arrays.asList(new int[]{0, 128, 0}, new int[]{0, 1, 0}, new int[]{0, 0}, null)) {
            try (var f = new Fixture()) {
                f.grant = grant;
                var error = assertThrows(IllegalStateException.class, f::start);
                assertEquals("aliyun_l2:mqtt_subscription_rejected", error.getMessage()); assertNull(error.getCause());
                assertEquals(true, f.collector.status().get("fatal")); assertEquals(false, f.collector.status().get("ready"));
                assertEquals(0, f.collector.status().get("authorizedTopicCount"));
                verify(f.client).close();
            }
        }
    }

    @Test void reconnectHasANewSessionResubscribesAndDiscardsStaleAcknowledgments() throws Exception {
        try (var f = new Fixture()) {
            f.start(); f.message("trans/563360", 0);
            String first = f.received.getFirst().sessionId();
            assertTrue(f.received.getFirst().subscriptionAuthorized());
            f.callback.connectionLost(new RuntimeException("fixture_secret must never escape"));
            assertEquals(false, f.collector.status().get("ready"));
            f.autoAck = false;
            f.timers.stream().filter(timer -> timer.seconds() == 5).findFirst().orElseThrow().task().run();
            assertEquals(2, f.acknowledgments.size()); assertNotEquals(first, f.collector.status().get("sessionId"));
            f.acknowledgments.getFirst().onSuccess(token(new int[]{0, 0, 0}));
            assertEquals(false, f.collector.status().get("ready")); assertEquals(0, f.collector.status().get("authorizedTopicCount"));
            f.message("trans/563360", 0);
            var beforeNewSessionSuback = f.received.getLast();
            assertFalse(beforeNewSessionSuback.subscriptionAuthorized());
            assertNotEquals(first, beforeNewSessionSuback.sessionId());
            f.acknowledgments.getLast().onSuccess(token(new int[]{0, 0, 0})); f.message("trans/563360", 0);
            assertEquals(true, f.collector.status().get("ready")); assertEquals(2L, f.collector.status().get("sessionCount"));
            assertNotEquals(first, f.received.getLast().sessionId());
            assertFalse(beforeNewSessionSuback.subscriptionAuthorized());
            assertTrue(f.received.getLast().subscriptionAuthorized());
            assertFalse(f.collector.status().toString().contains("fixture_secret"));
        }
    }

    @Test void aReconnectWithoutSubackBecomesFatalInsteadOfRemainingConnectedForever() throws Exception {
        try (var f = new Fixture()) {
            f.start(); f.callback.connectionLost(new RuntimeException()); f.autoAck = false;
            f.timers.stream().filter(timer -> timer.seconds() == 5).findFirst().orElseThrow().task().run();
            f.timers.getLast().task().run();
            assertEquals(true, f.collector.status().get("fatal")); assertEquals("mqtt_suback_timeout", f.collector.status().get("failureCode"));
            f.acknowledgments.getLast().onSuccess(token(new int[]{0, 0, 0}));
            assertEquals(false, f.collector.status().get("ready"));
        }
    }

    @Test void durableCaptureFailureCannotBecomeAReportedSuccessOrLeakTheCause() throws Exception {
        try (var f = new Fixture(message -> { throw new IllegalStateException("fixture_account fixture_secret"); })) {
            f.start();
            var error = assertThrows(MqttException.class, () -> f.message("trans/563360", 0));
            assertNull(error.getCause()); assertFalse(error.toString().contains("fixture_secret"));
            assertEquals("raw_capture_failed", f.collector.status().get("failureCode"));
            assertEquals(1L, f.collector.status().get("receivedCount")); assertEquals(0L, f.collector.status().get("persistedCount"));
            assertEquals(1L, f.collector.status().get("captureFailureCount")); assertEquals(true, f.collector.status().get("fatal"));
        }
    }

    @Test void unexpectedTopicsAndMessageQosAreStillRawCapturedThenRefused() throws Exception {
        for (boolean wrongTopic : List.of(true, false)) {
            try (var f = new Fixture()) {
                f.start(); f.message(wrongTopic ? "market/588010" : "market/563360", wrongTopic ? 0 : 1);
                assertEquals(1, f.received.size()); assertEquals(1L, f.collector.status().get("persistedCount"));
                assertEquals(true, f.collector.status().get("fatal"));
                assertEquals(wrongTopic ? "mqtt_unexpected_topic" : "mqtt_unexpected_message_qos", f.collector.status().get("failureCode"));
            }
        }
    }

    @Test void authenticationRejectAndInitialTimeoutReleaseTransportWithoutSecretOutput() throws Exception {
        try (var f = new Fixture()) {
            f.connectFailure = new MqttException(5);
            var error = assertThrows(IllegalStateException.class, f::start);
            assertEquals("aliyun_l2:mqtt_authentication_rejected", error.getMessage()); assertNull(error.getCause());
            assertEquals(true, f.collector.status().get("fatal")); verify(f.client).close();
            assertTrue(f.timers.isEmpty());
        }
        try (var f = new Fixture()) {
            f.autoConnect = false;
            assertThrows(IllegalStateException.class, () -> f.collector.start(Duration.ofMillis(20)));
            assertEquals("authorization_timeout", f.collector.status().get("failureCode")); verify(f.client).close();
        }
    }

    @Test void closeIsIdempotentAndLateCallbacksCannotReconnectOrReauthorize() throws Exception {
        try (var f = new Fixture()) {
            f.start(); f.collector.close(); f.collector.close();
            f.callback.connectComplete(true, "not-printed-server-uri");
            f.callback.connectionLost(new RuntimeException("fixture_secret"));
            f.acknowledgments.getFirst().onSuccess(token(new int[]{0, 0, 0}));
            assertEquals(true, f.collector.status().get("closed")); assertEquals(false, f.collector.status().get("ready"));
            verify(f.client, times(1)).close(); verify(f.scheduler, times(1)).shutdownNow();
            assertThrows(IllegalStateException.class, f::start);
        }
    }

    @Test void beginIsNonblockingIdempotentAndDoesNotRequireImmediateBrokerAvailability() throws Exception {
        try (var f = new Fixture()) {
            f.autoConnect = false;
            f.collector.begin(); f.collector.begin();
            assertEquals("connecting", f.collector.status().get("state"));
            assertEquals(false, f.collector.status().get("ready")); assertEquals(false, f.collector.status().get("fatal"));
            verify(f.client, times(1)).connect(any(MqttConnectOptions.class), isNull(), any(IMqttActionListener.class));
            f.collector.close(); assertThrows(IllegalStateException.class, f.collector::begin);
        }
    }

    @Test void beginKeepsTransientFailuresRetryingAtFiveThenTenSecondsAndResetsAfterAuthorization() throws Exception {
        try (var f = new Fixture()) {
            f.connectFailure = new RuntimeException("fixture_account fixture_secret");
            f.collector.begin();
            assertEquals(false, f.collector.status().get("fatal")); assertEquals(5, f.timers.getLast().seconds());
            f.timers.getLast().task().run(); assertEquals(10, f.timers.getLast().seconds());
            f.connectFailure = null; f.timers.getLast().task().run();
            assertEquals(true, f.collector.status().get("ready")); assertEquals(3L, f.collector.status().get("connectAttemptCount"));
            assertFalse(f.collector.status().toString().contains("fixture_secret"));
            f.callback.connectionLost(new RuntimeException()); assertEquals(5, f.timers.getLast().seconds());
        }
    }
}
