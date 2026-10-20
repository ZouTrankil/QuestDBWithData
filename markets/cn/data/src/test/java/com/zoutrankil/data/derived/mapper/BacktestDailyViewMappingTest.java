package com.zoutrankil.data.derived.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.derived.BacktestDailyKey;
import com.zoutrankil.data.domain.derived.BacktestDailyViewDataset;
import com.zoutrankil.data.domain.derived.BacktestDailyViewValue;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

class BacktestDailyViewMappingTest {
    private final BacktestDailyViewMapper mapper = new BacktestDailyViewMapper();

    @Test
    void allThirteenFieldsRoundTripWithPromotedLongSuspensionFlag() {
        var source =
                new BacktestDailyViewValue(
                        LocalDate.of(2026, 9, 17),
                        "000001.SZ",
                        11.68,
                        11.74,
                        11.57,
                        11.61,
                        691925.35,
                        806153.13,
                        139.008,
                        12.87,
                        10.53,
                        0L,
                        0);
        assertEquals(source, mapper.fromValues(mapper.values(source)));
        assertEquals(13, mapper.values(source).columns().size());
        assertEquals(new BacktestDailyKey(source.tradeDate(), source.tsCode()), source.key());
    }

    @Test
    void nullableMetricsRemainNullAndInvalidKeyIsRejected() {
        var source =
                new BacktestDailyViewValue(
                        LocalDate.of(2026, 9, 17),
                        "000001.SZ",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertEquals(source, mapper.fromValues(mapper.values(source)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BacktestDailyViewValue(
                                source.tradeDate(),
                                "bad-code",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void nativeMvHasItsOwnStorageAndOnlyReadCapability() {
        var definition = BacktestDailyViewDataset.DEFINITION;
        assertEquals("mv_backtest_daily", definition.objectName());
        assertEquals(DatasetDefinition.ObjectKind.MATERIALIZED_VIEW, definition.objectKind());
        assertEquals(List.of("trade_date", "ts_code"), definition.businessKey());
        assertTrue(definition.dedupKey().isEmpty());
        assertEquals("trade_date", definition.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.MONTH, definition.partition());
        assertTrue(definition.wal());
        assertEquals(
                List.of("stk_factor", "stk_limit", "stk_suspend", "stk_st_daily"),
                definition.dependencies());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), definition.capabilities());
        assertEquals(
                DatasetDefinition.StorageType.LONG,
                definition.columns().stream()
                        .filter(c -> c.storageName().equals("is_suspended"))
                        .findFirst()
                        .orElseThrow()
                        .storageType());
        assertThrows(
                IllegalArgumentException.class,
                () -> definition.requireCapability(DatasetDefinition.Capability.WRITE));
    }
}
