package com.zoutrankil.data.mapper;

import com.zoutrankil.data.client.dto.TushareDailyDto;
import com.zoutrankil.data.domain.DailyDataset;
import com.zoutrankil.data.domain.DailyMarketBar;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyMappingTest {
    private final DailyMapper mapper = new DailyMapper();

    @Test void mapsEveryPhysicalColumnAndPreservesNullAndNumericValues() {
        var bar = mapper.fromSource(new TushareDailyDto("000001.SZ", "20260928", 10.25, 11.5, 9.75,
                11.0, 10.0, 1.0, 10.0, 2500.5, 25005.0, null, 12.0));
        assertEquals(LocalDate.of(2026, 9, 28), bar.tradeDate());
        assertEquals(2500.5, bar.vol());
        assertEquals(25005.0, bar.amount());
        assertNull(bar.ahVol());
        assertEquals(bar, mapper.fromStorage(mapper.toStorage(bar)));
        assertEquals(bar, mapper.fromValues(mapper.values(bar)));
        assertEquals(Instant.parse("2026-09-28T00:00:00Z"), mapper.toStorage(bar).tradeDate());
    }

    @Test void rejectsInvalidBusinessDatesCodesAndNonfiniteProviderValues() {
        for (var date : List.of("20260230", "2026-09-28", "20260928T00:00:00", " 20260928")) {
            assertThrows(Exception.class, () -> mapper.fromSource(new TushareDailyDto("000001.SZ", date,
                    null, null, null, null, null, null, null, null, null, null, null)));
        }
        assertThrows(IllegalArgumentException.class, () -> mapper.fromSource(new TushareDailyDto("000001", "20260928",
                null, null, null, null, null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> new DailyMarketBar("000001.SZ",
                LocalDate.of(2026, 9, 28), Double.NaN, null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> mapper.fromStorage(new com.zoutrankil.data.domain.table.DailyRow(
                "000001.SZ", Instant.parse("2026-09-28T00:00:00.000001Z"), null, null, null, null,
                null, null, null, null, null, null, null)));
        assertEquals(LocalDate.of(2026, 9, 28), TemporalValues.CalendarTimestamp.fromStorage(
                mapper.toStorage(mapper.fromSource(new TushareDailyDto("000001.SZ", "20260928", null, null,
                        null, null, null, null, null, null, null, null, null))).tradeDate()).date());
    }

    @Test void definitionDeclaresAllPhysicalFieldsKeysAndStorageSemantics() {
        var definition = DailyDataset.DEFINITION;
        assertEquals(List.of("ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change",
                "pct_chg", "vol", "amount", "ah_vol", "ah_amount"), definition.storageColumns());
        assertEquals(List.of("ts_code", "trade_date"), definition.businessKey());
        assertEquals(definition.businessKey(), definition.dedupKey());
        assertEquals("trade_date", definition.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.YEAR, definition.partition());
        assertTrue(definition.wal());
        assertEquals(List.of("exchange_calendar", "stock_detail_info"), definition.dependencies());
        assertFalse(definition.columns().get(1).nullable());
        assertEquals(DatasetDefinition.TemporalKind.BUSINESS_DATE, definition.columns().get(1).temporal().kind());
        assertTrue(definition.columns().subList(2, definition.columns().size()).stream().allMatch(
                column -> column.storageType() == DatasetDefinition.StorageType.DOUBLE && column.nullable()));
    }
}
