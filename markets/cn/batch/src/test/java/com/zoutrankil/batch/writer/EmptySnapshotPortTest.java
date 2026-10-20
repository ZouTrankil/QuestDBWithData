package com.zoutrankil.batch.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.batch.support.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

class EmptySnapshotPortTest {
    @TempDir Path root;

    @Test
    void emptyIntentNeverPostsDataAndExistingRowsPreventEmptyProof() throws Exception {
        var writes = new AtomicInteger();
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    if (exchange.getRequestMethod().equals("POST")) writes.incrementAndGet();
                    String query =
                            URLDecoder.decode(
                                    Objects.toString(exchange.getRequestURI().getRawQuery(), ""),
                                    StandardCharsets.UTF_8);
                    Object value = query.contains("wal_tables") ? false : count.get();
                    byte[] body =
                            Json.write(Map.of("dataset", List.of(List.of(value))))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        try {
            var contract = SourceContract.load("stk_suspend");
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var port =
                    new QuestDbTablePort(endpoint, "", contract, "jdb_test_empty_port", List.of());
            Path artifact = root.resolve("empty.ilp");
            Files.write(artifact, new byte[0]);
            String sha =
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
            var intent =
                    new DurableWriter.Intent(
                            "empty-batch",
                            "empty-instance",
                            "jdb_test_empty_port",
                            "test",
                            sha,
                            artifact.toString(),
                            0);
            port.send(intent);
            assertEquals(0, writes.get());
            assertTrue(port.inspect(intent).verified(true));
            count.set(1);
            assertFalse(port.inspect(intent).verified(true));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new QuestDbTablePort(
                                    endpoint,
                                    "",
                                    SourceContract.load("daily"),
                                    "jdb_test_empty_daily",
                                    List.of()));
        } finally {
            server.stop(0);
        }
    }
}
