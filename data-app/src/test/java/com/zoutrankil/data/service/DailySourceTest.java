package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.DailySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.domain.StockDetailInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class DailySourceTest {
    @TempDir Path temp;

    @Test void exact6000RowResponseFallsBackToBoundedD002CodeGroupsAndReceiptCanBeReopened() throws Exception {
        var day = LocalDate.of(2026, 9, 28);
        var codes = new ArrayList<String>(6000);
        for (int i = 0; i < 6000; i++) codes.add(String.format(Locale.ROOT, "%06d.SZ", i));
        assertTrue(codes.stream().allMatch(StockDetailInfo::validCode));
        var calls = new AtomicInteger();
        var fake = new TusharePageService(org.mockito.Mockito.mock(TushareClient.class)) {
            @Override public PageExecutor.Completed execute(PageContract contract, Map<String, Object> parameters,
                    PageExecutor.Consumer consumer, PageExecutor.Validator validator, BooleanSupplier cancelled) throws Exception {
                calls.incrementAndGet();
                var selected = contract == DailySource.BY_DATE ? codes
                        : Arrays.asList(((String) parameters.get("ts_code")).split(",", -1));
                var rows = selected.stream().map(code -> row(code, "20260928")).toList();
                for (var row : rows) validator.validate(row);
                consumer.accept(new PageExecutor.Page(rows, null, false, null),
                        new PageExecutor.Receipt(1, 0, null, rows.size(), "test-page", null));
                return new PageExecutor.Completed(1, rows.size(), null);
            }
        };
        var source = new DailySource(fake, temp);
        var page = source.fetch(day, () -> codes, () -> false);
        assertEquals(6000, page.rows().size());
        assertEquals(7, calls.get());
        assertEquals(day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), page.cursor());
        var reopened = DailySource.reopen(Path.of(page.responseEvidence()), page.sourceFingerprint(), day);
        assertEquals(page.rows(), reopened.rows());
        var receipt = com.zoutrankil.data.domain.JobDefinitionJson.mapper()
                .readTree(Path.of(page.responseEvidence()).toFile());
        assertTrue(receipt.path("splitByCode").asBoolean());
        assertEquals(6000, receipt.path("initialCappedRowCount").asInt());
        assertEquals(6, receipt.path("fallbackGroups").size());
        assertEquals(6000, receipt.path("returnedRows").asInt());
    }

    @Test void endpointRequestBoundsRemainAtTheTushareCapAndDoNotInventOffsetPaging() {
        assertEquals("daily", DailySource.BY_DATE.endpoint());
        assertEquals(6001, DailySource.BY_DATE.sourceRowCap());
        assertEquals(PageContract.Paging.NONE, DailySource.BY_DATE.paging());
        assertTrue(DailySource.BY_DATE.allowedParameters().containsAll(Set.of("trade_date", "ts_code")));
        assertEquals(1000, DailySource.CODE_GROUP_SIZE);
    }

    private static Map<String, JsonNode> row(String code, String date) {
        var row = new LinkedHashMap<String, JsonNode>();
        row.put("ts_code", JsonNodeFactory.instance.textNode(code));
        row.put("trade_date", JsonNodeFactory.instance.textNode(date));
        for (var field : DailySource.FIELDS.subList(2, DailySource.FIELDS.size()))
            row.put(field, JsonNodeFactory.instance.numberNode(1.25));
        return Map.copyOf(row);
    }
}

