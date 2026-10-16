package com.zoutrankil.data.l2live.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class L2TradingSessionTest {
    @TempDir Path root;
    private static final Clock MORNING = Clock.fixed(Instant.parse("2026-10-16T00:55:00Z"), ZoneOffset.UTC);

    @Test void actualCalendarControlsAdmissionAndLunchKeepsTheSameSession() throws Exception {
        withCalendar(200, "[[1]]", url -> {
            var session = L2TradingSession.resolve(config(url), MORNING);
            assertTrue(session.open());
            assertTrue(session.waiting(MORNING));
            assertEquals(LocalDate.of(2026,10,16), session.date());
            assertEquals(Instant.parse("2026-10-16T01:00:00Z"), session.start());
            assertEquals(Instant.parse("2026-10-16T07:05:00Z"), session.stop());
            assertFalse(session.waiting(at("2026-10-16T04:00:00Z")));
            assertFalse(session.expired(at("2026-10-16T04:00:00Z")));
            assertTrue(session.expired(at("2026-10-16T07:05:00Z")));
        });
    }

    @Test void closedDayAndLateStartNeverOpenJournalOrRequestCredentials() throws Exception {
        withCalendar(200, "[[0]]", url -> assertSkipped(url, MORNING, "non_trading_day"));
        withCalendar(200, "[[1]]", url -> assertSkipped(url, at("2026-10-16T07:05:00Z"), "session_closed"));
    }

    @Test void absentDuplicateNullAndInvalidFlagsFailClosed() throws Exception {
        for (String rows : new String[]{"[]", "[[1],[1]]", "[[null]]", "[[2]]", "[[1.5]]", "[[\"1\"]]", "[[4294967297]]"}) {
            withCalendar(200, rows, url -> {
                assertEquals(2, AliyunL2LiveMain.execute(arguments(url), Map.of(), MORNING));
                assertFalse(Files.exists(root.resolve("raw-journal.sqlite3")));
                assertEquals("failed", phase());
            });
        }
    }

    @Test void calendarHttpFailureNeverConnectsToVendor() throws Exception {
        withCalendar(503, "[[1]]", url -> {
            assertEquals(2, AliyunL2LiveMain.execute(arguments(url), Map.of(), MORNING));
            assertFalse(Files.exists(root.resolve("raw-journal.sqlite3")));
            assertTrue(Files.readString(root.resolve("status.json")).contains("TRADING_CALENDAR_UNAVAILABLE"));
        });
    }

    @Test void missingCredentialsCannotKeepASessionAliveAfterClose() throws Exception {
        withCalendar(200, "[[1]]", url -> {
            var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-16T01:00:00Z"));
            Clock clock = mutable(now, ZoneOffset.UTC);
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var args = new String[]{"session", "--root", root.toString(), "--questdb", url,
                        "--symbols", "563360", "--wait-credentials", "true"};
                var result = executor.submit(() -> AliyunL2LiveMain.execute(args, Map.of(), clock));
                var deadline = Instant.now().plusSeconds(5);
                while ((!Files.exists(root.resolve("status.json")) || !phase().equals("awaiting_credentials"))
                        && Instant.now().isBefore(deadline)) Thread.sleep(20);
                assertEquals("awaiting_credentials", phase());
                now.set(Instant.parse("2026-10-16T07:05:00Z"));
                assertEquals(0, result.get(3, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("session_closed", phase());
                assertFalse(Files.exists(root.resolve("raw-journal.sqlite3")));
            }
        });
    }

    private static Clock mutable(java.util.concurrent.atomic.AtomicReference<Instant> now, ZoneId zone) {
        return new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId next) { return mutable(now, next); }
            @Override public Instant instant() { return now.get(); }
        };
    }

    @Test void stopDuringPreopenWaitWorksWithoutCreatingJournal() throws Exception {
        withCalendar(200, "[[1]]", url -> {
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var result = executor.submit(() -> AliyunL2LiveMain.execute(arguments(url), Map.of(), MORNING));
                var deadline = Instant.now().plusSeconds(5);
                while ((!Files.exists(root.resolve("status.json")) || !phase().equals("waiting_session"))
                        && Instant.now().isBefore(deadline)) Thread.sleep(20);
                assertEquals("waiting_session", phase());
                Files.writeString(root.resolve("stop.request"), "stop");
                assertEquals(0, result.get(3, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("stopped", phase());
                assertFalse(Files.exists(root.resolve("raw-journal.sqlite3")));
            }
        });
    }

    private void assertSkipped(String url, Clock clock, String expected) throws Exception {
        assertEquals(0, AliyunL2LiveMain.execute(arguments(url), Map.of(), clock));
        assertEquals(expected, phase());
        assertFalse(Files.exists(root.resolve("raw-journal.sqlite3")));
    }
    private String phase() throws Exception { return new ObjectMapper().readTree(root.resolve("status.json").toFile()).path("phase").asText(); }
    private String[] arguments(String url) { return new String[]{"session", "--root", root.toString(), "--questdb", url, "--symbols", "563360"}; }
    private L2LiveConfiguration config(String url) throws Exception { return L2LiveConfiguration.parse(arguments(url), Map.of()); }
    private static Clock at(String instant) { return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC); }
    @FunctionalInterface interface Check { void run(String url) throws Exception; }
    private void withCalendar(int code, String rows, Check check) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var queries = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/exec", exchange -> {
            queries.add(java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(), java.nio.charset.StandardCharsets.UTF_8));
            var body = ("{\"columns\":[{\"name\":\"is_open\",\"type\":\"INT\"}],\"dataset\":" + rows + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code,body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            check.run("http://127.0.0.1:" + server.getAddress().getPort());
            assertFalse(queries.isEmpty());
            assertTrue(queries.stream().allMatch(q -> q.startsWith("query=SELECT is_open FROM exchange_calendar")
                    && q.contains("exchange='SSE'") && q.contains("2026-10-16T00:00:00.000000Z") && q.endsWith("LIMIT 2")));
        } finally { server.stop(0); }
    }
}
