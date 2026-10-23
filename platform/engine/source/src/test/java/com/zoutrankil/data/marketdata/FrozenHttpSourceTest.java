package com.zoutrankil.data.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class FrozenHttpSourceTest {
    @TempDir Path root;

    private static final class Budget implements MarketRequestBudget {
        int calls, status, bytes;

        public String reserve(String p, String a, String s, int maximum) {
            calls++;
            return "request-" + calls;
        }

        public void complete(String id, int status, int bytes, Instant retry) {
            this.status = status;
            this.bytes = bytes;
        }
    }

    @Test
    void explicitDeadlineStopsStalledBodyAndRejectsUnboundedTimeoutBeforeReserving()
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/slow",
                exchange -> {
                    exchange.sendResponseHeaders(200, 10);
                    try (var body = exchange.getResponseBody()) {
                        body.write('0');
                        body.flush();
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        server.start();
        var budget = new Budget();
        var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
        try (var source = new FrozenHttpSource(root, budget, "")) {
            for (var invalid :
                    List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(301)))
                assertThrows(
                        IllegalArgumentException.class,
                        () -> source.get("test", "account", "X", uri, Map.of(), 20, null, invalid));
            assertEquals(0, budget.calls);
            assertThrows(
                    IOException.class,
                    () ->
                            source.get(
                                    "test",
                                    "account",
                                    "X",
                                    uri,
                                    Map.of(),
                                    20,
                                    null,
                                    Duration.ofMillis(100)));
            assertEquals(1, budget.calls);
            assertFalse(Files.exists(root.resolve("raw/test/request-1.response")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void freezesSuccessfulBodyButNeverWritesQueryCredentialsAndRejectsOversize() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var agents = new AtomicReference<List<String>>();
        server.createContext(
                "/data",
                exchange -> {
                    agents.set(exchange.getRequestHeaders().get("User-Agent"));
                    var body = "0123456789".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        var budget = new Budget();
        var uri =
                URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress().getPort()
                                + "/data?api_key=private-value&symbol=X");
        try (var source = new FrozenHttpSource(root, budget, "")) {
            var result =
                    source.get(
                            "test",
                            "account",
                            "X",
                            uri,
                            Map.of("User-Agent", "CompatibilityClient/1.0"),
                            20,
                            Version.HTTP_1_1,
                            Duration.ofSeconds(2));
            assertEquals(List.of("CompatibilityClient/1.0"), agents.get(), "来源覆盖 UA 时不能追加第二个请求头");
            assertEquals("0123456789", Files.readString(result.path()));
            assertFalse(result.sourceUrl().contains("private-value"));
            assertFalse(
                    Files.readString(result.path().resolveSibling("request-1.json"))
                            .contains("private-value"));
            assertThrows(
                    IllegalStateException.class,
                    () -> source.get("test", "account", "X", uri, Map.of(), 5));
            assertFalse(Files.exists(root.resolve("raw/test/request-2.response")));
            assertEquals(6, budget.bytes);
        } finally {
            server.stop(0);
        }
    }
}
