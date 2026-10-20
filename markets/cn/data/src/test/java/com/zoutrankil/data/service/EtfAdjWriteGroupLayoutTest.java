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
import com.zoutrankil.data.domain.fund.EtfAdj;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendarDataset;
import com.zoutrankil.data.etf.application.EtfAdjJobService;
import com.zoutrankil.data.etf.mapper.EtfAdjMapper;
import com.zoutrankil.data.etf.port.EtfAdjWriteSession;
import com.zoutrankil.data.etf.storage.EtfAdjReadRepository;
import com.zoutrankil.data.etf.storage.EtfAdjWritePort;
import com.zoutrankil.data.group.port.WriteGroupWriters;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class EtfAdjWriteGroupLayoutTest {
    @TempDir Path root;
    private static final String TABLE = "java_d015_etf_adj_group_layout";
    private static final String TARGET = "static-v2-" + "a".repeat(64);

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void groupUsesTheFrozenMemberVersionEvenWhenCurrentReaderDiffers(int version) throws Exception {
        var h = new Harness(version, version);
        assertEquals(SyncRunState.VERIFIED, h.service.run(h.input, null).state());
        verify(h.writer).send(List.of(h.row));
        if (version == 1) {
            verify(h.factory).etfAdj(TABLE, TARGET);
            verify(h.factory, never()).etfAdj(TABLE, TARGET, 2);
        } else {
            verify(h.factory).etfAdj(TABLE, TARGET, 2);
            verify(h.factory, never()).etfAdj(TABLE, TARGET);
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
        final EtfAdj row = new EtfAdj("510300.SH", LocalDate.of(2026, 9, 28), 1.25);
        final EtfAdjWriteSession.Versioned writer = mock(EtfAdjWriteSession.Versioned.class);
        final WriteGroupWriters factory = mock(WriteGroupWriters.class);
        final StockBasicWriteGroupService service;
        final Path input;

        Harness(int requested, int actual) throws Exception {
            var layout = CnTradeDateLayout.fromVersion(3 - requested);
            var registry =
                    new DatasetRegistry(
                            List.<DatasetImplementation>of(
                                    () -> ExchangeCalendarDataset.DEFINITION,
                                    new EtfAdjReadRepository(
                                            mock(QuestDbBoundedReader.class), TABLE, layout)));
            var owner = mock(EtfAdjJobService.class);
            when(owner.tableName()).thenReturn(TABLE);
            when(owner.targetId()).thenReturn(TARGET);
            when(writer.datasetVersion()).thenReturn(actual);
            when(writer.codec()).thenReturn(EtfAdjWritePort.CODEC);
            when(writer.readback(List.of(row.key()))).thenReturn(List.of(row));
            when(writer.walSettled()).thenReturn(true);
            when(factory.etfAdj(TABLE, TARGET)).thenReturn(writer);
            when(factory.etfAdj(TABLE, TARGET, 2)).thenReturn(writer);
            service =
                    new StockBasicWriteGroupService(
                            registry,
                            mock(StockBasicJobService.class),
                            factory,
                            root.resolve("ledger.sqlite3").toString());
            var field = StockBasicWriteGroupService.class.getDeclaredField("etfAdjTarget");
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
                                                    "adj",
                                                    "datasetId",
                                                    "etf_adj",
                                                    "definitionVersion",
                                                    requested,
                                                    "batchId",
                                                    "adj-batch",
                                                    "rows",
                                                    List.of(
                                                            new EtfAdjMapper()
                                                                    .values(row)
                                                                    .asMap())))));
        }
    }
}
