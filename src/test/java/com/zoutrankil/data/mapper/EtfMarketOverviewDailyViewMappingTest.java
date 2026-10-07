package com.zoutrankil.data.mapper;

import static org.junit.jupiter.api.Assertions.*;

import com.zoutrankil.data.domain.*;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EtfMarketOverviewDailyViewMappingTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final List<String> FIELDS = List.of("trade_date", "etf_count", "total_share", "total_size_yi");
    private final EtfMarketOverviewDailyViewMapper mapper = new EtfMarketOverviewDailyViewMapper();

    private EtfMarketOverviewDailyView populated() {
        return new EtfMarketOverviewDailyView(DAY, 765L, 117458005.02090001, 16500.887115112106);
    }

    @Test void fourPhysicalFieldsMapExplicitlyAndRoundTripWithoutNumericChanges() {
        var expected = populated();
        var stored = new com.zoutrankil.data.domain.view.EtfMarketOverviewDailyView(
                Instant.parse("2026-09-17T00:00:00Z"), expected.etfCount(), expected.totalShare(), expected.totalSizeYi());
        var actual = mapper.fromStorage(stored);
        assertEquals(expected, actual);
        assertEquals(expected, mapper.fromValues(mapper.values(actual)));
        assertEquals(FIELDS, mapper.values(actual).columns().stream().toList());
        assertEquals(expected.etfCount(), mapper.values(actual).get("etf_count", Long.class));
        assertEquals(Double.doubleToRawLongBits(expected.totalShare()), Double.doubleToRawLongBits(actual.totalShare()));
        assertEquals(Double.doubleToRawLongBits(expected.totalSizeYi()), Double.doubleToRawLongBits(actual.totalSizeYi()));
    }

    @Test void nullableSumsStayNullAndExactRequiredZeroCountIsNotAReplacementForNull() {
        var stored = new com.zoutrankil.data.domain.view.EtfMarketOverviewDailyView(
                Instant.parse("2026-09-17T00:00:00Z"), 0L, null, null);
        var actual = mapper.fromStorage(stored);
        assertEquals(0L, actual.etfCount()); assertNull(actual.totalShare()); assertNull(actual.totalSizeYi());
        assertEquals(actual, mapper.fromValues(mapper.values(actual)));
        assertTrue(mapper.values(actual).asMap().containsKey("total_share"));
        assertTrue(mapper.values(actual).asMap().containsKey("total_size_yi"));
        assertThrows(NullPointerException.class, () -> mapper.fromStorage(
                new com.zoutrankil.data.domain.view.EtfMarketOverviewDailyView(
                        Instant.parse("2026-09-17T00:00:00Z"), null, null, null)));
        var values = new LinkedHashMap<>(mapper.values(actual).asMap()); values.put("etf_count", null);
        assertThrows(NullPointerException.class, () -> mapper.fromValues(new DatasetValues(values)));
    }

    @Test void signedQuantitiesAndSignedZeroRemainExactWithoutRescalingOrClamping() {
        for (var row : List.of(new EtfMarketOverviewDailyView(DAY, 1, -123.45678901234567, -0.0),
                new EtfMarketOverviewDailyView(DAY, 1, -0.0, -987.6543210987654),
                new EtfMarketOverviewDailyView(DAY, 1, Double.MIN_VALUE, Double.MAX_VALUE))) {
            var actual = mapper.fromValues(mapper.values(row));
            assertEquals(Double.doubleToRawLongBits(row.totalShare()), Double.doubleToRawLongBits(actual.totalShare()));
            assertEquals(Double.doubleToRawLongBits(row.totalSizeYi()), Double.doubleToRawLongBits(actual.totalSizeYi()));
        }
    }

    @Test void onlyExactUtcMidnightCanCarryTheBusinessDate() {
        for (var carrier : List.of(Instant.parse("2026-09-17T00:00:00.000000001Z"),
                Instant.parse("2026-09-17T00:00:00.000001Z"), Instant.parse("2026-09-17T16:00:00Z"),
                Instant.parse("2026-09-16T16:00:00Z"))) {
            assertThrows(IllegalArgumentException.class, () -> mapper.fromStorage(
                    new com.zoutrankil.data.domain.view.EtfMarketOverviewDailyView(carrier, 1L, null, null)));
        }
        assertThrows(NullPointerException.class, () -> mapper.fromStorage(
                new com.zoutrankil.data.domain.view.EtfMarketOverviewDailyView(null, 1L, null, null)));
        assertThrows(NullPointerException.class, () -> new EtfMarketOverviewDailyViewKey(null));
        assertThrows(NullPointerException.class, () -> new EtfMarketOverviewDailyView(null, 1L, null, null));
    }

    @Test void countsRejectNegativeAndCoercingOrOverflowCarriersButRetainMaxLong() {
        for (long invalid : new long[]{-1L, Long.MIN_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new EtfMarketOverviewDailyView(DAY, invalid, null, null));
        for (Object invalid : List.of(Integer.valueOf(1), Double.valueOf(1), Boolean.TRUE,
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))) {
            var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); values.put("etf_count", invalid);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)));
        }
        assertEquals(Long.MAX_VALUE, mapper.fromValues(mapper.values(
                new EtfMarketOverviewDailyView(DAY, Long.MAX_VALUE, null, null))).etfCount());
    }

    @Test void eitherDoubleFieldRejectsNanAndBothInfinities() {
        for (String field : List.of("total_share", "total_size_yi")) {
            for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                var values = new LinkedHashMap<>(mapper.values(populated()).asMap()); values.put(field, invalid);
                assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(values)), field);
            }
        }
    }

    @Test void allFourColumnsAreRequiredInACompleteTypedProjectionWithoutTypeCoercion() {
        for (String field : FIELDS) {
            var missing = new LinkedHashMap<>(mapper.values(populated()).asMap()); missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(missing)), field);
            var wrong = new LinkedHashMap<>(mapper.values(populated()).asMap());
            Object value = switch (field) {
                case "trade_date" -> Instant.parse("2026-09-17T00:00:00Z");
                case "etf_count" -> Integer.valueOf(1);
                default -> Float.valueOf(1.0f);
            };
            wrong.put(field, value);
            assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(new DatasetValues(wrong)), field);
        }
    }

    @Test void viewDefinitionHasOnlyDateIdentityAndTwoExactBaseDependencies() {
        var definition = EtfMarketOverviewDailyViewDataset.DEFINITION;
        assertEquals("v_etf_market_overview_daily", definition.datasetId());
        assertEquals(definition.datasetId(), definition.objectName());
        assertEquals(DatasetDefinition.ObjectKind.VIEW, definition.objectKind());
        assertEquals(FIELDS, definition.storageColumns());
        assertEquals(List.of("trade_date"), definition.businessKey()); assertTrue(definition.dedupKey().isEmpty());
        assertEquals("trade_date", definition.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.NONE, definition.partition()); assertFalse(definition.wal());
        assertEquals(List.of("etf_share", "etf_daily"), definition.dependencies());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertEquals(List.of("etf_share.timestamp", "derived:count_distinct(etf_share.ts_code)",
                "derived:sum(etf_share.fd_share)", "derived:sum(etf_share.fd_share*etf_daily.close)/10000.0"),
                definition.columns().stream().map(DatasetDefinition.Column::sourceName).toList());
        assertEquals(List.of(DatasetDefinition.StorageType.TIMESTAMP, DatasetDefinition.StorageType.LONG,
                DatasetDefinition.StorageType.DOUBLE, DatasetDefinition.StorageType.DOUBLE),
                definition.columns().stream().map(DatasetDefinition.Column::storageType).toList());
        assertEquals(List.of(false, false, true, true),
                definition.columns().stream().map(DatasetDefinition.Column::nullable).toList());
        var date = definition.columns().getFirst().temporal();
        assertEquals(DatasetDefinition.TemporalKind.BUSINESS_DATE, date.kind());
        assertEquals("calendar", date.zone()); assertEquals("DAY", date.precision());
    }

    @Test void directWriteAndBothReplacementCapabilitiesAreRejected() {
        for (var capability : List.of(DatasetDefinition.Capability.WRITE,
                DatasetDefinition.Capability.STATIC_REPLACE, DatasetDefinition.Capability.WAL_REPLACE))
            assertThrows(IllegalArgumentException.class,
                    () -> EtfMarketOverviewDailyViewDataset.DEFINITION.requireCapability(capability));
    }

    @Test void revisedBaseValuesKeepTheDateKeyWithoutAddingACacheGenerationField() {
        var before = populated();
        var after = new EtfMarketOverviewDailyView(DAY, before.etfCount(), before.totalShare() + 1, before.totalSizeYi());
        assertEquals(before.key(), after.key()); assertNotEquals(before, after);
        assertEquals(new EtfMarketOverviewDailyViewKey(DAY), before.key());
        assertFalse(mapper.values(before).columns().contains("source_version"));
    }
}
