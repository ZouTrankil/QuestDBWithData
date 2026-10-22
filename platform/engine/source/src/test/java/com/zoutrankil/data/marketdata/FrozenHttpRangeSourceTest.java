package com.zoutrankil.data.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

class FrozenHttpRangeSourceTest {
    @TempDir Path root;

    static final class Budget implements MarketRequestBudget {
        int requests, status, bytes, maximum;

        public String reserve(String p, String a, String s, int maximum) {
            this.maximum = maximum;
            return "r" + (++requests);
        }

        public void complete(String id, int status, int bytes, Instant retry) {
            this.status = status;
            this.bytes = bytes;
        }
    }

    @Test
    void freezesOnlyTheExactRangeAndCarriesItsEntityVersion() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var range = new AtomicReference<String>();
        var validator = new AtomicReference<String>();
        var encoding = new AtomicReference<String>();
        server.createContext(
                "/file",
                e -> {
                    range.set(e.getRequestHeaders().getFirst("Range"));
                    validator.set(e.getRequestHeaders().getFirst("If-Range"));
                    encoding.set(e.getRequestHeaders().getFirst("Accept-Encoding"));
                    e.getResponseHeaders().set("Content-Range", "bytes 2-5/10");
                    e.getResponseHeaders().set("ETag", "\"v1\"");
                    e.sendResponseHeaders(206, 4);
                    try (var out = e.getResponseBody()) {
                        out.write("2345".getBytes());
                    }
                });
        server.start();
        var budget = new Budget();
        try (var source = new FrozenHttpRangeSource(root, budget, "")) {
            var response =
                    source.get(
                            "test",
                            "public",
                            "file",
                            URI.create(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                                            + "/file?token=private-value"),
                            2,
                            4,
                            "\"v1\"");
            assertEquals("2345", Files.readString(response.path()));
            assertEquals(10, response.total());
            assertEquals("\"v1\"", response.validator());
            assertEquals("bytes=2-5", range.get());
            assertEquals("\"v1\"", validator.get());
            assertEquals("identity", encoding.get());
            assertEquals(5, budget.maximum);
            assertEquals(206, budget.status);
            assertEquals(4, budget.bytes);
            assertFalse(
                    Files.readString(root.resolve("raw/test/r1.json")).contains("private-value"));
        } finally {
            server.stop(0);
        }
    }

    private void rejects(int status, String header, String etag, String body, String ifRange)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/file",
                e -> {
                    e.getResponseHeaders().set("Content-Range", header);
                    e.getResponseHeaders().set("ETag", etag);
                    e.sendResponseHeaders(status, body.length());
                    try (var out = e.getResponseBody()) {
                        out.write(body.getBytes());
                    }
                });
        server.start();
        var budget = new Budget();
        try (var source = new FrozenHttpRangeSource(root, budget, "")) {
            assertThrows(
                    Exception.class,
                    () ->
                            source.get(
                                    "test",
                                    "public",
                                    "file",
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/file"),
                                    0,
                                    4,
                                    ifRange));
            assertEquals(1, budget.requests);
            assertFalse(Files.exists(root.resolve("raw/test/r1.response")));
            assertTrue(
                    Files.readString(root.resolve("raw/test/r1.json"))
                            .contains("\"accepted\":false"));
            assertEquals(status == 200 ? 0 : status, budget.status);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ignoredRangeNeverBecomesAnAcceptedWholeFile() throws Exception {
        rejects(200, "", "\"v1\"", "0123456789", "");
    }

    @Test
    void rejectsShiftedRange() throws Exception {
        rejects(206, "bytes 1-4/10", "\"v1\"", "1234", "");
    }

    @Test
    void rejectsShortBody() throws Exception {
        rejects(206, "bytes 0-3/10", "\"v1\"", "012", "");
    }

    @Test
    void rejectsOversizeBody() throws Exception {
        rejects(206, "bytes 0-3/10", "\"v1\"", "01234", "");
    }

    @Test
    void rejectsChangedEntity() throws Exception {
        rejects(206, "bytes 0-3/10", "\"v2\"", "0123", "\"v1\"");
    }

    @Test
    void rejectsUnknownTotalAndDoesNotUseWeakIfRange() throws Exception {
        rejects(206, "bytes 0-3/*", "\"v1\"", "0123", "");
        rejects(206, "bytes 0-3/999999999999999999999999", "\"v1\"", "0123", "");
        var budget = new Budget();
        try (var source = new FrozenHttpRangeSource(root, budget, "")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            source.get(
                                    "test",
                                    "public",
                                    "x",
                                    URI.create("http://127.0.0.1/file"),
                                    0,
                                    1,
                                    "W/\"v1\""));
            assertEquals(0, budget.requests);
        }
    }
}
