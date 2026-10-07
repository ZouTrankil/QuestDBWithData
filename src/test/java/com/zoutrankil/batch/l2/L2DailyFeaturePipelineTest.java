package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.batch.DfcfCsvParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen full P0-P13 regression against the canonical Python v1.3 / dfcf_csv_v1.2 pipeline. */
class L2DailyFeaturePipelineTest {
    private static final String RESOURCE = "/l2-daily-pipeline/";
    private static final String DATE_TEXT = "20260924";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 24);
    private static final long MAX_BYTES = 1_000_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test void completeShanghaiEtfPipelineMatchesAllFrozenPythonFields() throws Exception {
        Path source = copyFixture("510300.SZ");
        var parsed = DfcfCsvParser.readProductionDay(source, "510300.SZ", DATE, MAX_BYTES);
        assertEquals("510300.SH", parsed.symbol());
        assertEquals(858, parsed.rawDealRows());
        assertEquals(825, parsed.deals().size(), "Unknown vendor aggressor rows are filtered");
        assertEquals(3363, parsed.rawOrderRows());
        assertEquals(3330, parsed.orders().size(), "Unknown vendor order rows are filtered");
        assertEquals(990, parsed.quotes().size());
        assertFeatures("510300.SZ", L2DailyFeaturePipeline.output(L2DailyFeaturePipeline.compute(parsed)));
    }

    @Test void completeShenzhenEtfPipelineIncludesReconstructedCancelsAndMatchesAllFields() throws Exception {
        Path source = copyFixture("159915.SZ");
        var parsed = DfcfCsvParser.readProductionDay(source, "159915.SZ", DATE, MAX_BYTES);
        assertEquals(1823, parsed.rawDealRows());
        assertEquals(1790, parsed.deals().size());
        assertEquals(2398, parsed.rawOrderRows());
        assertEquals(3330, parsed.orders().size());
        var cancellations = parsed.orders().stream().filter(order -> !order.submission()).toList();
        assertEquals(965, cancellations.size(), "DEAL cancellation rows must enter the canonical ORDER table");
        assertTrue(cancellations.stream().allMatch(order -> order.priceCny().signum() > 0),
                "Cancellation prices are reconstructed from submitted orders");
        assertFeatures("159915.SZ", L2DailyFeaturePipeline.output(L2DailyFeaturePipeline.compute(parsed)));
    }

    @Test void cliGeneratesOneCompleteNormalizedEtfRow() throws Exception {
        copyFixture("510300.SZ");
        Path output = directory.resolve("daily.jsonl");
        L2DailyFeatureCli.main(arguments("510300.SZ", output));
        List<String> rows = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertEquals(1, rows.size());
        JsonNode actual = JSON.readTree(rows.getFirst());
        assertFeatures("510300.SZ", actual);
        assertEquals("510300.SH", actual.get("symbol").textValue());
        assertEquals(DATE_TEXT, actual.get("ts").textValue());
        assertNoTemporaryOutputs(output);
    }

    @Test void failingLaterSymbolKeepsExistingOutputAtomic() throws Exception {
        copyFixture("510300.SZ");
        Path badSource = directory.resolve(DATE_TEXT).resolve("000001.SZ");
        Files.createDirectories(badSource);
        Files.writeString(badSource.resolve("逐笔成交.csv"), "malformed-source\n", StandardCharsets.UTF_8);
        Path output = directory.resolve("daily.jsonl");
        Files.writeString(output, "existing-result\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> L2DailyFeatureCli.main(arguments("510300.SZ,000001.SZ", output)));
        assertEquals("existing-result\n", Files.readString(output, StandardCharsets.UTF_8));
        assertNoTemporaryOutputs(output);
    }

    @Test void failingLaterSymbolDoesNotPublishPartialNewOutput() throws Exception {
        copyFixture("510300.SZ");
        Path output = directory.resolve("daily.jsonl");
        assertThrows(IllegalArgumentException.class,
                () -> L2DailyFeatureCli.main(arguments("510300.SZ,000001.SZ", output)));
        assertFalse(Files.exists(output), "A successfully calculated first row must not escape a failed batch");
        assertNoTemporaryOutputs(output);
    }

    @Test void cliRejectsDuplicateCanonicalEtfAliasesBeforePublishing() throws Exception {
        copyFixture("510300.SZ");
        Path output = directory.resolve("daily.jsonl");
        Files.writeString(output, "existing-result\n", StandardCharsets.UTF_8);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> L2DailyFeatureCli.main(arguments("510300.SZ,510300.SH", output)));
        assertTrue(failure.getMessage().contains("Duplicate canonical"));
        assertEquals("existing-result\n", Files.readString(output, StandardCharsets.UTF_8));
        assertNoTemporaryOutputs(output);
    }

    @Test void cliRejectsCaseInsensitiveDuplicateSymbolsBeforePublishing() throws Exception {
        Path output = directory.resolve("daily.jsonl");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> L2DailyFeatureCli.main(arguments("159915.SZ,159915.sz", output)));
        assertTrue(failure.getMessage().contains("Duplicate canonical"));
        assertFalse(Files.exists(output));
        assertNoTemporaryOutputs(output);
    }

    private String[] arguments(String symbols, Path output) {
        return new String[]{"--source-root", directory.toString(), "--date", DATE_TEXT,
                "--symbols", symbols, "--output", output.toString(), "--max-bytes-per-file", Long.toString(MAX_BYTES)};
    }

    private Path copyFixture(String symbol) throws Exception {
        Path target = directory.resolve(DATE_TEXT).resolve(symbol);
        Files.createDirectories(target);
        for (String filename : List.of("逐笔成交.csv", "逐笔委托.csv", "行情.csv")) {
            try (InputStream stream = getClass().getResourceAsStream(RESOURCE + DATE_TEXT + "/" + symbol + "/" + filename)) {
                assertNotNull(stream, "Fixture resource " + symbol + "/" + filename);
                Files.copy(stream, target.resolve(filename));
            }
        }
        return target;
    }

    private void assertFeatures(String symbol, Map<String, Object> actual) throws Exception {
        JsonNode tree = JSON.valueToTree(actual);
        assertFeatures(symbol, tree);
    }

    private void assertFeatures(String symbol, JsonNode actual) throws Exception {
        JsonNode expected;
        try (InputStream stream = getClass().getResourceAsStream(RESOURCE + symbol + ".expected.json")) {
            assertNotNull(stream, "Frozen Python expected values " + symbol);
            expected = JSON.readTree(stream);
        }
        assertEquals(110, expected.size());
        assertEquals(expected.size(), actual.size(), "Complete schema projection");
        var fields = expected.properties().iterator();
        while (fields.hasNext()) {
            var entry = fields.next(); String field = entry.getKey(); JsonNode reference = entry.getValue();
            JsonNode value = actual.get(field);
            assertNotNull(value, symbol + " missing field " + field);
            if (reference.isIntegralNumber()) {
                assertTrue(value.isIntegralNumber(), symbol + " integral type " + field);
                assertEquals(reference.longValue(), value.longValue(), symbol + " " + field);
            } else if (reference.isFloatingPointNumber()) {
                assertTrue(value.isFloatingPointNumber(), symbol + " floating type " + field);
                double number = reference.doubleValue();
                assertTrue(Double.isFinite(value.doubleValue()), symbol + " finite " + field);
                assertEquals(number, value.doubleValue(), Math.max(1e-9, Math.abs(number) * 1e-10), symbol + " " + field);
            } else {
                assertEquals(reference, value, symbol + " exact boolean/text/null " + field);
            }
        }
        assertTrue(actual.get("gmm_main_force_ratio").doubleValue() > 0);
        assertTrue(actual.get("gmm_hft_ratio").doubleValue() > 0);
        assertTrue(actual.get("gmm_retail_ratio").doubleValue() > 0);
        assertTrue(actual.get("partial_fill_ratio").doubleValue() > 0);
        assertTrue(actual.get("tick_rule_fallback_ratio").doubleValue() > 0);
    }

    private void assertNoTemporaryOutputs(Path output) throws Exception {
        try (var files = Files.list(output.getParent())) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(output.getFileName() + ".tmp-")));
        }
    }
}
