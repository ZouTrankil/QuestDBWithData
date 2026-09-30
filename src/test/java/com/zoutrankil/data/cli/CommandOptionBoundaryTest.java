package com.zoutrankil.data.cli;

import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandOptionBoundaryTest {
    @Test void ambiguousInputsAreRejectedBeforeAnyRunnerOrLegacyWrite() {
        var legacy = mock(StockBasicSyncService.class);
        var jobs = mock(StockBasicJobService.class);
        var groups = mock(StockBasicGroupService.class);
        var write = mock(StockBasicWriteGroupService.class);
        var schedule = mock(StockBasicScheduleService.class);
        var cli = new CommandLineRunner(legacy, mock(DatasetRegistry.class), mock(SyncJobRegistry.class),
                jobs, groups, mock(ReadGroupReader.class), write, schedule);
        for (String[] args : new String[][] {
                {"run-stock-basic-job", "--codes", "000001.SZ", "--codes=600000.SH", "--logical-date", "2026-09-29"},
                {"write-dataset-group", "--request=a.json", "--request=b.json"},
                {"schedule-enable", "--id", "sample", "--enabled=true", "--enabled=false"},
                {"write-dataset-group", "--request="},
                {"write-dataset-group", "--request", "   "},
                {"sync-stock-basic-questdb", "--=invalid"},
                {"sync-stock-basic-questdb", "--unexpected", "value"},
                {"migrate-questdb-schema", "--unexpected", "value"},
                {"sync-stock-basic", "--unexpected", "value"},
                {"show-stock-basic-latest", "--unexpected", "value"},
                {"verify-questdb-jdbc", "--unexpected", "value"}
        }) assertThrows(IllegalArgumentException.class, () -> cli.run(new DefaultApplicationArguments(args)));
        verifyNoInteractions(legacy, jobs, groups, write, schedule);
    }
}
