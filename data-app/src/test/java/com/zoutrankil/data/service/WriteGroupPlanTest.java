package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WriteGroupPlanTest {
    private static final LocalDate DAY = LocalDate.of(2026,9,29);
    private final DatasetRegistry registry = new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION,
            () -> StockBasicDataset.LATEST));
    private DatasetValues row(String name) {
        var values = new LinkedHashMap<String,Object>();
        values.put("snapshot_ts", DAY.atStartOfDay(ZoneOffset.UTC).toInstant());
        values.put("ts_code", "000001.SZ"); values.put("symbol", "000001"); values.put("name", name);
        values.put("area", null); values.put("industry", null); values.put("list_date", LocalDate.of(1991,4,3));
        return new DatasetValues(values);
    }
    private WriteGroupRequest request(List<DatasetValues> rows) {
        return new WriteGroupRequest("group-batch-1", DAY, List.of(new WriteGroupRequest.Member(
                "stocks", "stock_basic_snapshot", 1, "stock-batch-1", rows)));
    }
    private WriteGroupPlan prepare(WriteGroupRequest request) {
        return WriteGroupPlan.prepare(request, registry, Map.of("stock_basic_snapshot", "target-1"));
    }
    @Test void fingerprintsBindRowsDateDefinitionTargetAndBatchWhileRowsRemainTyped() {
        var plan = prepare(request(List.of(row("first"))));
        assertEquals(plan.fingerprint(), prepare(request(List.of(row("first")))).fingerprint());
        assertNotEquals(plan.fingerprint(), prepare(request(List.of(row("changed")))).fingerprint());
        assertNotEquals(plan.fingerprint(), WriteGroupPlan.prepare(request(List.of(row("first"))), registry,
                Map.of("stock_basic_snapshot", "target-2")).fingerprint());
        assertNotEquals(plan.fingerprint(), prepare(new WriteGroupRequest("another-batch", DAY,
                request(List.of(row("first"))).members())).fingerprint());
        assertNotEquals(plan.fingerprint(), prepare(new WriteGroupRequest("group-batch-1", DAY.plusDays(1),
                request(List.of(row("first"))).members())).fingerprint());
        assertEquals(LocalDate.of(1991,4,3), plan.members().getFirst().batch().rows().getFirst().get("list_date", LocalDate.class));
        assertFalse(plan.atomicAcrossTables());
        assertThrows(UnsupportedOperationException.class, () -> plan.members().clear());
    }
    @Test void viewsDuplicatesAndIncompleteRowsAreRejectedBeforeExecution() {
        var view = new WriteGroupRequest("group-batch", DAY, List.of(new WriteGroupRequest.Member(
                "view", "stock_basic_latest", 1, "view-batch", List.of(row("first")))));
        assertThrows(IllegalArgumentException.class, () -> WriteGroupPlan.prepare(view, registry,
                Map.of("stock_basic_latest", "target-1")));
        assertThrows(IllegalArgumentException.class, () -> prepare(request(List.of(row("first"), row("second")))));
        assertThrows(IllegalArgumentException.class, () -> prepare(request(List.of(new DatasetValues(Map.of("ts_code", "000001.SZ"))))));
        assertThrows(IllegalArgumentException.class, () -> WriteGroupPlan.prepare(request(List.of()), registry, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new WriteGroupRequest("group-batch", DAY,
                List.of(request(List.of()).members().getFirst(), request(List.of()).members().getFirst())));
    }
    @Test void emptyInputRemainsExplicitAndDoesNotPretendAWriteWasVerified() {
        var plan = prepare(request(List.of()));
        assertTrue(plan.members().getFirst().batch().empty());
        assertEquals(0, plan.members().getFirst().batch().normalizedBytes());
    }
}
