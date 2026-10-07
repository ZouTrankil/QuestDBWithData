package com.zoutrankil.data.derived.mapper;

import com.zoutrankil.data.mapper.*;

import static org.junit.jupiter.api.Assertions.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.storage.MacroCoreMonthlyWritePort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MacroCoreMonthlyMappingTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 6);
    private final MacroCoreMonthlyMapper mapper = new MacroCoreMonthlyMapper();
    private LinkedHashMap<String, Object> values() {
        var values = new LinkedHashMap<String, Object>(); values.put("month", MONTH.atDay(1));
        int i = 1;
        for (var field : MacroCoreMonthlyDataset.STORAGE_COLUMNS.subList(1, 9)) values.put(field, i++ / -7.0);
        return values;
    }
    @Test void allNineFieldsHaveExplicitIndependentStorageAndBusinessMapping() {
        var input = values(); var row = mapper.fromValues(input);
        assertEquals(MONTH, row.month()); assertEquals(new MacroCoreMonthlyKey(MONTH), row.key());
        assertEquals(9, mapper.columns().size()); assertEquals(mapper.columns(), mapper.values(row).columns().stream().toList());
        assertEquals(row, mapper.fromStorage(mapper.toStorage(row))); assertEquals(row, mapper.fromValues(mapper.values(row)));
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), mapper.toStorage(row).month());
        assertEquals(input.get("cpi_yoy"), row.cpiYoy()); assertEquals(input.get("ppi_yoy"), row.ppiYoy());
        assertEquals(input.get("pmi_mfg"), row.pmiMfg()); assertEquals(input.get("gdp_yoy"), row.gdpYoy());
        assertEquals(input.get("m2_yoy"), row.m2Yoy()); assertEquals(input.get("social_financing_stock"), row.socialFinancingStock());
        assertEquals(input.get("new_rmb_loan"), row.newRmbLoan()); assertEquals(input.get("social_financing_yoy"), row.socialFinancingYoy());
    }
    @Test void allEightNullableMetricsIncludingRequiredMonthlyHistoricalNullsRemainReadable() {
        for (var field : mapper.columns().subList(1, 9)) {
            var input = values(); input.put(field, null); var row = mapper.fromValues(input);
            assertNull(mapper.values(mapper.fromStorage(mapper.toStorage(row))).asMap().get(field), field);
        }
        var input = values(); mapper.columns().subList(1, 9).forEach(f -> input.put(f, null));
        var row = mapper.fromValues(input); assertEquals(row, mapper.fromStorage(mapper.toStorage(row)));
        assertEquals(8, mapper.values(row).asMap().values().stream().filter(Objects::isNull).count());
    }
    @Test void eachMetricPreservesSignedZeroExtremeFiniteValuesAndStoredUnitsWithoutScaling() {
        for (var field : mapper.columns().subList(1, 9))
            for (double value : new double[]{-0.0, 0.0, Double.MIN_VALUE, -Double.MAX_VALUE, 12.345678901234567}) {
                var input = values(); input.put(field, value);
                var result = mapper.values(mapper.fromStorage(mapper.toStorage(mapper.fromValues(input)))).asMap();
                assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits((Double) result.get(field)), field);
            }
    }
    @Test void everyNumericColumnRejectsNanInfinityAndWrongNumericTransport() {
        for (var field : mapper.columns().subList(1, 9))
            for (Object invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Float.valueOf(1), Integer.valueOf(1), "1.0", Boolean.TRUE)) {
                var input = values(); input.put(field, invalid);
                assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(input), field);
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
    @Test void monthIsExactFirstCalendarDayAtUtcMidnightAndPositiveFourDigitYear() {
        var row = mapper.fromValues(values()); assertEquals(1780272000000000L, row.key().storageMicros());
        for (var invalid : List.of(Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-06-01T00:00:00.000001Z"),
                Instant.parse("2026-05-31T16:00:00Z"), Instant.parse("2026-06-01T16:00:00Z")))
            assertThrows(IllegalArgumentException.class, () -> MacroCoreMonthlyKey.fromStorage(invalid));
        assertEquals(MONTH, MacroCoreMonthlyKey.fromStorage(mapper.toStorage(row).month()).month());
        assertThrows(IllegalArgumentException.class, () -> MacroCoreMonthlyKey.fromDate(LocalDate.of(2026, 6, 2)));
        assertThrows(IllegalArgumentException.class, () -> new MacroCoreMonthlyKey(YearMonth.of(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> new MacroCoreMonthlyKey(YearMonth.of(10000, 1)));
        assertThrows(NullPointerException.class, () -> new MacroCoreMonthlyKey(null));
    }
    @Test void monthKeyRoundTripsLeapYearsAndYearBoundaryWithoutTimezoneGuessing() {
        for (var month : List.of(YearMonth.of(2024, 2), YearMonth.of(2026, 12), YearMonth.of(2027, 1))) {
            var key = new MacroCoreMonthlyKey(month); assertEquals(month.atDay(1), key.storageDate());
            assertEquals(key, MacroCoreMonthlyKey.fromStorage(key.storageCarrier())); assertEquals(0, key.storageCarrier().getNano());
        }
    }
    @Test void datasetDeclaresNineNullableColumnsYearWalMonthDedupAndNoInventedUpstreamGraph() {
        var definition = MacroCoreMonthlyDataset.DEFINITION;
        assertEquals("macro_core_monthly", definition.datasetId()); assertEquals(1, definition.schemaVersion());
        assertEquals(mapper.columns(), definition.storageColumns()); assertEquals(9, definition.columns().size());
        assertEquals(List.of("month"), definition.businessKey()); assertEquals(List.of("month"), definition.dedupKey());
        assertEquals("month", definition.designatedTimestamp()); assertEquals(DatasetDefinition.Partition.YEAR, definition.partition());
        assertTrue(definition.wal()); assertEquals(DatasetDefinition.ObjectKind.TABLE, definition.objectKind());
        assertTrue(definition.dependencies().isEmpty());
        assertEquals(List.of("cn_cpi", "cn_ppi", "cn_pmi", "cn_m", "cn_gdp", "sf_month"), MacroCoreMonthlyDataset.SOURCE_TABLES);
        assertEquals(Set.of(DatasetDefinition.Capability.READ, DatasetDefinition.Capability.WRITE), definition.capabilities());
        assertFalse(definition.columns().getFirst().nullable());
        assertTrue(definition.columns().subList(1, 9).stream().allMatch(c -> c.nullable() && c.storageType() == DatasetDefinition.StorageType.DOUBLE));
        assertEquals(DatasetDefinition.StorageType.TIMESTAMP, definition.columns().getFirst().storageType());
        var temporal = definition.columns().getFirst().temporal();
        assertEquals(DatasetDefinition.TemporalKind.BUSINESS_DATE, temporal.kind()); assertEquals("BASIC", temporal.sourceFormat());
        assertEquals("calendar", temporal.zone()); assertEquals("MONTH", temporal.precision());
        for (var capability : List.of(DatasetDefinition.Capability.STATIC_REPLACE, DatasetDefinition.Capability.WAL_REPLACE))
            assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(capability));
    }
    @Test void sourceBindingsAndLegacySocialFinancingAliasStayExplicit() {
        var columns = MacroCoreMonthlyDataset.columns();
        assertEquals("cn_cpi.nt_yoy", columns.get(1).sourceName()); assertEquals("cn_ppi.ppi_yoy", columns.get(2).sourceName());
        assertEquals("cn_pmi.pmi010000", columns.get(3).sourceName()); assertTrue(columns.get(4).sourceName().contains("report_date:own_month"));
        assertEquals("cn_m.m2_yoy", columns.get(5).sourceName()); assertEquals("sf_month.stk_endval", columns.get(6).sourceName());
        assertEquals("sf_month.inc_month", columns.get(7).sourceName());
        assertTrue(columns.get(7).meaning().contains("total social-financing monthly increment"));
        assertTrue(columns.get(8).meaning().contains("Fractional stock change"));
        assertEquals(List.of("cpi_yoy", "ppi_yoy", "pmi_mfg", "m2_yoy", "social_financing_stock", "new_rmb_loan"),
                MacroCoreMonthlyDataset.REQUIRED_MONTHLY_FIELDS);
        assertTrue(MacroCoreMonthlyDataset.DEFINITION.storageRationale().contains("no forward fill"));
        assertTrue(MacroCoreMonthlyDataset.DEFINITION.storageRationale().contains("no multiplication by 100"));
    }
    @Test void gdpAndSocialRatioKeepNullAndRawRatioRatherThanCarriedOrPercentValues() {
        var input = values(); input.put("gdp_yoy", null); input.put("social_financing_yoy", 0.08123456789012345);
        input.put("social_financing_stock", 425.25); input.put("new_rmb_loan", 10000.75);
        var row = mapper.fromStorage(mapper.toStorage(mapper.fromValues(input)));
        assertNull(row.gdpYoy()); assertEquals(0.08123456789012345, row.socialFinancingYoy());
        assertEquals(425.25, row.socialFinancingStock()); assertEquals(10000.75, row.newRmbLoan());
    }
    @Test void codecRetainsFieldPositionNullBitmapSignedZeroAndMonthIdentity() {
        var input = values(); input.put("cpi_yoy", -0.0); input.put("gdp_yoy", null);
        var row = mapper.fromValues(input); var codec = MacroCoreMonthlyWritePort.CODEC;
        assertEquals(MONTH, codec.key(row)); assertArrayEquals(codec.canonicalBytes(row), codec.canonicalBytes(mapper.fromStorage(mapper.toStorage(row))));
        var changed = new LinkedHashMap<>(input); changed.put("cpi_yoy", 0.0);
        assertFalse(Arrays.equals(codec.canonicalBytes(row), codec.canonicalBytes(mapper.fromValues(changed))));
        changed = new LinkedHashMap<>(input); changed.put("cpi_yoy", null); changed.put("gdp_yoy", -0.0);
        assertFalse(Arrays.equals(codec.canonicalBytes(row), codec.canonicalBytes(mapper.fromValues(changed))));
        changed = new LinkedHashMap<>(input); changed.put("month", MONTH.plusMonths(1).atDay(1));
        assertFalse(Arrays.equals(codec.canonicalBytes(row), codec.canonicalBytes(mapper.fromValues(changed))));
    }
}
