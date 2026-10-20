package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.domain.stock.market.StockSuspendKey;
import com.zoutrankil.data.group.port.WriteGroupWriters;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockSuspendJobService;
import com.zoutrankil.data.stock.mapper.StockSuspendMapper;
import com.zoutrankil.data.stock.port.StockSuspendWriteSession;
import com.zoutrankil.data.stock.storage.StockSuspendReadRepository;
import com.zoutrankil.data.stock.storage.StockSuspendWritePort;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class StockSuspendWriteGroupLayoutTest {
    @TempDir Path root;
    private static final String TABLE = "java_d011_stk_suspend_group_layout";
    private static final String TARGET = "static-v2-" + "a".repeat(64);

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void groupUsesTheFrozenMemberVersionEvenWhenCurrentReaderDiffers(int version) throws Exception {
        var h = new Harness(version, version);
        assertEquals(SyncRunState.VERIFIED, h.service.run(h.input, null).state());
        verify(h.writer).send(List.of(h.row));
        if (version == 1) {
            verify(h.factory).stockSuspend(TABLE, TARGET);
            verify(h.factory, never()).stockSuspend(TABLE, TARGET, 2);
        } else {
            verify(h.factory).stockSuspend(TABLE, TARGET, 2);
            verify(h.factory, never()).stockSuspend(TABLE, TARGET);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void mismatchedSessionStopsBeforePreflightOrSend(int version) throws Exception {
        var h = new Harness(version, 3 - version);
        assertThrows(IllegalArgumentException.class, () -> h.service.run(h.input, null));
        verify(h.writer, never()).preflight();
        verify(h.writer, never()).send(anyList());
    }

    private final class Harness {
        final StockSuspend row =
                new StockSuspend(new StockSuspendKey("000004.SZ", LocalDate.of(2026, 9, 28)), 1L);
        final StockSuspendWriteSession.Versioned writer =
                mock(StockSuspendWriteSession.Versioned.class);
        final WriteGroupWriters factory = mock(WriteGroupWriters.class);
        final StockBasicWriteGroupService service;
        final Path input;

        Harness(int requested, int actual) throws Exception {
            var layout = CnTradeDateLayout.fromVersion(3 - requested);
            var registry =
                    new DatasetRegistry(
                            List.<DatasetImplementation>of(
                                    new StockSuspendReadRepository(
                                            mock(QuestDbBoundedReader.class), TABLE, layout)));
            var owner = mock(StockSuspendJobService.class);
            when(owner.tableName()).thenReturn(TABLE);
            when(owner.physicalTargetId()).thenReturn(TARGET);
            when(writer.datasetVersion()).thenReturn(actual);
            when(writer.codec()).thenReturn(StockSuspendWritePort.CODEC);
            when(writer.readback(List.of(row.key()))).thenReturn(List.of(row));
            when(writer.walSettled()).thenReturn(true);
            when(factory.stockSuspend(TABLE, TARGET)).thenReturn(writer);
            when(factory.stockSuspend(TABLE, TARGET, 2)).thenReturn(writer);
            service =
                    new StockBasicWriteGroupService(
                            registry,
                            mock(StockBasicJobService.class),
                            factory,
                            root.resolve("ledger.sqlite3").toString());
            var field = StockBasicWriteGroupService.class.getDeclaredField("stockSuspendTarget");
            field.setAccessible(true);
            field.set(service, owner);
            input = root.resolve("group.json");
            JobDefinitionJson.mapper()
                    .writeValue(
                            input.toFile(),
                            Map.of(
                                    "batchId",
                                    "batch",
                                    "logicalDate",
                                    "2026-09-28",
                                    "members",
                                    List.of(
                                            Map.of(
                                                    "memberId",
                                                    "suspend",
                                                    "datasetId",
                                                    "stk_suspend",
                                                    "definitionVersion",
                                                    requested,
                                                    "batchId",
                                                    "suspend-batch",
                                                    "rows",
                                                    List.of(
                                                            new StockSuspendMapper()
                                                                    .values(row)
                                                                    .asMap())))));
        }
    }
}
