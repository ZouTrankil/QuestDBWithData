package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.service.PageExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Collection state belongs to one invocation, including nested calls and aborted ST pagination. */
class SourceCollectionIsolationTest {
    @TempDir Path archive;

    @Test void nestedCollectionsOnOneCollectorDoNotShareStHistoryOrCounters() throws Exception {
        var collector = new SourceCollector(archive);
        var inner = new AtomicReference<SourceCollector.Collected>();
        var outer = collector.collect(request("stk_st_daily", "000001.SZ"), parameters -> {
            inner.set(collector.collect(request("stk_st_daily", "000002.SZ"), nestedParameters ->
                    page(List.of(row("stk_st_daily", "000002.SZ")), true)));
            return page(List.of(row("stk_st_daily", "000001.SZ")), true);
        });
        assertSnapshot(collector, outer, "000001.SZ");
        assertSnapshot(collector, inner.get(), "000002.SZ");
        assertNotEquals(outer.fingerprint(), inner.get().fingerprint());
        assertReceipt(outer, 1, 1, 1);
        assertReceipt(inner.get(), 1, 1, 1);
    }

    @Test void concurrentCollectionsOnOneCollectorKeepIndependentStSessions() throws Exception {
        var collector = new SourceCollector(archive);
        var entered = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> collectAfterBothEnter(collector, entered, "000001.SZ"));
            var second = executor.submit(() -> collectAfterBothEnter(collector, entered, "000002.SZ"));
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertSnapshot(collector, a, "000001.SZ");
            assertSnapshot(collector, b, "000002.SZ");
            assertReceipt(a, 1, 1, 1);
            assertReceipt(b, 1, 1, 1);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void interruptedStPaginationRetainsFirstRawPageAndCannotLeakHistoryIntoRetry() throws Exception {
        var collector = new SourceCollector(archive);
        var request = request("stk_st_daily", "000001.SZ");
        var calls = new AtomicInteger();
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            var failure = assertThrows(PageExecutor.Incomplete.class, () -> collector.collect(request, parameters -> {
                if (calls.incrementAndGet() == 1) {
                    assertFalse(parameters.containsKey("cursor"));
                    return new PageExecutor.Page(List.of(row("stk_st_daily", "000001.SZ")), "next", false, "fixture-v1");
                }
                assertEquals("next", parameters.get("cursor"));
                throw new InterruptedException("stopped");
            }));
            assertEquals("Source page failed (InterruptedException)", failure.getMessage());
            assertEquals(1, failure.consumedPages());
            assertEquals(1, failure.consumedRows());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(2, calls.get());
        assertEquals(1, rawFiles().size());
        assertNoCompletedArtifacts();

        var retried = collector.collect(request, parameters -> page(List.of(), true));
        assertEquals(0, retried.rows());
        assertEquals(1, retried.pages());
        assertTrue(collector.read(retried.fingerprint()).rows().isEmpty());
        assertReceipt(retried, 0, 0, 1);
    }

