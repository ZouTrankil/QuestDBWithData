package com.zoutrankil.data.l2live.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Actual Paho wire exchange against a loopback-only minimal MQTT3.1.1 test server. */
class AliyunL2MqttLoopbackTest {
    private static final String USER = "fixture_wire_account", PASSWORD = "fixture_wire_secret";
    private static final byte[] PAYLOAD = {0, 1, -1, 42, 13, 10};

    private record Packet(int header, byte[] body) {}

    private static final class Broker implements AutoCloseable {
        final ServerSocket server = new ServerSocket();
        final ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        task -> {
                            var thread = new Thread(task, "l2-loopback-test-broker");
                            thread.setDaemon(true);
                            return thread;
                        });
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch allowSuback = new CountDownLatch(1);
        final CountDownLatch allowPublish = new CountDownLatch(1);
        final int[] granted;
        final boolean publishBeforeSuback;
        final Future<?> work;
        volatile Socket connection;

        Broker(int... granted) throws IOException {
            this(false, granted);
        }

        Broker(boolean publishBeforeSuback, int... granted) throws IOException {
            this.granted = granted.clone();
            this.publishBeforeSuback = publishBeforeSuback;
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            server.setSoTimeout(7_000);
            work =
                    worker.submit(
                            () -> {
                                serve();
                                return null;
                            });
        }

        L2MqttSettings settings() {
            return new L2MqttSettings(
                    "tcp://127.0.0.1:" + server.getLocalPort(),
                    USER,
                    PASSWORD,
                    List.of("563360.SH"),
                    48,
                    20);
        }

        private void serve() throws Exception {
            try (Socket socket = server.accept()) {
                connection = socket;
                socket.setSoTimeout(7_000);
                assertTrue(socket.getInetAddress().isLoopbackAddress());
                var input = new DataInputStream(socket.getInputStream());
                var output = new DataOutputStream(socket.getOutputStream());
                Packet connect = read(input);
                assertNotNull(connect);
                assertEquals(1, connect.header() >>> 4);
                var fields = new DataInputStream(new ByteArrayInputStream(connect.body()));
                assertEquals("MQTT", text(fields));
                assertEquals(4, fields.readUnsignedByte());
                int flags = fields.readUnsignedByte();
                assertEquals(0xC2, flags);
                assertEquals(20, fields.readUnsignedShort());
                String clientId = text(fields), username = text(fields), password = text(fields);
                assertTrue(
                        USER.equals(clientId) && clientId.equals(username),
                        "Wire client identity must match username");
                assertTrue(
                        PASSWORD.equals(password),
                        "Wire password must match the private test fixture");
                assertEquals(0, fields.available());
                write(output, 0x20, new byte[] {0, 0});

                Packet subscribe = read(input);
                assertNotNull(subscribe);
                assertEquals(0x82, subscribe.header());
                var topics = new DataInputStream(new ByteArrayInputStream(subscribe.body()));
                int messageId = topics.readUnsignedShort();
                var actual = new ArrayList<String>();
                while (topics.available() > 0) {
                    actual.add(text(topics));
                    assertEquals(0, topics.readUnsignedByte());
                }
                assertEquals(List.of("trans/563360", "order/563360", "market/563360"), actual);
                subscribed.countDown();
                if (publishBeforeSuback) publish(output);
                assertTrue(allowSuback.await(5, TimeUnit.SECONDS), "Test must release SUBACK");
                var acknowledgment = new ByteArrayOutputStream();
                var ack = new DataOutputStream(acknowledgment);
                ack.writeShort(messageId);
                for (int qos : granted) ack.writeByte(qos);
                write(output, 0x90, acknowledgment.toByteArray());

                if (Arrays.stream(granted).allMatch(qos -> qos == 0)) {
                    assertTrue(
                            allowPublish.await(5, TimeUnit.SECONDS), "Test must release PUBLISH");
                    publish(output);
                }
                Packet disconnect = read(input);
                if (disconnect != null) {
                    assertEquals(14, disconnect.header() >>> 4);
                    assertEquals(0, disconnect.body().length);
                }
            }
        }

        private static void publish(DataOutputStream output) throws IOException {
            var publish = new ByteArrayOutputStream();
            var body = new DataOutputStream(publish);
            text(body, "market/563360");
            body.write(PAYLOAD);
            write(
                    output,
                    0x31,
                    publish.toByteArray()); // QoS0 retained snapshot, exact binary payload.
        }

