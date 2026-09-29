package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PageExecutorBoundaryTest {
    private static Map<String, JsonNode> row(int id) {
        return Map.of("id", JsonNodeFactory.instance.numberNode(id));
    }

    private static PageContract contract(PageContract.Paging paging) {
        return new PageContract("sample", List.of("id"), List.of("id"),
                paging == PageContract.Paging.NONE ? Set.of() : Set.of("limit", "offset"),
                paging, paging == PageContract.Paging.CURSOR
                ? PageContract.Completion.EXPLICIT_END : PageContract.Completion.SHORT_PAGE,
                paging == PageContract.Paging.NONE ? null : "limit",
                paging == PageContract.Paging.NONE ? null : "offset",
                2, 2, 4, 8, "fixture capability");
    }

    @Test void saturatedUnpagedSliceDeliversNoRows() {
        AtomicInteger consumed = new AtomicInteger();
        assertThrows(PageExecutor.Truncated.class, () -> new PageExecutor().execute(
                contract(PageContract.Paging.NONE), Map.of(),
                ignored -> new PageExecutor.Page(List.of(row(1), row(2)), null, false, "v1"),
                (page, receipt) -> consumed.addAndGet(page.rows().size()), ignored -> {}, () -> false));
        assertEquals(0, consumed.get());
    }

    @Test void repeatedCursorAndDuplicateKeysCannotFinishSlice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var cursorFailure = assertThrows(PageExecutor.Incomplete.class, () -> new PageExecutor().execute(
                contract(PageContract.Paging.CURSOR), Map.of(), ignored -> {
                    int call = calls.incrementAndGet();
                    return new PageExecutor.Page(call == 1 ? List.of(row(1), row(2)) : List.of(row(3), row(4)),
                            "same", false, "v1");
                }, (page, receipt) -> {}, ignored -> {}, () -> false));
        assertEquals(1, cursorFailure.consumedPages());
        calls.set(0);
        var duplicate = assertThrows(PageExecutor.Incomplete.class, () -> new PageExecutor().execute(
                contract(PageContract.Paging.OFFSET), Map.of(), ignored -> calls.incrementAndGet() == 1
                        ? new PageExecutor.Page(List.of(row(1), row(2)), null, false, "v1")
                        : new PageExecutor.Page(List.of(row(2)), null, false, "v1"),
                (page, receipt) -> {}, ignored -> {}, () -> false));
        assertEquals(1, duplicate.consumedPages());
    }

    @Test void laterSourceFailureRetainsOnlyPriorPageReceipt() {
        AtomicInteger calls = new AtomicInteger();
        var failure = assertThrows(PageExecutor.Incomplete.class, () -> new PageExecutor().execute(
                contract(PageContract.Paging.OFFSET), Map.of(), ignored -> {
                    if (calls.incrementAndGet() == 1)
                        return new PageExecutor.Page(List.of(row(1), row(2)), null, false, "v1");
                    throw new IOException("source failed");
                }, (page, receipt) -> {}, ignored -> {}, () -> false));
        assertEquals(1, failure.consumedPages());
        assertEquals(2, failure.consumedRows());
    }
}
