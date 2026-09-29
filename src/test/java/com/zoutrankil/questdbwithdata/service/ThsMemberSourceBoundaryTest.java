package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ThsMemberSourceBoundaryTest {
    @TempDir Path folder;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant OBSERVED = Instant.parse("2026-09-29T01:02:03.123456Z");

    @Test void responseAtUnpagedCapIsIncompleteAndWritesNoReceipt() throws Exception {
        var rows = new ArrayList<Map<String, JsonNode>>(10000);
        for (int i = 0; i < 10000; i++) rows.add(row("700001.TI", String.format("%06d.SZ", i)));
        var source = new ThsMemberSource(fake(rows), folder);
        assertThrows(PageExecutor.Truncated.class,
                () -> source.fetchBoard("700001.TI", OBSERVED, () -> false));
        assertFalse(Files.exists(folder.resolve("source-review.json")));
        try (var files = Files.list(folder)) { assertEquals(0, files.count()); }
    }

    @Test void duplicateOrOutOfBoardRowCannotComplete() throws Exception {
        var duplicate = new ThsMemberSource(fake(List.of(row("700001.TI", "000001.SZ"),
                row("700001.TI", "000001.SZ"))), folder);
        assertThrows(PageExecutor.Incomplete.class,
                () -> duplicate.fetchBoard("700001.TI", OBSERVED, () -> false));
        var outside = new ThsMemberSource(fake(List.of(row("885800.TI", "000001.SZ"))), folder);
        assertThrows(PageExecutor.Incomplete.class,
                () -> outside.fetchBoard("700001.TI", OBSERVED, () -> false));
        try (var files = Files.list(folder)) { assertEquals(0, files.count()); }
    }

    @Test void cancellationBeforeFetchCannotWriteReceipt() throws Exception {
        var source = new ThsMemberSource(fake(List.of(row("700001.TI", "000001.SZ"))), folder);
        assertThrows(PageExecutor.Incomplete.class,
                () -> source.fetchBoard("700001.TI", OBSERVED, () -> true));
        try (var files = Files.list(folder)) { assertEquals(0, files.count()); }
    }

    private static TusharePageService fake(List<Map<String, JsonNode>> rows) {
        return new TusharePageService(null) {
            @Override public PageExecutor.Completed execute(PageContract contract, Map<String, Object> baseParams,
                    PageExecutor.Consumer consumer, PageExecutor.Validator validator,
                    BooleanSupplier cancelled) throws Exception {
                return new PageExecutor().execute(contract, baseParams,
                        ignored -> new PageExecutor.Page(rows, null, false, null),
                        consumer, validator, cancelled);
            }
        };
    }

    private static Map<String, JsonNode> row(String board, String constituent) {
        var result = new LinkedHashMap<String, JsonNode>();
        result.put("ts_code", JSON.valueToTree(board));
        result.put("con_code", JSON.valueToTree(constituent));
        result.put("con_name", JSON.valueToTree("sample"));
        result.put("weight", JSON.getNodeFactory().nullNode());
        result.put("in_date", JSON.getNodeFactory().nullNode());
        result.put("out_date", JSON.getNodeFactory().nullNode());
        result.put("is_new", JSON.valueToTree("Y"));
        return result;
    }
}
