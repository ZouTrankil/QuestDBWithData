package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class DatasetWritePreparationTest {
    private DatasetValues row(String code) {
        var values = new LinkedHashMap<String, Object>();
        values.put("snapshot_ts", Instant.parse("2026-09-29T00:00:00Z"));
        values.put("ts_code", code); values.put("symbol", code); values.put("name", "sample");
        values.put("area", null); values.put("industry", null); values.put("list_date", LocalDate.of(1991, 4, 3));
        return new DatasetValues(values);
    }
    private DatasetWritePreparation.Batch prepare(List<DatasetValues> rows, int maxRows, int maxBytes) {
        return DatasetWritePreparation.prepare(StockBasicDataset.DEFINITION, rows, Function.identity(),
                new DatasetWritePreparation.Limits(maxRows, maxBytes));
    }
    @Test void fullValuesAndNullsHaveStableFingerprintWhileDuplicateKeysAreRejected() {
        var first = prepare(List.of(row("a")), 2, 4096);
        assertEquals(first.fingerprint(), prepare(List.of(row("a")), 2, 4096).fingerprint());
        assertNotEquals(first.fingerprint(), prepare(List.of(row("b")), 2, 4096).fingerprint());
        assertNull(first.rows().getFirst().get("area", String.class));
        assertThrows(IllegalArgumentException.class, () -> prepare(List.of(row("a"), row("a")), 2, 4096));
    }
    @Test void sizeMissingColumnsAndViewWritesFailBeforeTransport() {
        assertThrows(IllegalArgumentException.class, () -> prepare(List.of(row("a"), row("b")), 1, 4096));
        assertThrows(IllegalArgumentException.class, () -> prepare(List.of(row("a")), 1, 10));
        assertThrows(IllegalArgumentException.class, () -> prepare(List.of(new DatasetValues(Map.of("ts_code", "a"))), 1, 4096));
        assertThrows(IllegalArgumentException.class, () -> DatasetWritePreparation.prepare(StockBasicDataset.LATEST,
                List.of(row("a")), Function.identity(), new DatasetWritePreparation.Limits(1, 4096)));
        assertTrue(prepare(List.of(), 1, 4096).empty());
    }
    @Test void ackCannotBePromotedToVerifiedWithoutNonemptyValueEvidence() {
        var ack = new DatasetWriteReceipt("batch", DatasetWriteReceipt.Status.ACKNOWLEDGED, true, 2, 0, 0, null);
        assertFalse(ack.verified());
        assertThrows(IllegalArgumentException.class, () -> new DatasetWriteReceipt("batch",
                DatasetWriteReceipt.Status.VERIFIED, true, 2, 2, 1, "count-only"));
        assertThrows(IllegalArgumentException.class, () -> new DatasetWriteReceipt("batch",
                DatasetWriteReceipt.Status.VERIFIED, true, 2, 2, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new DatasetWriteReceipt("batch",
                DatasetWriteReceipt.Status.VERIFIED, true, 0, 0, 0, "empty"));
        assertFalse(new DatasetWriteReceipt("batch", DatasetWriteReceipt.Status.IN_DOUBT, null, 2, 0, 0, null).verified());
    }
}
