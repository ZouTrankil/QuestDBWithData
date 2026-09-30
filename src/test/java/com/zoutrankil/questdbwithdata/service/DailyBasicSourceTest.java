package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class DailyBasicSourceTest {
    @TempDir Path evidence;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void capturesARealShapedBoundedResponseAndMapsIt() throws Exception {
        var pages = new StubPages((params, cap) -> page(List.of(row("000001.SZ", "20260928", 12.3))));
        var result = new DailyBasicSource(pages, new com.zoutrankil.questdbwithdata.mapper.DailyBasicMapper(), evidence)
                .fetch(LocalDate.of(2026, 9, 28), () -> false);
        assertEquals(1, result.rows().size());
        assertEquals(12.3d, result.rows().getFirst().close());
        assertTrue(Files.exists(Path.of(result.responseEvidence())));
        assertTrue(listEvidence().stream().anyMatch(path -> path.getFileName().toString().startsWith("response-")));
    }

    @Test void emptyDateRemainsAnExplicitCompleteResponse() throws Exception {
        var pages = new StubPages((params, cap) -> page(List.of()));
        var result = new DailyBasicSource(pages, new com.zoutrankil.questdbwithdata.mapper.DailyBasicMapper(), evidence)
                .fetch(LocalDate.of(2026, 9, 28), () -> false);
        assertTrue(result.rows().isEmpty());
        assertTrue(Files.exists(Path.of(result.responseEvidence())));
    }

    @Test void officialMaximumRowCountFailsClosedAndRetainsTheResponse() {
        var capped = new ArrayList<Map<String, JsonNode>>(DailyBasicSource.API_ROW_CAP);
        for (int i = 0; i < DailyBasicSource.API_ROW_CAP; i++)
            capped.add(row(String.format(Locale.ROOT, "%06d.SZ", i), "20260928", 1.0));
        var pages = new StubPages((params, cap) -> page(capped));
        var source = new DailyBasicSource(pages, new com.zoutrankil.questdbwithdata.mapper.DailyBasicMapper(), evidence);
        assertThrows(PageExecutor.Truncated.class,
                () -> source.fetch(LocalDate.of(2026, 9, 28), () -> false));
        assertTrue(listEvidence().stream().anyMatch(path -> path.getFileName().toString().startsWith("response-")));
    }

    private List<Path> listEvidence() {
        try (var files = Files.list(evidence)) { return files.toList(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static PageExecutor.Page page(List<Map<String, JsonNode>> rows) {
        return new PageExecutor.Page(rows, null, false, null);
    }
    private static Map<String, JsonNode> row(String code, String date, double close) {
        ObjectNode row = JSON.createObjectNode();
        row.put("ts_code", code); row.put("trade_date", date);
        for (String field : DailyBasicSource.FIELDS) {
            if (field.equals("ts_code") || field.equals("trade_date")) continue;
            if (field.equals("close")) row.put(field, close); else row.putNull(field);
        }
        var result = new LinkedHashMap<String, JsonNode>();
        row.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static final class StubPages extends TusharePageService {
        @FunctionalInterface interface Response { PageExecutor.Page fetch(Map<String, Object> params, int cap) throws Exception; }
        private final Response response;
        StubPages(Response response) { super(null); this.response = response; }
        @Override public PageExecutor.Fetcher fetcher(PageContract contract, BooleanSupplier cancelled) {
            return params -> {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                assertEquals("daily_basic", contract.endpoint());
                assertEquals(Set.of("trade_date"), params.keySet());
                return response.fetch(params, contract.sourceRowCap());
            };
        }
    }
}