    @Test void interruptionBeforeCollectionCallsNoSourceAndWritesNoEvidence() throws Exception {
        var collector = new SourceCollector(archive);
        var request = request("daily", "000001.SZ");
        var calls = new AtomicInteger();
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            Thread.currentThread().interrupt();
            var failure = assertThrows(PageExecutor.Incomplete.class, () -> collector.collect(request, parameters -> {
                calls.incrementAndGet();
                return page(List.of(row("daily", "000001.SZ")), false);
            }));
            assertEquals("Slice cancelled", failure.getMessage());
            assertEquals(0, failure.consumedPages());
            assertEquals(0, failure.consumedRows());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, calls.get());
        assertTrue(allFiles().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"wrongDate", "missingField", "numericType", "duplicateKey"})
    void validationFailuresKeepRawEvidenceButDoNotPolluteTheNextCollection(String defect) throws Exception {
        var collector = new SourceCollector(archive);
        var request = request("daily", "000001.SZ");
        var invalid = row("daily", "000001.SZ");
        switch (defect) {
            case "wrongDate" -> invalid.put("trade_date", Json.MAPPER.valueToTree("20260927"));
            case "missingField" -> invalid.remove("close");
            case "numericType" -> invalid.put("close", Json.MAPPER.valueToTree("12.25"));
            case "duplicateKey" -> { }
            default -> throw new AssertionError(defect);
        }
        var sourcePage = page(defect.equals("duplicateKey") ? List.of(invalid, invalid) : List.of(invalid), false);
        assertThrows(PageExecutor.Incomplete.class, () -> collector.collect(request, parameters -> sourcePage));
        assertEquals(1, rawFiles().size());
        assertEquals(Json.MAPPER.valueToTree(sourcePage), Json.MAPPER.readTree(rawFiles().getFirst().toFile()));
        assertNoCompletedArtifacts();

        var completed = collector.collect(request, parameters -> page(List.of(row("daily", "000001.SZ")), false));
        assertEquals(1, completed.rows());
        assertEquals(1, completed.pages());
        assertEquals(BusinessState.VERIFYING, completed.state());
        assertEquals(1, collector.read(completed.fingerprint()).rows().size());
        assertReceipt(completed, 1, 1, 1);
    }

    @Test void sourceSpecificPreparationFailureHappensAfterRawRetention() throws Exception {
        var collector = new SourceCollector(archive);
        var request = request("index_daily_market", "801080.SI");
        var incompatible = row("index_daily_market", "801080.SI");
        var sourcePage = page(List.of(incompatible), false);
        var failure = assertThrows(PageExecutor.Incomplete.class, () -> collector.collect(request, parameters -> sourcePage));
        assertEquals("Source page failed (IllegalArgumentException)", failure.getMessage());
        assertEquals(1, rawFiles().size());
        assertEquals(Json.MAPPER.valueToTree(sourcePage), Json.MAPPER.readTree(rawFiles().getFirst().toFile()));
        assertNoCompletedArtifacts();

        var compatible = row("index_daily_market", "801080.SI");
        compatible.put("pct_change", compatible.remove("pct_chg"));
        compatible.remove("pre_close");
        var completed = collector.collect(request, parameters -> page(List.of(compatible), false));
        assertEquals(1, completed.rows());
        assertNull(collector.read(completed.fingerprint()).rows().getFirst().get("pre_close"));
        assertReceipt(completed, 1, 1, 1);
    }

    @Test void categoryMismatchAndRawRowOverflowAreRejectedBeforeRawRetention() throws Exception {
        var collector = new SourceCollector(archive);
        var finance = row("fina_mainbz", "000001.SZ");
        finance.put("bz_code", Json.MAPPER.valueToTree("D"));
        var failure = assertThrows(PageExecutor.Incomplete.class, () -> collector.collect(
                request("fina_mainbz", "000001.SZ"), parameters -> {
                    assertEquals("P", parameters.get("type"));
                    return page(List.of(finance), false);
                }));
        assertEquals("Source page failed (IllegalArgumentException)", failure.getMessage());
        assertTrue(allFiles().isEmpty());

        var overflow = Collections.nCopies(6001, row("daily", "000001.SZ"));
        assertThrows(PageExecutor.Incomplete.class, () -> collector.collect(request("daily", "000001.SZ"),
                parameters -> page(overflow, false)));
        assertTrue(allFiles().isEmpty());
    }

    private SourceCollector.Collected collectAfterBothEnter(SourceCollector collector, CountDownLatch entered, String code) throws Exception {
        return collector.collect(request("stk_st_daily", code), parameters -> {
            entered.countDown();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            return page(List.of(row("stk_st_daily", code)), true);
        });
    }
    private static void assertSnapshot(SourceCollector collector, SourceCollector.Collected result, String code) throws Exception {
        assertEquals(1, result.rows());
        assertEquals(1, result.pages());
        assertTrue(result.completeCoverage());
        assertEquals(BusinessState.VERIFYING, result.state());
        var rows = collector.read(result.fingerprint()).rows();
        assertEquals(1, rows.size());
        assertEquals(code, rows.getFirst().get("ts_code"));
        assertEquals(1L, ((Number)rows.getFirst().get("is_st")).longValue());
    }
    private void assertReceipt(SourceCollector.Collected collected, int rows, int sourceRows, int pages) throws Exception {
        var receipts = allFiles().stream().filter(path -> path.getFileName().toString()
                .startsWith(collected.fingerprint() + ".probe-")).toList();
        assertEquals(1, receipts.size());
        var receipt = Json.MAPPER.readTree(receipts.getFirst().toFile());
        assertEquals(rows, receipt.path("rows").asInt());
        assertEquals(sourceRows, receipt.path("sourceRows").asInt());
        assertEquals(pages, receipt.path("pages").asInt());
        assertEquals(pages, receipt.path("rawArtifacts").size());
        assertTrue(receipt.path("sourceProbeComplete").asBoolean());
        for (var raw : receipt.path("rawArtifacts")) assertTrue(Files.isRegularFile(Path.of(raw.asText())));
    }
    private void assertNoCompletedArtifacts() throws Exception {
        assertTrue(allFiles().stream().allMatch(path -> path.getParent().getFileName().toString().equals("raw")));
    }
    private List<Path> rawFiles() throws Exception {
        return allFiles().stream().filter(path -> path.getParent().getFileName().toString().equals("raw")).toList();
    }
    private List<Path> allFiles() throws Exception {
        try (var paths = Files.walk(archive)) { return paths.filter(Files::isRegularFile).toList(); }
    }
    private static SourceCollector.Request request(String dataset, String code) {
        return NativeSourceTest.request(dataset, Set.of(code));
    }
    private static Map<String,JsonNode> row(String dataset, String code) {
        return new LinkedHashMap<>(NativeSourceTest.row(dataset, code));
    }
    private static PageExecutor.Page page(List<Map<String,JsonNode>> rows, boolean end) {
        return new PageExecutor.Page(rows, null, end, "fixture-v1");
    }
}
