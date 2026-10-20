package com.zoutrankil.data.strategy.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class L2ScoringInputContractTest {
    @Test
    void distinguishesMissingColumnNullNanAndInvalidValuesWithoutFillingOrApprovingThem() {
        var row = new LinkedHashMap<String, Object>();
        row.put("ts", LocalDate.of(2026, 10, 16));
        row.put("symbol", "000001.SZ");
        row.put("clean_amount", null);
        row.put("q2_count", Double.NaN);
        row.put("q2_accumulation", Double.POSITIVE_INFINITY);
        row.put("q3_count", "错误类型");
        row.put("q4_count", 0.0);
        row.put("main_funds_buy_amount", 1000.0);
        var before = new LinkedHashMap<>(row);
        var audit = L2ScoringInputContract.inspect(clocks(), List.of(row));
        assertEquals(before, row);
        assertEquals(1L, field(audit, "clean_amount").get("nullRows"));
        assertEquals(1L, field(audit, "q2_count").get("nonFiniteNumericRows"));
        assertEquals(1L, field(audit, "q2_accumulation").get("nonFiniteNumericRows"));
        assertEquals(1L, field(audit, "q3_count").get("nonNumericRows"));
        assertEquals(1L, field(audit, "q4_count").get("finiteNumericRows"));
        assertEquals(0L, field(audit, "symbol").get("nonNumericRows"));
        for (var optional :
                List.of(
                        "q2_accumulation_ratio",
                        "active_buy_ratio",
                        "q4_distribution",
                        "q4_distribution_ratio",
                        "active_sell_ratio",
                        "large_active_sell_amount",
                        "daily_amount",
                        "amount",
                        "turnover_rate")) {
            var field = field(audit, optional);
            assertEquals(false, field.get("schemaPresent"));
            assertEquals(false, field.get("requiredSourceColumn"));
            assertEquals(1L, field.get("missingKeyRows"));
            assertEquals(0L, field.get("nullRows"));
        }
        assertEquals(false, audit.get("valuesChanged"));
        assertEquals(false, audit.get("modelAcceptanceVerified"));
        assertEquals(false, audit.get("sourceCoverageVerifiedByThisAudit"));
    }

    @Test
    void emptySourceAndAllNullOptionalColumnRemainDifferentObservations() {
        var empty = L2ScoringInputContract.inspect(clocks("active_buy_ratio"), List.of());
        assertEquals(0, empty.get("sourceRows"));
        assertEquals(true, field(empty, "active_buy_ratio").get("schemaPresent"));
        assertEquals(0L, field(empty, "active_buy_ratio").get("nullRows"));
        var row = new LinkedHashMap<String, Object>();
        row.put("active_buy_ratio", null);
        var full = L2ScoringInputContract.inspect(clocks("active_buy_ratio"), List.of(row));
        assertEquals(1L, field(full, "active_buy_ratio").get("nullRows"));
        assertEquals(0L, field(full, "active_buy_ratio").get("missingKeyRows"));
    }

    @Test
    void documentsActualMarketOverridesAndKeepsUnverifiedUnitClaimsExplicit() {
        var audit = L2ScoringInputContract.inspect(clocks("amount", "turnover_rate"), List.of());
        assertTrue(
                field(audit, "amount").get("scoringRule").toString().contains("关联行情提供 amount 时覆盖"));
        assertTrue(field(audit, "turnover_rate").get("scoringRule").toString().contains("不从日行情补入"));
        assertTrue(
                field(audit, "main_funds_buy_amount")
                        .get("scoringRule")
                        .toString()
                        .contains("量纲仍待独立验收"));
        assertEquals("trade_date", field(audit, "ts").get("logicalField"));
        assertEquals("instrument_code", field(audit, "symbol").get("logicalField"));
        assertFalse(
                List.of(L2ScoringInputContract.projection(clocks()).split(",")).contains("amount"));
    }

    private static Map<String, Object> clocks(String... optional) {
        var fields =
                new ArrayList<String>(
                        List.of(
                                "ts",
                                "symbol",
                                "clean_amount",
                                "q2_count",
                                "q2_accumulation",
                                "q3_count",
                                "q4_count",
                                "main_funds_buy_amount"));
        fields.addAll(List.of(optional));
        return Map.of(
                "l2_daily_features",
                Map.of("schema", fields.stream().map(field -> Map.of("column", field)).toList()));
    }

    private static Map<?, ?> field(Map<String, Object> audit, String name) {
        return ((List<?>) audit.get("fields"))
                .stream()
                        .map(item -> (Map<?, ?>) item)
                        .filter(item -> name.equals(item.get("sourceField")))
                        .findFirst()
                        .orElseThrow();
    }
}
