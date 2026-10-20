package com.zoutrankil.market.us.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

class UsYahooAnnualBundleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate FROM = LocalDate.of(2006, 1, 1),
            THROUGH = LocalDate.of(2009, 1, 5);
    private static final String BUNDLE = "broad-00000000-0000-0000-0000-000000000001";
    private static final UsYahooAnnualBundle.Page PAGE =
            new UsYahooAnnualBundle.Page(10000, 8388608);

    @Test
    void yearlyProjectionRetainsEveryFullResponseFieldIncludingSplitsAfterTheWindowAndEmptyYears()
            throws Exception {
        var sources = List.of(source("X"), source("A"));
        var b =
                UsYahooAnnualBundle.parse(
                        UsYahooAnnualBundle.freeze(BUNDLE, FROM, THROUGH, PAGE, sources));
        assertEquals(4, b.windows().size());
        assertEquals(6, b.totalRows());
        assertEquals(
                List.of(2, 2, 0, 2),
                b.windows().stream().map(UsYahooAnnualBundle.Window::expectedRows).toList());
        var expected = new ArrayList<Map<String, Object>>();
        for (var s : sources)
            expected.addAll(
                    YahooPriceMapper.daily(
                            s.body(),
                            s.symbol(),
                            s.yahooSymbol(),
                            s.sha256(),
                            s.observedAt(),
                            FROM,
                            THROUGH));
        expected.sort(
                Comparator.comparing((Map<String, Object> r) -> r.get("trade_date").toString())
                        .thenComparing(r -> r.get("symbol").toString()));
        var actual = new ArrayList<Map<String, Object>>();
        for (var w : b.windows()) {
            var rows = b.rows(w);
            assertTrue(
                    rows.stream()
                            .allMatch(
                                    r ->
                                            r.get("trade_date")
                                                    .toString()
                                                    .startsWith("" + w.from().getYear())));
            actual.addAll(rows);
        }
        assertEquals(expected, actual);
        assertEquals(20, actual.getFirst().size());
        assertEquals(20., actual.getFirst().get("close"));
        assertEquals(50., actual.getFirst().get("volume"));
        assertEquals(.5, actual.getFirst().get("dividend"));
        assertEquals(9., actual.getFirst().get("adj_close"));
        assertTrue(b.rows(b.windows().get(2)).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        YahooPriceMapper.daily(
                                sources.getFirst().body(),
                                "X",
                                "X",
                                "sha",
                                Instant.EPOCH,
                                LocalDate.of(2008, 1, 1),
                                LocalDate.of(2008, 12, 31)));
    }

    @Test
    void missingWindowsFalseEmptyCountsChangedCutoffAndFractionalOrOverflowedBudgetsAreRejected()
            throws Exception {
        byte[] good = UsYahooAnnualBundle.freeze(BUNDLE, FROM, THROUGH, PAGE, List.of(source("X")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsYahooAnnualBundle.parse(
                                change(good, c -> ((ArrayNode) c.get("windows")).remove(1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsYahooAnnualBundle.parse(
                                change(
                                        good,
                                        c ->
                                                ((ObjectNode) c.get("windows").get(0))
                                                        .put("expectedRows", 0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> UsYahooAnnualBundle.parse(change(good, c -> c.put("through", "2009-01-06"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsYahooAnnualBundle.parse(
                                change(
                                        good,
                                        c -> ((ObjectNode) c.get("page")).put("rows", 10000.5))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsYahooAnnualBundle.parse(
                                change(
                                        good,
                                        c ->
                                                ((ObjectNode) c.get("page"))
                                                        .put("rows", 4294977296L))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsYahooAnnualBundle.freeze(
                                BUNDLE, FROM, THROUGH, PAGE, List.of(source("X"), source("X"))));
    }

    @Test
    void accepts128IndependentSourcesAndRejectsThe129thOrASingleOversizedResponse()
            throws Exception {
        var sources = new ArrayList<UsYahooDailyBundle.Source>();
        for (int i = 0; i < 128; i++) sources.add(source("S" + i));
        var b =
                UsYahooAnnualBundle.parse(
                        UsYahooAnnualBundle.freeze(BUNDLE, FROM, THROUGH, PAGE, sources));
        assertEquals(384, b.totalRows());
        assertEquals(128, b.rows(b.windows().getFirst()).size());
        sources.add(source("EXTRA"));
        assertThrows(
                IllegalArgumentException.class,
                () -> UsYahooAnnualBundle.freeze(BUNDLE, FROM, THROUGH, PAGE, sources));
        var s = source("X");
        byte[] huge = new byte[4 * 1024 * 1024 + 1];
        var oversized =
                new UsYahooDailyBundle.Source(
                        s.task(),
                        s.symbol(),
                        s.yahooSymbol(),
                        s.sourceUrl(),
                        sha(huge),
                        s.observedAt(),
                        huge);
        assertThrows(
                IllegalArgumentException.class,
                () -> UsYahooAnnualBundle.freeze(BUNDLE, FROM, THROUGH, PAGE, List.of(oversized)));
    }

    @Test
    void cachedSourceStructureKeepsPublicSourcesImmutableAndNeverReusesMutableProjectedRows()
            throws Exception {
        var parsed =
                UsYahooAnnualBundle.parse(
                        UsYahooAnnualBundle.freeze(
                                BUNDLE, FROM, THROUGH, PAGE, List.of(source("X"))));
        var window = parsed.windows().getFirst();
        var before = parsed.rows(window);
        before.getFirst().put("close", 999.);
        byte[] external = parsed.sources().getFirst().body();
        Arrays.fill(external, (byte) 0);
        assertEquals(20., parsed.rows(window).getFirst().get("close"));
        assertThrows(UnsupportedOperationException.class, () -> parsed.sources().clear());
        // 旧七参数构造入口也可独立使用；并发读取只共享解析结构，不共享行。
        var legacy =
                new UsYahooAnnualBundle.Parsed(
                        parsed.bundle(),
                        parsed.from(),
                        parsed.through(),
                        parsed.page(),
                        List.copyOf(parsed.sources()),
                        parsed.windows(),
                        parsed.totalRows());
        try (var workers = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<List<Map<String, Object>>>>();
            for (int i = 0; i < 12; i++) futures.add(workers.submit(() -> legacy.rows(window)));
            for (var future : futures) assertEquals(parsed.rows(window), future.get());
        }
    }

    @Test
    void sourceCacheDoesNotEnterTheExistingSevenFieldBundleSerialization() throws Exception {
        var parsed =
                UsYahooAnnualBundle.parse(
                        UsYahooAnnualBundle.freeze(
                                BUNDLE, FROM, THROUGH, PAGE, List.of(source("X"))));
        parsed.rows(parsed.windows().getFirst());
        var module = new SimpleModule();
        module.addSerializer(Instant.class, ToStringSerializer.instance);
        module.addSerializer(LocalDate.class, ToStringSerializer.instance);
        var tree = JSON.copy().registerModule(module).valueToTree(parsed);
        assertEquals(7, tree.size());
        assertEquals(1, tree.path("sources").size());
        assertEquals(7, tree.path("sources").get(0).size());
        assertFalse(tree.toString().contains("prepared"));
        assertFalse(tree.toString().contains("readers"));
    }

    static UsYahooDailyBundle.Source source(String symbol) throws Exception {
        var quote = new HashMap<String, Object>();
        for (String f : List.of("open", "high", "low", "close"))
            quote.put(f, List.of(10., 11., 12.));
        quote.put("volume", List.of(100, 200, 300));
        var dates =
                List.of("2006-01-03", "2007-01-03", "2009-01-05").stream()
                        .map(d -> LocalDate.parse(d).atTime(14, 30).toEpochSecond(ZoneOffset.UTC))
                        .toList();
        var result =
                Map.of(
                        "meta",
                        Map.of(
                                "symbol",
                                symbol,
                                "currency",
                                "USD",
                                "exchangeTimezoneName",
                                "America/New_York",
                                "instrumentType",
                                "EQUITY"),
                        "timestamp",
                        dates,
                        "events",
                        Map.of(
                                "splits",
                                Map.of(
                                        "split",
                                        Map.of(
                                                "date",
                                                dates.get(2),
                                                "numerator",
                                                2,
                                                "denominator",
                                                1)),
                                "dividends",
                                Map.of("cash", Map.of("date", dates.getFirst(), "amount", .25))),
                        "indicators",
                        Map.of(
                                "quote",
                                List.of(quote),
                                "adjclose",
                                List.of(Map.of("adjclose", List.of(9., 10., 12.)))));
        var chart = new HashMap<String, Object>();
        chart.put("error", null);
        chart.put("result", List.of(result));
        byte[] body = JSON.writeValueAsBytes(Map.of("chart", chart));
        return new UsYahooDailyBundle.Source(
                sha(("v1|DAILY|" + symbol + "|" + THROUGH + "|").getBytes(StandardCharsets.UTF_8)),
                symbol,
                symbol,
                "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol,
                sha(body),
                Instant.parse("2026-10-19T00:00:00Z"),
                body);
    }

    private static byte[] change(byte[] frozen, Consumer<ObjectNode> mutation) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var in = new ZipInputStream(new ByteArrayInputStream(frozen));
                var out = new ZipOutputStream(bytes)) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                byte[] body = in.readAllBytes();
                if (e.getName().equals("manifest.json")) {
                    var card = (ObjectNode) JSON.readTree(body);
                    mutation.accept(card);
                    body = JSON.writeValueAsBytes(card);
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(body);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }
}
