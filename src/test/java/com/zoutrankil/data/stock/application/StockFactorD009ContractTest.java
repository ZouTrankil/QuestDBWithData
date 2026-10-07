package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.stock.application.StockFactorJobService;
import com.zoutrankil.data.stock.application.StockFactorSource;
import com.zoutrankil.data.stock.application.StockFactorSyncAdapter;
import com.zoutrankil.data.stock.application.StockFactorSyncJobOwner;
import com.zoutrankil.data.stock.mapper.StockFactorMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.PageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import static org.junit.jupiter.api.Assertions.*;

class StockFactorD009ContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    private static final String TARGET = "static-v2-" + "a".repeat(64);
    @TempDir Path evidence;

    @Test void routesAreMutuallyExclusiveAndBounded() {
        var allMarket = new StockFactorSource.Query(DAY, DAY, null);
        assertEquals(Map.of("trade_date", "20260928"), allMarket.parameters());

        var exactCode = new StockFactorSource.Query(DAY, DAY.plusDays(4), "000001.SZ");
        assertEquals(Set.of("ts_code", "start_date", "end_date"), exactCode.parameters().keySet());
        assertEquals("000001.SZ", exactCode.parameters().get("ts_code"));
        assertThrows(IllegalArgumentException.class,
                () -> new StockFactorSource.Query(DAY, DAY.plusDays(5), "000001.SZ"));
    }

    @Test void bootstrapCeilingIncludesPhysicalMaximumButKeepsFiveDayWindow() {
        var window = StockFactorJobService.resolveBootstrapWindow(
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 29), 5);
        assertEquals(LocalDate.of(2026, 9, 21), window.from());
        assertEquals(LocalDate.of(2026, 9, 25), window.to());
        assertTrue(window.cappedByBudget());

        var withinWindow = StockFactorJobService.resolveBootstrapWindow(
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 29), 5);
        assertFalse(withinWindow.cappedByBudget());
        assertThrows(IllegalStateException.class, () -> StockFactorJobService.resolveBootstrapWindow(
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 29), 5));
    }

    @Test void targetIdentityIsRequiredFrozenAndComparedBeforeExecution() {
        var from = LocalDate.of(2026, 9, 28);
        var parameters = Map.<String, Object>of("targetId", TARGET, "checkpointAnchor", from);
        var request = StockFactorSyncJobOwner.DEFINITION.freeze(Mode.INCREMENTAL, parameters, from, from, from);
        StockFactorSyncAdapter.validateRequest(request);
        assertThrows(IllegalArgumentException.class, () -> StockFactorSyncJobOwner.DEFINITION.freeze(
                Mode.INCREMENTAL, Map.of("checkpointAnchor", from), from, from, from));
        assertDoesNotThrow(() -> StockFactorJobService.requireSameTarget(TARGET, TARGET));
        assertThrows(IllegalStateException.class, () -> StockFactorJobService.requireSameTarget(
                TARGET, "static-v2-" + "b".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new StockFactorJobService.Plan(
                request, "static-v2-" + "b".repeat(64), null, from, null, null, from, true, false));
    }

    @Test void capResponseIsRejectedAndRetainedAsUnverifiedRawEvidence() throws Exception {
        var rows = new ArrayList<Map<String, JsonNode>>(StockFactorSource.SOURCE_ROW_CAP);
        for (int i = 0; i < StockFactorSource.SOURCE_ROW_CAP; i++)
            rows.add(row(String.format(Locale.ROOT, "%06d.SZ", i), "20260928"));
        var pages = new StubPages(page(rows));
        var source = new StockFactorSource(pages, evidence);

        assertThrows(PageExecutor.Truncated.class,
                () -> source.fetch(new StockFactorSource.Query(DAY, DAY, null), () -> false));

        Path retained;
        try (var files = Files.list(evidence)) {
            retained = files.filter(path -> path.getFileName().toString().startsWith("incomplete-"))
                    .findFirst().orElseThrow();
        }
        JsonNode receipt = JSON.readTree(retained.toFile());
        assertFalse(receipt.path("sourceComplete").asBoolean());
        assertEquals("unverified_raw_response", receipt.path("evidenceStatus").asText());
        assertEquals(StockFactorSource.SOURCE_ROW_CAP, receipt.path("responseRows").asInt());
        assertEquals(StockFactorSource.SOURCE_ROW_CAP, receipt.path("rows").size());
        assertEquals("000000.SZ", receipt.path("rows").get(0).path("ts_code").asText());
    }

    private static PageExecutor.Page page(List<Map<String, JsonNode>> rows) {
        return new PageExecutor.Page(rows, null, false, "fixture-source-version");
    }

    private static Map<String, JsonNode> row(String code, String date) {
        ObjectNode row = JSON.createObjectNode();
        row.put("ts_code", code);
        row.put("trade_date", date);
        for (String field : com.zoutrankil.data.stock.mapper.StockFactorMapper.SOURCE_FIELDS) {
            if (!field.equals("ts_code") && !field.equals("trade_date")) row.putNull(field);
        }
        var result = new LinkedHashMap<String, JsonNode>();
        row.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static final class StubPages extends TusharePageService {
        private final PageExecutor.Page response;
        StubPages(PageExecutor.Page response) { super(null); this.response = response; }
        @Override public PageExecutor.Fetcher fetcher(PageContract contract, BooleanSupplier cancelled) {
            return parameters -> {
                assertFalse(cancelled.getAsBoolean());
                assertEquals("stk_factor", contract.endpoint());
                assertEquals(Set.of("trade_date"), parameters.keySet());
                return response;
            };
        }
    }
}
