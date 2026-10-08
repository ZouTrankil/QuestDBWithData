package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DatasetWriteComparisonTest {
    private DatasetValues row(String code, String name, String area) {
        var values = new LinkedHashMap<String, Object>();
        values.put("snapshot_ts", Instant.parse("2026-09-29T00:00:00Z"));
        values.put("ts_code", code); values.put("symbol", code); values.put("name", name);
        values.put("area", area); values.put("industry", null); values.put("list_date", LocalDate.of(1991, 4, 3));
        return new DatasetValues(values);
    }
    @Test void sameCountsWithWrongKeysOrValuesCannotPass() {
        var expected = List.of(row("a", "original", null));
        var wrongKey = DatasetWriteComparison.compare(StockBasicDataset.DEFINITION, expected, List.of(row("b", "original", null)));
        assertEquals(1, wrongKey.actualRows());
        assertFalse(wrongKey.matches());
        assertEquals(Set.of("missing", "unexpected"), new HashSet<>(wrongKey.differences().stream().map(DatasetWriteComparison.Difference::kind).toList()));
        var wrongValue = DatasetWriteComparison.compare(StockBasicDataset.DEFINITION, expected, List.of(row("a", "modified", "")));
        assertFalse(wrongValue.matches());
        assertEquals(List.of("name", "area"), wrongValue.differences().getFirst().columns());
    }
    @Test void duplicatesAndEmptyCannotPassButOrderDoesNotMatter() {
        var a = row("a", "first", null);
        var b = row("b", "second", "SZ");
        var good = DatasetWriteComparison.compare(StockBasicDataset.DEFINITION, List.of(a, b), List.of(b, a));
        assertTrue(good.matches());
        assertEquals(good.expectedDigest(), good.actualDigest());
        assertFalse(DatasetWriteComparison.compare(StockBasicDataset.DEFINITION, List.of(a), List.of(a, a)).matches());
        assertFalse(DatasetWriteComparison.compare(StockBasicDataset.DEFINITION, List.of(a, a), List.of(a)).matches());
        assertFalse(DatasetWriteComparison.compare(StockBasicDataset.DEFINITION, List.of(), List.of()).matches());
    }
}
