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
import com.zoutrankil.data.domain.fund.EtfDaily;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendarDataset;
import com.zoutrankil.data.etf.application.EtfDailyJobService;
import com.zoutrankil.data.etf.mapper.EtfDailyMapper;
import com.zoutrankil.data.etf.port.EtfDailyWriteSession;
import com.zoutrankil.data.etf.storage.EtfDailyReadRepository;
import com.zoutrankil.data.etf.storage.EtfDailyWritePort;
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

class EtfDailyWriteGroupLayoutTest {
    @TempDir Path root;
    private static final String TABLE = "java_d014_etf_daily_group_layout";
    private static final String TARGET = "static-v2-" + "a".repeat(64);

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void groupUsesTheFrozenMemberVersionEvenWhenCurrentReaderDiffers(int version) throws Exception {
        var h = new Harness(version, version);
        assertEquals(SyncRunState.VERIFIED, h.service.run(h.input, null).state());
        verify(h.writer).send(List.of(h.row));
        if (version == 1) {
            verify(h.factory).etfDaily(TABLE, TARGET);
            verify(h.factory, never()).etfDaily(TABLE, TARGET, 2);
        } else {
            verify(h.factory).etfDaily(TABLE, TARGET, 2);
            verify(h.factory, never()).etfDaily(TABLE, TARGET);
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
        final EtfDaily row =
                new EtfDaily(
                        "510300.SH",
                        LocalDate.of(2026, 9, 28),
                        10.0,
                        10.1,
                        10.2,
                        9.8,
                        10.0,
                        0.0,
                        0.0,
                        123.0,
                        456.0);
        final EtfDailyWriteSession.Versioned writer = mock(EtfDailyWriteSession.Versioned.class);
        final WriteGroupWriters factory = mock(WriteGroupWriters.class);
        final StockBasicWriteGroupService service;
        final Path input;

        Harness(int requested, int actual) throws Exception {
            var layout = CnTradeDateLayout.fromVersion(3 - requested);
            var registry =
                    new DatasetRegistry(
                            List.<DatasetImplementation>of(
                                    () -> ExchangeCalendarDataset.DEFINITION,
                                    new EtfDailyReadRepository(
                                            mock(QuestDbBoundedReader.class), TABLE, layout)));
            var owner = mock(EtfDailyJobService.class);
            when(owner.tableName()).thenReturn(TABLE);
            when(owner.targetId()).thenReturn(TARGET);
            when(writer.datasetVersion()).thenReturn(actual);
            when(writer.codec()).thenReturn(EtfDailyWritePort.CODEC);
            when(writer.readback(List.of(row.key()))).thenReturn(List.of(row));
            when(writer.walSettled()).thenReturn(true);
            when(factory.etfDaily(TABLE, TARGET)).thenReturn(writer);
            when(factory.etfDaily(TABLE, TARGET, 2)).thenReturn(writer);
            service =
                    new StockBasicWriteGroupService(
                            registry,
                            mock(StockBasicJobService.class),
                            factory,
                            root.resolve("ledger.sqlite3").toString());
            var field = StockBasicWriteGroupService.class.getDeclaredField("etfDailyTarget");
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
                                                    "etf_daily",
                                                    "definitionVersion",
                                                    requested,
                                                    "batchId",
                                                    "adj-batch",
                                                    "rows",
                                                    List.of(
                                                            new EtfDailyMapper()
                                                                    .values(row)
                                                                    .asMap())))));
        }
    }
}
