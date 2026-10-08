package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.group.port.WriteGroupWriters;
import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.storage.StockBasicWritePort;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockBasicWriteGroupSessionTest {
    @TempDir Path temporary;
    @Test @SuppressWarnings("unchecked")
    void stockSampleStillUsesDefinitionTableAndCreatesFreshSessionsPerRun() throws Exception {
        var row = new StockBasicSnapshot(Instant.parse("2026-09-29T00:00:00Z"),
                new StockBasic("000001.SZ", "000001", "sample", null, "bank", LocalDate.of(1991, 4, 3)));
        var registry = new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION));
        var owner = mock(StockBasicJobService.class);
        when(owner.targetId()).thenReturn("static-v2-" + "a".repeat(64));
        var factory = mock(WriteGroupWriters.class);
        VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> first = mock(VerifiedWriteSession.class);
        VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> second = mock(VerifiedWriteSession.class);
        for (var session : List.of(first, second)) {
            when(session.codec()).thenReturn(StockBasicWritePort.CODEC);
            when(session.readback(List.of(row.key()))).thenReturn(List.of(row));
            when(session.walSettled()).thenReturn(true);
        }
        when(factory.stockBasic(StockBasicDataset.DEFINITION.objectName())).thenReturn(first, second);
        var service = new StockBasicWriteGroupService(registry, owner, factory, temporary.resolve("ledger.sqlite3").toString());
        Path input = temporary.resolve("group.json");
        JobDefinitionJson.mapper().writeValue(input.toFile(), Map.of("batchId", "group-1", "logicalDate", "2026-09-29", "members", List.of(Map.of(
                "memberId", "stocks", "datasetId", "stock_basic_snapshot", "definitionVersion", 1, "batchId", "stocks-1",
                "rows", List.of(StockBasicWriteGroupService.encode(row).asMap())))));
        assertEquals(SyncRunState.VERIFIED, service.run(input, null).state());
        assertEquals(SyncRunState.VERIFIED, service.run(input, null).state());
        verify(factory, times(2)).stockBasic(StockBasicDataset.DEFINITION.objectName());
        verifyNoMoreInteractions(factory);
        verify(first).send(List.of(row));
        verify(second).send(List.of(row));
    }
}