        void finished() throws Exception {
            work.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            allowSuback.countDown();
            allowPublish.countDown();
            if (connection != null) connection.close();
            server.close();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Packet read(DataInputStream input) throws IOException {
        int header = input.read();
        if (header < 0) return null;
        int size = 0, multiplier = 1, value;
        for (int n = 0; ; n++) {
            if (n >= 4) throw new IOException("Invalid loopback MQTT remaining length");
            value = input.readUnsignedByte();
            size += (value & 127) * multiplier;
            if (size > 1_048_576) throw new IOException("Loopback MQTT packet exceeds bound");
            if ((value & 128) == 0) break;
            multiplier *= 128;
        }
        byte[] body = input.readNBytes(size);
        if (body.length != size) throw new EOFException("Short loopback MQTT packet");
        return new Packet(header, body);
    }

    private static void write(DataOutputStream output, int header, byte[] body) throws IOException {
        output.writeByte(header);
        int remaining = body.length;
        do {
            int value = remaining % 128;
            remaining /= 128;
            output.writeByte(remaining == 0 ? value : value | 128);
        } while (remaining > 0);
        output.write(body);
        output.flush();
    }

    private static String text(DataInputStream input) throws IOException {
        int size = input.readUnsignedShort();
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException("Short MQTT UTF8 field");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static void eventually(BooleanSupplier ready) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(ready.getAsBoolean(), "Bounded loopback callback did not complete");
    }

    private static void secretSafe(AliyunL2MqttCollector collector, Throwable error) {
        String status = collector.status().toString();
        assertFalse(status.contains(USER));
        assertFalse(status.contains(PASSWORD));
        if (error != null) {
            var stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            assertFalse(stack.toString().contains(USER));
            assertFalse(stack.toString().contains(PASSWORD));
        }
    }

    @Test
    void realPahoWaitsForEveryWireSubackAndFreezesReceiveTimeAuthorizationWithExactRawBytes()
            throws Exception {
        var received = new CopyOnWriteArrayList<ReceivedMessage>();
        try (var broker = new Broker(true, 0, 0, 0);
                var collector = new AliyunL2MqttCollector(broker.settings(), received::add);
                var starter = Executors.newSingleThreadExecutor()) {
            collector.begin();
            collector.begin();
            var start = starter.submit(() -> collector.start(Duration.ofSeconds(6)));
            assertTrue(broker.subscribed.await(5, TimeUnit.SECONDS));
            assertFalse(start.isDone());
            assertEquals(false, collector.status().get("ready"));
            eventually(() -> Long.valueOf(1).equals(collector.status().get("persistedCount")));
            assertEquals(1, received.size());
            assertFalse(received.getFirst().subscriptionAuthorized());
            assertFalse(start.isDone());
            broker.allowSuback.countDown();
            start.get(6, TimeUnit.SECONDS);
            assertEquals(true, collector.status().get("ready"));
            assertEquals(3, collector.status().get("authorizedTopicCount"));
            broker.allowPublish.countDown();
            eventually(() -> Long.valueOf(2).equals(collector.status().get("persistedCount")));
            assertEquals(2, received.size());
            assertFalse(received.getFirst().subscriptionAuthorized());
            assertTrue(received.getLast().subscriptionAuthorized());
            assertEquals(received.getFirst().sessionId(), received.getLast().sessionId());
            for (var message : received) {
                assertArrayEquals(PAYLOAD, message.payload());
                assertEquals("market/563360", message.topic());
                assertEquals(0, message.qos());
                assertTrue(message.retained());
                assertFalse(message.duplicate());
                assertFalse(message.sessionId().isBlank());
                assertNotNull(message.receivedAt());
            }
            collector.close();
            broker.finished();
            secretSafe(collector, null);
        }
    }

    @Test
    void realPahoRejectsWireSuback128WithoutClaimingAuthorizationOrExposingCredentials()
            throws Exception {
        try (var broker = new Broker(0, 128, 0);
                var collector =
                        new AliyunL2MqttCollector(
                                broker.settings(),
                                message -> fail("Denied subscription must not publish"));
                var starter = Executors.newSingleThreadExecutor()) {
            var start = starter.submit(() -> collector.start(Duration.ofSeconds(6)));
            assertTrue(broker.subscribed.await(5, TimeUnit.SECONDS));
            broker.allowSuback.countDown();
            var error =
                    assertThrows(ExecutionException.class, () -> start.get(6, TimeUnit.SECONDS));
            assertEquals(true, collector.status().get("fatal"));
            assertEquals(false, collector.status().get("ready"));
            assertEquals(0, collector.status().get("authorizedTopicCount"));
            // Paho can report a denied topic through either failed action or granted QoS128; both
            // refuse readiness.
            assertTrue(
                    Set.of("mqtt_subscription_rejected", "mqtt_subscription_failed")
                            .contains(collector.status().get("failureCode")));
            collector.close();
            broker.finished();
            secretSafe(collector, error);
        }
    }

    @Test
    void realPahoCaptureFailureIsFatalAndTheOriginalExceptionNeverEscapes() throws Exception {
        var logRecords = Collections.synchronizedList(new ArrayList<LogRecord>());
        var logger = Logger.getLogger("org.eclipse.paho.client.mqttv3");
        var handler =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        logRecords.add(record);
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        logger.addHandler(handler);
        try (var broker = new Broker(0, 0, 0);
                var collector =
                        new AliyunL2MqttCollector(
                                broker.settings(),
                                message -> {
                                    throw new IllegalStateException(USER + " " + PASSWORD);
                                });
                var starter = Executors.newSingleThreadExecutor()) {
            var start = starter.submit(() -> collector.start(Duration.ofSeconds(6)));
            assertTrue(broker.subscribed.await(5, TimeUnit.SECONDS));
            broker.allowSuback.countDown();
            start.get(6, TimeUnit.SECONDS);
            broker.allowPublish.countDown();
            eventually(() -> Boolean.TRUE.equals(collector.status().get("fatal")));
            assertEquals("raw_capture_failed", collector.status().get("failureCode"));
            assertEquals(0L, collector.status().get("persistedCount"));
            assertEquals(1L, collector.status().get("captureFailureCount"));
            collector.close();
            broker.finished();
            secretSafe(collector, null);
            assertTrue(logRecords.isEmpty(), "Account-bearing Paho diagnostics must be suppressed");
        } finally {
            logger.removeHandler(handler);
        }
    }
}
