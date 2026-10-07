package com.zoutrankil.data.mapper;

import static org.junit.jupiter.api.Assertions.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.EquityStyleMonthlyWritePort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class EquityStyleMonthlyMappingTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 6);
    private final EquityStyleMonthlyMapper mapper = new EquityStyleMonthlyMapper();
    private LinkedHashMap<String, Object> values() {
        var values = new LinkedHashMap<String, Object>(); values.put("month", MONTH.atDay(1));
        int i = 1;
        for (var field : EquityStyleMonthlyDataset.STORAGE_COLUMNS.subList(1, 30)) values.put(field, i++ / -7.0);
        return values;
    }
    @Test void allThirtyFieldsHaveExplicitIndependentStorageAndBusinessMapping() {
        var expected = mapper.fromValues(values());
        assertEquals(MONTH, expected.month()); assertEquals(new EquityStyleMonthlyKey(MONTH), expected.key());
        assertEquals(30, mapper.columns().size()); assertEquals(mapper.columns(), mapper.values(expected).columns().stream().toList());
        assertEquals(expected, mapper.fromStorage(mapper.toStorage(expected)));
        assertEquals(expected, mapper.fromValues(mapper.values(expected)));
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), mapper.toStorage(expected).month());
        for (var field : mapper.columns().subList(1, 30))
            assertEquals(Double.doubleToRawLongBits((Double) values().get(field)),
                    Double.doubleToRawLongBits((Double) mapper.values(expected).asMap().get(field)), field);
    }
    @Test void nullableFieldsKeepEveryNullIncludingAnAllNullHistoricalRow() {
        for (var field : mapper.columns().subList(1, 30)) {
            var input = values(); input.put(field, null);
            var row = mapper.fromValues(input);
            assertNull(mapper.values(mapper.fromStorage(mapper.toStorage(row))).asMap().get(field), field);
        }
        var input = values(); mapper.columns().subList(1, 30).forEach(f -> input.put(f, null));
        var row = mapper.fromValues(input);
        assertEquals(row, mapper.fromStorage(mapper.toStorage(row)));
        assertEquals(29, mapper.values(row).asMap().values().stream().filter(Objects::isNull).count());
    }
    @Test void eachFieldKeepsSignedZeroExtremeFiniteValuesAndPercentageUnitsWithoutScaling() {
        for (var field : mapper.columns().subList(1, 30))
            for (double value : new double[]{-0.0, 0.0, Double.MIN_VALUE, -Double.MAX_VALUE, 12.345678901234567}) {
                var input = values(); input.put(field, value);
                var actual = mapper.values(mapper.fromStorage(mapper.toStorage(mapper.fromValues(input)))).asMap();
                assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits((Double) actual.get(field)), field);
            }
    }
    @Test void everyNumericColumnRejectsNanInfinityAndNumericCoercion() {
        for (var field : mapper.columns().subList(1, 30)) {
            for (Object invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Float.valueOf(1), Integer.valueOf(1), "1.0", Boolean.TRUE)) {
                var input = values(); input.put(field, invalid);
                assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(input), field);
            }
        }
    }
    @Test void completeProjectionRejectsMissingExtraAndWrongMonthTypes() {
        for (var field : mapper.columns()) {
            var input = values(); input.remove(field);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(input), field);
        }
        var extra = values(); extra.put("unregistered", 1.0);
        assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(extra));
        for (Object wrong : List.of(MONTH, "202606", Instant.parse("2026-06-01T00:00:00Z"))) {
            var input = values(); input.put("month", wrong);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(input));
        }
        var missing = values(); missing.put("month", null);
        assertThrows(NullPointerException.class, () -> mapper.fromValues(missing));
    }
    @Test void monthIsExactFirstCalendarDayAtUtcMidnightRatherThanMonthEnd() {
        var expected = mapper.fromValues(values()); var storage = mapper.toStorage(expected);
        assertEquals(1780272000000000L, expected.key().storageMicros());
        for (var invalid : List.of(Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-06-01T00:00:00.000001Z"),
                Instant.parse("2026-05-31T16:00:00Z"), Instant.parse("2026-06-01T16:00:00Z")))
            assertThrows(IllegalArgumentException.class, () -> EquityStyleMonthlyKey.fromStorage(invalid));
        assertEquals(MONTH, EquityStyleMonthlyKey.fromStorage(storage.month()).month());
        assertThrows(IllegalArgumentException.class, () -> EquityStyleMonthlyKey.fromDate(LocalDate.of(2026, 6, 2)));
        assertThrows(IllegalArgumentException.class, () -> new EquityStyleMonthlyKey(YearMonth.of(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> new EquityStyleMonthlyKey(YearMonth.of(10000, 1)));
        assertThrows(NullPointerException.class, () -> new EquityStyleMonthlyKey(null));
    }
    @Test void monthCarrierWorksAcrossLeapYearAndYearBoundaryWithoutTimezoneOrDayGuessing() {
        for (var month : List.of(YearMonth.of(2024, 2), YearMonth.of(2026, 12), YearMonth.of(2027, 1))) {
            var key = new EquityStyleMonthlyKey(month);
            assertEquals(month.atDay(1), key.storageDate());
            assertEquals(key, EquityStyleMonthlyKey.fromStorage(key.storageCarrier()));
            assertEquals(0, key.storageCarrier().getNano());
        }
    }
    @Test void datasetDeclaresOrderedNullableThirtyColumnsAndYearWalSingleMonthDedup() {
        var definition = EquityStyleMonthlyDataset.DEFINITION;
        assertEquals("equity_style_monthly", definition.datasetId()); assertEquals(1, definition.schemaVersion());
        assertEquals(mapper.columns(), definition.storageColumns()); assertEquals(30, definition.columns().size());
        assertEquals(List.of("month"), definition.businessKey()); assertEquals(List.of("month"), definition.dedupKey());
        assertEquals("month", definition.designatedTimestamp()); assertEquals(DatasetDefinition.Partition.YEAR, definition.partition());
        assertTrue(definition.wal()); assertEquals(DatasetDefinition.ObjectKind.TABLE, definition.objectKind());
        assertEquals(List.of("index_monthly"), definition.dependencies());
        assertEquals(Set.of(DatasetDefinition.Capability.READ, DatasetDefinition.Capability.WRITE), definition.capabilities());
        assertFalse(definition.columns().getFirst().nullable());
        assertTrue(definition.columns().subList(1, 30).stream().allMatch(c -> c.nullable() && c.storageType() == DatasetDefinition.StorageType.DOUBLE));
        assertEquals(DatasetDefinition.StorageType.TIMESTAMP, definition.columns().getFirst().storageType());
        var temporal = definition.columns().getFirst().temporal();
        assertEquals(DatasetDefinition.TemporalKind.BUSINESS_DATE, temporal.kind()); assertEquals("BASIC", temporal.sourceFormat());
        assertEquals("calendar", temporal.zone()); assertEquals("MONTH", temporal.precision());
        for (var capability : List.of(DatasetDefinition.Capability.STATIC_REPLACE, DatasetDefinition.Capability.WAL_REPLACE))
            assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(capability));
    }
    @Test void legacyValue920Growth921BindingAndDifferenceDirectionRemainExplicit() {
        var columns = EquityStyleMonthlyDataset.columns();
        assertTrue(columns.stream().filter(c -> c.storageName().equals("value_ret_1m")).findFirst().orElseThrow().sourceName().contains("000920.SH"));
        assertTrue(columns.stream().filter(c -> c.storageName().equals("growth_ret_1m")).findFirst().orElseThrow().sourceName().contains("000921.SH"));
        assertEquals("derived:growth_ret_1m-value_ret_1m",
                columns.stream().filter(c -> c.storageName().equals("growth_value_ret_1m")).findFirst().orElseThrow().sourceName());
        assertTrue(EquityStyleMonthlyDataset.DEFINITION.storageRationale().contains("same stored units"));
        assertFalse(EquityStyleMonthlyDataset.DEFINITION.storageRationale().contains("percentage points"));
    }
    @Test void canonicalCodecPreservesFieldPositionNullBitmapSignedZeroAndMonthIdentity() {
        var base = values(); base.put("hs300_ret_1m", -0.0); base.put("zz500_ret_1m", null);
        var row = mapper.fromValues(base); var codec = EquityStyleMonthlyWritePort.CODEC;
        assertEquals(MONTH, codec.key(row)); assertArrayEquals(codec.canonicalBytes(row), codec.canonicalBytes(mapper.fromStorage(mapper.toStorage(row))));
        var changed = new LinkedHashMap<>(base); changed.put("hs300_ret_1m", 0.0);
        assertFalse(Arrays.equals(codec.canonicalBytes(row), codec.canonicalBytes(mapper.fromValues(changed))));
        changed = new LinkedHashMap<>(base); changed.put("hs300_ret_1m", null); changed.put("zz500_ret_1m", -0.0);
        assertFalse(Arrays.equals(codec.canonicalBytes(row), codec.canonicalBytes(mapper.fromValues(changed))));
        changed = new LinkedHashMap<>(base); changed.put("month", MONTH.plusMonths(1).atDay(1));
        assertFalse(Arrays.equals(codec.canonicalBytes(row), codec.canonicalBytes(mapper.fromValues(changed))));
    }
}

