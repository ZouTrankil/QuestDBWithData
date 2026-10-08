package com.zoutrankil.data.derived.mapper;


import static org.junit.jupiter.api.Assertions.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.JobDefinitionJson;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MacroCoreMonthlyViewMappingTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 6);
    private static final List<String> FIELDS = List.of("month", "cpi_yoy", "ppi_yoy", "pmi_mfg", "gdp_yoy",
            "m2_yoy", "social_financing_stock", "new_rmb_loan", "social_financing_yoy");
    private final MacroCoreMonthlyViewMapper mapper = new MacroCoreMonthlyViewMapper();
    private LinkedHashMap<String, Object> values() {
        var result = new LinkedHashMap<String, Object>(); result.put("month", MONTH.atDay(1));
        int i = 1; for (String field : FIELDS.subList(1, 9)) result.put(field, i++ / -7.0);
        return result;
    }

    @Test void nineExplicitFieldsRoundTripThroughPreservedProjectionAndTypedValues() {
        var input = values(); var row = mapper.fromValues(input);
        assertEquals(MONTH, row.month()); assertEquals(new MacroCoreMonthlyViewKey(MONTH), row.key());
        assertEquals(FIELDS, mapper.columns()); assertEquals(FIELDS, mapper.values(row).columns().stream().toList());
        assertEquals(row, mapper.fromStorage(mapper.toStorage(row))); assertEquals(row, mapper.fromValues(mapper.values(row)));
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), mapper.toStorage(row).month());
        assertEquals(input.get("cpi_yoy"), row.cpiYoy()); assertEquals(input.get("ppi_yoy"), row.ppiYoy());
        assertEquals(input.get("pmi_mfg"), row.pmiMfg()); assertEquals(input.get("gdp_yoy"), row.gdpYoy());
        assertEquals(input.get("m2_yoy"), row.m2Yoy()); assertEquals(input.get("social_financing_stock"), row.socialFinancingStock());
        assertEquals(input.get("new_rmb_loan"), row.newRmbLoan()); assertEquals(input.get("social_financing_yoy"), row.socialFinancingYoy());
    }

    @Test void everyNullableMetricIncludingHistoricalMissingMonthlyComponentsRemainsNull() {
        for (String field : FIELDS.subList(1, 9)) {
            var input = values(); input.put(field, null); var row = mapper.fromValues(input);
            assertNull(mapper.values(mapper.fromStorage(mapper.toStorage(row))).asMap().get(field), field);
        }
        var input = values(); FIELDS.subList(1, 9).forEach(field -> input.put(field, null));
        var row = mapper.fromValues(input);
        assertEquals(row, mapper.fromStorage(mapper.toStorage(row)));
        assertEquals(8, mapper.values(row).asMap().values().stream().filter(Objects::isNull).count());
    }

    @Test void eachMetricPreservesExactBinary64SignedZeroAndFiniteExtremeWithoutScaling() {
        for (String field : FIELDS.subList(1, 9))
            for (double value : new double[]{-0.0, 0.0, Double.MIN_VALUE, -Double.MAX_VALUE, 12.345678901234567}) {
                var input = values(); input.put(field, value);
                var actual = mapper.values(mapper.fromStorage(mapper.toStorage(mapper.fromValues(input)))).asMap();
                assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits((Double) actual.get(field)), field);
            }
    }

    @Test void everyMetricRejectsNanInfinityAndCoercingNumericCarriers() {
        for (String field : FIELDS.subList(1, 9))
            for (Object invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Float.valueOf(1), Integer.valueOf(1), Long.valueOf(1), "1.0", Boolean.TRUE)) {
                var input = values(); input.put(field, invalid);
                assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(input), field);
            }
    }

    @Test void completeProjectionRejectsMissingExtraAndNonDateMonthTransport() {
        for (String field : FIELDS) {
            var input = values(); input.remove(field);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(input), field);
        }
        var extra = values(); extra.put("source_version", "unrelated");
        assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(extra));
        for (Object invalid : List.of(MONTH, "202606", Instant.parse("2026-06-01T00:00:00Z"))) {
            var input = values(); input.put("month", invalid);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(input));
        }
        var missing = values(); missing.put("month", null);
        assertThrows(NullPointerException.class, () -> mapper.fromValues(missing));
        assertThrows(NullPointerException.class, () -> mapper.fromStorage(null));
    }

    @Test void monthMustBeItsExactFirstDayAtUtcMidnightWithoutTimezoneOrSubsecondGuessing() {
        assertEquals(1780272000000000L, new MacroCoreMonthlyViewKey(MONTH).storageMicros());
        for (Instant invalid : List.of(Instant.parse("2026-06-30T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00.000000001Z"), Instant.parse("2026-06-01T00:00:00.000001Z"),
                Instant.parse("2026-05-31T16:00:00Z"), Instant.parse("2026-06-01T16:00:00Z")))
            assertThrows(IllegalArgumentException.class, () -> MacroCoreMonthlyViewKey.fromStorage(invalid));
        assertThrows(IllegalArgumentException.class, () -> MacroCoreMonthlyViewKey.fromDate(MONTH.atDay(2)));
        assertThrows(NullPointerException.class, () -> MacroCoreMonthlyViewKey.fromStorage(null));
        assertThrows(NullPointerException.class, () -> MacroCoreMonthlyViewKey.fromDate(null));
    }

    @Test void naturalMonthKeyHasPositiveFourDigitYearAndRoundTripsLeapAndYearBoundaries() {
        for (YearMonth month : List.of(YearMonth.of(2024, 2), YearMonth.of(2026, 12), YearMonth.of(2027, 1))) {
            var key = new MacroCoreMonthlyViewKey(month);
            assertEquals(month.atDay(1), key.storageDate());
            assertEquals(key, MacroCoreMonthlyViewKey.fromStorage(key.storageCarrier()));
        }
        assertThrows(IllegalArgumentException.class, () -> new MacroCoreMonthlyViewKey(YearMonth.of(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> new MacroCoreMonthlyViewKey(YearMonth.of(10000, 1)));
        assertThrows(NullPointerException.class, () -> new MacroCoreMonthlyViewKey(null));
        assertThrows(NullPointerException.class, () -> new MacroCoreMonthlyView(null, null, null, null, null, null, null, null, null));
    }

    @Test void ordinaryAliasDefinitionIsReadOnlyUnpartitionedAndReferencesTheCanonicalBase() {
        var definition = MacroCoreMonthlyViewDataset.DEFINITION;
        assertEquals("v_macro_core_monthly", definition.datasetId()); assertEquals(definition.datasetId(), definition.objectName());
        assertEquals(1, definition.schemaVersion()); assertEquals(DatasetDefinition.ObjectKind.VIEW, definition.objectKind());
        assertEquals(FIELDS, definition.storageColumns()); assertEquals(List.of("month"), definition.businessKey());
        assertTrue(definition.dedupKey().isEmpty()); assertEquals("month", definition.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.NONE, definition.partition()); assertFalse(definition.wal());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertEquals(List.of("macro_core_monthly"), definition.dependencies());
        assertEquals("data.macro_core_monthly", MacroCoreMonthlyViewDataset.BASE_REFRESH_JOB_ID);
        assertEquals(1, MacroCoreMonthlyViewDataset.BASE_REFRESH_JOB_VERSION);
        assertFalse(definition.columns().getFirst().nullable());
        assertEquals(DatasetDefinition.StorageType.TIMESTAMP, definition.columns().getFirst().storageType());
        assertTrue(definition.columns().subList(1, 9).stream().allMatch(c -> c.nullable() && c.storageType() == DatasetDefinition.StorageType.DOUBLE));
        var temporal = definition.columns().getFirst().temporal();
        assertEquals(DatasetDefinition.TemporalKind.BUSINESS_DATE, temporal.kind()); assertEquals("BASIC", temporal.sourceFormat());
        assertEquals("calendar", temporal.zone()); assertEquals("MONTH", temporal.precision());
    }

    @Test void allNineAliasBindingsPointToBaseColumnsAndWriteReplacementCapabilitiesAreRejected() {
        var definition = MacroCoreMonthlyViewDataset.DEFINITION;
        assertEquals(FIELDS.stream().map(field -> "macro_core_monthly." + field).toList(),
                definition.columns().stream().map(DatasetDefinition.Column::sourceName).toList());
        for (var capability : List.of(DatasetDefinition.Capability.WRITE,
                DatasetDefinition.Capability.STATIC_REPLACE, DatasetDefinition.Capability.WAL_REPLACE))
            assertThrows(IllegalArgumentException.class, () -> definition.requireCapability(capability));
        assertTrue(definition.storageRationale().contains("Canonical source refresh delegates to data.macro_core_monthly v1"));
    }

    @Test void gdpNullRatioAndLegacyTotalIncrementStayExactWithoutNewBusinessFormula() {
        var input = values(); input.put("gdp_yoy", null); input.put("social_financing_yoy", 0.07395872071401999);
        input.put("social_financing_stock", 462.06); input.put("new_rmb_loan", 33671.0);
        var actual = mapper.fromStorage(mapper.toStorage(mapper.fromValues(input)));
        assertNull(actual.gdpYoy()); assertEquals(0.07395872071401999, actual.socialFinancingYoy());
        assertEquals(462.06, actual.socialFinancingStock()); assertEquals(33671.0, actual.newRmbLoan());
        var base = new MacroCoreMonthlyMapper().fromValues(input);
        assertEquals(new MacroCoreMonthlyMapper().values(base).asMap(), mapper.values(actual).asMap());
    }

    @Test void changedBaseValuesKeepMonthIdentityWithoutInventingSourceGenerationColumn() {
        var before = mapper.fromValues(values()); var revised = values(); revised.put("cpi_yoy", 12.125);
        var after = mapper.fromValues(revised);
        assertEquals(before.key(), after.key()); assertNotEquals(before, after);
        assertFalse(mapper.values(after).columns().contains("source_version"));
    }

    @Test void canonicalYearMonthJsonPreservesNullsAndSignedZero() throws Exception {
        var input = values(); input.put("gdp_yoy", null); input.put("cpi_yoy", -0.0);
        var row = mapper.fromValues(input); var json = JobDefinitionJson.mapper().writeValueAsString(row);
        assertEquals("2026-06", JobDefinitionJson.mapper().readTree(json).path("month").asText());
        var actual = JobDefinitionJson.mapper().readValue(json, MacroCoreMonthlyView.class);
        assertEquals(row, actual); assertNull(actual.gdpYoy());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(actual.cpiYoy()));
    }
}
