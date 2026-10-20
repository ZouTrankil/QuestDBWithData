package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.zoutrankil.data.client.HttpObservations;
import com.zoutrankil.data.client.SharedRequestBudget;
import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.client.TushareFailure;
import com.zoutrankil.data.client.dto.TushareRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.resources.ConnectionProvider;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class TushareHttpClientTest {
    record Reply(int status, String body, long delayMillis, String retryAfter) {
        Reply(int status, String body, long delayMillis) {
            this(status, body, delayMillis, null);
        }
    }

    private HttpServer server;
    private ExecutorService executor;
    private ConnectionProvider pool;
    private TushareClient client;
    private TushareProperties properties;
    private HttpObservations observations;
    private SharedRequestBudget budget;
    @TempDir Path leaseDirectory;
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final TushareRequest request =
            new TushareRequest(
                    "stock_basic", Map.of("ts_code", "000001.SZ"), List.of("ts_code", "name"), 2);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(
                "/",
                exchange -> {
                    received.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    try {
                        var reply = replies.poll(3, TimeUnit.SECONDS);
                        if (reply == null) throw new IllegalStateException("Missing test reply");
                        Thread.sleep(reply.delayMillis());
                        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        if (reply.retryAfter() != null)
                            exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
                        exchange.sendResponseHeaders(reply.status(), body.length);
                        exchange.getResponseBody().write(body);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        properties = new TushareProperties();
        properties.setApiUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setToken("test-secret-never-log");
        properties.setRequestTimeout(Duration.ofSeconds(2));
        observations = new HttpObservations();
        var config = new ClientConfiguration();
        pool = config.tushareConnectionProvider();
        budget =
                new SharedRequestBudget(
                        new SharedRequestBudget.Policy(
                                60000,
                                60000,
                                Map.of(),
                                2,
                                16,
                                1,
                                Duration.ofSeconds(3),
                                Duration.ZERO,
                                Duration.ZERO,
                                Set.of()),
                        leaseDirectory);
        client =
                new TushareClient(
                        config.tushareWebClient(
                                WebClient.builder(), properties, pool, observations),
                        properties,
                        budget);
    }

    @AfterEach
    void stop() throws Exception {
        if (budget != null) budget.close();
        if (server != null) server.stop(0);
        if (pool != null) pool.dispose();
        if (executor != null) executor.close();
    }

    private String good() {
        return "{\"code\":0,\"data\":{\"fields\":[\"name\",\"ts_code\"],\"items\":[[null,\"000001.SZ\"]]}}";
    }

    private void respond(String body) {
        replies.add(new Reply(200, body, 0));
    }

    @Test
    void realHttpRetriesAcquireBudgetAndHonorRetryAfter() throws Exception {
        budget.close();
        budget =
                new SharedRequestBudget(
                        new SharedRequestBudget.Policy(
                                60000,
                                60000,
                                Map.of(),
                                2,
                                16,
                                3,
                                Duration.ofSeconds(4),
                                Duration.ofMillis(10),
                                Duration.ofMillis(20),
                                Set.of()),
                        leaseDirectory);
        client =
                new TushareClient(
                        new ClientConfiguration()
                                .tushareWebClient(
                                        WebClient.builder(), properties, pool, observations),
                        properties,
                        budget);
        replies.add(new Reply(429, "limited", 0, "1"));
        respond(good());
        assertEquals(1, client.request(request).rows().size());
        var attempts = budget.observations();
        assertEquals(2, attempts.size());
        assertTrue(
                Duration.between(attempts.getFirst().startedAt(), attempts.getLast().startedAt())
                                .toMillis()
                        >= 1000);
        respond("{\"code\":-2001,\"msg\":\"抱歉，您每分钟最多访问该接口10次\"}");
        respond(good());
        assertEquals(1, client.request(request).rows().size());
        assertEquals(4, budget.observations().size());
        assertEquals(4, received.size());
    }

    @Test
    void alternateRateMessageIsClassifiedButPermissionCodeIsNotRetried() {
        respond("{\"code\":-2001,\"msg\":\"频率超限\"}");
        assertEquals(
                TushareFailure.Kind.BUSINESS_RATE_LIMIT,
                assertThrows(TushareFailure.class, () -> client.request(request)).kind());
        respond("{\"code\":2002,\"msg\":\"访问频率权限不足\"}");
        assertEquals(
                TushareFailure.Kind.BUSINESS,
                assertThrows(TushareFailure.class, () -> client.request(request)).kind());
        assertEquals(2, received.size());
    }

    @Test
    void mapsReorderedFieldsPreservesNullAndReusesConnection() throws Exception {
        respond(good());
        respond(good());
        for (int i = 0; i < 2; i++) {
            var row = client.request(request).rows().getFirst();
            assertEquals("000001.SZ", row.get("ts_code").asText());
            assertTrue(row.get("name").isNull());
        }
        var events = observations.snapshot();
        assertEquals(2, events.size());
        assertEquals("HTTP/1.1", events.getFirst().protocol());
        assertEquals(events.getFirst().connectionId(), events.getLast().connectionId());
        assertFalse(events.toString().contains(properties.getToken()));
    }

    @Test
    void acceptsProviderFieldsWithNumericLeadingTenorNames() throws Exception {
        var shibor =
                new TushareRequest(
                        "shibor",
                        Map.of("start_date", "20260928", "end_date", "20260928"),
                        List.of("date", "on", "1w", "3m"),
                        1);
        respond(
                "{\"code\":0,\"data\":{\"fields\":[\"1w\",\"date\",\"on\",\"3m\"],\"items\":[[1.5,\"20260928\",1.25,1.8]]}}");
        var row = client.request(shibor).rows().getFirst();
        assertEquals(1.5, row.get("1w").doubleValue());
        assertEquals("20260928", row.get("date").asText());
    }

    @Test
    void malformedSchemaIsNotEmptySuccess() {
        for (var body :
                List.of(
                        "{}",
                        "not-json",
                        "{\"code\":0,\"data\":{\"fields\":[\"ts_code\"],\"items\":[]}}",
                        "{\"code\":0,\"data\":{\"fields\":[\"ts_code\",\"name\",\"name\"],\"items\":[]}}",
                        "{\"code\":0,\"data\":{\"fields\":[\"ts_code\",\"name\"],\"items\":[[\"x\"]]}}",
                        "{\"code\":0,\"data\":{\"fields\":[\"ts_code\",\"name\"],\"items\":[[1,2],[3,4],[5,6]]}}")) {
            respond(body);
            assertEquals(
                    TushareFailure.Kind.CONTRACT,
                    assertThrows(TushareFailure.class, () -> client.request(request)).kind());
        }
    }

    @Test
    void oversizedResponseIsAContractFailure() throws Exception {
        // 此处验证字节上限；首次网络初始化和 8 MiB 传输不应先耗尽测试预算。
        properties.setRequestTimeout(Duration.ofSeconds(20));
        budget.close();
        budget =
                new SharedRequestBudget(
                        new SharedRequestBudget.Policy(
                                60000,
                                60000,
                                Map.of(),
                                2,
                                16,
                                1,
                                Duration.ofSeconds(30),
                                Duration.ZERO,
                                Duration.ZERO,
                                Set.of()),
                        leaseDirectory);
        client =
                new TushareClient(
                        new ClientConfiguration()
                                .tushareWebClient(
                                        WebClient.builder(), properties, pool, observations),
                        properties,
                        budget);
        respond("x".repeat(8 * 1024 * 1024 + 1));
        assertEquals(
                TushareFailure.Kind.CONTRACT,
                assertThrows(TushareFailure.class, () -> client.request(request)).kind());
    }

    @Test
    void businessAndHttpErrorsAreDistinctAndDoNotLeakBodies() {
        respond("{\"code\":-2001,\"msg\":\"test-secret-never-log\"}");
        var error = assertThrows(TushareFailure.class, () -> client.request(request));
        assertEquals(TushareFailure.Kind.BUSINESS, error.kind());
        assertFalse(error.toString().contains(properties.getToken()));
        assertNull(error.getCause());
        replies.add(new Reply(429, properties.getToken(), 0));
        error = assertThrows(TushareFailure.class, () -> client.request(request));
        assertEquals(TushareFailure.Kind.HTTP, error.kind());
        assertEquals(429, error.code());
        assertFalse(error.toString().contains(properties.getToken()));
        assertEquals(2, received.size()); // No hidden retry.
    }

    @Test
    void emptyResponseTimeoutAndCancellationRemainDistinct() throws Exception {
        respond("{\"code\":0,\"data\":{\"fields\":[\"ts_code\",\"name\"],\"items\":[]}}");
        assertTrue(client.request(request).rows().isEmpty());
        received.clear();
        replies.add(new Reply(200, good(), 600));
        var future = client.requestAsync(request);
        assertNotNull(received.poll(2, TimeUnit.SECONDS));
        assertTrue(future.cancel(true));
        assertTrue(future.isCancelled());
        properties.setRequestTimeout(Duration.ofMillis(150));
        replies.add(new Reply(200, good(), 600));
        assertEquals(
                TushareFailure.Kind.TRANSPORT,
                assertThrows(TushareFailure.class, () -> client.request(request)).kind());
    }
}
