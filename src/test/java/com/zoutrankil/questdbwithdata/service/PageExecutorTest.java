package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.zoutrankil.questdbwithdata.domain.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.questdbwithdata.domain.PageContract.*;

class PageExecutorTest {
    private PageContract contract(Paging mode, Completion completion, int maxPages) {
        return new PageContract("fixture", List.of("id", "value"), List.of("id"),
                Set.of("start_date", "end_date", "limit", "position"), mode, completion,
                mode == Paging.NONE ? null : "limit", mode == Paging.NONE ? null : "position", 2, 2,
                maxPages, 20, "Explicit fixture protocol, not a production endpoint claim");
    }
    private Map<String, JsonNode> row(String id) { return Map.of("id", TextNode.valueOf(id), "value", TextNode.valueOf("v")); }
    private PageExecutor.Page page(String... ids) { return new PageExecutor.Page(Arrays.stream(ids).map(this::row).toList(), null, false, "v1"); }
    private final PageExecutor executor = new PageExecutor();

    @Test void offsetStreamsToConsumerBeforeNextFetchAndCompletesOnlyOnShortPage() throws Exception {
        var consumed = new AtomicInteger();
        var requests = new ArrayList<Map<String, Object>>();
        var result = executor.execute(contract(Paging.OFFSET, Completion.SHORT_PAGE, 3), Map.of(), params -> {
            requests.add(params);
            if (requests.size() == 1) return page("a", "b");
            assertEquals(2, consumed.get());
            return page("c");
        }, (p, receipt) -> consumed.addAndGet(p.rows().size()), r -> {}, () -> false);
        assertEquals(new PageExecutor.Completed(2, 3, "v1"), result);
        assertEquals(List.of(0L, 2L), requests.stream().map(r -> r.get("position")).toList());
    }

    @Test void repeatedPagesAndOverlappingKeysRejectCompletion() {
        for (var second : List.of(page("a", "b"), page("b", "c"))) {
            var calls = new AtomicInteger();
            var consumed = new AtomicInteger();
            var failure = assertThrows(PageExecutor.Incomplete.class, () -> executor.execute(
                    contract(Paging.OFFSET, Completion.SHORT_PAGE, 3), Map.of(),
                    params -> calls.incrementAndGet() == 1 ? page("a", "b") : second,
                    (p, receipt) -> consumed.incrementAndGet(), r -> {}, () -> false));
            assertEquals(1, consumed.get());
            assertEquals(2, failure.consumedRows());
        }
    }

    @Test void fullUnpagedResultIsNeverDeliveredAndUnsupportedParametersAreRejected() {
        var calls = new AtomicInteger();
        assertThrows(PageExecutor.Truncated.class, () -> executor.execute(contract(Paging.NONE, Completion.SHORT_PAGE, 1),
                Map.of("start_date", "20240101"), params -> {
                    assertFalse(params.containsKey("limit"));
                    assertFalse(params.containsKey("position"));
                    return page("a", "b");
                }, (p, receipt) -> calls.incrementAndGet(), r -> {}, () -> false));
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, () -> executor.execute(contract(Paging.NONE, Completion.SHORT_PAGE, 1),
                Map.of("unsupported", "x"), params -> page(), (p, receipt) -> {}, r -> {}, () -> false));
    }

    @Test void missingAdvancingCursorAndVersionChangesFail() {
        var calls = new AtomicInteger();
        assertThrows(PageExecutor.Incomplete.class, () -> executor.execute(contract(Paging.CURSOR, Completion.EXPLICIT_END, 3),
                Map.of(), params -> new PageExecutor.Page(page(Integer.toString(calls.incrementAndGet())).rows(), "same", false, "v1"),
                (p, receipt) -> {}, r -> {}, () -> false));
        assertEquals(2, calls.get());
        calls.set(0);
        assertThrows(PageExecutor.Incomplete.class, () -> executor.execute(contract(Paging.OFFSET, Completion.SHORT_PAGE, 3),
                Map.of(), params -> calls.incrementAndGet() == 1 ? page("a", "b")
                        : new PageExecutor.Page(page("c").rows(), null, false, "v2"), (p, receipt) -> {}, r -> {}, () -> false));
    }

    @Test void cursorCompletesOnlyWithExplicitTerminalAndEmptyEndDoesNotWrite() throws Exception {
        var calls = new AtomicInteger();
        var consumed = new AtomicInteger();
        var result = executor.execute(contract(Paging.CURSOR, Completion.EXPLICIT_END, 3), Map.of(), params -> {
            if (calls.incrementAndGet() == 1) return new PageExecutor.Page(page("a").rows(), "next", false, "v1");
            assertEquals("next", params.get("position"));
            return new PageExecutor.Page(List.of(), null, true, "v1");
        }, (p, receipt) -> consumed.incrementAndGet(), r -> {}, () -> false);
        assertEquals(2, result.pages());
        assertEquals(1, consumed.get());
    }

    @Test void pageFailureBudgetAndCancellationCannotReturnCompletion() {
        assertThrows(PageExecutor.Incomplete.class, () -> executor.execute(contract(Paging.OFFSET, Completion.SHORT_PAGE, 1),
                Map.of(), params -> page("a", "b"), (p, receipt) -> {}, r -> {}, () -> false));
        assertThrows(PageExecutor.Incomplete.class, () -> executor.execute(contract(Paging.OFFSET, Completion.SHORT_PAGE, 2),
                Map.of(), params -> { throw new java.io.IOException("failure"); }, (p, receipt) -> {}, r -> {}, () -> false));
        var cancelled = new AtomicBoolean();
        assertThrows(PageExecutor.Incomplete.class, () -> executor.execute(contract(Paging.OFFSET, Completion.SHORT_PAGE, 2),
                Map.of(), params -> page("a"), (p, receipt) -> cancelled.set(true), r -> {}, cancelled::get));
    }

    @Test void adaptiveWindowsSplitWithoutDeliveringTruncatedParentAndStopAtMinimum() throws Exception {
        var initial = new SyncSlice(LocalDate.parse("2024-02-28"), LocalDate.parse("2024-03-02"), "", "", null);
        var accepted = new ArrayList<String>();
        var adaptive = new AdaptiveSliceExecutor();
        var completed = adaptive.execute(contract(Paging.NONE, Completion.SHORT_PAGE, 1), initial, Map.of(),
                "start_date", "end_date", 1, 8, 10, params -> params.get("start_date").equals(params.get("end_date"))
                        ? page(params.get("start_date").toString()) : page("truncated1", "truncated2"),
                slice -> r -> {}, (slice, p, receipt) -> accepted.add(p.rows().getFirst().get("id").asText()), () -> false);
        assertEquals(new AdaptiveSliceExecutor.Completed(7, 4, 4), completed);
        assertEquals(List.of("20240228", "20240229", "20240301", "20240302"), accepted);
        assertThrows(PageExecutor.Incomplete.class, () -> adaptive.execute(contract(Paging.NONE, Completion.SHORT_PAGE, 1),
                initial, Map.of(), "start_date", "end_date", 1, 8, 10, params -> page("a", "b"),
                slice -> r -> {}, (slice, p, receipt) -> fail("Truncated rows must not be delivered"), () -> false));
    }
}
