package com.zoutrankil.data.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static com.zoutrankil.data.domain.DatasetDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class DatasetSemanticsTest {
    private static final List<String> DAILY_FIELDS = List.of("ts_code", "trade_date", "open", "high", "low", "close",
            "pre_close", "change", "pct_chg", "vol", "amount", "ah_vol", "ah_amount");

    @Test void dailyV1DeclaresTheOriginalOrderedKeysTypesAndCalendarMeaning() {
        var semantics = DailySemantics.V1;
        assertEquals("daily", semantics.datasetId());
        assertEquals(1, semantics.semanticVersion());
        assertEquals(DAILY_FIELDS, semantics.sourceFields());
        assertEquals(DAILY_FIELDS, semantics.logicalFields());
        assertEquals(DAILY_FIELDS, semantics.storageFields());
        assertEquals(List.of("ts_code", "trade_date"), semantics.businessKey());
        assertEquals("trade_date", semantics.businessDateColumn());
        assertEquals(semantics.fields().get(1), semantics.businessDateField());
        assertEquals(StorageType.SYMBOL, semantics.fields().getFirst().storageType());
        assertFalse(semantics.fields().getFirst().nullable());
        assertEquals(StorageType.TIMESTAMP, semantics.businessDateField().storageType());
        assertFalse(semantics.businessDateField().nullable());
        assertEquals(TemporalKind.BUSINESS_DATE, semantics.businessDateField().temporal().kind());
        assertEquals("BASIC", semantics.businessDateField().temporal().sourceFormat());
        assertEquals("calendar", semantics.businessDateField().temporal().zone());
        assertEquals("DAY", semantics.businessDateField().temporal().precision());
        for (var metric : semantics.fields().subList(2, 13)) {
            assertEquals(StorageType.DOUBLE, metric.storageType());
            assertTrue(metric.nullable());
            assertNull(metric.temporal());
        }
        assertEquals("Provider percent change in percent points, not a fraction", semantics.fields().get(8).meaning());
        assertEquals("Provider volume in lots (手)", semantics.fields().get(9).meaning());
        assertEquals("Provider turnover in thousands of CNY (千元)", semantics.fields().get(10).meaning());
    }

    @Test void defensivelyCopiesTheInputAndExposesOnlyImmutableOrderedCollections() {
        var fields = new ArrayList<>(DailySemantics.V1.fields());
        var keys = new ArrayList<>(DailySemantics.V1.businessKey());
        var semantics = new DatasetSemantics("daily", 1, fields, keys, "trade_date");
        fields.clear(); keys.clear();
        assertEquals(DAILY_FIELDS, semantics.sourceFields());
        assertEquals(List.of("ts_code", "trade_date"), semantics.businessKey());
        assertThrows(UnsupportedOperationException.class, () -> semantics.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> semantics.businessKey().add("open"));
        assertThrows(UnsupportedOperationException.class, () -> semantics.sourceFields().set(0, "changed"));
        assertThrows(UnsupportedOperationException.class, () -> semantics.logicalFields().clear());
        assertThrows(UnsupportedOperationException.class, () -> semantics.storageFields().add("changed"));
    }

    @Test void projectionsPreserveDistinctSourceLogicalAndStorageNames() {
        var date = new Column("vendor_day", "business_day", "storage_ts", StorageType.TIMESTAMP, false,
                "Explicit business date", DailySemantics.V1.businessDateField().temporal());
        var code = new Column("vendor_code", "entity", "symbol", StorageType.SYMBOL, false, "Entity", null);
        var semantics = new DatasetSemantics("custom_daily", 2, List.of(code, date), List.of("entity", "business_day"), "business_day");
        assertEquals(List.of("vendor_code", "vendor_day"), semantics.sourceFields());
        assertEquals(List.of("entity", "business_day"), semantics.logicalFields());
        assertEquals(List.of("symbol", "storage_ts"), semantics.storageFields());
        assertEquals(date, semantics.businessDateField());
        assertEquals(2, semantics.semanticVersion());
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "bad-id", "bad.id", "1daily"})
    void rejectsInvalidDatasetIdentifiers(String dataset) {
        assertThrows(IllegalArgumentException.class, () -> new DatasetSemantics(dataset, 1,
                DailySemantics.V1.fields(), List.of("ts_code", "trade_date"), "trade_date"));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void requiresPositiveSemanticVersions(int version) {
        assertThrows(IllegalArgumentException.class, () -> new DatasetSemantics("daily", version,
                DailySemantics.V1.fields(), List.of("ts_code", "trade_date"), "trade_date"));
    }

    @ParameterizedTest @ValueSource(strings = {"emptyFields", "emptyKey", "duplicateLogical", "duplicateStorage",
            "duplicateKey", "unknownKey", "nullableKey", "unknownDate", "nonTemporalDate", "instantDate"})
    void rejectsIncompleteOrContradictorySemanticBindings(String defect) {
        var fields = new ArrayList<>(DailySemantics.V1.fields());
        var key = new ArrayList<>(List.of("ts_code", "trade_date"));
        String date = "trade_date";
        switch (defect) {
            case "emptyFields" -> fields.clear();
            case "emptyKey" -> key.clear();
            case "duplicateLogical" -> fields.add(new Column("extra", "open", "extra", StorageType.DOUBLE, true, "Extra", null));
            case "duplicateStorage" -> fields.add(new Column("extra", "extra", "open", StorageType.DOUBLE, true, "Extra", null));
            case "duplicateKey" -> key.add("ts_code");
            case "unknownKey" -> key.add("missing");
            case "nullableKey" -> key.add("open");
            case "unknownDate" -> date = "missing";
            case "nonTemporalDate" -> date = "open";
            case "instantDate" -> fields.set(1, new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP,
                    false, "Event instant", new TemporalContract(TemporalKind.INSTANT, "ISO", "UTC", "MICROS", "Event clock")));
            default -> throw new AssertionError(defect);
        }
        String businessDate = date;
        assertThrows(IllegalArgumentException.class, () -> new DatasetSemantics("daily", 1, fields, key, businessDate));
    }

    @Test void rejectsNullCollectionsAndElementsAtConstruction() {
        assertThrows(NullPointerException.class, () -> new DatasetSemantics("daily", 1, null, List.of("trade_date"), "trade_date"));
        assertThrows(NullPointerException.class, () -> new DatasetSemantics("daily", 1, DailySemantics.V1.fields(), null, "trade_date"));
        var fields = new ArrayList<>(DailySemantics.V1.fields()); fields.add(null);
        assertThrows(NullPointerException.class, () -> new DatasetSemantics("daily", 1, fields, List.of("trade_date"), "trade_date"));
        assertThrows(NullPointerException.class, () -> new DatasetSemantics("daily", 1, DailySemantics.V1.fields(),
                Arrays.asList("trade_date", null), "trade_date"));
    }
}
