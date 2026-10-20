package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

class WebApiConfigurationTest {
    private record Fixture(DisposableServer server, HttpClient client, AtomicInteger calls)
            implements AutoCloseable {
        int get(String path, String bearer) throws Exception {
            var request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                            .timeout(Duration.ofSeconds(5));
            if (bearer != null) request.header("Authorization", bearer);
            return client.send(request.GET().build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        }

        public void close() {
            client.close();
            server.disposeNow();
        }
    }

    private Fixture fixture(String token) {
        var calls = new AtomicInteger();
        var filter = new WebApiConfiguration().webApiTokenFilter(new WebApiProperties(token, "*"));
        var handler =
                WebHttpHandlerBuilder.webHandler(
                                exchange -> {
                                    calls.incrementAndGet();
                                    return exchange.getResponse().setComplete();
                                })
                        .filter(filter)
                        .build();
        var server =
                HttpServer.create()
                        .host("127.0.0.1")
                        .port(0)
                        .handle(new ReactorHttpHandlerAdapter(handler))
                        .bindNow();
        return new Fixture(
                server,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                calls);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingTokenCannotOpenProtectedApiEvenWithEmptyBearer(String token) throws Exception {
        try (var f = fixture(token)) {
            for (String supplied : new String[] {null, "Bearer ", "Bearer wrong"})
                assertEquals(401, f.get("/api/v1/datasets", supplied));
            assertEquals(0, f.calls().get());
        }
    }

    @Test
    void configuredTokenStillRequiresExactBearer() throws Exception {
        String token = "test-api-token-123456789012345";
        try (var f = fixture(token)) {
            assertEquals(401, f.get("/api/v1/datasets", null));
            assertEquals(401, f.get("/api/v1/datasets", "Bearer wrong"));
            assertEquals(200, f.get("/api/v1/datasets", "Bearer " + token));
            assertEquals(1, f.calls().get());
        }
    }

    @Test
    void healthRemainsPublicWithoutCredentials() throws Exception {
        try (var f = fixture("")) {
            assertEquals(200, f.get("/api/v1/health", null));
            assertEquals(1, f.calls().get());
        }
    }
}
