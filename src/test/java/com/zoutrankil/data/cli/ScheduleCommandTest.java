package com.zoutrankil.data.cli;

import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduleCommandTest {
    private final StockBasicScheduleService schedule=mock(StockBasicScheduleService.class);
    private CommandLineRunner cli() {
        return new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),
                mock(SyncJobRegistry.class),mock(StockBasicJobService.class),mock(StockBasicGroupService.class),
                mock(ReadGroupReader.class),mock(StockBasicWriteGroupService.class),schedule);
    }
    @Test void putAndTickUseExplicitCommandsOnly() throws Exception {
        cli().run(new DefaultApplicationArguments("schedule-put","--request","schedule.json"));
        verify(schedule).put(Path.of("schedule.json"));
        when(schedule.tick()).thenReturn(List.of());
        cli().run(new DefaultApplicationArguments("schedule-tick"));
        verify(schedule).tick();
        assertThrows(IllegalArgumentException.class,() -> cli().run(
                new DefaultApplicationArguments("schedule-tick","--now","2026-09-29")));
    }
    @Test void incompleteScheduledResultMakesCliFail() throws Exception {
        when(schedule.tick()).thenReturn(List.of(new com.zoutrankil.data.repository.SyncScheduleStore.History(
                "sample",java.time.Instant.parse("2026-09-29T01:30:00Z"),
                com.zoutrankil.data.repository.SyncScheduleStore.State.IN_DOUBT,null,"unknown")));
        assertThrows(IllegalStateException.class,() -> cli().run(new DefaultApplicationArguments("schedule-tick")));
    }
}
